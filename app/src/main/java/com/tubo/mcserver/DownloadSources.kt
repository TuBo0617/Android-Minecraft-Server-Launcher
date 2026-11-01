package com.tubo.mcserver

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一个可切换的下载源。
 *
 * `kind` 表示它服务于什么：原版清单 / 加载器元数据 / Paper 系核心 / 资源中心 / Java 运行时。
 * 同一 kind 下可以有多个源（例如 Termux 官方与清华、中科大镜像），
 * 实际取用哪一个由 [DownloadSources.pick] 决定：手动指定优先，否则按「已启用 + 延迟最低」。
 */
data class DownloadSource(
    val id: String,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val desc: String = "",
    /** 测速探测地址；空则直接用 [baseUrl] */
    val probeUrl: String = ""
) {
    fun probe(): String = probeUrl.ifEmpty { baseUrl }
}

/**
 * 下载源管理（更新日志第 2、11 条）。
 *
 * 开关、手动选中项与测速结果都存在独立的 SharedPreferences 里，避免污染主配置。
 */
object DownloadSources {

    private const val SP = "mcsl_sources"

    /** 延迟值语义：负数区分「没测过」和「测过但不通」 */
    const val LATENCY_UNTESTED = -1L
    const val LATENCY_DEAD = -2L

    private fun sp() = App.ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** 分类显示名 */
    val KIND_LABELS: Map<String, String> = mapOf(
        "mojang" to "Minecraft 原版",
        "fabric" to "Fabric 加载器元数据",
        "quilt" to "Quilt 加载器元数据",
        "paper" to "Paper 系服务端核心",
        "purpur" to "Purpur 服务端核心",
        "modrinth" to "Modrinth 资源",
        "curseforge" to "CurseForge 资源",
        "termux" to "Java 运行时（Termux 软件源）",
        "github" to "GitHub 发布"
    )

    fun kindLabel(kind: String): String = KIND_LABELS[kind] ?: kind

    val all: List<DownloadSource> = listOf(
        // ---------------- Java 运行时（Termux 源，多镜像可切换）
        DownloadSource(
            id = "termux_official",
            name = "Termux 官方源",
            kind = "termux",
            baseUrl = "https://packages.termux.dev/apt/termux-main",
            desc = "官方仓库，境外线路，境内常连不上",
            probeUrl = "https://packages.termux.dev/apt/termux-main/dists/stable/Release"
        ),
        DownloadSource(
            id = "termux_tuna",
            name = "清华 TUNA 镜像",
            kind = "termux",
            baseUrl = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
            desc = "境内推荐，同步及时",
            probeUrl = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/dists/stable/Release"
        ),
        DownloadSource(
            id = "termux_ustc",
            name = "中科大 USTC 镜像",
            kind = "termux",
            baseUrl = "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
            desc = "境内备选，教育网快",
            probeUrl = "https://mirrors.ustc.edu.cn/termux/apt/termux-main/dists/stable/Release"
        ),
        DownloadSource(
            id = "termux_bfsu",
            name = "北外 BFSU 镜像",
            kind = "termux",
            baseUrl = "https://mirrors.bfsu.edu.cn/termux/apt/termux-main",
            desc = "境内备选",
            probeUrl = "https://mirrors.bfsu.edu.cn/termux/apt/termux-main/dists/stable/Release"
        ),
        DownloadSource(
            id = "termux_iscas",
            name = "中科院 ISCAS 镜像",
            kind = "termux",
            baseUrl = "https://mirror.iscas.ac.cn/termux/apt/termux-main",
            desc = "境内备选",
            probeUrl = "https://mirror.iscas.ac.cn/termux/apt/termux-main/dists/stable/Release"
        ),

        // ---------------- 原版
        DownloadSource(
            id = "mojang",
            name = "Mojang 官方",
            kind = "mojang",
            baseUrl = "https://piston-meta.mojang.com",
            desc = "原版服务端核心与版本清单",
            probeUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        ),

        // ---------------- 加载器元数据
        DownloadSource(
            id = "fabric",
            name = "Fabric Meta",
            kind = "fabric",
            baseUrl = "https://meta.fabricmc.net",
            desc = "Fabric 加载器元数据",
            probeUrl = "https://meta.fabricmc.net/v2/versions/loader"
        ),
        DownloadSource(
            id = "quilt",
            name = "Quilt Meta",
            kind = "quilt",
            baseUrl = "https://meta.quiltmc.org",
            desc = "Quilt 加载器元数据（境内可能不通）",
            probeUrl = "https://meta.quiltmc.org/v3/versions/loader"
        ),

        // ---------------- Paper 系（v2 已 410 下线，统一走 fill v3）
        DownloadSource(
            id = "paper",
            name = "PaperMC fill (v3)",
            kind = "paper",
            baseUrl = "https://fill.papermc.io/v3",
            desc = "Paper / Folia / Velocity / Waterfall 官方新接口",
            probeUrl = "https://fill.papermc.io/v3/projects/paper"
        ),
        DownloadSource(
            id = "purpur",
            name = "Purpur 官方 API",
            kind = "purpur",
            baseUrl = "https://api.purpurmc.org/v2",
            desc = "Purpur 核心",
            probeUrl = "https://api.purpurmc.org/v2/purpur"
        ),

        // ---------------- 资源中心
        DownloadSource(
            id = "modrinth",
            name = "Modrinth",
            kind = "modrinth",
            baseUrl = "https://api.modrinth.com/v2",
            desc = "模组 / 资源包 / 整合包",
            probeUrl = "https://api.modrinth.com/v2/search?limit=1"
        ),
        DownloadSource(
            id = "cf_official",
            name = "CurseForge 官方",
            kind = "curseforge",
            baseUrl = "https://api.curseforge.com/v1",
            desc = "需要 API Key，未配置时不可用",
            probeUrl = "https://api.curseforge.com/v1/games"
        ),
        DownloadSource(
            id = "cf_mcim",
            name = "CurseForge 镜像 (MCIM)",
            kind = "curseforge",
            baseUrl = "https://mod.mcimirror.top/curseforge/v1",
            desc = "免 Key 镜像，境内更稳",
            probeUrl = "https://mod.mcimirror.top/curseforge/v1/mods/search?gameId=432"
        ),

        // ---------------- GitHub
        DownloadSource(
            id = "github",
            name = "GitHub API",
            kind = "github",
            baseUrl = "https://api.github.com",
            desc = "Leaves / Lightfall / Arclight 等发布",
            probeUrl = "https://api.github.com/rate_limit"
        )
    )

    fun find(id: String): DownloadSource? = all.firstOrNull { it.id == id }

    fun isEnabled(id: String): Boolean = sp().getBoolean("on_$id", true)

    fun setEnabled(id: String, enabled: Boolean) {
        sp().edit().putBoolean("on_$id", enabled).apply()
    }

    fun latency(id: String): Long = sp().getLong("ms_$id", LATENCY_UNTESTED)

    fun setLatency(id: String, ms: Long) {
        sp().edit().putLong("ms_$id", ms).apply()
    }

    fun enabled(): List<DownloadSource> = all.filter { isEnabled(it.id) }

    fun ofKind(kind: String): List<DownloadSource> = all.filter { it.kind == kind }

    /** 实际会被下载逻辑用到的分类（有源就有） */
    fun kinds(): List<String> = all.map { it.kind }.distinct()

    // ------------------------------------------------------------ 手动切换

    /** 某个用途下用户手动指定的源；空表示自动（按延迟挑） */
    fun selectedId(kind: String): String = sp().getString("sel_$kind", "") ?: ""

    fun select(kind: String, id: String) {
        sp().edit().putString("sel_$kind", id).apply()
    }

    fun clearSelection(kind: String) {
        sp().edit().remove("sel_$kind").apply()
    }

    /** 该源是否正被这个用途使用 */
    fun isActive(source: DownloadSource): Boolean {
        val manual = selectedId(source.kind)
        if (manual.isNotEmpty()) return manual == source.id
        return pick(source.kind)?.id == source.id
    }

    /**
     * 取某个用途下当前应使用的源。
     *
     * 1. 手动指定且已启用 → 直接用；
     * 2. 否则在已启用源里挑延迟最低的（没测过或不通的排最后）；
     * 3. 全都没测过时按声明顺序取第一个。
     */
    fun pick(kind: String): DownloadSource? {
        val candidates = enabled().filter { it.kind == kind }
        if (candidates.isEmpty()) return null
        val manual = selectedId(kind)
        if (manual.isNotEmpty()) {
            candidates.firstOrNull { it.id == manual }?.let { return it }
        }
        return candidates.minByOrNull { s ->
            latency(s.id).takeIf { it > 0 } ?: Long.MAX_VALUE
        } ?: candidates.first()
    }

    /**
     * 某个用途下的候选源，按「手动指定 → 延迟从低到高 → 未测速」排序。
     * 下载时按这个顺序逐个重试，一个源挂了自动换下一个。
     */
    fun ordered(kind: String): List<DownloadSource> {
        val candidates = enabled().filter { it.kind == kind }
        if (candidates.isEmpty()) return emptyList()
        val manual = selectedId(kind)
        return candidates.sortedWith(
            compareByDescending<DownloadSource> { it.id == manual }
                .thenBy { s -> latency(s.id).takeIf { it > 0 } ?: Long.MAX_VALUE }
        )
    }

    fun baseUrlOf(kind: String, fallback: String): String = pick(kind)?.baseUrl ?: fallback

    // ------------------------------------------------------------ 测速

    /**
     * 测速：对源探测地址发起一次 GET 并读取首字节，返回耗时毫秒；失败返回 [LATENCY_DEAD]。
     * 结果会写回 SharedPreferences，供列表刷新后展示。
     */
    fun test(source: DownloadSource, timeoutMs: Int = 8000): Long {
        val start = System.currentTimeMillis()
        val proxy = Prefs.proxyOrNull()
        var conn: HttpURLConnection? = null
        return try {
            val url = source.probe()
            conn = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection())
                    as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "MCServerLauncher/1.0")
            conn.setRequestProperty("Range", "bytes=0-1023")
            val code = conn.responseCode
            if (code !in 200..399) {
                setLatency(source.id, LATENCY_DEAD)
                return LATENCY_DEAD
            }
            conn.inputStream.use { it.read() }
            val ms = System.currentTimeMillis() - start
            setLatency(source.id, ms)
            ms
        } catch (e: Exception) {
            setLatency(source.id, LATENCY_DEAD)
            LATENCY_DEAD
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 延迟的展示文本 */
    fun latencyLabel(id: String): String = when (val ms = latency(id)) {
        LATENCY_UNTESTED -> "未测速"
        LATENCY_DEAD -> "不可用"
        in 0..199 -> "${ms}ms 极快"
        in 200..599 -> "${ms}ms 良好"
        in 600..1499 -> "${ms}ms 一般"
        else -> "${ms}ms 较慢"
    }

    fun isDead(id: String): Boolean = latency(id) == LATENCY_DEAD
}
