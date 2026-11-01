package com.tubo.mcserver

import org.json.JSONArray
import org.json.JSONObject

/**
 * 查询「某个服务端核心支持哪些 Minecraft 版本」。
 *
 * 新建实例页与实例设置的核心页据此把不支持的加载器置灰。
 * 查询失败时返回 `null`（未知），界面**不置灰**——宁可让用户点一下报错，
 * 也不要因为接口抽风把所有加载器都灰掉。
 */
object CoreSupport {

    /** coreId -> 支持的版本集合；null 表示不限制/未知 */
    private val cache = HashMap<String, Set<String>?>()
    private val loaded = HashSet<String>()

    /** Mojang release 版本列表（多个核心共用，只拉一次） */
    private var mojangReleases: Set<String>? = null

    fun isLoaded(coreId: String): Boolean = loaded.contains(coreId)

    fun known(coreId: String): Set<String>? = cache[coreId]

    /**
     * 是否支持该 MC 版本。
     * 未知（没查过/查询失败）一律当作支持，避免误灰。
     */
    fun supports(coreId: String, mcVersion: String): Boolean {
        if (mcVersion.isBlank()) return true
        val opt = ServerCoreCatalog.find(coreId) ?: return true
        // 需要本地 BuildTools 的核心在启动器里装不了，直接禁用
        if (opt.source == CoreSource.SPIGOT_BUILDTOOLS) return false
        if (!loaded.contains(coreId)) return true
        val set = cache[coreId] ?: return true
        return set.contains(mcVersion)
    }

    /** 该核心是否在启动器内根本无法安装 */
    fun installable(coreId: String): Boolean {
        val opt = ServerCoreCatalog.find(coreId) ?: return false
        return opt.source != CoreSource.SPIGOT_BUILDTOOLS
    }

    /** 阻塞查询，需在 IO 线程调用 */
    fun load(coreId: String) {
        if (loaded.contains(coreId)) return
        val opt = ServerCoreCatalog.find(coreId) ?: return
        val result = runCatching { query(opt) }.getOrNull()
        cache[coreId] = result
        loaded.add(coreId)
    }

    /** 批量查询，去重后逐个加载 */
    fun loadAll(coreIds: Collection<String>) {
        coreIds.distinct().forEach { load(it) }
    }

    private fun query(opt: ServerCoreOption): Set<String>? = when (opt.source) {
        CoreSource.MOJANG -> mojangReleases ?: run {
            val set = runCatching {
                VersionRepo.fetchVersions().filter { it.type == "release" }.map { it.id }.toSet()
            }.getOrNull()
            mojangReleases = set
            set
        }

        CoreSource.FABRIC_META -> {
            val arr = JSONArray(Net.getString("https://meta.fabricmc.net/v2/versions/game"))
            val out = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optBoolean("stable", true)) out += o.optString("version")
            }
            out.ifEmpty { null }
        }

        CoreSource.QUILT_META -> {
            val arr = JSONArray(Net.getString("https://meta.quiltmc.org/v3/versions/game"))
            val out = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optBoolean("stable", true)) out += o.optString("version")
            }
            out.ifEmpty { null }
        }

        CoreSource.PAPER_API -> {
            val base = DownloadSources.baseUrlOf("paper", "https://fill.papermc.io/v3").trimEnd('/')
            val project = opt.project.ifEmpty { "paper" }
            val root = Net.getJson("$base/projects/$project/versions")
            val arr = root.optJSONArray("versions")
            val out = HashSet<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i)?.optJSONObject("version") ?: continue
                    v.optString("id").takeIf { it.isNotEmpty() }?.let { out += it }
                }
            }
            out.ifEmpty { null }
        }

        CoreSource.PURPUR_API -> {
            val base = DownloadSources.baseUrlOf("purpur", "https://api.purpurmc.org/v2").trimEnd('/')
            val arr = Net.getJson("$base/purpur").optJSONArray("versions")
            val out = HashSet<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotEmpty() }?.let { out += it }
                }
            }
            out.ifEmpty { null }
        }

        CoreSource.FORGE_MAVEN -> {
            val promos = Net.getJson(
                "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
            ).optJSONObject("promos")
            val out = HashSet<String>()
            if (promos != null) {
                promos.keys().forEach { key ->
                    if (key.endsWith("-recommended") || key.endsWith("-latest")) {
                        out += key.substringBeforeLast('-')
                    }
                }
            }
            out.ifEmpty { null }
        }

        // 代理端与 MC 版本无关；NeoForge 版本号映射不精确；GitHub 系无从判断
        CoreSource.NEOFORGE_MAVEN,
        CoreSource.BUNGEECORD_CI,
        CoreSource.GITHUB_RELEASE,
        CoreSource.SPIGOT_BUILDTOOLS -> null
    }
}
