package com.tubo.mcserver

import java.io.File

/**
 * 配置文件管理器。
 *
 * 负责把服务端目录下的配置文件读成「有效配置项」列表，并预先翻译常见选项的中文名称，
 * 原始名称原样保留（界面上放在右侧小字）。未预设的选项直接展示原始内容。
 *
 * 目前支持 `key=value` 形式的 properties（server.properties 等）。
 */

enum class ConfigValueType { BOOLEAN, INT, STRING }

data class ConfigEntry(
    val key: String,
    var value: String,
    /** 该行是否是注释/空行，重写时原样保留 */
    val raw: String? = null
) {
    val isComment: Boolean get() = raw != null

    val displayName: String get() = ConfigManager.displayName(key)

    /** 未预设中文名时返回 null，界面据此决定是否显示副标题 */
    val translation: String? get() = ConfigManager.describe(key)

    val type: ConfigValueType get() = ConfigManager.typeOf(key, value)
}

data class ConfigFile(
    val file: File,
    val entries: List<ConfigEntry>
) {
    val path: String get() = file.absolutePath
    val name: String get() = file.name
}

object ConfigManager {

    /** 实例内需要纳入配置管理器的文件（存在才读取） */
    private val FILE_NAMES = listOf(
        "server.properties",
        "bukkit.yml",
        "spigot.yml",
        "paper.yml",
        "config/paper-global.yml"
    )

    /** 读取实例目录下全部存在的配置文件 */
    fun readAll(inst: Instance): List<ConfigFile> =
        FILE_NAMES.map { File(inst.dir(), it) }.filter { it.isFile }.map { read(it) }

    fun read(file: File): ConfigFile {
        if (!file.exists()) return ConfigFile(file, emptyList())
        val entries = ArrayList<ConfigEntry>()
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!") ->
                    entries += ConfigEntry("", "", raw = line)
                trimmed.startsWith("[") ->
                    entries += ConfigEntry("", "", raw = line)   // yml 段落头
                else -> {
                    val idx = line.indexOf('=')
                    if (idx <= 0) {
                        entries += ConfigEntry("", "", raw = line)
                    } else {
                        val k = line.substring(0, idx).trim()
                        val v = line.substring(idx + 1).trim()
                        entries += ConfigEntry(k, v, raw = null)
                    }
                }
            }
        }
        return ConfigFile(file, entries)
    }

    /** 写回，注释与原顺序保持不变；只替换有效项的值 */
    fun write(cfg: ConfigFile) {
        val sb = StringBuilder()
        cfg.entries.forEach { e ->
            if (e.isComment) sb.append(e.raw).append('\n')
            else sb.append(e.key).append('=').append(e.value).append('\n')
        }
        cfg.file.parentFile?.mkdirs()
        cfg.file.writeText(sb.toString())
    }

    fun value(cfg: ConfigFile, key: String): String? =
        cfg.entries.firstOrNull { !it.isComment && it.key == key }?.value

    fun typeOf(key: String, value: String): ConfigValueType = when {
        value.equals("true", true) || value.equals("false", true) -> ConfigValueType.BOOLEAN
        value.toIntOrNull() != null -> ConfigValueType.INT
        else -> ConfigValueType.STRING
    }

    /** 中文名称；未预设则回退原始 key */
    fun displayName(key: String): String = NAMES[key] ?: key

    /** 说明文字；未预设返回 null */
    fun describe(key: String): String? = DESCRIPTIONS[key]

    private val NAMES: Map<String, String> = mapOf(
        "server-port" to "服务器端口",
        "server-ip" to "绑定 IP",
        "max-players" to "最大玩家数",
        "level-name" to "世界名称",
        "level-seed" to "世界种子",
        "level-type" to "世界类型",
        "gamemode" to "游戏模式",
        "force-gamemode" to "强制游戏模式",
        "difficulty" to "难度",
        "hardcore" to "极限模式",
        "pvp" to "允许 PVP",
        "motd" to "服务器描述",
        "online-mode" to "正版验证",
        "white-list" to "启用白名单",
        "enforce-whitelist" to "白名单强制踢出",
        "view-distance" to "视距",
        "simulation-distance" to "模拟距离",
        "spawn-protection" to "出生点保护半径",
        "allow-flight" to "允许飞行",
        "allow-nether" to "允许下界",
        "enable-command-block" to "启用命令方块",
        "enable-rcon" to "启用 RCON",
        "rcon.port" to "RCON 端口",
        "rcon.password" to "RCON 密码",
        "query.port" to "Query 端口",
        "enable-query" to "启用 Query",
        "player-idle-timeout" to "挂机踢出时间（分）",
        "max-world-size" to "世界边界上限",
        "network-compression-threshold" to "网络压缩阈值",
        "entity-broadcast-range-percentage" to "实体广播范围",
        "max-tick-time" to "单刻最大耗时",
        "sync-chunk-writes" to "同步区块写入",
        "enforce-secure-profile" to "强制安全档案",
        "hide-online-players" to "隐藏在线玩家",
        "prevent-proxy-connections" to "阻止代理连接",
        "use-native-transport" to "启用原生传输",
        "op-permission-level" to "OP 权限等级",
        "function-permission-level" to "函数权限等级",
        "generate-structures" to "生成结构",
        "spawn-monsters" to "生成怪物",
        "spawn-npcs" to "生成 NPC",
        "spawn-animals" to "生成动物",
        "log-ips" to "记录玩家 IP",
        "text-filtering-config" to "文本过滤配置"
    )

    private val DESCRIPTIONS: Map<String, String> = mapOf(
        "server-port" to "服务端监听端口，默认 25565",
        "online-mode" to "开启后仅正版可进入；离线服请关闭",
        "max-players" to "同时在线玩家上限",
        "view-distance" to "视距越大占用内存越高",
        "simulation-distance" to "实体/方块刻模拟距离",
        "motd" to "多人游戏列表里的服务器简介，支持 § 颜色代码",
        "enable-rcon" to "远程控制台，注意设置强密码",
        "level-seed" to "留空则随机生成世界",
        "difficulty" to "peaceful / easy / normal / hard"
    )
}
