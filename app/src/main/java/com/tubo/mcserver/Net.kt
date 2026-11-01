package com.tubo.mcserver

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object Net {
    fun getString(
        url: String,
        timeout: Int = 20000,
        headers: Map<String, String> = emptyMap()
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeout
            readTimeout = timeout
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MCServerLauncher/1.0")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("HTTP $code @ $url")
            conn.inputStream.use { input ->
                BufferedReader(input.reader()).use { r ->
                    return r.readText()
                }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    fun getJson(url: String): JSONObject = JSONObject(getString(url))

    /**
     * 跟随 302/301 得到最终下载地址。
     *
     * 必要性：CurseForge 的 `edge.forgecdn.net` 会 302 到 `mediafilez.forgecdn.net`，
     * 而**带 Range 头请求 edge 会直接 404**。所以分块下载前必须先拿到最终地址。
     */
    fun resolveFinalUrl(url: String, timeout: Int = 15000): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                requestMethod = "HEAD"
                connectTimeout = timeout
                readTimeout = timeout
                setRequestProperty("User-Agent", "MCServerLauncher/1.0")
            }
            conn.responseCode
            conn.url?.toString() ?: url
        } catch (e: Exception) {
            url
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 取二进制内容（索引文件等），失败抛异常并带上 HTTP 状态码 */
    fun getBytes(
        url: String,
        timeout: Int = 30000,
        headers: Map<String, String> = emptyMap()
    ): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeout
            readTimeout = timeout
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MCServerLauncher/1.0")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("HTTP $code")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 下载文件，带进度回调与取消支持。返回是否成功（取消也返回 false）。 */
    fun download(
        url: String,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit,
        shouldCancel: () -> Boolean
    ): Boolean {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 60000
                setRequestProperty("User-Agent", "MCServerLauncher/1.0")
            }
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input: InputStream ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        if (shouldCancel()) {
                            out.flush()
                            dest.delete()
                            return false
                        }
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                    out.flush()
                }
            }
            return dest.exists() && dest.length() > 0
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            conn?.disconnect()
        }
    }

    /** 兼容旧调用 */
    fun download(url: String, dest: File, onProgress: (done: Long, total: Long) -> Unit): Boolean =
        download(url, dest, onProgress) { false }
}
