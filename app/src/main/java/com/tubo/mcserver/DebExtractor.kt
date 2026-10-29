package com.tubo.mcserver

import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.charset.StandardCharsets

/**
 * 解 Termux .deb 包（ar 归档 + data.tar.xz）到目标目录。
 * deb 内路径形如 data/data/com.termux/files/usr/...，统一剥离前缀落到 destRoot。
 */
object DebExtractor {

    /** 解整个 deb，返回成功与否 */
    fun extract(deb: File, destRoot: File, log: (String) -> Unit): Boolean {
        return try {
            destRoot.mkdirs()
            val bytes = FileInputStream(deb).use { it.readBytes() }
            // 1. ar magic
            val magic = String(bytes, 0, 8, StandardCharsets.US_ASCII)
            if (magic != "!<arch>\n") { log("不是 ar 归档: $magic"); return false }
            var off = 8
            var dataTar: ByteArray? = null
            while (off + 60 <= bytes.size) {
                val hdr = String(bytes, off, 60, StandardCharsets.US_ASCII)
                val name = hdr.substring(0, 16).trim().trimEnd('/')
                val sizeStr = hdr.substring(48, 58).trim()
                val size = sizeStr.toInt()
                off += 60
                val data = bytes.copyOfRange(off, off + size)
                when {
                    name.startsWith("data.tar.xz") -> dataTar = data
                    name.startsWith("data.tar.gz") -> { /* 暂不支持 */ }
                    name.startsWith("data.tar") -> dataTar = data
                }
                off += size
                if (size % 2 == 1) off += 1  // 2字节对齐
            }
            if (dataTar == null) { log("deb 内无 data.tar"); return false }
            log("解包 data.tar.xz (${dataTar.size / 1024} KB)...")
            untarXz(ByteArrayInputStream(dataTar), destRoot, log)
            true
        } catch (e: Exception) {
            log("解包失败: ${e.message}")
            false
        }
    }

    private fun untarXz(inXz: InputStream, destRoot: File, log: (String) -> Unit) {
        XZInputStream(inXz).use { xz ->
            val buf = ByteArray(512)
            while (true) {
                val n = readFull(xz, buf, 512)
                if (n < 512) break
                // 全零块 = 结束
                if (buf.all { it == 0.toByte() }) break
                val name = String(buf, 0, 100, StandardCharsets.US_ASCII).trimEnd('\u0000').trim()
                val sizeStr = String(buf, 124, 12, StandardCharsets.US_ASCII).trim().trimEnd('\u0000')
                val typeFlag = buf[156].toInt().toChar()
                val size = if (sizeStr.isEmpty()) 0 else parseOctal(sizeStr)
                // 读文件数据
                val data = if (size > 0) ByteArray(size).also { readFull(xz, it, size) } else null
                // 补到 512 对齐
                val pad = (512 - size % 512) % 512
                if (pad > 0) readFull(xz, ByteArray(pad), pad)

                if (name.isEmpty()) continue
                // 剥离 termux 前缀
                val rel = stripPrefix(name) ?: continue
                val target = File(destRoot, rel)
                when (typeFlag) {
                    '5' -> target.mkdirs()
                    '0', '\u0000' -> {
                        target.parentFile?.mkdirs()
                        if (data != null) FileOutputStream(target).use { it.write(data) }
                        if (name.endsWith("/")) target.mkdirs()
                        // 保留可执行位
                        val mode = String(buf, 100, 8, StandardCharsets.US_ASCII).trim().trimEnd('\u0000')
                        if (mode.isNotEmpty()) {
                            val m = parseOctal(mode)
                            if (m and 0b001_000_000 != 0) target.setExecutable(true, false)
                        }
                    }
                    '1', '2' -> {
                        // '2' = 软链接, '1' = 硬链接。tar 里链接目标在 157 偏移的 100 字节。
                        val linkName = String(buf, 157, 100, StandardCharsets.US_ASCII)
                            .trimEnd('\u0000').trim()
                        target.parentFile?.mkdirs()
                        // 链接目标同样需要剥掉 termux 前缀（相对路径除外）
                        val linkRel = stripPrefix(linkName) ?: linkName
                        val linkTarget = if (linkRel.startsWith("/")) {
                            File(linkRel)
                        } else {
                            // 相对路径：相对于被链接文件所在目录
                            File(target.parentFile, linkRel)
                        }
                        if (!target.exists()) {
                            if (!createSymlink(linkTarget.absolutePath, target)) {
                                // 软链接不可用时退化为复制真实文件
                                runCatching {
                                    if (File(linkTarget.absolutePath).isFile)
                                        FileInputStream(linkTarget.absolutePath).use { i ->
                                            FileOutputStream(target).use { o -> i.copyTo(o) }
                                        }
                                }.onFailure { log("链接失败: ${target.name} -> $linkName") }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 创建软链接。优先用 android.system.Os.symlink（API 21 起存在），
     * 失败再尝试 java.nio.file.Files.createSymbolicLink（API 26 起）。
     */
    private fun createSymlink(targetPath: String, link: File): Boolean {
        try {
            val os = Class.forName("android.system.Os")
            val m = os.getMethod("symlink", String::class.java, String::class.java)
            m.invoke(null, targetPath, link.absolutePath)
            return true
        } catch (_: Throwable) {
        }
        return try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), File(targetPath).toPath())
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun stripPrefix(raw: String): String? {
        val p = "data/data/com.termux/files/usr/"
        return when {
            raw.startsWith(p) -> raw.removePrefix(p)
            raw.startsWith("./") -> raw.removePrefix("./")
            raw.startsWith("/data/data/com.termux/files/usr/") ->
                raw.removePrefix("/data/data/com.termux/files/usr/")
            else -> null
        }
    }

    private fun parseOctal(s: String): Int {
        var v = 0
        for (c in s) {
            if (c in '0'..'7') v = v * 8 + (c - '0')
        }
        return v
    }

    private fun readFull(inp: InputStream, buf: ByteArray, len: Int): Int {
        var done = 0
        while (done < len) {
            val r = inp.read(buf, done, len - done)
            if (r == -1) break
            done += r
        }
        return done
    }
}
