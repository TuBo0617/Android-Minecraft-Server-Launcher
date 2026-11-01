package com.tubo.mcserver

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** 网页控制台的一个本机账户 */
data class WebUser(
    val id: String,
    var username: String,
    var passwordHash: String,
    var isAdmin: Boolean,
    var permissions: MutableSet<String>,
    var enabled: Boolean,
    /** 新建用户首次登录强制改密 */
    var mustReset: Boolean,
    val createdAt: Long
) {
    fun can(perm: String): Boolean = isAdmin || permissions.contains(perm)
}

/**
 * 网页控制台账户体系（更新日志「网页控制台用户权限模块」）。
 *
 * - 默认自带一个「所有者」管理员账户，首次启用时生成随机密码，可在启动器设置里查看；
 * - 权限分两类：管理员（全部权限）/ 自定义（细粒度勾选）；
 * - 新建用户默认关闭，需手动开启；
 * - 密码只存加盐 SHA-256，不落明文。
 */
object WebAuth {

    const val PERM_VIEW_LOG = "view_log"
    const val PERM_SEND_CMD = "send_command"
    const val PERM_START = "start_server"
    const val PERM_STOP = "stop_server"
    const val PERM_MANAGE_USERS = "manage_users"

    /** 细粒度权限清单（顺序即界面顺序） */
    val ALL_PERMISSIONS: Map<String, String> = linkedMapOf(
        PERM_VIEW_LOG to "查看控制台日志",
        PERM_SEND_CMD to "发送服务器指令",
        PERM_START to "启动服务端",
        PERM_STOP to "停止服务端",
        PERM_MANAGE_USERS to "管理网页用户"
    )

    private const val FILE = "web_users.json"
    private val rnd = SecureRandom()
    private val users = ArrayList<WebUser>()
    private var loaded = false

    private fun file(): File = File(Storage.root(), FILE)

    @Synchronized
    fun all(): List<WebUser> {
        ensureLoaded()
        return users.toList()
    }

    @Synchronized
    fun byId(id: String): WebUser? {
        ensureLoaded()
        return users.firstOrNull { it.id == id }
    }

    @Synchronized
    fun byName(name: String): WebUser? {
        ensureLoaded()
        return users.firstOrNull { it.username.equals(name, ignoreCase = true) }
    }

    /** 首次调用时创建所有者账户并返回它（密码随机，可在设置里查看/重置） */
    @Synchronized
    fun ensureOwner(): WebUser {
        ensureLoaded()
        users.firstOrNull { it.isAdmin }?.let { return it }
        val owner = WebUser(
            id = newId(),
            username = "owner",
            passwordHash = hash(generatePassword()),
            isAdmin = true,
            permissions = ALL_PERMISSIONS.keys.toMutableSet(),
            enabled = true,
            mustReset = false,
            createdAt = System.currentTimeMillis()
        )
        users.add(owner)
        persist()
        return owner
    }

    /** 重置所有者密码，返回新的明文密码 */
    @Synchronized
    fun resetOwnerPassword(): String {
        val owner = ensureOwner()
        val pwd = generatePassword()
        owner.passwordHash = hash(pwd)
        owner.mustReset = false
        persist()
        return pwd
    }

    @Synchronized
    fun add(
        username: String,
        password: String,
        isAdmin: Boolean,
        permissions: Set<String>
    ): Pair<WebUser?, String?> {
        ensureLoaded()
        val name = username.trim()
        when {
            name.isEmpty() -> return Pair(null, "用户名不能为空")
            name.length < 3 -> return Pair(null, "用户名至少 3 位")
            name.length > 24 -> return Pair(null, "用户名最多 24 位")
            !name.all { it.isLetterOrDigit() || it == '_' || it == '-' } ->
                return Pair(null, "用户名只能包含字母、数字、下划线和短横线")
            byName(name) != null -> return Pair(null, "用户名已存在")
        }
        validatePassword(password)?.let { return Pair(null, it) }

        val u = WebUser(
            id = newId(),
            username = name,
            passwordHash = hash(password),
            isAdmin = isAdmin,
            permissions = if (isAdmin) ALL_PERMISSIONS.keys.toMutableSet()
            else permissions.intersect(ALL_PERMISSIONS.keys).toMutableSet(),
            enabled = false,          // 新建用户默认关闭
            mustReset = true,         // 首次登录强制改密
            createdAt = System.currentTimeMillis()
        )
        users.add(u)
        persist()
        return Pair(u, null)
    }

    @Synchronized
    fun delete(id: String): Boolean {
        ensureLoaded()
        val u = users.firstOrNull { it.id == id } ?: return false
        if (u.isAdmin && users.count { it.isAdmin } <= 1) return false   // 至少留一个管理员
        users.remove(u)
        persist()
        return true
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        ensureLoaded()
        users.firstOrNull { it.id == id }?.let {
            it.enabled = enabled
            persist()
        }
    }

    @Synchronized
    fun setPermissions(id: String, perms: Set<String>) {
        ensureLoaded()
        users.firstOrNull { it.id == id }?.let {
            it.permissions = perms.intersect(ALL_PERMISSIONS.keys).toMutableSet()
            persist()
        }
    }

    @Synchronized
    fun setAdmin(id: String, isAdmin: Boolean) {
        ensureLoaded()
        val u = users.firstOrNull { it.id == id } ?: return
        if (!isAdmin && u.isAdmin && users.count { it.isAdmin } <= 1) return
        u.isAdmin = isAdmin
        if (isAdmin) u.permissions = ALL_PERMISSIONS.keys.toMutableSet()
        persist()
    }

    /**
     * 改密。
     * @param checkOld 是否校验旧密码（首次登录强制改密时传 false）
     * @return null 表示成功，否则为错误信息
     */
    @Synchronized
    fun changePassword(id: String, oldPassword: String?, newPassword: String, checkOld: Boolean): String? {
        ensureLoaded()
        val u = users.firstOrNull { it.id == id } ?: return "用户不存在"
        if (checkOld) {
            if (oldPassword == null || !matches(u, oldPassword)) return "原密码不正确"
        }
        validatePassword(newPassword)?.let { return it }
        if (newPassword == oldPassword) return "新密码不能与原密码相同"
        u.passwordHash = hash(newPassword)
        u.mustReset = false
        persist()
        return null
    }

    /** 校验账号密码；成功返回用户，失败返回 null */
    @Synchronized
    fun verify(username: String, password: String): WebUser? {
        ensureLoaded()
        val u = byName(username.trim()) ?: return null
        if (!u.enabled) return null
        if (!matches(u, password)) return null
        return u
    }

    /** 密码规则校验，返回 null 表示通过 */
    fun validatePassword(pwd: String): String? = when {
        pwd.length < 8 -> "密码至少 8 位"
        pwd.length > 64 -> "密码最多 64 位"
        pwd.any { it.code > 127 } -> "只能使用英文、数字和符号，不支持中文等字符"
        pwd.any { it.isWhitespace() } -> "密码不能包含空格"
        !pwd.any { it in 'A'..'Z' } -> "需要至少一个大写字母"
        !pwd.any { it in 'a'..'z' } -> "需要至少一个小写字母"
        !pwd.any { it in '0'..'9' } -> "需要至少一个数字"
        !pwd.any { !it.isLetterOrDigit() } -> "需要至少一个符号"
        else -> null
    }

    /** 生成满足规则的随机密码 */
    fun generatePassword(len: Int = 14): String {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghijkmnopqrstuvwxyz"
        val digit = "23456789"
        val symbol = "!@#%^&*-_=+"
        val all = upper + lower + digit + symbol
        val sb = StringBuilder()
        sb.append(upper[rnd.nextInt(upper.length)])
        sb.append(lower[rnd.nextInt(lower.length)])
        sb.append(digit[rnd.nextInt(digit.length)])
        sb.append(symbol[rnd.nextInt(symbol.length)])
        while (sb.length < len) sb.append(all[rnd.nextInt(all.length)])
        val chars = sb.toString().toMutableList()
        chars.shuffle()
        return chars.joinToString("")
    }

    // ------------------------------------------------------------ 内部

    private fun matches(u: WebUser, pwd: String): Boolean =
        runCatching { u.passwordHash == hash(pwd, u.passwordHash.substringBefore('$')) }
            .getOrDefault(false)

    private fun hash(pwd: String, salt: String = newSalt()): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt.toByteArray())
        md.update(pwd.toByteArray())
        val hex = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return "$salt\$$hex"
    }

    private fun newSalt(): String {
        val b = ByteArray(16)
        rnd.nextBytes(b)
        return b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun newId(): String =
        java.lang.Long.toHexString(System.nanoTime()) + rnd.nextInt(1000)

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val f = file()
            if (!f.isFile) return@runCatching
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val perms = HashSet<String>()
                o.optJSONArray("permissions")?.let { p ->
                    for (j in 0 until p.length()) perms += p.getString(j)
                }
                users += WebUser(
                    id = o.optString("id"),
                    username = o.optString("username"),
                    passwordHash = o.optString("passwordHash"),
                    isAdmin = o.optBoolean("isAdmin", false),
                    permissions = perms,
                    enabled = o.optBoolean("enabled", true),
                    mustReset = o.optBoolean("mustReset", false),
                    createdAt = o.optLong("createdAt", 0L)
                )
            }
        }
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            users.forEach { u ->
                arr.put(
                    JSONObject().apply {
                        put("id", u.id)
                        put("username", u.username)
                        put("passwordHash", u.passwordHash)
                        put("isAdmin", u.isAdmin)
                        put("permissions", JSONArray(u.permissions.toList()))
                        put("enabled", u.enabled)
                        put("mustReset", u.mustReset)
                        put("createdAt", u.createdAt)
                    }
                )
            }
            file().writeText(arr.toString(2))
        }
    }
}
