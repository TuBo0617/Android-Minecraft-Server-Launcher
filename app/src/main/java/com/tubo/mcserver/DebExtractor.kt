package com.tubo.mcserver

import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * 解 Termux .deb 包（ar 归档 + data.tar.xz）到目标目录。
 *
 * deb 内路径形如 `./data/data/com.termux/files/usr/...`，统一剥离 Termux 前缀后落到 destRoot。
 *
 * 之前这里有两个坑，导致 openjdk 解包到一半报 EISDIR：
 * 1. `./` 开头的条目只去掉了 `./`，没有继续剥离 Termux 前缀，文件被写到
 *    `destRoot/data/data/com.termux/files/usr/...`；
 * 2. 同名路径先当目录建、后当文件写时直接抛 `EISDIR (Is a directory)`。
 *
 * 另外补齐了 GNU 长文件名（'L' / 'K'）与 PAX 扩展头（'x'）解析 —— JDK 里有大量
 * 超过 100 字节的路径，不处理会被截断或直接丢掉。
 */
object DebExtractor {

    private const val TERMUX_USR = "data/data/com.termux/files/usr/"

    /** 单个 tar 条目上限，超过就跳过（正常 JDK 文件远小于此） */
    private const val MAX_ENTRY_SIZE = 256 * 1024 * 1024

    /**
     * 解压普通 tar 归档（`.tar` / `.tar.gz`），用于 glibc rootfs 这类不是 deb 的包。
     *
     * 复用同一套 tar 解析（含 GNU 长文件名、PAX、软/硬链接），
     * 只是跳过了 ar 头与 Termux 前缀剥离（rootfs 里本来就没有那个前缀，剥离是空操作）。
     */
    fun extractTar(file: File, destRoot: File, gzip: Boolean, log: (String) -> Unit): Boolean {
        return try {
            destRoot.mkdirs()
            val raw = FileInputStream(file).buffered()
            val input: InputStream = if (gzip) java.util.zip.GZIPInputStream(raw, 64 * 1024) else raw
            input.use { untar(it, destRoot, log, guestLinks = true) }
            true
        } catch (e: Exception) {
            log("解包失败: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /** 解整个 deb，返回成功与否。全程流式，不把整包读进内存（openjdk 的 deb 有两百多 MB） */
    fun extract(deb: File, destRoot: File, log: (String) -> Unit): Boolean {
        return try {
            destRoot.mkdirs()
            FileInputStream(deb).use { fis ->
                val magic = ByteArray(8)
                if (readFull(fis, magic, 8) < 8 ||
                    String(magic, StandardCharsets.US_ASCII) != "!<arch>\n"
                ) {
                    log("不是 ar 归档")
                    return false
                }

                val hdr = ByteArray(60)
                while (true) {
                    if (readFull(fis, hdr, 60) < 60) break
                    // ar 头布局：name[0..16] / mtime[16..28] / uid[28..34] / gid[34..40]
                    //           mode[40..48] / size[48..58] / magic[58..60]
                    val name = String(hdr, 0, 16, StandardCharsets.US_ASCII).trim().trimEnd('/')
                    val size = String(hdr, 48, 10, StandardCharsets.US_ASCII).trim().toLongOrNull() ?: break
                    if (size <= 0) break
                    val padded = size + (size % 2)

                    when {
                        name == "data.tar.xz" -> {
                            log("解包 data.tar.xz (${size / 1024} KB)...")
                            val limited = LimitedInputStream(fis, size)
                            XZInputStream(limited).use { untar(it, destRoot, log) }
                            return true
                        }
                        name == "data.tar" -> {
                            log("解包 data.tar (${size / 1024} KB)...")
                            untar(LimitedInputStream(fis, size), destRoot, log)
                            return true
                        }
                        name.startsWith("data.tar.") -> {
                            log("不支持的压缩格式 $name（只支持 data.tar.xz）")
                            return false
                        }
                    }
                    skipFully(fis, padded)
                }
            }
            log("deb 内无 data.tar")
            false
        } catch (e: Exception) {
            log("解包失败: ${e.message}")
            false
        }
    }

    /**
     * @param guestLinks true 表示这棵树将来要在 **proot 里**当 guest 根使用。
     *
     * 这一点非常关键：proot 里的软链接目标是**相对 guest 根**的。
     * 如果把 `bin -> usr/bin` 写成宿主机绝对路径 `/data/user/0/.../rootfs/usr/bin`，
     * 在 guest 里这个路径根本不存在，于是 proot 报 `'/bin/sh' not found`。
     * 所以 guest 模式必须原样保留 tar 里的 linkname（相对的就相对，绝对的就写 `/xxx`）。
     */
    private fun untar(
        input: InputStream,
        destRoot: File,
        log: (String) -> Unit,
        guestLinks: Boolean = false
    ) {
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        var fileCount = 0
        var skipped = 0

        while (true) {
            val n = readFull(input, header, 512)
            if (n < 512) break
            if (header.all { it == 0.toByte() }) break

            var name = readString(header, 0, 100)
            val modeStr = readString(header, 100, 8)
            val sizeStr = readString(header, 124, 12)
            val typeFlag = header[156].toInt().toChar()
            var linkName = readString(header, 157, 100)

            val size = parseSize(header, sizeStr)
            val pad = (512 - size % 512) % 512

            // 元数据条目很小，直接读进内存；普通文件流式落盘，避免大文件 OOM
            val meta: ByteArray? = if (typeFlag == 'L' || typeFlag == 'K' ||
                typeFlag == 'x' || typeFlag == 'g'
            ) {
                val buf = ByteArray(size)
                readFull(input, buf, size)
                if (pad > 0) readFull(input, ByteArray(pad), pad)
                buf
            } else {
                null
            }

            when (typeFlag) {
                'L' -> {
                    longName = String(meta ?: ByteArray(0), StandardCharsets.UTF_8).trimEnd('\u0000')
                    continue
                }
                'K' -> {
                    longLink = String(meta ?: ByteArray(0), StandardCharsets.UTF_8).trimEnd('\u0000')
                    continue
                }
                'x', 'g' -> {
                    val pax = parsePax(meta ?: ByteArray(0))
                    pax["path"]?.let { paxPath = it }
                    pax["linkpath"]?.let { paxLink = it }
                    continue
                }
            }

            longName?.let { name = it; longName = null }
            longLink?.let { linkName = it; longLink = null }
            paxPath?.let { name = it; paxPath = null }
            paxLink?.let { linkName = it; paxLink = null }

            val rel = if (name.isEmpty()) null else stripPrefix(name)
            if (rel == null) {
                skipFully(input, size.toLong() + pad)
                continue
            }
            val target = File(destRoot, rel)

            when (typeFlag) {
                '5' -> {
                    if (target.isFile) target.delete()
                    target.mkdirs()
                    skipFully(input, size.toLong() + pad)
                }
                '0', '\u0000', '7' -> {
                    if (size > MAX_ENTRY_SIZE) {
                        log("跳过超大条目: $rel (${size / 1024 / 1024} MB)")
                        skipFully(input, size.toLong() + pad)
                        skipped++
                        continue
                    }
                    if (target.isDirectory) {
                        val children = target.list()
                        if (children != null && children.isNotEmpty()) {
                            log("跳过（同名目录已存在）: $rel")
                            skipFully(input, size.toLong() + pad)
                            skipped++
                            continue
                        }
                        target.delete()
                    }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> copyExactly(input, out, size) }
                    if (pad > 0) skipFully(input, pad.toLong())
                    val m = parseOctalSafe(modeStr)
                    if (m and 0b001_000_000 != 0 || rel.startsWith("bin/")) {
                        target.setExecutable(true, false)
                    }
                    fileCount++
                }
                '2', '1' -> {
                    skipFully(input, size.toLong() + pad)
                    if (target.isDirectory) continue
                    target.parentFile?.mkdirs()
                    val linkRel = stripPrefix(linkName) ?: linkName.trimStart('/')
                    val linkTarget = if (linkName.startsWith("/")) {
                        File(destRoot, linkRel)
                    } else {
                        File(target.parentFile, linkRel)
                    }
                    // guest 模式：原样写 tar 里的 linkname（相对保持相对，绝对保持 /xxx）
                    val linkText = if (guestLinks) linkName else linkTarget.absolutePath
                    if (!target.exists()) {
                        if (!createSymlink(linkText, target)) {
                            // 软链接不可用时退化为复制真实文件
                            runCatching {
                                if (linkTarget.isFile) {
                                    FileInputStream(linkTarget).use { i ->
                                        FileOutputStream(target).use { o -> i.copyTo(o) }
                                    }
                                }
                            }.onFailure { log("链接失败: ${target.name} -> $linkName") }
                        }
                    }
                }
                else -> skipFully(input, size.toLong() + pad)
            }
        }
        log("已写入 $fileCount 个文件" + if (skipped > 0) "，跳过 $skipped 个" else "")
    }

    /** 从 [input] 精确拷贝 [size] 字节到 [out] */
    private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Int) {
        val buf = ByteArray(64 * 1024)
        var left = size.toLong()
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r == -1) break
            out.write(buf, 0, r)
            left -= r
        }
        out.flush()
    }

    /** 创建软链接。优先 android.system.Os.symlink，失败再试 Files.createSymbolicLink */
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

    /**
     * 剥离 Termux 前缀。
     *
     * 兼容 `./data/data/...`、`data/data/...`、`/data/data/...` 三种写法，
     * 并反复剥离直到稳定（有些包会出现前缀重复）。
     */
    private fun stripPrefix(raw: String): String? {
        var s = raw.replace('\\', '/').trim()
        while (s.startsWith("./")) s = s.removePrefix("./")

        val prefixes = listOf(
            TERMUX_USR,
            "/$TERMUX_USR",
            "data/data/com.termux/files/home/",
            "/data/data/com.termux/files/home/"
        )
        var changed = true
        while (changed) {
            changed = false
            for (p in prefixes) {
                if (s.startsWith(p)) {
                    s = s.substring(p.length)
                    changed = true
                }
            }
        }

        s = s.trimStart('/')
        if (s.isEmpty()) return null
        // 拒绝越界路径
        if (s == ".." || s.startsWith("../") || s.contains("/../")) return null
        return s
    }

    private fun parsePax(data: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < data.size) {
            var sp = i
            while (sp < data.size && data[sp].toInt().toChar() != ' ') sp++
            if (sp >= data.size) break
            val len = String(data, i, sp - i, StandardCharsets.US_ASCII).toIntOrNull() ?: break
            if (len <= 0 || i + len > data.size) break
            val record = String(data, i, len, StandardCharsets.UTF_8)
            val kv = record.substringAfter(' ', "")
            val eq = kv.indexOf('=')
            if (eq > 0) {
                out[kv.substring(0, eq)] = kv.substring(eq + 1).trimEnd('\u0000', '\n')
            }
            i += len
        }
        return out
    }

    private fun readString(buf: ByteArray, offset: Int, len: Int): String =
        String(buf, offset, len, StandardCharsets.US_ASCII).trimEnd('\u0000').trim()

    /** tar 的 size 字段：八进制字符串，或 GNU base-256 编码 */
    private fun parseSize(header: ByteArray, sizeStr: String): Int {
        val first = header[124].toInt() and 0xFF
        if (first and 0x80 != 0) {
            var v = 0L
            for (i in 125 until 136) {
                v = (v shl 8) or (header[i].toLong() and 0xFF)
            }
            return v.coerceAtMost(Int.MAX_VALUE.toLong() - 1).toInt()
        }
        if (sizeStr.isEmpty()) return 0
        return parseOctalSafe(sizeStr).coerceAtLeast(0)
    }

    private fun parseOctalSafe(s: String): Int {
        var v = 0L
        for (c in s) {
            if (c in '0'..'7') v = v * 8 + (c - '0')
        }
        return v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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

    private fun skipFully(inp: InputStream, len: Long) {
        var left = len
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r == -1) break
            left -= r
        }
    }

    /** 把底层流限制在 ar 成员范围内，防止 xz 解压读过头吃进下一个成员 */
    private class LimitedInputStream(
        private val src: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = src.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = src.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(src.available().toLong(), remaining).toInt().coerceAtLeast(0)
    }
}
