package com.tubo.mcserver

import android.os.Build
import java.io.File

/** 一个已安装的 Java 运行时 */
data class JavaInstall(
    val label: String,      // openjdk-17
    val home: File,         // .../java/openjdk-17
    val bin: File           // .../java/openjdk-17/bin/java
) {
    val exists: Boolean get() = bin.exists()
    val active: Boolean get() = Prefs.javaHome == home.absolutePath
}

/**
 * Java 运行时管理。
 * 默认安装位置：/storage/emulated/0/MCServerLauncher/java/<openjdk-XX>
 * 所有下载与解包过程都会写入 DlManager 的下载任务（下载管理页可看进度与完整日志）。
 */
object JdkManager {
    private const val BASE = "https://packages.termux.dev/apt/termux-main"

    private val indexCache = HashMap<String, String>()

    fun arch(): String {
        val a = Build.SUPPORTED_ABIS[0]
        return when {
            a.startsWith("arm64") -> "aarch64"
            a.startsWith("x86_64") -> "x86_64"
            a.startsWith("x86") -> "i686"
            a.startsWith("armeabi") -> "arm"
            else -> "aarch64"
        }
    }

    /** 可执行文件必须放在应用私有目录：/storage/emulated/0 是 noexec 挂载点 */
    fun runtimeRoot(): File {
        val f = File(App.ctx.filesDir, "java")
        if (!f.exists()) f.mkdirs()
        return f
    }

    /** 下载包与用户可见目录：<存储根>/java */
    fun javaRoot(): File = Storage.javaRoot()

    /** proot 等附属二进制目录（同样必须在私有目录才能执行） */
    fun prefixDir(): File {
        val f = File(App.ctx.filesDir, "termux-prefix")
        if (!f.exists()) f.mkdirs()
        return f
    }

    /** 扫描所有已安装的 Java（含手动拷进目录的版本） */
    fun listInstalled(): List<JavaInstall> {
        val out = ArrayList<JavaInstall>()
        val seen = HashSet<String>()
        val roots = listOf(runtimeRoot(), prefixDir(), javaRoot())
        for (r in roots) {
            if (!r.exists() || !r.isDirectory) continue
            val bins = ArrayList<File>()
            collectJavaBins(r, 0, bins)
            for (b in bins) {
                val home = b.parentFile?.parentFile ?: continue
                if (!seen.add(home.absolutePath)) continue
                val label = labelOf(home, b)
                out += JavaInstall(label, home, b)
            }
        }
        return out.sortedBy { it.label }
    }

    private fun collectJavaBins(dir: File, depth: Int, out: MutableList<File>) {
        if (depth > 4) return
        val bin = File(dir, "bin/java")
        if (bin.exists() && bin.isFile) {
            out.add(bin)
            return
        }
        dir.listFiles()?.forEach { if (it.isDirectory) collectJavaBins(it, depth + 1, out) }
    }

    private fun labelOf(home: File, bin: File): String {
        val n = home.name
        return if (n.startsWith("bin") || n.startsWith("jvm")) {
            // 老结构：lib/jvm/java-17-openjdk/bin/java
            home.parentFile?.name ?: n
        } else n
    }

    /** 当前生效的 java 可执行文件 */
    fun activeBin(): File? {
        val saved = Prefs.javaHome
        if (saved.isNotEmpty()) {
            val f = File(saved)
            if (f.isFile && f.exists()) return f          // 直接存的是 bin/java
            val b = File(f, "bin/java")
            if (b.exists()) return b
        }
        val first = listInstalled().firstOrNull { it.exists } ?: return null
        Prefs.javaHome = first.home.absolutePath
        return first.bin
    }

    fun hasJdk(): Boolean = activeBin() != null

    /** 选中某个已安装 Java */
    fun select(home: File) {
        Prefs.javaHome = home.absolutePath
    }

    /** 从 Packages 索引查包文件名（带内存缓存） */
    private fun lookupFilename(pkg: String): String? {
        val arch = arch()
        indexCache[arch]?.let { text -> parseFilename(text, pkg)?.let { return it } }
        val plainUrl = "$BASE/dists/stable/main/binary-$arch/Packages"
        val text = try {
            Net.getString(plainUrl, 60000)
        } catch (e: Exception) {
            return null
        }
        indexCache[arch] = text
        return parseFilename(text, pkg)
    }

    private fun parseFilename(index: String, pkg: String): String? {
        for (b in index.split("\n\n")) {
            if (b.lines().any { it.trim() == "Package: $pkg" }) {
                return b.lines().firstOrNull { it.startsWith("Filename: ") }
                    ?.removePrefix("Filename: ")?.trim()
            }
        }
        return null
    }

    private val DEPS = listOf("libandroid-shmem", "libandroid-spawn", "libiconv", "libandroid-support")

    /**
     * 下载并安装指定版本 JDK（"17" / "21" / "25"）。
     * 过程登记到「下载管理」，返回该下载任务。
     */
    fun install(ver: String): DlTask {
        val task = DlManager.create("Java openjdk-$ver", "java")
        Thread {
            DlManager.start(task)
            var dest: File? = null
            try {
                val pkg = "openjdk-$ver"
                task.log("设备架构: ${arch()}")
                task.log("运行目录: ${runtimeRoot().absolutePath}")
                task.log("下载缓存: ${javaRoot().absolutePath}")
                if (!Storage.usingPublic()) {
                    task.log("[警告] 未获得公共目录写入权限，下载缓存回退到应用私有目录")
                }
                task.log("解析 Termux 包索引…")
                val filename = lookupFilename(pkg)
                    ?: throw Exception("仓库中找不到 $pkg（8/11 已停止提供，请选 17/21/25）")
                task.log("找到 $pkg -> $filename")

                val jobs = ArrayList<Pair<String, String>>()
                jobs += pkg to filename
                for (d in DEPS) lookupFilename(d)?.let { jobs += d to it }
                task.log("依赖 ${jobs.size - 1} 个，开始下载")

                dest = File(runtimeRoot(), pkg)
                if (!dest.exists()) dest.mkdirs()
                // 安装包缓存放在用户可见的 MCServerLauncher/java/packages，方便备份
                val pkgCache = File(javaRoot(), "packages").apply { mkdirs() }

                var idx = 0
                for ((name, fn) in jobs) {
                    idx++
                    if (task.cancelRequested) {
                        DlManager.cancelled(task); return@Thread
                    }
                    val deb = File(pkgCache, "$name.deb")
                    if (deb.exists() && deb.length() > 1024) {
                        task.log("[$idx/${jobs.size}] 使用缓存 $name.deb（${deb.length() / 1024 / 1024} MB）")
                    } else {
                        task.log("[$idx/${jobs.size}] 下载 $name …")
                        task.total = 0; task.done = 0
                        val ok = Net.download(
                            "$BASE/$fn", deb,
                            { d, t ->
                                task.done = d
                                task.total = t
                                task.message = if (t > 0) "$name ${d * 100 / t}%" else name
                                DlManager.notifyChanged()
                            },
                            { task.cancelRequested }
                        )
                        if (!ok) {
                            if (task.cancelRequested) { DlManager.cancelled(task); return@Thread }
                            throw Exception("下载失败: $name")
                        }
                        task.log("   已保存: ${deb.absolutePath}")
                    }
                    task.log("[$idx/${jobs.size}] 解压 $name …")
                    val okExtract = DebExtractor.extract(deb, dest) { line -> task.log("   $line") }
                    if (!okExtract) throw Exception("解压失败: $name")
                }

                val bin = File(dest, "bin/java")
                bin.setExecutable(true, false)
                if (!bin.exists()) throw Exception("解包完成但未生成 bin/java")
                Prefs.javaHome = dest.absolutePath
                DlManager.success(task, "安装完成: ${dest.absolutePath}")
            } catch (e: Exception) {
                dest?.let { d ->
                    // 失败时清掉半成品，避免列表里出现坏条目
                    if (!File(d, "bin/java").exists()) runCatching { d.deleteRecursively() }
                }
                DlManager.fail(task, "安装失败: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
        return task
    }

    /** 兼容旧接口 */
    fun installJdk(ver: String, log: (String) -> Unit): Pair<Boolean, String> {
        val task = install(ver)
        log("已加入下载任务：${task.title}（可在「下载」页查看进度）")
        return Pair(true, "已开始下载 Java $ver，进度见「下载」页")
    }

    fun javaBin(): File = activeBin() ?: File(javaRoot(), "openjdk-17/bin/java")
}
