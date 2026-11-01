package com.tubo.mcserver

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 玩家管理：白名单 / 管理员 / 封禁。
 *
 * ## 写入策略
 *
 * 优先走**服务端控制台指令**（`whitelist add`、`op`、`ban`），因为 UUID 由服务端自己解析，
 * 正版和离线模式都不会出错。服务端没在跑时才退回直接改 JSON 文件，此时：
 * - 离线模式（`online-mode=false`）：UUID 可以用算法算出来，和官方一致；
 * - 正版模式：必须联网查 Mojang，容易失败，所以直接要求先启动服务端。
 *
 * 直接改文件还有个前提：服务端运行中会把内存里的名单写回磁盘，
 * 所以运行中改文件会被覆盖——这也是「优先用指令」的原因。
 */
object PlayerManager {

    enum class Kind(
        val file: String,
        val title: String,
        val addCmd: String,
        val removeCmd: String,
        /** 封禁列表的条目字段和其它两个不一样 */
        val isBan: Boolean = false
    ) {
        WHITELIST("whitelist.json", "白名单", "whitelist add ", "whitelist remove "),
        OPS("ops.json", "管理员", "op ", "deop "),
        BANNED("banned-players.json", "封禁", "ban ", "pardon ", isBan = true)
    }

    data class Player(
        val name: String,
        val uuid: String,
        /** 附加信息：管理员等级 / 封禁原因等 */
        val note: String = ""
    )

    private val NAME_RE = Regex("^[A-Za-z0-9_]{1,16}$")

    fun fileOf(inst: Instance, kind: Kind): File = File(inst.dir(), kind.file)

    /** 读取名单。文件不存在（服务端还没生成）时返回空列表 */
    fun list(inst: Instance, kind: Kind): List<Player> {
        val f = fileOf(inst, kind)
        if (!f.isFile) return emptyList()
        val arr = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Player>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").ifEmpty { o.optString("uuid").take(8) }
            val note = when {
                kind == Kind.OPS -> "等级 ${o.optInt("level", 4)}"
                kind.isBan -> o.optString("reason").ifEmpty { "已封禁" }
                else -> ""
            }
            out += Player(name, o.optString("uuid"), note)
        }
        return out
    }

    /** 添加。返回 null 表示成功，否则是失败原因 */
    fun add(inst: Instance, kind: Kind, rawName: String): String? {
        val name = rawName.trim()
        if (name.isEmpty()) return "请填写玩家名"
        if (!NAME_RE.matches(name)) return "玩家名只能是 1~16 位字母、数字或下划线"

        // 服务端在跑：交给它处理，UUID 解析最准
        if (ServerController.isRunning(inst.name)) {
            ServerController.sendCommand(inst.name, kind.addCmd + name)
            return null
        }

        val uuid = if (onlineMode(inst)) {
            mojangUuid(name) ?: return "正版模式需要联网查 UUID，请先启动服务端再添加"
        } else {
            offlineUuid(name)
        }
        return editFile(inst, kind) { arr ->
            // 已存在就不重复添加
            var dup = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("name").equals(name, true) || o.optString("uuid") == uuid) dup = true
            }
            if (!dup) arr.put(entryOf(kind, name, uuid))
            !dup
        }
    }

    /** 移除。返回 null 表示成功，否则是失败原因 */
    fun remove(inst: Instance, kind: Kind, name: String): String? {
        if (ServerController.isRunning(inst.name)) {
            ServerController.sendCommand(inst.name, kind.removeCmd + name)
            return null
        }
        return editFile(inst, kind) { arr ->
            var removed = false
            for (i in arr.length() - 1 downTo 0) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("name").equals(name, true)) {
                    arr.remove(i)
                    removed = true
                }
            }
            removed
        }
    }

    /** `server.properties` 里的 `online-mode`，缺省为 true（正版） */
    fun onlineMode(inst: Instance): Boolean {
        val f = File(inst.dir(), "server.properties")
        if (!f.isFile) return true
        return runCatching {
            f.readLines()
                .firstOrNull { it.trim().startsWith("online-mode") }
                ?.substringAfter('=', "true")
                ?.trim()
                ?.toBoolean()
                ?: true
        }.getOrDefault(true)
    }

    /**
     * 离线模式 UUID：`MD5("OfflinePlayer:<name>")` 再按 RFC 4122 置版本 3 与变体位。
     * 和 Minecraft 服务端离线模式的算法一致。
     */
    fun offlineUuid(name: String): String {
        val hash = MessageDigest.getInstance("MD5")
            .digest("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0F) or 0x30).toByte()
        hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = hash.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return buildString {
            append(hex, 0, 8); append('-')
            append(hex, 8, 12); append('-')
            append(hex, 12, 16); append('-')
            append(hex, 16, 20); append('-')
            append(hex, 20, 32)
        }
    }

    /** 正版 UUID 查询（境外接口，失败返回 null，由调用方提示） */
    private fun mojangUuid(name: String): String? = runCatching {
        val text = Net.getString(
            "https://api.mojang.com/users/profiles/minecraft/$name",
            headers = mapOf("User-Agent" to "MCServerLauncher/1.0")
        )
        val id = JSONObject(text).optString("id")
        if (id.length != 32) null else buildString {
            append(id, 0, 8); append('-')
            append(id, 8, 12); append('-')
            append(id, 12, 16); append('-')
            append(id, 16, 20); append('-')
            append(id, 20, 32)
        }
    }.getOrNull()

    private fun entryOf(kind: Kind, name: String, uuid: String): JSONObject = JSONObject().apply {
        put("uuid", uuid)
        put("name", name)
        when {
            kind == Kind.OPS -> {
                put("level", 4)
                put("bypassesPlayerLimit", false)
            }
            kind.isBan -> {
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
                put("created", now)
                put("source", "Server")
                put("expires", "forever")
                put("reason", "Banned by an operator.")
            }
        }
    }

    /**
     * 读改写 JSON 文件。
     *
     * @param change 返回 true 表示确实改动过；false 表示无需写盘（例如已存在）
     * @return null 表示成功，否则是失败原因
     */
    private fun editFile(inst: Instance, kind: Kind, change: (JSONArray) -> Boolean): String? {
        val f = fileOf(inst, kind)
        return runCatching {
            val arr = if (f.isFile) {
                runCatching { JSONArray(f.readText()) }.getOrElse { JSONArray() }
            } else JSONArray()
            if (!change(arr)) return null
            // 服务端要求 2 空格缩进，但读的时候无所谓；这里保持可读
            f.writeText(arr.toString(2))
            null
        }.getOrElse { "写入失败：${it.message ?: it.javaClass.simpleName}" }
    }
}
