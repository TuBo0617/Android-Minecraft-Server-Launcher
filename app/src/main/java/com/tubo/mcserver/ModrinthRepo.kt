package com.tubo.mcserver

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * 资源中心条目（Modrinth / CurseForge 共用结构）。
 *
 * @param id 项目 id（Modrinth 是 base62 project_id，CurseForge 是数字 modId）
 */
data class ModEntry(
    val slug: String,
    val title: String,
    val description: String,
    val downloads: Int,
    val id: String = slug,
    val iconUrl: String = "",
    val source: String = "modrinth",
    /** mod / resourcepack / modpack / plugin */
    val type: String = "mod"
)

/** 某个资源的可下载版本（Modrinth / CurseForge 共用） */
data class PackVersion(
    val id: String,
    val name: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val fileName: String,
    val url: String
)

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) out += optString(i)
    return out
}

object ModrinthRepo {
    private const val API = "https://api.modrinth.com/v2"

    /** 资源类型 → 可用的 loader 列表；资源包/整合包不按 loader 过滤 */
    private fun loadersOf(type: String, instanceLoader: String): List<String> = when (type) {
        "plugin" -> listOf("bukkit", "paper", "spigot", "purpur", "folia")
        "mod" -> when (instanceLoader.uppercase()) {
            "FORGE" -> listOf("forge")
            "NEOFORGE" -> listOf("neoforge")
            "QUILT" -> listOf("quilt", "fabric")
            "FABRIC" -> listOf("fabric", "quilt")
            else -> listOf("fabric", "forge", "quilt", "neoforge")
        }
        else -> emptyList()
    }

    /**
     * 检索资源。
     *
     * 实测（2026-10）：`facets` 是「数组的数组」，每个内层数组是 OR 组、组之间是 AND；
     * `project_type` 支持 mod / plugin / resourcepack / modpack / shader。
     */
    fun search(
        query: String,
        mcVersion: String,
        type: String,
        limit: Int = 20,
        offset: Int = 0
    ): List<ModEntry> {
        val groups = ArrayList<String>()
        groups += "[\"project_type:$type\"]"
        if (mcVersion.isNotBlank()) groups += "[\"versions:$mcVersion\"]"
        val facets = "[" + groups.joinToString(",") + "]"

        val url = "$API/search?query=${enc(query)}&facets=${enc(facets)}" +
                "&limit=$limit&offset=$offset&index=relevance"
        val o = Net.getJson(url)
        val arr = o.optJSONArray("hits") ?: return emptyList()
        val out = ArrayList<ModEntry>()
        for (i in 0 until arr.length()) {
            val h = arr.getJSONObject(i)
            val slug = h.optString("slug")
            out += ModEntry(
                slug = slug,
                title = h.optString("title", slug),
                description = h.optString("description", ""),
                downloads = h.optLong("downloads", 0L).toInt(),
                id = h.optString("project_id", slug),
                iconUrl = h.optString("icon_url", ""),
                source = "modrinth",
                type = type
            )
        }
        return out
    }

    /**
     * 下载适配当前版本的文件到 destDir，返回文件名。
     *
     * @param loader 实例的加载器（FABRIC/FORGE/...），用于收窄 mod 的 loader 过滤。
     *               放在参数末尾，避免破坏旧的位置传参调用。
     */
    fun install(
        slug: String,
        mcVersion: String,
        type: String,
        destDir: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
        loader: String = ""
    ): String {
        val params = ArrayList<String>()
        params += "game_versions=" + enc("[\"$mcVersion\"]")
        val loaders = loadersOf(type, loader)
        if (loaders.isNotEmpty()) {
            params += "loaders=" + enc(loaders.joinToString(",", "[", "]") { "\"$it\"" })
        }

        onLog("查询版本文件（$type${if (loaders.isEmpty()) "" else " · " + loaders.joinToString("/")}）…")
        val arr = JSONArray(Net.getString("$API/project/$slug/version?" + params.joinToString("&")))
        if (arr.length() == 0) throw Exception("没有适配 $mcVersion 的文件")

        val v = arr.getJSONObject(0)
        val files = v.getJSONArray("files")
        var target: JSONObject = files.getJSONObject(0)
        for (i in 0 until files.length()) {
            if (files.getJSONObject(i).optBoolean("primary", false)) {
                target = files.getJSONObject(i)
                break
            }
        }
        val fname = target.getString("filename")
        val furl = target.getString("url")
        val dest = File(destDir, fname)
        destDir.mkdirs()

        onLog("下载 $fname …")
        if (!MultiDownloader.download(furl, dest, onProgress = onProgress, shouldCancel = shouldCancel)) {
            throw Exception("下载失败: $fname")
        }
        onLog("已保存到 ${dest.absolutePath}")
        return fname
    }

    /** 拉取项目的全部版本（不做过滤，交给界面按 MC 版本/加载器筛） */
    fun versions(projectId: String): List<PackVersion> {
        val arr = JSONArray(Net.getString("$API/project/$projectId/version"))
        val out = ArrayList<PackVersion>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val files = v.optJSONArray("files") ?: continue
            var picked: JSONObject? = null
            for (j in 0 until files.length()) {
                val f = files.getJSONObject(j)
                if (picked == null || f.optBoolean("primary", false)) picked = f
                if (f.optBoolean("primary", false)) break
            }
            val f = picked ?: continue
            out += PackVersion(
                id = v.optString("id"),
                name = v.optString("version_number"),
                gameVersions = v.optJSONArray("game_versions").toStringList(),
                loaders = v.optJSONArray("loaders").toStringList(),
                fileName = f.optString("filename"),
                url = f.optString("url")
            )
        }
        return out
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
