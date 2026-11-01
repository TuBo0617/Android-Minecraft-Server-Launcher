package com.tubo.mcserver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Context
import java.net.InetSocketAddress
import java.net.Proxy

object Prefs {

    /**
     * 背景音乐配置的版本号。
     *
     * SharedPreferences 不是 Compose 状态，改完外壳不会重组；
     * 这里用自增 state 通知 `DisposableEffect` 重建播放器。
     */
    var audioRevision by mutableStateOf(0)
        private set
    private const val NAME = "mcsl"
    private const val K_JAVA_HOME = "java_home"
    private const val K_MEM = "max_mem"
    private const val K_EULA = "auto_eula"
    private const val K_NOGUI = "use_nogui"
    private const val K_INSTANCE = "cur_instance"
    private const val K_STORAGE_ASKED = "storage_asked"
    private const val K_THEME = "theme_type"
    private const val K_SIDEBAR = "sidebar_expanded"
    private const val K_DL_CONC = "dl_concurrency"
    private const val K_DL_THREADS = "dl_threads"
    private const val K_DL_LIMIT = "dl_speed_limit"
    private const val K_PROXY_ON = "proxy_enabled"
    private const val K_PROXY_HOST = "proxy_host"
    private const val K_PROXY_PORT = "proxy_port"
    private const val K_CF_KEY = "curseforge_key"
    private const val K_KEEP_ALIVE = "keep_alive"
    private const val K_CONSOLE_SIZE = "console_font_size"
    private const val K_CONSOLE_THEME = "console_theme"
    private const val K_FPS = "fps_limit"
    private const val K_BG_AUDIO = "bg_audio_path"
    private const val K_BG_AUDIO_ON = "bg_audio_on"
    private const val K_MP_ACCENT = "mp_accent"

    private fun sp() = App.ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 选中的 Java 安装目录（如 .../java/openjdk-17）；空表示自动检测 */
    var javaHome: String
        get() = sp().getString(K_JAVA_HOME, "") ?: ""
        set(v) = sp().edit().putString(K_JAVA_HOME, v).apply()

    var maxMem: Int
        get() = sp().getInt(K_MEM, 1024)
        set(v) = sp().edit().putInt(K_MEM, v).apply()

    var autoEula: Boolean
        get() = sp().getBoolean(K_EULA, true)
        set(v) = sp().edit().putBoolean(K_EULA, v).apply()

    var useNogui: Boolean
        get() = sp().getBoolean(K_NOGUI, true)
        set(v) = sp().edit().putBoolean(K_NOGUI, v).apply()

    var currentInstance: String
        get() = sp().getString(K_INSTANCE, "") ?: ""
        set(v) = sp().edit().putString(K_INSTANCE, v).apply()

    /** 是否已经提示过存储权限 */
    var storageAsked: Boolean
        get() = sp().getBoolean(K_STORAGE_ASKED, false)
        set(v) = sp().edit().putBoolean(K_STORAGE_ASKED, v).apply()

    /** 当前主题（对应 ZThemeType 的枚举名） */
    var themeType: String
        get() = sp().getString(K_THEME, "EMBERMIRE") ?: "EMBERMIRE"
        set(v) = sp().edit().putString(K_THEME, v).apply()

    /** 侧边栏是否展开 */
    var sidebarExpanded: Boolean
        get() = sp().getBoolean(K_SIDEBAR, true)
        set(v) = sp().edit().putBoolean(K_SIDEBAR, v).apply()

    // ---------------------------------------------------------------- 下载

    /** 下载中心最大并发任务数 */
    var downloadConcurrency: Int
        get() = sp().getInt(K_DL_CONC, 3).coerceIn(1, 8)
        set(v) = sp().edit().putInt(K_DL_CONC, v.coerceIn(1, 8)).apply()

    /** 单任务分块线程数（1~128，默认 8） */
    var downloadThreads: Int
        get() = sp().getInt(K_DL_THREADS, 8).coerceIn(1, 128)
        set(v) = sp().edit().putInt(K_DL_THREADS, v.coerceIn(1, 128)).apply()

    /** 传输限速，单位 KB/s；0 表示不限速 */
    var downloadSpeedLimitKb: Int
        get() = sp().getInt(K_DL_LIMIT, 0).coerceAtLeast(0)
        set(v) = sp().edit().putInt(K_DL_LIMIT, v.coerceAtLeast(0)).apply()

    var proxyEnabled: Boolean
        get() = sp().getBoolean(K_PROXY_ON, false)
        set(v) = sp().edit().putBoolean(K_PROXY_ON, v).apply()

    var proxyHost: String
        get() = sp().getString(K_PROXY_HOST, "") ?: ""
        set(v) = sp().edit().putString(K_PROXY_HOST, v).apply()

    var proxyPort: Int
        get() = sp().getInt(K_PROXY_PORT, 0)
        set(v) = sp().edit().putInt(K_PROXY_PORT, v).apply()

    /** 控制台字号（sp） */
    var consoleFontSize: Int
        get() = sp().getInt(K_CONSOLE_SIZE, 11).coerceIn(9, 18)
        set(v) = sp().edit().putInt(K_CONSOLE_SIZE, v.coerceIn(9, 18)).apply()

    /** 控制台配色：auto 跟随主题 / classic 经典绿字 / contrast 高对比 */
    var consoleTheme: String
        get() = sp().getString(K_CONSOLE_THEME, "auto") ?: "auto"
        set(v) = sp().edit().putString(K_CONSOLE_THEME, v).apply()

    /**
     * 界面刷新上限（0~240，0 表示不限制）。
     *
     * Compose 本身没法真正限帧，这里用它换算成「最小刷新间隔」，
     * 作用在控制台日志轮询与进度刷新上——设得越低越省电、越滞后。
     */
    var fpsLimit: Int
        get() = sp().getInt(K_FPS, 0).coerceIn(0, 240)
        set(v) = sp().edit().putInt(K_FPS, v.coerceIn(0, 240)).apply()

    /** 背景音乐文件（应用私有目录内的路径）；空表示没设置 */
    var bgAudioPath: String
        get() = sp().getString(K_BG_AUDIO, "") ?: ""
        set(v) {
            sp().edit().putString(K_BG_AUDIO, v).apply()
            audioRevision++
        }

    /** 是否播放背景音乐（默认关） */
    var bgAudioOn: Boolean
        get() = sp().getBoolean(K_BG_AUDIO_ON, false)
        set(v) {
            sp().edit().putBoolean(K_BG_AUDIO_ON, v).apply()
            audioRevision++
        }

    /** 联机页强调色（ARGB）；0 表示跟随主题 */
    var multiplayerAccent: Int
        get() = sp().getInt(K_MP_ACCENT, 0)
        set(v) = sp().edit().putInt(K_MP_ACCENT, v).apply()

    /** 前台服务保活（默认开） */
    var keepAlive: Boolean
        get() = sp().getBoolean(K_KEEP_ALIVE, true)
        set(v) = sp().edit().putBoolean(K_KEEP_ALIVE, v).apply()

    /**
     * CurseForge API Key（运行时覆盖）。
     * 空则回落到构建时注入的 BuildConfig.CURSEFORGE_API。
     */
    var curseForgeKey: String
        get() = sp().getString(K_CF_KEY, "") ?: ""
        set(v) = sp().edit().putString(K_CF_KEY, v.trim()).apply()

    /** 代理可用时返回 [Proxy]，否则 null（直连） */
    fun proxyOrNull(): Proxy? {
        if (!proxyEnabled) return null
        val host = proxyHost.trim()
        val port = proxyPort
        if (host.isEmpty() || port !in 1..65535) return null
        return try {
            Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        } catch (e: Exception) {
            null
        }
    }
}
