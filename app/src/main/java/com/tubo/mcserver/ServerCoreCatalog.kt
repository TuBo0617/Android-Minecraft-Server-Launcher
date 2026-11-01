package com.tubo.mcserver

/**
 * 服务端核心目录。
 *
 * 只描述「有哪些核心、属于哪一类、从哪里下载」，不承载 UI。
 * 实例设置页的「服务端核心」分类直接消费这份数据。
 */

/** 核心形态，决定启动方式与能否装 mod / 插件 */
enum class CoreKind {
    VANILLA,        // 原版服务端
    PLUGIN_SERVER,  // 支持 Bukkit 插件
    MODDED_SERVER,  // 支持模组
    HYBRID,         // 模组 + 插件（Mohist / Arclight / Magma 等）
    PROXY;          // 代理端，不承载世界

    val supportsMods: Boolean get() = this == MODDED_SERVER || this == HYBRID
    val supportsPlugins: Boolean get() = this == PLUGIN_SERVER || this == HYBRID
}

/** 下载来源标识，实际取 URL 的逻辑在 [CoreDownloader] 里 */
enum class CoreSource {
    MOJANG,             // piston-meta 版本清单
    FABRIC_META,
    QUILT_META,
    FORGE_MAVEN,
    NEOFORGE_MAVEN,
    PAPER_API,          // PaperMC Downloads API（paper / folia / velocity / waterfall）
    PURPUR_API,
    BUNGEECORD_CI,      // ci.md-5.net Jenkins
    SPIGOT_BUILDTOOLS,  // 需本地 BuildTools 构建，启动器内无法直接产出
    GITHUB_RELEASE      // Leaves / Lightfall / Arclight / Mohist / Magma / CatServer
}

data class ServerCoreOption(
    val id: String,
    val name: String,
    val kind: CoreKind,
    val source: CoreSource,
    /** 能力/来源说明，展示在卡片副标题 */
    val note: String = "",
    /** 与启动器内部 loader 标识的对应关系 */
    val loader: String = "VANILLA",
    /** PaperMC 系的项目名（paper / folia / velocity / waterfall） */
    val project: String = "",
    /** GitHub 仓库 owner/repo */
    val repo: String = "",
    /** 从 release 资产里挑文件的关键字 */
    val assetKeyword: String = "",
    /** 下载落地后的 jar 文件名；空则按 [ServerCoreCatalog.jarNameOf] 推导 */
    val jarName: String = ""
)

data class CoreCategory(
    val id: String,
    val title: String,
    val options: List<ServerCoreOption>
)

object ServerCoreCatalog {

    val categories: List<CoreCategory> = listOf(
        CoreCategory(
            id = "paper_family",
            title = "Bukkit 系插件服务端",
            options = listOf(
                ServerCoreOption("PAPER", "Paper", CoreKind.PLUGIN_SERVER, CoreSource.PAPER_API, "最流行的插件服务端", "PAPER", project = "paper"),
                ServerCoreOption("PURPUR", "Purpur", CoreKind.PLUGIN_SERVER, CoreSource.PURPUR_API, "Paper 分支，可调项更多", "PURPUR"),
                ServerCoreOption("FOLIA", "Folia", CoreKind.PLUGIN_SERVER, CoreSource.PAPER_API, "多线程区域调度", "FOLIA", project = "folia"),
                ServerCoreOption("SPIGOT", "Spigot", CoreKind.PLUGIN_SERVER, CoreSource.SPIGOT_BUILDTOOLS, "需 BuildTools 本地构建", "SPIGOT"),
                ServerCoreOption("BUKKIT", "Bukkit", CoreKind.PLUGIN_SERVER, CoreSource.SPIGOT_BUILDTOOLS, "CraftBukkit 上游", "BUKKIT"),
                ServerCoreOption("LEGACY", "Legacy", CoreKind.PLUGIN_SERVER, CoreSource.SPIGOT_BUILDTOOLS, "旧版 Minecraft 插件服务端", "BUKKIT")
            )
        ),
        CoreCategory(
            id = "vanilla_loaders",
            title = "原版与模组加载器",
            options = listOf(
                ServerCoreOption("VANILLA", "Vanilla", CoreKind.VANILLA, CoreSource.MOJANG, "Mojang 官方原版服务端", "VANILLA"),
                ServerCoreOption("FABRIC", "Fabric", CoreKind.MODDED_SERVER, CoreSource.FABRIC_META, "轻量模组加载器", "FABRIC", jarName = "fabric-server-launch.jar"),
                ServerCoreOption("FORGE", "Forge", CoreKind.MODDED_SERVER, CoreSource.FORGE_MAVEN, "老牌模组加载器，需安装器", "FORGE"),
                ServerCoreOption("NEOFORGE", "NeoForge", CoreKind.MODDED_SERVER, CoreSource.NEOFORGE_MAVEN, "Forge 社区继任者，需安装器", "NEOFORGE"),
                ServerCoreOption("QUILT", "Quilt", CoreKind.MODDED_SERVER, CoreSource.QUILT_META, "Fabric 分支", "QUILT", jarName = "quilt-server-launch.jar")
            )
        ),
        CoreCategory(
            id = "fabric_forge_extended",
            title = "Fabric / Forge 衍生核心",
            options = listOf(
                ServerCoreOption("FABRIC", "Fabric Server", CoreKind.MODDED_SERVER, CoreSource.FABRIC_META, "官方 Fabric 服务端启动器", "FABRIC", jarName = "fabric-server-launch.jar"),
                ServerCoreOption("FORGE", "Forge Server", CoreKind.MODDED_SERVER, CoreSource.FORGE_MAVEN, "官方 Forge 安装器", "FORGE"),
                ServerCoreOption("MOHIST", "Mohist", CoreKind.HYBRID, CoreSource.GITHUB_RELEASE, "Forge + Bukkit 插件", "FORGE", repo = "MohistMC/Mohist", assetKeyword = "server"),
                ServerCoreOption("ARCLIGHT", "Arclight", CoreKind.HYBRID, CoreSource.GITHUB_RELEASE, "Forge/Fabric + 插件", "FORGE", repo = "IzzelAliz/Arclight", assetKeyword = "arclight-forge"),
                ServerCoreOption("MAGMA", "Magma", CoreKind.HYBRID, CoreSource.GITHUB_RELEASE, "Forge + 插件", "FORGE", repo = "magmafoundation/Magma", assetKeyword = "server"),
                ServerCoreOption("CATSERVER", "CatServer", CoreKind.HYBRID, CoreSource.GITHUB_RELEASE, "Forge + 插件（国内）", "FORGE", repo = "Luohuayu/CatServer", assetKeyword = "universal")
            )
        ),
        CoreCategory(
            id = "vanilla_bukkit",
            title = "原版与 Bukkit 衍生",
            options = listOf(
                ServerCoreOption("VANILLA", "Vanilla", CoreKind.VANILLA, CoreSource.MOJANG, "官方原版", "VANILLA"),
                ServerCoreOption("BUKKIT", "CraftBukkit", CoreKind.PLUGIN_SERVER, CoreSource.SPIGOT_BUILDTOOLS, "插件 API 基准实现", "BUKKIT"),
                ServerCoreOption("SPIGOT", "Spigot", CoreKind.PLUGIN_SERVER, CoreSource.SPIGOT_BUILDTOOLS, "性能优化版 CraftBukkit", "SPIGOT"),
                ServerCoreOption("PAPER", "Paper", CoreKind.PLUGIN_SERVER, CoreSource.PAPER_API, "高性能插件服务端", "PAPER", project = "paper"),
                ServerCoreOption("LEAVES", "Leaves", CoreKind.PLUGIN_SERVER, CoreSource.GITHUB_RELEASE, "Paper 分支，兼顾原版特性", "PAPER", repo = "LeavesMC/Leaves", assetKeyword = "leaves")
            )
        ),
        CoreCategory(
            id = "proxy_cross",
            title = "代理与跨端核心",
            options = listOf(
                ServerCoreOption("VELOCITY", "Velocity", CoreKind.PROXY, CoreSource.PAPER_API, "现代群组代理", "VELOCITY", project = "velocity", jarName = "velocity.jar"),
                ServerCoreOption("BUNGEECORD", "BungeeCord", CoreKind.PROXY, CoreSource.BUNGEECORD_CI, "经典群组代理", "BUNGEECORD", jarName = "BungeeCord.jar"),
                ServerCoreOption("LIGHTFALL", "Lightfall", CoreKind.PROXY, CoreSource.GITHUB_RELEASE, "BungeeCord 分支，支持新版", "BUNGEECORD", repo = "LightfallMC/Lightfall", assetKeyword = "Lightfall", jarName = "BungeeCord.jar"),
                ServerCoreOption("WATERFALL", "Waterfall", CoreKind.PROXY, CoreSource.PAPER_API, "Paper 团队维护的 BungeeCord 分支", "BUNGEECORD", project = "waterfall", jarName = "BungeeCord.jar"),
                ServerCoreOption("LEAVES", "Leaves", CoreKind.PLUGIN_SERVER, CoreSource.GITHUB_RELEASE, "Paper 分支，兼顾原版特性", "PAPER", repo = "LeavesMC/Leaves", assetKeyword = "leaves")
            )
        )
    )

    private val byId: Map<String, ServerCoreOption> by lazy {
        categories.flatMap { it.options }.associateBy { it.id }
    }

    fun find(id: String): ServerCoreOption? =
        byId[id] ?: byId[id.uppercase()] ?: byId.values.firstOrNull { it.loader.equals(id, true) }

    /** 分类内去重后的选项（第三/四/五类与前面存在重复 id） */
    fun optionsOf(category: CoreCategory): List<ServerCoreOption> =
        category.options.distinctBy { it.id }

    fun categoryOf(id: String): CoreCategory? =
        categories.firstOrNull { c -> c.options.any { it.id == id } }

    /** 全部核心（按 id 去重），供版本列表等场景遍历 */
    fun allOptions(): List<ServerCoreOption> = byId.values.toList()

    /**
     * 下载落地后的 jar 文件名。
     * 未显式指定时按 loader 推导，保证启动逻辑能找到文件。
     */
    fun jarNameOf(coreId: String): String {
        val opt = find(coreId)
        if (opt != null && opt.jarName.isNotEmpty()) return opt.jarName
        return when (opt?.loader?.uppercase()) {
            "FABRIC" -> "fabric-server-launch.jar"
            "QUILT" -> "quilt-server-launch.jar"
            "VELOCITY" -> "velocity.jar"
            "BUNGEECORD" -> "BungeeCord.jar"
            else -> "server.jar"
        }
    }

    /** 界面提示文案：尚未选择核心时的引导语 */
    const val EMPTY_HINT = "还没有选择服务端核心，先从左侧分类里挑一个吧"
}
