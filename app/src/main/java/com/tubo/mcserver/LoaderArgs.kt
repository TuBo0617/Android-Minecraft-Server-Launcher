package com.tubo.mcserver

import android.content.Context

/**
 * 各加载器的独立启动命令模板（更新日志第 10 条）。
 *
 * 设计意图：有些加载器（比如 Velocity、Paper）需要额外的 JVM 参数或完全不同的命令，
 * 但又不想到每个实例里单独改一遍。这里给每个加载器存一套**完整命令**，各自有独立开关。
 *
 * 优先级：**实例自己的自定义命令 > 这里的模板 > 内置默认命令**。
 * 实例层面永远是最高优先级，模板只是省事，不能反过来覆盖用户的显式设置。
 */
object LoaderArgs {

    private const val SP = "mcsl_loader_args"

    private fun sp() = App.ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** 可配置的加载器，顺序即界面顺序 */
    val LOADERS: List<Pair<String, String>> = listOf(
        "VANILLA" to "原版",
        "PAPER" to "Paper / 插件服",
        "FABRIC" to "Fabric",
        "QUILT" to "Quilt",
        "FORGE" to "Forge",
        "NEOFORGE" to "NeoForge",
        "VELOCITY" to "Velocity",
        "BUNGEECORD" to "BungeeCord"
    )

    /** 统一成大写键，NeoForged 这类别名归一 */
    private fun key(loader: String): String = when (loader.uppercase()) {
        "NEOFORGED" -> "NEOFORGE"
        else -> loader.uppercase()
    }

    fun enabled(loader: String): Boolean = sp().getBoolean("on_${key(loader)}", false)

    fun setEnabled(loader: String, value: Boolean) {
        sp().edit().putBoolean("on_${key(loader)}", value).apply()
    }

    fun args(loader: String): String = sp().getString("args_${key(loader)}", "") ?: ""

    fun setArgs(loader: String, value: String) {
        sp().edit().putString("args_${key(loader)}", value).apply()
    }

    /**
     * 该实例实际应使用的模板命令；null 表示走内置默认。
     *
     * 实例自己开了 `argsOverride` 时直接让位——用户显式设置优先。
     */
    fun effective(inst: Instance): String? {
        if (inst.argsOverride && inst.customArgs.isNotBlank()) return null
        if (!enabled(inst.loader)) return null
        return args(inst.loader).takeIf { it.isNotBlank() }
    }

    /** 内置默认命令，用作输入框的占位提示 */
    fun defaultHint(loader: String): String = when (key(loader)) {
        "VELOCITY", "BUNGEECORD" ->
            "java -Xms256M -Xmx512M -jar <jar>"
        else ->
            "java -Xmx1024M -Xms256M -Dterminal.jline=false -Dfml.queryResult=confirm -jar <jar> nogui"
    }
}
