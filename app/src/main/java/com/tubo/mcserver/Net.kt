package com.tubo.mcserver

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object Net {
    fun getString(url: String, timeout: Int = 20000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeout
            readTimeout = timeout
            setRequestProperty("User-Agent", "MCServerLauncher/1.0")
        }
        conn.inputStream.use { input ->
            BufferedReader(input.reader()).use { r ->
                return r.readText()
            }
        }
    }

    fun getJson(url: String): JSONObject = JSONObject(getString(url))

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
