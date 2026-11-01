package com.tubo.mcserver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.Build
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

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
 *
 * 安装包来自 Termux 软件源。源不写死：走 [DownloadSources] 的 `termux` 分类，
 * 支持多镜像切换与测速，取索引时按「手动指定 → 延迟最低」的顺序逐个尝试，
 * 一个镜像挂了自动换下一个（官方源在境内经常连不上，这就是之前下载报错的原因）。
 *
 * 安装位置：应用私有目录（`/storage/emulated/0` 是 noexec，二进制放那里跑不起来）。
 */
object JdkManager {

    /**
     * 安装/卸载/切换 Java 时自增。
     *
     * 实例列表用 `remember(mcVersion, JdkManager.revision)` 做 key，
     * 否则装完 Java 后卡片上的「缺少适配的 Java」警告要等重启才消失。
     */
    var revision by mutableStateOf(0)
        private set

    /** 索引缓存：repoId + arch -> 包名到包信息 */
    private val indexCache = HashMap<String, Map<String, PkgInfo>>()

    private data class PkgInfo(
        val name: String,
        val filename: String,
        val depends: List<String>
    )

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
                // 跟随软链接拿到真实 JDK 根目录，避免把 openjdk-25 这种「容器目录」当成 java.home
                val home = javaHomeOf(b) ?: continue
                if (!seen.add(home.absolutePath)) continue
                val label = labelOf(home)
                out += JavaInstall(label, home, File(home, "bin/java"))
            }
        }
        return out.sortedBy { it.label }
    }

    /** 从 java 可执行文件反推 JDK 根目录（跟随软链接） */
    fun javaHomeOf(bin: File): File? {
        val real = runCatching { bin.canonicalFile }.getOrNull() ?: bin
        if (!real.isFile) return null
        return real.parentFile?.parentFile
    }

    /**
     * JDK 运行所需的库搜索路径。
     *
     * Termux 的 JDK 装在 `lib/jvm/<name>/` 下，`libjli.so` 在 `<jdk>/lib/jli`；
     * 而依赖包的 .so 落在安装根目录的 `lib/`。两者都要给，否则启动时
     * `CANNOT LINK EXECUTABLE: library "libjli.so" not found`。
     */
    fun libPathOf(home: File): String {
        val dirs = LinkedHashSet<File>()
        // 自身与各级祖先的 lib 目录（覆盖安装根目录下的依赖库）
        var cur: File? = home
        repeat(4) {
            val c = cur ?: return@repeat
            dirs += File(c, "lib")
            cur = c.parentFile
        }
        dirs += File(home, "lib/jli")
        dirs += File(home, "lib/server")
        // 兜底：直接按文件名找
        runCatching {
            home.walkTopDown().maxDepth(5)
                .filter { it.isFile && (it.name == "libjli.so" || it.name == "libjvm.so") }
                .forEach { dirs += it.parentFile }
        }
        return dirs.filter { it.isDirectory }.joinToString(":") { it.absolutePath }
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

    private fun labelOf(home: File): String {
        val n = home.name
        // Termux 里是 java-25-openjdk，展示成 openjdk-25 更好认
        Regex("java-(\\d+)-openjdk").find(n)?.let { return "openjdk-${it.groupValues[1]}" }
        return if (n.startsWith("bin") || n.startsWith("jvm")) home.parentFile?.name ?: n else n
    }

    /** 当前生效的 java 可执行文件 */
    fun activeBin(): File? {
        val saved = Prefs.javaHome
        if (saved.isNotEmpty()) {
            val f = File(saved)
            val bin = if (f.isFile) f else File(f, "bin/java")
            if (bin.exists()) {
                // 归一化到真实 JDK 根目录，避免旧配置里存的「容器目录」导致找错 lib
                val home = javaHomeOf(bin)
                if (home != null) {
                    val real = File(home, "bin/java")
                    if (Prefs.javaHome != home.absolutePath) Prefs.javaHome = home.absolutePath
                    return real
                }
                return bin
            }
        }
        val first = listInstalled().firstOrNull { it.exists } ?: return null
        Prefs.javaHome = first.home.absolutePath
        return first.bin
    }

    fun hasJdk(): Boolean = activeBin() != null

    /** 选中某个已安装 Java */
    fun select(home: File) {
        Prefs.javaHome = home.absolutePath
        revision++
    }

    /** 卸载：删除安装目录；如果正是当前使用的版本，清掉选择 */
    fun uninstall(install: JavaInstall): Boolean {
        val home = install.home
        val ok = runCatching { home.deleteRecursively() }.getOrDefault(false)
        if (ok) {
            clearIfActive(home)
            revision++
        }
        return ok
    }

    /** 更新：删掉旧目录后用同版本重装（走下载中心） */
    fun reinstall(install: JavaInstall): DlTask? {
        val ver = Regex("openjdk-(\\d+)").find(install.label)?.groupValues?.get(1)
            ?: Regex("java-(\\d+)-openjdk").find(install.home.name)?.groupValues?.get(1)
            ?: return null
        runCatching { install.home.deleteRecursively() }
        clearIfActive(install.home)
        return install(ver)
    }

    private fun clearIfActive(home: File) {
        val cur = Prefs.javaHome
        if (cur.isEmpty()) return
        if (cur == home.absolutePath || cur.startsWith(home.absolutePath + File.separator)) {
            Prefs.javaHome = ""
        }
    }

    // ------------------------------------------------------------ 源与索引

    /** 候选源：优先手动指定/低延迟，全部被关掉时退回全部 termux 源 */
    private fun candidateRepos(): List<DownloadSource> {
        val ordered = DownloadSources.ordered("termux")
        if (ordered.isNotEmpty()) return ordered
        return DownloadSources.ofKind("termux")
    }

    /**
     * 拉取 Packages 索引。
     * 依次尝试 `.xz` / `.gz` / 纯文本 —— 很多镜像只提供压缩版，
     * 之前写死纯文本 Packages 是境内镜像直接报错的原因之一。
     */
    private fun fetchIndex(
        source: DownloadSource,
        arch: String,
        log: (String) -> Unit
    ): Map<String, PkgInfo>? {
        val key = "${source.id}|$arch"
        indexCache[key]?.let { return it }

        val suffixes = listOf("Packages.xz", "Packages.gz", "Packages")
        for (suffix in suffixes) {
            val url = "${source.baseUrl}/dists/stable/main/binary-$arch/$suffix"
            try {
                val raw = Net.getBytes(url, 45000)
                val stream = when {
                    suffix.endsWith(".xz") -> XZInputStream(ByteArrayInputStream(raw))
                    suffix.endsWith(".gz") -> GZIPInputStream(ByteArrayInputStream(raw))
                    else -> ByteArrayInputStream(raw)
                }
                val map = stream.use { parseIndex(it) }
                if (map.isEmpty()) {
                    log("  索引 $suffix 内容异常（无 Package 字段）")
                    continue
                }
                log("  索引就绪：$suffix，${map.size} 个包")
                indexCache[key] = map
                return map
            } catch (e: Exception) {
                log("  $suffix 失败：${e.message}")
            }
        }
        return null
    }

    /** 流式解析 Packages 索引，避免把几十 MB 文本整块切分成数组 */
    private fun parseIndex(input: java.io.InputStream): Map<String, PkgInfo> {
        val map = HashMap<String, PkgInfo>()
        var name = ""
        var filename = ""
        var depends = ""

        fun flush() {
            if (name.isNotEmpty() && filename.isNotEmpty()) {
                map[name] = PkgInfo(name, filename, splitDepends(depends))
            }
            name = ""
            filename = ""
            depends = ""
        }

        input.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) { flush(); continue }
                when {
                    line.startsWith("Package: ") -> name = line.removePrefix("Package: ").trim()
                    line.startsWith("Filename: ") -> filename = line.removePrefix("Filename: ").trim()
                    line.startsWith("Depends: ") -> depends = line.removePrefix("Depends: ").trim()
                    line.startsWith("Pre-Depends: ") -> depends =
                        (depends + ", " + line.removePrefix("Pre-Depends: ").trim()).trim(',', ' ')
                }
            }
        }
        flush()
        return map
    }

    /** `libc++ (>= 25), a | b` → ["libc++", "a"] */
    private fun splitDepends(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('|').substringBefore('(').trim() }
            .filter { it.isNotEmpty() }
    }

    /** 从 [root] 出发解析依赖闭包，返回按依赖顺序排列的安装列表（被依赖的在前） */
    private fun resolve(index: Map<String, PkgInfo>, root: String): List<PkgInfo> {
        val out = ArrayList<PkgInfo>()
        val visited = HashSet<String>()

        fun visit(name: String, depth: Int) {
            if (depth > 12 || !visited.add(name)) return
            val info = index[name] ?: return
            info.depends.forEach { visit(it, depth + 1) }
            out += info
        }
        visit(root, 0)
        return out
    }

    // ------------------------------------------------------------ 安装

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
                val arch = arch()
                task.log("设备架构: $arch")
                task.log("安装目录: ${runtimeRoot().absolutePath}")
                if (!Storage.usingPublic()) {
                    task.log("[提示] 未获得公共目录写入权限，安装包缓存放在应用私有目录")
                }

                val repos = candidateRepos()
                if (repos.isEmpty()) throw Exception("没有可用的 Java 下载源，请到「设置 → 下载 → 下载源」里启用一个 Termux 镜像")
                task.log("候选源 ${repos.size} 个：" + repos.joinToString("、") { it.name })

                var index: Map<String, PkgInfo>? = null
                var repo: DownloadSource? = null
                var lastError = ""
                for (r in repos) {
                    if (task.cancelRequested) { DlManager.cancelled(task); return@Thread }
                    task.log("→ 尝试 ${r.name}")
                    val idx = fetchIndex(r, arch) { line -> task.log(line) }
                    if (idx == null) {
                        lastError = "${r.name} 索引获取失败"
                        continue
                    }
                    if (!idx.containsKey(pkg)) {
                        lastError = "${r.name} 索引里没有 $pkg"
                        task.log("  该源没有 $pkg，换下一个")
                        continue
                    }
                    index = idx
                    repo = r
                    break
                }
                if (index == null || repo == null) {
                    throw Exception("所有源都失败：$lastError")
                }

                val jobs = resolve(index, pkg)
                if (jobs.isEmpty()) throw Exception("仓库里找不到 $pkg（8/11 已停止提供，请选 17 / 21 / 25）")
                task.log("${repo.name}：需下载 ${jobs.size} 个包（含依赖）")

                val destDir = File(runtimeRoot(), pkg)
                dest = destDir
                // 上次失败可能留下半成品，先清干净再解
                if (destDir.exists() && !File(destDir, "bin/java").exists()) {
                    task.log("清理上次残留目录…")
                    runCatching { destDir.deleteRecursively() }
                }
                destDir.mkdirs()
                val pkgCache = File(javaRoot(), "packages").apply { mkdirs() }

                var idxNo = 0
                for (info in jobs) {
                    idxNo++
                    if (task.cancelRequested) {
                        DlManager.cancelled(task); return@Thread
                    }
                    val deb = File(pkgCache, "${info.name}.deb")
                    if (deb.exists() && deb.length() > 1024) {
                        task.log("[$idxNo/${jobs.size}] 使用缓存 ${info.name}.deb（${deb.length() / 1024 / 1024} MB）")
                    } else {
                        task.log("[$idxNo/${jobs.size}] 下载 ${info.name} …")
                        task.total = 0; task.done = 0
                        val ok = downloadDeb(repo, info.filename, deb, task)
                        if (!ok) {
                            if (task.cancelRequested) { DlManager.cancelled(task); return@Thread }
                            throw Exception("下载失败: ${info.name}（${repo.name}）")
                        }
                        task.log("   已保存: ${deb.absolutePath}")
                    }
                    task.log("[$idxNo/${jobs.size}] 解压 ${info.name} …")
                    val okExtract = DebExtractor.extract(deb, destDir) { line -> task.log("   $line") }
                    if (!okExtract) throw Exception("解压失败: ${info.name}")
                }

                fixPermissions(destDir)
                val jdkHome = resolveJavaHome(destDir, task)
                    ?: throw Exception("解包完成但没找到 bin/java（日志里看是否缺依赖包）")
                val bin = File(jdkHome, "bin/java")
                bin.setExecutable(true, false)
                Prefs.javaHome = jdkHome.absolutePath
                task.log("JDK 根目录: ${jdkHome.absolutePath}")

                // 自检：跑一次 java -version，把真实输出写进日志，方便定位缺库等问题
                val check = verifyJava(bin, jdkHome, task)
                DlManager.success(
                    task,
                    if (check == null) "安装完成: ${jdkHome.absolutePath}"
                    else "已安装到 ${jdkHome.absolutePath}，但 java -version 自检未通过（详见日志）"
                )
            } catch (e: Exception) {
                dest?.let { d ->
                    if (!File(d, "bin/java").exists()) runCatching { d.deleteRecursively() }
                }
                DlManager.fail(task, "安装失败: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
        return task
    }

    private fun downloadDeb(
        repo: DownloadSource,
        filename: String,
        deb: File,
        task: DlTask
    ): Boolean {
        val url = if (filename.startsWith("http")) filename else "${repo.baseUrl}/$filename"
        return MultiDownloader.download(
            url = url,
            dest = deb,
            onProgress = { d, t ->
                task.done = d
                task.total = t
                task.message = if (t > 0) "${deb.nameWithoutExtension} ${d * 100 / t}%" else deb.nameWithoutExtension
                DlManager.notifyChanged()
            },
            shouldCancel = { task.cancelRequested }
        )
    }

    /** 解包后统一补可执行位：bin/ 下全部、lib/ 下的 .so */
    private fun fixPermissions(root: File) {
        File(root, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        File(root, "lib").listFiles()?.forEach {
            if (it.isFile && it.name.endsWith(".so")) it.setExecutable(true, false)
        }
    }

    /**
     * 找到真正的 JDK 根目录（同时含 `bin/java` 与 `lib/`）。
     *
     * Termux 的 openjdk 装在 `lib/jvm/<name>/` 下，顶层 `bin/java` 只是一条软链接；
     * 把容器目录当成 java.home 会导致找不到 `lib/modules` 与 `libjli.so`。
     */
    private fun resolveJavaHome(root: File, task: DlTask): File? {
        val direct = File(root, "bin/java")
        if (direct.exists()) {
            javaHomeOf(direct)?.let { return it }
        }
        val nested = File(root, "lib/jvm").listFiles()
            ?.sortedBy { it.name }
            ?.firstNotNullOfOrNull { d -> d.takeIf { File(d, "bin/java").isFile } }
        if (nested != null) {
            task.log("从 lib/jvm 找到 JDK: ${nested.name}")
            return nested
        }
        val bins = ArrayList<File>()
        collectJavaBins(root, 0, bins)
        return bins.firstOrNull()?.let { javaHomeOf(it) }
    }

    /**
     * 自检：跑一次 `java -version`。
     * @return null 表示通过；否则返回失败输出，供日志展示
     */
    private fun verifyJava(bin: File, home: File, task: DlTask): String? {
        return try {
            val libs = libPathOf(home)
            val pb = ProcessBuilder(bin.absolutePath, "-version")
            pb.redirectErrorStream(true)
            if (libs.isNotEmpty()) pb.environment()["LD_LIBRARY_PATH"] = libs
            val p = pb.start()
            val output = p.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = p.waitFor()
            task.log("自检 java -version → code=$code")
            output.lines().filter { it.isNotBlank() }.take(4).forEach { task.log("   $it") }
            if (code == 0) null else output.ifEmpty { "退出码 $code" }
        } catch (e: Exception) {
            task.log("自检异常: ${e.message}")
            e.message
        }
    }

    /** 兼容旧接口 */
    fun installJdk(ver: String, log: (String) -> Unit): Pair<Boolean, String> {
        val task = install(ver)
        log("已加入下载任务：${task.title}（可在「下载」页查看进度）")
        return Pair(true, "已开始下载 Java $ver，进度见「下载」页")
    }

    fun javaBin(): File = activeBin() ?: File(javaRoot(), "openjdk-17/bin/java")
}
