package com.tubo.mcserver

import java.io.File

/**
 * 实例。
 *
 * `*Override` 开关表示“该实例是否脱离全局设置”：关闭时沿用全局 Prefs，开启后才使用实例自身的值。
 * 这样旧实例文件缺失这些字段时行为与以前完全一致。
 */
data class Instance(
    val name: String,
    val mcVersion: String,
    val loader: String,                       // VANILLA / FABRIC / PAPER / BEDROCK / ...
    val core: String = "",                    // 服务端核心 id，空则按 loader 推导
    val loaderVersion: String = "",
    val pinned: Boolean = false,              // 星标置顶
    val order: Int = 0,                       // 拖拽排序
    val iconPath: String = "",                // 自定义 1:1 图标
    val javaOverride: Boolean = false,
    val javaHome: String = "",
    val memoryOverride: Boolean = false,
    val minMemMb: Int = 256,
    val maxMemMb: Int = 2048,
    val noguiOverride: Boolean = false,
    val nogui: Boolean = true,
    val argsOverride: Boolean = false,
    val customArgs: String = "",
    /** 由整合包创建的实例会记录整合包名，实例列表可据此筛选 */
    val modpack: String = "",
    /** 自定义 Jar：实例目录内的文件名，非空时优先于 core 推导 */
    val customJar: String = ""
) {
    fun dir(): File = File(Storage.servers(), name)

    fun isBedrock(): Boolean = loader == "BEDROCK"

    /** 代理端不承载世界，但有独立的启动 jar 名称 */
    fun isProxy(): Boolean = loader == "VELOCITY" || loader == "BUNGEECORD"

    /**
     * 核心 jar 的落地文件名。
     *
     * 优先按 `core`（核心 id）推导，这样 Paper / Purpur / Velocity 等各自落到
     * 约定的文件名；旧实例没有 `core` 时退回按 `loader` 推导，行为与以前一致。
     */
    fun serverJar(): File {
        // 自定义 Jar 优先：用户选的包名不受核心约定约束
        if (customJar.isNotEmpty()) return File(dir(), customJar)
        val name = when {
            isBedrock() -> "bedrock_server"
            core.isNotEmpty() -> ServerCoreCatalog.jarNameOf(core)
            loader == "FABRIC" -> "fabric-server-launch.jar"
            loader == "QUILT" -> "quilt-server-launch.jar"
            loader == "VELOCITY" -> "velocity.jar"
            loader == "BUNGEECORD" -> "BungeeCord.jar"
            else -> "server.jar"
        }
        return File(dir(), name)
    }

    /** Forge / NeoForge 安装器生成的启动脚本（存在即代表安装完成） */
    fun installScript(): File = File(dir(), "run.sh")

    /** 安装器类核心（无法直接拿到 jar，需要先跑安装器） */
    fun isInstallerCore(): Boolean = core == "FORGE" || core == "NEOFORGE"

    /** 核心是否已就绪：安装器类核心以 run.sh 为准 */
    fun hasServerJar(): Boolean =
        serverJar().isFile || (isInstallerCore() && installScript().isFile)

    fun modsDir(): File = File(dir(), "mods").apply { mkdirs() }
    fun pluginsDir(): File = File(dir(), "plugins").apply { mkdirs() }
    fun resourcePacksDir(): File = File(dir(), "resourcepacks").apply { mkdirs() }
    fun backupsDir(): File = File(dir(), "backups").apply { mkdirs() }

    fun eulaFile(): File = File(dir(), "eula.txt")

    /** 自定义图标文件；未设置或文件已丢失时返回 null，界面回落到默认图标 */
    fun iconFile(): File? = iconPath.takeIf { it.isNotEmpty() }?.let { File(it).takeIf(File::isFile) }

    fun displayName(): String = name
}
