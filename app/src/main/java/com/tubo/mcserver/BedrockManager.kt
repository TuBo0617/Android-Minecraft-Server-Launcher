package com.tubo.mcserver

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/** 基岩版 BDS 下载与 proot 依赖管理 */
object BedrockManager {
    // 微软官方 BDS 下载地址（版本号会随时间更新，可在此替换）
    private const val BDS_URL = "https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-1.21.50.03.zip"

    fun downloadBds(
        inst: Instance,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit = {},
        shouldCancel: () -> Boolean = { false }
    ): Pair<Boolean, String> {
        return try {
            val zip = File(inst.dir(), "bds.zip")
            inst.dir().mkdirs()
            onLog("下载 BDS: $BDS_URL")
            val ok = Net.download(BDS_URL, zip, onProgress, shouldCancel)
            if (!ok) return Pair(false, "BDS 下载失败（链接可能已更新，需替换 BDS_URL）")
            onLog("解压 BDS…")
            // 解压 zip
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var e = zis.nextEntry
                val buf = ByteArray(8192)
                while (e != null) {
                    val f = File(inst.dir(), e.name)
                    if (e.isDirectory) f.mkdirs()
                    else {
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { out ->
                            var n: Int
                            while (zis.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                        }
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            zip.delete()
            // bedrock_server 加可执行权限
            File(inst.dir(), "bedrock_server").setExecutable(true, false)
            Pair(true, "BDS 解压完成")
        } catch (e: Exception) {
            Pair(false, "错误: ${e.message}")
        }
    }

    /** 下载 proot 二进制到 termux-prefix/bin/proot */
    fun ensureProot(log: (String) -> Unit): File? {
        val proot = File(JdkManager.prefixDir(), "bin/proot")
        if (proot.exists()) return proot
        log("下载 proot...")
        // 复用 JdkManager 的包索引查询
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
            fn@ for (deps in listOf("libtalloc", "libandroid-shmem")) {
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
            proot
        } catch (e: Exception) {
            log("proot 下载失败: ${e.message}")
            null
        }
    }
}
