package com.tubo.mcserver

import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

data class ModEntry(
    val slug: String,
    val title: String,
    val description: String,
    val downloads: Int
)

object ModrinthRepo {
    private const val API = "https://api.modrinth.com/v2"

    /** type: mod 或 plugin */
    fun search(query: String, mcVersion: String, type: String): List<ModEntry> {
        val facet = """[["project_type:$type"],["versions:$mcVersion"]]"""
        val url = "$API/search?query=${URLEncoder.encode(query, "UTF-8")}" +
                "&facets=${URLEncoder.encode(facet, "UTF-8")}&limit=20&index=relevance"
        val o = Net.getJson(url)
        val arr = o.getJSONArray("hits")
        val out = ArrayList<ModEntry>()
        for (i in 0 until arr.length()) {
            val h = arr.getJSONObject(i)
            out += ModEntry(
                slug = h.getString("slug"),
                title = h.optString("title", h.getString("slug")),
                description = h.optString("description", ""),
                downloads = h.optInt("downloads", 0)
            )
        }
        return out
    }

    /** 下载该项目适配当前 mcVersion 的最新文件到 destDir，返回文件名 */
    fun install(
        slug: String,
        mcVersion: String,
        type: String,
        destDir: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
        shouldCancel: () -> Boolean = { false }
    ): String {
        val loaderFacet = if (type == "plugin") "[\"bukkit\",\"paper\",\"spigot\",\"purpur\"]"
                           else "[\"fabric\",\"forge\",\"quilt\"]"
        val url = "$API/project/$slug/version?loaders=$loaderFacet&game_versions=" +
                URLEncoder.encode("[\"$mcVersion\"]", "UTF-8")
        onLog("查询版本文件…")
        val arr = org.json.JSONArray(Net.getString(url))
        if (arr.length() == 0) throw Exception("没有适配 $mcVersion 的文件")
        val v = arr.getJSONObject(0)
        val files = v.getJSONArray("files")
        val target: JSONObject = if (files.length() == 1) files.getJSONObject(0)
            else {
                // 优先 primary
                var picked = files.getJSONObject(0)
                for (i in 0 until files.length()) {
                    if (files.getJSONObject(i).optBoolean("primary", false)) { picked = files.getJSONObject(i); break }
                }
                picked
            }
        val fname = target.getString("filename")
        val furl = target.getString("url")
        val dest = File(destDir, fname)
        onLog("下载 $fname …")
        val ok = Net.download(furl, dest, onProgress, shouldCancel)
        if (!ok) throw Exception("下载失败: $fname")
        onLog("已保存到 ${dest.absolutePath}")
        return fname
    }
}
