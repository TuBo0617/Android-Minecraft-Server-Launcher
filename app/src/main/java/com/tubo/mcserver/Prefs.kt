package com.tubo.mcserver

import android.content.Context

object Prefs {
    private const val NAME = "mcsl"
    private const val K_JAVA_HOME = "java_home"
    private const val K_MEM = "max_mem"
    private const val K_EULA = "auto_eula"
    private const val K_NOGUI = "use_nogui"
    private const val K_INSTANCE = "cur_instance"
    private const val K_STORAGE_ASKED = "storage_asked"

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
}
