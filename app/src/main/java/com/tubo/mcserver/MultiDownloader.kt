package com.tubo.mcserver

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 多线程分块下载器（更新日志第 11 条）。
 *
 * - 用 HTTP `Range` 把文件切成 N 段并行拉取，写入同一文件的对应偏移；
 * - 支持按 KB/s 限速（按线程数均分配额）；
 * - 支持 HTTP 代理（来自 [Prefs.proxyOrNull]）；
 * - 服务端不支持 Range 或长度未知时自动退回单流下载。
 */
object MultiDownloader {

    private const val UA = "MCServerLauncher/1.0"
    private const val BUF = 64 * 1024

    fun download(
        url: String,
        dest: File,
        threads: Int = Prefs.downloadThreads,
        speedLimitKb: Int = Prefs.downloadSpeedLimitKb,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false }
    ): Boolean {
        val n = threads.coerceIn(1, 128)
        val proxy = Prefs.proxyOrNull()

        // 先跟随重定向：CurseForge 的 edge CDN 对 Range 请求返回 404，
        // 只有最终的 mediafilez 地址才支持分块
        val realUrl = Net.resolveFinalUrl(url)

        val (total, supportRange) = probe(realUrl, proxy)

        dest.parentFile?.mkdirs()

        // 长度未知 / 不支持分块 / 单线程 → 走单流
        if (total <= 0 || !supportRange || n == 1) {
            return singleStream(realUrl, dest, proxy, speedLimitKb, onProgress, shouldCancel)
        }

        val done = AtomicLong(0)
        val failed = AtomicBoolean(false)
        val partSize = (total + n - 1) / n
        val perThreadLimit = if (speedLimitKb > 0) speedLimitKb.toLong() * 1024 / n else 0L

        val raf = try {
            RandomAccessFile(dest, "rw").apply { setLength(total) }
        } catch (e: Exception) {
            return false
        }

        val pool = Executors.newFixedThreadPool(n)
        try {
            for (i in 0 until n) {
                val start = i.toLong() * partSize
                val end = minOf(total - 1, start + partSize - 1)
                if (start > end) continue
                pool.execute {
                    if (failed.get() || shouldCancel()) return@execute
                    downloadChunk(realUrl, proxy, raf, start, end, done, total, perThreadLimit, onProgress, shouldCancel, failed)
                }
            }
            pool.shutdown()
            pool.awaitTermination(6, TimeUnit.HOURS)
        } catch (e: Exception) {
            failed.set(true)
        } finally {
            runCatching { raf.close() }
            runCatching { pool.shutdownNow() }
        }

        return when {
            shouldCancel() -> {
                runCatching { dest.delete() }
                false
            }
            failed.get() -> {
                // 分块失败（部分 CDN 对 Range 不友好）→ 退回单线程整包下载
                runCatching { dest.delete() }
                singleStream(realUrl, dest, proxy, speedLimitKb, onProgress, shouldCancel)
            }
            else -> dest.exists() && dest.length() > 0
        }
    }

    private fun downloadChunk(
        url: String,
        proxy: Proxy?,
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        done: AtomicLong,
        total: Long,
        perThreadLimit: Long,
        onProgress: (Long, Long) -> Unit,
        shouldCancel: () -> Boolean,
        failed: AtomicBoolean
    ) {
        var conn: HttpURLConnection? = null
        try {
            conn = open(url, proxy)
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Range", "bytes=$start-$end")
            val code = conn.responseCode
            if (code != 206 && code != 200) throw Exception("HTTP $code")

            conn.inputStream.use { input ->
                val buf = ByteArray(BUF)
                var pos = start
                var windowStart = System.currentTimeMillis()
                var windowBytes = 0L
                while (true) {
                    if (failed.get() || shouldCancel()) return
                    val read = input.read(buf)
                    if (read == -1) break
                    val toWrite = minOf(read.toLong(), end - pos + 1).toInt()
                    if (toWrite <= 0) break
                    synchronized(raf) {
                        raf.seek(pos)
                        raf.write(buf, 0, toWrite)
                    }
                    pos += toWrite
                    onProgress(done.addAndGet(toWrite.toLong()), total)

                    if (perThreadLimit > 0) {
                        windowBytes += toWrite
                        val elapsed = System.currentTimeMillis() - windowStart
                        val expected = windowBytes * 1000L / perThreadLimit
                        if (expected > elapsed) {
                            runCatching { Thread.sleep(expected - elapsed) }
                        }
                        if (elapsed > 2000L) {
                            windowStart = System.currentTimeMillis()
                            windowBytes = 0
                        }
                    }
                }
            }
        } catch (e: Exception) {
            failed.set(true)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun singleStream(
        url: String,
        dest: File,
        proxy: Proxy?,
        speedLimitKb: Int,
        onProgress: (Long, Long) -> Unit,
        shouldCancel: () -> Boolean
    ): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(url, proxy)
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            val limit = if (speedLimitKb > 0) speedLimitKb.toLong() * 1024 else 0L
            var done = 0L
            var windowStart = System.currentTimeMillis()
            var windowBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(BUF)
                    while (true) {
                        if (shouldCancel()) {
                            out.flush()
                            dest.delete()
                            return false
                        }
                        val read = input.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                        done += read
                        onProgress(done, total)
                        if (limit > 0) {
                            windowBytes += read
                            val elapsed = System.currentTimeMillis() - windowStart
                            val expected = windowBytes * 1000L / limit
                            if (expected > elapsed) runCatching { Thread.sleep(expected - elapsed) }
                            if (elapsed > 2000L) {
                                windowStart = System.currentTimeMillis()
                                windowBytes = 0
                            }
                        }
                    }
                    out.flush()
                }
            }
            dest.exists() && dest.length() > 0
        } catch (e: Exception) {
            runCatching { dest.delete() }
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 探测总长度与是否支持 Range；HEAD 不可用时用 `Range: bytes=0-0` 兜底 */
    private fun probe(url: String, proxy: Proxy?): Pair<Long, Boolean> {
        var conn: HttpURLConnection? = null
        try {
            conn = open(url, proxy)
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.requestMethod = "HEAD"
            conn.setRequestProperty("User-Agent", UA)
            val len = conn.contentLengthLong
            val accept = (conn.getHeaderField("Accept-Ranges") ?: "").contains("bytes", ignoreCase = true)
            if (len > 0) return Pair(len, accept || conn.responseCode == 200)
        } catch (_: Exception) {
        } finally {
            runCatching { conn?.disconnect() }
        }

        var conn2: HttpURLConnection? = null
        try {
            conn2 = open(url, proxy)
            conn2.connectTimeout = 15000
            conn2.readTimeout = 15000
            conn2.requestMethod = "GET"
            conn2.setRequestProperty("User-Agent", UA)
            conn2.setRequestProperty("Range", "bytes=0-0")
            val code = conn2.responseCode
            val rangeHeader = conn2.getHeaderField("Content-Range") ?: ""
            val total = rangeHeader.substringAfterLast('/', "-").toLongOrNull() ?: conn2.contentLengthLong
            runCatching { conn2.inputStream.use { it.read() } }
            return Pair(total, code == 206)
        } catch (_: Exception) {
            return Pair(-1L, false)
        } finally {
            runCatching { conn2?.disconnect() }
        }
    }

    private fun open(url: String, proxy: Proxy?): HttpURLConnection =
        (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()) as HttpURLConnection
}
