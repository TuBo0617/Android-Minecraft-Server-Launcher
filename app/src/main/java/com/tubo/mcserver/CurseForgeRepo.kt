package com.tubo.mcserver

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * CurseForge 资源仓库。
 *
 * Key 策略与参考实现一致：key 只从 `BuildConfig.CURSEFORGE_API` 读取（构建时由环境变量
 * `CURSEFORGE_API_KEY` / `.curseforge_api.txt` / `-Pcurseforge_api_key=` 注入），默认为空，
 * 绝不硬编码或提交。key 为空时自动回落免 key 的 MCIM 镜像。
 */
object CurseForgeRepo {

    /** 官方 API，需要 x-api-key */
    private const val OFFICIAL = "https://api.curseforge.com/v1"

    /** MCIM 镜像，无需 key */
    private const val MCIM = "https://mod.mcimirror.top/curseforge/v1"

    private const val GAME_ID_MINECRAFT = 432

    /** 实际使用的 key：设置里填的优先，其次构建时注入的 */
    val apiKey: String
        get() = Prefs.curseForgeKey.ifBlank { BuildConfig.CURSEFORGE_API }

    /** 失效或未配置 key 时是否还能用 */
    val hasOfficialKey: Boolean get() = apiKey.isNotBlank()

    /** 当前实际使用的 API 根地址 */
    val apiBase: String get() = if (hasOfficialKey) OFFICIAL else MCIM

    /** 下载源列表，供“下载源管理”页展示与切换 */
    val sources: List<Pair<String, String>>
        get() = buildList {
            add("官方 CurseForge" to OFFICIAL)
            add("MCIM 镜像" to MCIM)
        }

    fun classIdOf(type: String): Int = when (type) {
        "mod" -> 6
        "resourcepack" -> 12
        "modpack" -> 4471
        "plugin" -> 5          // Bukkit Plugins
        else -> 6
    }

    fun modLoaderTypeOf(loader: String): Int = when (loader.uppercase()) {
        "FORGE" -> 1
        "FABRIC" -> 4
        "QUILT" -> 5
        "NEOFORGE" -> 6
        else -> 0
    }

    /**
     * 检索资源。
     * @param type mod / resourcepack / modpack / plugin
     * @return 与 ModrinthRepo 同构的结果，便于资源中心复用同一套列表 UI
     */
    fun search(
        query: String,
        type: String,
        mcVersion: String? = null,
        loader: String? = null,
        pageSize: Int = 20,
        index: Int = 0,
        base: String = apiBase
    ): List<ModEntry> {
        val params = ArrayList<String>()
        params += "gameId=$GAME_ID_MINECRAFT"
        params += "classId=${classIdOf(type)}"
        params += "pageSize=$pageSize"
        params += "index=$index"
        params += "sortField=2"
        params += "sortOrder=desc"
        if (query.isNotBlank()) params += "searchFilter=${enc(query)}"
        if (!mcVersion.isNullOrBlank()) params += "gameVersion=${enc(mcVersion)}"
        modLoaderTypeOf(loader ?: "").takeIf { it != 0 }?.let { params += "modLoaderType=$it" }

        val o = getJson("$base/mods/search?" + params.joinToString("&"))
        val data = o.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<ModEntry>()
        for (i in 0 until data.length()) {
            val p = data.getJSONObject(i)
            val id = p.optString("id")
            out += ModEntry(
                slug = p.optString("slug", id),
                title = p.optString("name", ""),
                description = p.optString("summary", ""),
                downloads = p.optLong("downloadCount", 0L).toInt(),
                id = id,
                iconUrl = p.optJSONObject("logo")?.optString("thumbnailUrl", "") ?: "",
                source = "curseforge",
                type = type
            )
        }
        return out
    }

    data class CfFile(val id: Long, val fileName: String, val downloadUrl: String?)

    /** 基岩版（gameId 78022）的资源分类，classId 由官方 /categories 接口确认 */
    val BEDROCK_CLASSES: List<Pair<Int, String>> = listOf(
        4984 to "附加包",
        6929 to "材质包",
        6913 to "地图",
        6940 to "脚本",
        6925 to "皮肤"
    )

    /**
     * 基岩版资源检索。
     *
     * Modrinth 没有基岩版内容，所以这里只能走 CurseForge（gameId=78022）。
     * 基岩版没有「加载器」概念，也不按 Java 版本过滤，`gameVersion` 传基岩版本。
     */
    fun searchBedrock(
        query: String,
        classId: Int,
        gameVersion: String? = null,
        pageSize: Int = 20,
        index: Int = 0,
        base: String = apiBase
    ): List<ModEntry> {
        val params = ArrayList<String>()
        params += "gameId=78022"
        params += "classId=$classId"
        params += "pageSize=$pageSize"
        params += "index=$index"
        params += "sortField=2"
        params += "sortOrder=desc"
        if (query.isNotBlank()) params += "searchFilter=${enc(query)}"
        if (!gameVersion.isNullOrBlank()) params += "gameVersion=${enc(gameVersion)}"

        val root = getJson("$base/mods/search?" + params.joinToString("&"))
        val data = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<ModEntry>(data.length())
        for (i in 0 until data.length()) {
            val p = data.optJSONObject(i) ?: continue
            out += ModEntry(
                slug = p.optString("slug", p.optString("id")),
                title = p.optString("name", ""),
                description = p.optString("summary", ""),
                downloads = p.optLong("downloadCount", 0L).toInt(),
                id = p.optString("id"),
                iconUrl = p.optJSONObject("logo")?.optString("thumbnailUrl")
                    ?: p.optJSONObject("logo")?.optString("url") ?: "",
                source = "curseforge",
                type = "bedrock"
            )
        }
        return out
    }

    /** 取该项目适配指定 MC 版本的文件（默认取最新一个） */
    fun pickFile(modId: String, mcVersion: String?, base: String = apiBase): CfFile? {
        val q = buildString {
            append("$base/mods/$modId/files?pageSize=20")
            if (!mcVersion.isNullOrBlank()) append("&gameVersion=${enc(mcVersion)}")
        }
        // 注意：files 接口必须带 x-api-key，否则 403；返回是 {data:[...]} 对象
        val arr = filesData(q)
        if (arr.length() == 0) return null
        val f = arr.getJSONObject(0)
        val id = f.optLong("id")
        val name = f.optString("fileName", "file-$id.jar")
        return CfFile(id, name, f.optString("downloadUrl").takeIf { it.isNotBlank() })
    }

    /**
     * 构造下载地址。
     * 官方 key 失效或没 key 时，CF 会在 downloadUrl 上返回 null，此时回落到镜像的
     * `/{base}/mods/{modId}/files/{fileId}/download`，再不行用 CDN 规则拼。
     */
    fun downloadUrl(modId: String, file: CfFile, base: String = apiBase): String {
        file.downloadUrl?.let { return it }
        return if (base == MCIM) {
            "$MCIM/mods/$modId/files/${file.id}/download"
        } else {
            cdnUrl(file.id, file.fileName)
        }
    }

    /** Forge CDN 规则：files/{id/1000}/{id%1000}/{fileName} */
    fun cdnUrl(fileId: Long, fileName: String): String =
        "https://mediafilez.forgecdn.net/files/${fileId / 1000}/${fileId % 1000}/$fileName"

    /** 下载到指定目录，返回文件名 */
    fun install(
        modId: String,
        destDir: File,
        mcVersion: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
        base: String = apiBase
    ): String {
        onLog("查询 CurseForge 文件（$base）")
        val f = pickFile(modId, mcVersion, base) ?: throw Exception("没有适配 $mcVersion 的文件")
        val url = downloadUrl(modId, f, base)
        val dest = File(destDir, f.fileName)
        onLog("下载 ${f.fileName} 从 $url")
        destDir.mkdirs()
        if (!Net.download(url, dest, onProgress, shouldCancel)) throw Exception("下载失败: ${f.fileName}")
        onLog("已保存到 ${dest.absolutePath}")
        return f.fileName
    }

    /**
     * 拉取项目的文件列表。
     * 注意：CF 的 `gameVersions` 数组把 MC 版本和加载器混在一起
     * （例如 ["1.20.1", "Forge"]），这里按是否形如 x.y 拆开。
     */
    fun versions(modId: String, base: String = apiBase): List<PackVersion> {
        val arr = filesData("$base/mods/$modId/files?pageSize=50")
        val out = ArrayList<PackVersion>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val gv = f.optJSONArray("gameVersions")
            val games = ArrayList<String>()
            val loaders = ArrayList<String>()
            if (gv != null) {
                for (j in 0 until gv.length()) {
                    val v = gv.optString(j)
                    when {
                        Regex("^\\d+(\\.\\d+)*$").matches(v) -> games += v
                        v.equals("Client", true) || v.equals("Server", true) -> { /* 不是加载器 */ }
                        v.isNotEmpty() -> loaders += v
                    }
                }
            }
            val id = f.optLong("id")
            val name = f.optString("fileName", "file-$id")
            val url = f.optString("downloadUrl").takeIf { it.isNotBlank() }
                ?: if (base == MCIM) "$MCIM/mods/$modId/files/$id/download" else cdnUrl(id, name)
            out += PackVersion(
                id = id.toString(),
                name = f.optString("displayName", name),
                gameVersions = games,
                loaders = loaders,
                fileName = name,
                url = url
            )
        }
        return out
    }

    /** 带鉴权地取 `{data:[...]}` 里的数组；无 key 时走镜像 */
    private fun filesData(url: String): JSONArray {
        val text = if (hasOfficialKey) {
            Net.getString(url, headers = mapOf("x-api-key" to apiKey))
        } else {
            Net.getString(url)
        }
        return JSONObject(text).optJSONArray("data") ?: JSONArray()
    }

    private fun getJson(url: String): JSONObject {
        val text = if (hasOfficialKey) {
            Net.getString(url, headers = mapOf("x-api-key" to apiKey))
        } else {
            Net.getString(url)
        }
        return JSONObject(text)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
