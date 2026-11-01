package com.tubo.mcserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import net.burningtnt.terracotta.TerracottaAndroidAPI
import org.json.JSONObject

/**
 * 解析后的陶瓦状态。
 *
 * 字段名取自官方 `TerracottaState.java`（`@JsonType(property = "state")`）：
 * - `state`：waiting / host-scanning / host-starting / host-ok /
 *   guest-connecting / guest-starting / guest-ok / exception
 * - `index`：自增序号，越大越新
 * - `room`：**只有 host-ok** 才有，房主拿到的房间码
 * - `url`：**只有 guest-ok** 才有，客户端该连的地址
 * - `type`：**只有 exception** 才有，错误类型枚举
 */
data class TcState(
    val state: String = "",
    val index: Int = -1,
    val room: String = "",
    val url: String = "",
    val errorType: String = "",
    /** host-ok 才有的房间成员列表（访客名） */
    val profiles: List<String> = emptyList()
) {
    val active: Boolean get() = state.isNotEmpty()
}

/**
 * 陶瓦联机（Terracotta）封装。
 *
 * 官方从 0.4 起为 Android 提供 `libterracotta.so` + 一份 Java API，
 * 底层是 EasyTier，通过 VpnService 建立虚拟局域网，让不同网络的玩家出现在同一局域网里。
 *
 * 注意：Android 版**不是可执行文件**，是 FFI 动态库，只能 `System.loadLibrary` 后调 API。
 *
 * ## 线程约定（重要）
 *
 * 所有 native 方法**都必须在工作线程调用**，绝不能在主线程。
 * 原因：EasyTier 申请 VPN 时，native 线程会进入最长 30 秒的等待循环，
 * 等待期间它会持锁，此时主线程再调 `getState()` 就会被卡住 —— 界面直接假死，
 * 而主线程又恰好是唯一能执行 `onStartCommand` 去建 VPN 的线程，于是互相等死。
 * 本类的 `ensureInit` / `state` / `host` / `join` / `dumpLogs` 全部按阻塞调用设计。
 */
object Terracotta {

    /** exception 状态的 type 枚举，顺序与官方 TerracottaState.Exception.Type 一致 */
    private val EXCEPTION_NAMES = listOf(
        "PING_HOST_FAIL",
        "PING_HOST_RST",
        "GUEST_ET_CRASH",
        "HOST_ET_CRASH",
        "PING_SERVER_RST",
        "SCAFFOLDING_INVALID_RESPONSE"
    )

    @Volatile private var loaded = false
    @Volatile private var initialized = false
    @Volatile private var lastError: String? = null
    @Volatile private var lastRaw: String = ""

    /** VPN 请求标记：Terracotta 需要建 VPN 时会置位，界面可据此展示 */
    @Volatile var vpnRequested: Boolean = false
        private set

    fun reportError(msg: String) {
        lastError = msg
    }

    fun lastError(): String? = lastError
    fun isLoaded(): Boolean = loaded
    fun isInitialized(): Boolean = initialized
    fun rawState(): String = lastRaw

    /**
     * 加载并初始化后端（可重复调用，内部只真正跑一次）。
     *
     * 阻塞调用，请放在 IO 线程。返回 null 表示可用，否则是失败原因。
     */
    @Synchronized
    fun ensureInit(ctx: Context): String? {
        if (initialized) return null
        if (!loaded) {
            try {
                // 交给官方 API 的静态块加载：它会先设好 net.burningtnt.terracotta.native_location
                // 再 System.loadLibrary。自己抢先 load 会漏掉那个属性，native 侧可能找不到自身路径。
                Class.forName("net.burningtnt.terracotta.TerracottaAndroidAPI")
                loaded = true
            } catch (e: Throwable) {
                val msg = "原生库加载失败：${e.message ?: e.javaClass.simpleName}"
                lastError = msg
                return msg
            }
        }
        return try {
            TerracottaAndroidAPI.initialize(ctx.applicationContext) {
                // 这个回调跑在 native 线程，且 native 正阻塞等我们把 VPN 建好（30 秒超时）。
                // 所以必须在这里直接拉起服务，绝不能绕回主线程去轮询。
                onVpnRequest()
            }
            initialized = true
            null
        } catch (e: Throwable) {
            // 已经初始化过（例如并发调用），按成功处理
            if (e is IllegalStateException && e.message?.contains("already started") == true) {
                initialized = true
                return null
            }
            val msg = "初始化失败：${e.message ?: e.javaClass.simpleName}"
            lastError = msg
            msg
        }
    }

    /** 在 native 线程上响应 VPN 请求 */
    private fun onVpnRequest() {
        vpnRequested = true
        val ctx = runCatching { App.ctx }.getOrNull() ?: run {
            lastError = "拿不到应用上下文，无法建立 VPN"
            return
        }
        runCatching {
            ctx.startService(Intent(ctx, TerracottaVpnService::class.java))
        }.onFailure {
            lastError = "拉起 VPN 服务失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    /** 取当前状态。阻塞调用，请放在 IO 线程。 */
    fun state(): TcState {
        if (!initialized) return TcState()
        val raw = try {
            TerracottaAndroidAPI.getState()
        } catch (e: Throwable) {
            lastError = "读取状态失败：${e.message ?: e.javaClass.simpleName}"
            return TcState()
        }
        lastRaw = raw
        return parse(raw)
    }

    /** 解析官方状态 JSON；早期状态可能是裸字符串 */
    private fun parse(raw: String): TcState {
        val t = raw.trim()
        if (t.isEmpty()) return TcState()
        if (!t.startsWith("{")) {
            return TcState(state = t.trim('"'))
        }
        val o = runCatching { JSONObject(t) }.getOrNull() ?: return TcState(state = t)
        return TcState(
            state = o.optString("state", ""),
            index = o.optInt("index", -1),
            room = o.optString("room", ""),
            url = o.optString("url", ""),
            errorType = when (val ty = o.opt("type")) {
                is Int -> EXCEPTION_NAMES.getOrNull(ty) ?: "type=$ty"
                is String -> ty
                else -> ""
            },
            profiles = o.optJSONArray("profiles")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                }
            } ?: emptyList()
        )
    }

    /**
     * 创建房间。阻塞调用，成功后房间码在 [state] 的 `room` 里。
     *
     * ## 为什么是 setScanning 而不是 setWaiting
     *
     * `setWaiting()` 在 Rust 侧只做一件事：把状态置成 Waiting（`set_waiting` 的实现
     * 只有一句 `state.set(AppState::Waiting)`）。**它不会开房**，
     * 之前调它就是「点了没反应」的直接原因。
     *
     * 真正开房的是 `set_scanning`，流程是：
     * 1. 要求当前状态必须是 Waiting（所以先 `setWaiting()` 复位）；
     * 2. 起一个 Minecraft 扫描器，监听组播 `224.0.2.60:4445` 找本地服务端；
     * 3. 找到端口 → `host-starting` → 连脚手架服务器 → `host-ok`（带房间码）。
     *
     * 第 2 步是关键：**必须有服务端在广播局域网通告**，否则永远停在扫描态。
     * 专用服务端不会自己发这个包，所以由 [LanBroadcaster] 代发。
     */
    fun host(gamePort: Int): String? = try {
        LanBroadcaster.start(gamePort)
        // set_scanning 要求处于 Waiting，先复位（已经在 Waiting 时是空操作）
        TerracottaAndroidAPI.setWaiting()
        TerracottaAndroidAPI.setScanning(null, null)
        null
    } catch (e: Throwable) {
        LanBroadcaster.stop()
        "创建房间失败：${e.message ?: e.javaClass.simpleName}"
    }

    /** 加入房间。阻塞调用。 */
    fun join(room: String): String? {
        val code = room.trim()
        if (code.isEmpty()) return "请先填写房间码"
        return try {
            // 先做格式校验，能直接告诉用户是「码不对」而不是笼统的失败
            val type = TerracottaAndroidAPI.parseRoomCode(code)
                ?: return "房间码格式不对，请完整复制（不要漏字符）"
            // set_guesting 同样要求状态为 Waiting，否则直接返回 false
            TerracottaAndroidAPI.setWaiting()
            if (TerracottaAndroidAPI.setGuesting(code, null)) null
            else "加入失败（$type）：房间码可能已过期或房主已退出"
        } catch (e: Throwable) {
            "加入失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 退出房间 / 取消开房。
     *
     * 注意**不能**用 `setScanning` 来退出 —— 那恰好是「开始开房」，
     * 会让扫描器一直跑下去。官方的复位入口就是 `setWaiting`。
     */
    fun leave(): String? = try {
        LanBroadcaster.stop()
        TerracottaAndroidAPI.setWaiting()
        null
    } catch (e: Throwable) {
        "退出失败：${e.message ?: e.javaClass.simpleName}"
    }

    /**
     * 导出 Terracotta 自己的运行日志，用于排查。
     *
     * 注意：读取期间会阻塞其它 native 方法，所以必须立刻读完并关闭。
     */
    fun dumpLogs(maxChars: Int = 20000): String {
        if (!initialized) return "（尚未初始化）"
        return try {
            TerracottaAndroidAPI.collectLogs().use { r ->
                val text = r.readText()
                if (text.length > maxChars) "…（仅显示末尾 ${maxChars} 字符）\n" + text.takeLast(maxChars)
                else text
            }
        } catch (e: Throwable) {
            "读取日志失败：${e.message ?: e.javaClass.simpleName}"
        }
    }
}

/**
 * Terracotta 需要的 VpnService。
 *
 * 它不会自己建 VPN，而是通过 [TerracottaAndroidAPI.VpnServiceCallback.onStartVpnService]
 * 通知我们去启动这个服务；服务里把 builder 交给 Terracotta 完成配置与 establish。
 *
 * 关键点：**必须在 30 秒内完成**，否则 native 侧抛异常、整个联机功能卡死。
 */
class TerracottaVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = try {
            TerracottaAndroidAPI.getPendingVpnServiceRequest()
        } catch (e: Throwable) {
            // 没有待处理请求（已处理过 / 已超时）——直接退出，不要误报
            stopSelf()
            return START_NOT_STICKY
        }

        return try {
            runCatching { tun?.close() }
            // 不要自己 addAddress / setMtu，Terracotta 会往里塞地址和路由
            tun = request.startVpnService(
                Builder().setSession("MCServerLauncher 联机")
            )
            promoteForeground()
            START_STICKY
        } catch (e: Throwable) {
            Terracotta.reportError("建立 VPN 失败：${e.message ?: e.javaClass.simpleName}")
            runCatching { request.reject() }
            stopSelf()
            START_NOT_STICKY
        }
    }

    /** 提成前台服务，避免系统回收导致 VPN 掉线；失败不影响主流程 */
    private fun promoteForeground() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "联机状态", NotificationManager.IMPORTANCE_LOW)
                )
            }
            // Notification.Builder(ctx, channelId) 本身也要 API 26，低版本得用旧构造
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val n = builder
                .setContentTitle("联机已建立")
                .setContentText("虚拟局域网运行中")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build()
            startForeground(NOTIFY_ID, n)
        }
    }

    override fun onRevoke() {
        runCatching { tun?.close() }
        tun = null
        runCatching { stopForeground(true) }
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { tun?.close() }
        tun = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "terracotta_vpn"
        private const val NOTIFY_ID = 0x7A11
    }
}
