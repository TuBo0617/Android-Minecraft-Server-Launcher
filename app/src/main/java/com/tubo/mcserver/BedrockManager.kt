package com.tubo.mcserver

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 基岩版（BDS）下载与运行环境准备。
 *
 * ## 为什么不能直接跑
 *
 * 官方的 `bedrock_server` 是 **glibc 动态链接的 x86_64/arm64 二进制**，
 * 而 Android 用的是 bionic libc，直接执行会报 `CANNOT LINK EXECUTABLE`。
 * 所以必须用 proot 套一个 Debian/Ubuntu 的 rootfs，在它里面跑。
 *
 * 三件套：`proot`（在 Termux 源里） + `glibc rootfs`（Ubuntu base 官方 tar.gz） + BDS 本体。
 */
object BedrockManager {

    /** 官方下载清单接口，`serverBedrockLinux` 就是当前最新 BDS 的直链 */
    private const val LINKS_API = "https://net-secondary.web.minecraft-services.net/api/v1.0/download/links"

    /** Ubuntu base 官方最小 rootfs（glibc），比各种第三方打包源稳 */
    private const val ROOTFS_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"

    private const val ROOTFS_URL_X86 =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-amd64.tar.gz"

    data class Bds(val version: String, val url: String)

    /**
     * 取当前最新 BDS 版本。
     *
     * 之前这里写死了 `bedrock-server-1.21.50.03.zip`，版本一更新就 404，
     * 所以改成读微软自己的下载清单接口。
     */
    fun latest(): Bds? = runCatching {
        val text = Net.getString(LINKS_API, headers = mapOf("User-Agent" to "MCServerLauncher/1.0"))
        val links = JSONObject(text).getJSONObject("result").getJSONArray("links")
        for (i in 0 until links.length()) {
            val o = links.optJSONObject(i) ?: continue
            if (o.optString("downloadType") != "serverBedrockLinux") continue
            val url = o.optString("downloadUrl")
            if (url.isEmpty()) continue
            // 从文件名里抠版本号：bedrock-server-1.26.61.1.zip
            val version = Regex("bedrock-server-([0-9.]+)\\.zip").find(url)?.groupValues?.get(1)
                ?: "latest"
            return Bds(version, url)
        }
        null
    }.getOrNull()

    /** 下载并解压 BDS 到实例目录；[url] 为空时自动取最新版 */
    fun downloadBds(
        inst: Instance,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
        url: String? = null
    ): Pair<Boolean, String> {
        return try {
            val target = url ?: latest()?.url
                ?: return Pair(false, "拿不到 BDS 下载地址（微软接口不可达）")

            val zip = File(inst.dir(), "bds.zip")
            inst.dir().mkdirs()
            onLog("下载 BDS: $target")
            if (!MultiDownloader.download(target, zip, onProgress = onProgress, shouldCancel = shouldCancel)) {
                return Pair(false, "BDS 下载失败：$target")
            }

            onLog("解压 BDS…")
            var count = 0
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var e = zis.nextEntry
                val buf = ByteArray(64 * 1024)
                while (e != null) {
                    if (shouldCancel()) return Pair(false, "已取消")
                    // 防 zip-slip
                    val name = e.name.replace('\\', '/')
                    if (name.contains("..")) {
                        zis.closeEntry(); e = zis.nextEntry; continue
                    }
                    val f = File(inst.dir(), name)
                    if (e.isDirectory) {
                        f.mkdirs()
                    } else {
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { out ->
                            var n: Int
                            while (zis.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                        }
                        count++
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            zip.delete()
            File(inst.dir(), "bedrock_server").setExecutable(true, false)
            // BDS 首次启动会自己生成 server.properties，先写好端口与正版验证。
            // online-mode 默认关：开着会去连微软服务，连不上（境内很常见）BDS 会直接退出。
            val props = File(inst.dir(), "server.properties")
            if (!props.exists()) {
                props.writeText(
                    "server-port=19132\n" +
                            "server-portv6=19133\n" +
                            "online-mode=false\n" +
                            // 离线模式不能开白名单，BDS 会拒绝启动
                            "allow-list=false\n"
                )
            }
            Pair(true, "BDS 安装完成（$count 个文件）")
        } catch (e: Exception) {
            Pair(false, "错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ 运行环境

    fun rootfsDir(): File = File(JdkManager.prefixDir(), "glibc-rootfs")

    /** 读 server.properties 里的某个布尔项 */
    fun boolProp(inst: Instance, key: String, default: Boolean = false): Boolean {
        val f = File(inst.dir(), "server.properties")
        if (!f.isFile) return default
        return runCatching {
            f.readLines().firstOrNull { it.trimStart().startsWith("$key=") }
                ?.substringAfter('=')?.trim()?.toBoolean() ?: default
        }.getOrDefault(default)
    }

    /** 改 server.properties 里的某个键，保留其它行 */
    fun setProp(inst: Instance, key: String, value: String) {
        val f = File(inst.dir(), "server.properties")
        runCatching {
            if (!f.exists()) {
                f.writeText("server-port=19132\nserver-portv6=19133\n$key=$value\n")
                return
            }
            val lines = f.readLines().toMutableList()
            val idx = lines.indexOfFirst { it.trimStart().startsWith("$key=") }
            if (idx >= 0) lines[idx] = "$key=$value" else lines += "$key=$value"
            f.writeText(lines.joinToString("\n") + "\n")
        }
    }

    /** 基岩版是否开启正版验证（默认关，读不到配置时按关闭处理） */
    fun onlineMode(inst: Instance): Boolean = boolProp(inst, "online-mode", false)

    /**
     * 开/关正版验证。
     *
     * BDS 有硬性约束：**`allow-list=true` 必须搭配 `online-mode=true`**，
     * 否则启动时报 "Using an allowlist without online authentication ... is not allowed"
     * 并直接退出（code=2）。所以关正版验证时顺手把白名单一起关掉，
     * 避免用户只点一下开关就起不来。
     */
    fun setOnlineMode(inst: Instance, enabled: Boolean) {
        setProp(inst, "online-mode", enabled.toString())
        if (!enabled) setProp(inst, "allow-list", "false")
    }

    /**
     * 找 proot 的 loader。
     *
     * Termux 的 proot 把 loader 装在 `libexec/proot/loader`，但不同打包版本位置会变；
     * 而它的内置默认路径是编译期写死的 `/data/data/com.termux/...`，
     * 我们的 prefix 在应用私有目录，所以必须用 `PROOT_LOADER` 显式指定。
     * 这里先试常见位置，再兜底扫一遍 prefix。
     */
    fun findProotLoader(): File? {
        val prefix = JdkManager.prefixDir()
        val candidates = listOf(
            File(prefix, "libexec/proot/loader"),
            File(prefix, "lib/proot/loader"),
            File(prefix, "libexec/loader"),
            File(prefix, "lib/loader")
        )
        val found = candidates.firstOrNull { it.isFile } ?: runCatching {
            prefix.walkTopDown().maxDepth(4).firstOrNull {
                it.isFile && it.name == "loader" && !it.path.contains("glibc-rootfs")
            }
        }.getOrNull() ?: return null
        // deb 解包时 exec 位不一定落上，这里兜一道；没有执行位的 loader proot 直接报
        // "the loader was not found or doesn't work"
        if (!found.canExecute()) found.setExecutable(true, false)
        if (!found.canExecute()) {
            // prefix 里的权限有时改不动，复制一份到私有目录再授权
            val copy = File(App.ctx.filesDir, "bedrock/loader").apply { parentFile?.mkdirs() }
            runCatching {
                found.copyTo(copy, overwrite = true)
                copy.setExecutable(true, false)
            }
            if (copy.canExecute()) return copy
        }
        return found
    }

    /** 解包格式版本；改了软链接策略就要 +1，让旧 rootfs 自动重解 */
    private const val ROOTFS_LAYOUT = "v2-guestlinks"

    fun hasRootfs(): Boolean =
        File(rootfsDir(), "bin/sh").exists() && File(rootfsDir(), ".layout").let {
            it.isFile && it.readText().trim() == ROOTFS_LAYOUT
        }

    /** 该 ABI 下 glibc 解释器的标准位置（Ubuntu 24.04 是 usrmerge 布局） */
    private fun interpreterPaths(): List<String> =
        if (JdkManager.arch() == "aarch64") {
            listOf("lib/ld-linux-aarch64.so.1", "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
        } else {
            listOf("lib64/ld-linux-x86-64.so.2", "usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2")
        }

    /**
     * 检查 rootfs 关键文件，缺了就顺手修。
     *
     * Ubuntu 24.04 用 **usrmerge** 布局：`/lib64` 只是指向 `usr/lib64` 的软链接，
     * 而 `usr/lib64/ld-linux-x86-64.so.2` 又指向 `../lib/x86_64-linux-gnu/...`。
     * 这条链任何一环没建出来，proot 都会报
     * `execve("...bedrock_server"): No such file or directory`（其实是找不到解释器）。
     *
     * @return 给控制台看的结果描述
     */
    fun checkAndRepairRootfs(): List<String> {
        val root = rootfsDir()
        val out = ArrayList<String>()

        // 1) 顶层 usrmerge 软链接：bin / lib / lib64 / sbin -> usr/*
        for (name in listOf("bin", "lib", "lib64", "sbin")) {
            val link = File(root, name)
            val real = File(root, "usr/$name")
            if (link.exists() || (!real.exists() && !real.isDirectory)) continue
            if (symlink("usr/$name", link)) out += "已重建 $name -> usr/$name"
        }

        // 2) 解释器本体
        val interp = interpreterPaths().firstOrNull { File(root, it).exists() }
        if (interp == null) {
            out += "⚠ 没找到 glibc 解释器（${interpreterPaths().joinToString(" 或 ")}）"
        }

        // 3) 关键库
        val libc = if (JdkManager.arch() == "aarch64") {
            "usr/lib/aarch64-linux-gnu/libc.so.6"
        } else {
            "usr/lib/x86_64-linux-gnu/libc.so.6"
        }
        if (!File(root, libc).exists()) out += "⚠ 缺少 $libc"

        if (out.isEmpty()) out += "rootfs 关键文件检查通过"
        return out
    }

    /**
     * 用 `proot -r <rootfs> /bin/sh -c "echo PROOT_OK"` 做一次自检。
     *
     * 这条命令会走完整的「proot → loader → glibc 解释器 → /bin/sh」链路，
     * 正好覆盖最容易出错的三段。返回 (是否成功, 输出)。
     */
    fun probeProot(
        proot: File,
        rootfs: File,
        env: Map<String, String>,
        timeoutSeconds: Long = 20
    ): Pair<Boolean, String> = runCatching {
        val pb = ProcessBuilder(
            proot.absolutePath, "-r", rootfs.absolutePath, "/bin/sh", "-c", "echo PROOT_OK"
        )
        pb.redirectErrorStream(true)
        pb.directory(rootfs)
        env.forEach { (k, v) -> pb.environment()[k] = v }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val done = p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) runCatching { p.destroyForcibly() }
        out.contains("PROOT_OK") to out.trim()
    }.getOrElse { false to (it.message ?: it.javaClass.simpleName) }

    /** 建软链接，失败返回 false */
    private fun symlink(target: String, link: File): Boolean = runCatching {
        link.parentFile?.mkdirs()
        if (link.exists()) link.delete()
        android.system.Os.symlink(target, link.absolutePath)
        true
    }.getOrDefault(false)

    /** 下载 proot 二进制到 termux-prefix/bin/proot */
    fun ensureProot(log: (String) -> Unit): File? {
        val proot = File(JdkManager.prefixDir(), "bin/proot")
        if (proot.exists()) return proot
        log("下载 proot...")
        val url = "https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-${JdkManager.arch()}/Packages"
        return try {
            val text = Net.getString(url)
            var filename: String? = null
            text.split("\n\n").forEach { b ->
                if (b.lines().any { it == "Package: proot" }) {
                    b.lines().firstOrNull { it.startsWith("Filename: ") }?.let {
                        filename = it.removePrefix("Filename: ").trim()
                    }
                }
            }
            for (deps in listOf("libtalloc", "libandroid-shmem")) {
                text.split("\n\n").forEach { b ->
                    if (b.lines().any { it == "Package: $deps" }) {
                        b.lines().firstOrNull { it.startsWith("Filename: ") }?.let {
                            val f = it.removePrefix("Filename: ").trim()
                            val deb = File(App.ctx.cacheDir, "$deps.deb")
                            Net.download("https://packages.termux.dev/apt/termux-main/$f", deb) { _, _ -> }
                            DebExtractor.extract(deb, JdkManager.prefixDir(), log)
                            deb.delete()
                        }
                    }
                }
            }
            filename ?: return null
            val deb = File(App.ctx.cacheDir, "proot.deb")
            Net.download("https://packages.termux.dev/apt/termux-main/$filename", deb) { _, _ -> }
            DebExtractor.extract(deb, JdkManager.prefixDir(), log)
            deb.delete()
            proot.setExecutable(true, false)
            // loader 也要能执行，否则 proot 起不来
            findProotLoader()?.setExecutable(true, false)
            proot
        } catch (e: Exception) {
            log("proot 下载失败: ${e.message}")
            null
        }
    }

    /**
     * 准备 glibc rootfs（Ubuntu base）。已存在就直接返回。
     *
     * 大约 30MB 下载 + 解压，只在第一次启动基岩版时做一次。
     */
    fun ensureRootfs(
        log: (String) -> Unit,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false }
    ): File? {
        val dir = rootfsDir()
        if (hasRootfs()) return dir

        val url = if (JdkManager.arch().contains("aarch64") || JdkManager.arch().contains("arm")) {
            ROOTFS_URL
        } else {
            ROOTFS_URL_X86
        }
        val tar = File(App.ctx.cacheDir, "rootfs.tar.gz")
        // 之前下过就复用，避免换解包策略时重复下载 30MB
        val cached = tar.isFile && tar.length() > 20L * 1024 * 1024
        return try {
            if (cached) {
                log("复用已下载的 rootfs 包（${tar.length() / 1024 / 1024}MB）")
            } else {
                log("下载 glibc rootfs（约 30MB）…")
                if (!MultiDownloader.download(url, tar, onProgress = onProgress, shouldCancel = shouldCancel)) {
                    log("rootfs 下载失败：$url")
                    return null
                }
            }
            log("解压 rootfs…")
            dir.deleteRecursively()
            val ok = DebExtractor.extractTar(tar, dir, gzip = true, log = log)
            if (!ok) {
                log("rootfs 解压失败")
                return null
            }
            File(dir, ".layout").writeText(ROOTFS_LAYOUT)
            if (!hasRootfs()) {
                log("rootfs 解压不完整（找不到 bin/sh）")
                return null
            }
            runCatching { tar.delete() }
            log("rootfs 就绪：${dir.absolutePath}")
            dir
        } catch (e: Exception) {
            log("rootfs 准备失败：${e.message ?: e.javaClass.simpleName}")
            null
        }
    }
}
