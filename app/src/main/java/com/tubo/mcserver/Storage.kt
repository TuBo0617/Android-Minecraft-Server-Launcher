package com.tubo.mcserver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 统一数据目录管理。
 * 目标位置：/storage/emulated/0/MCServerLauncher/
 *    servers/    服务器实例（世界、mods、plugins、核心 jar）
 *    java/       下载安装的 JDK（每个版本一个子目录）
 *    downloads/  下载缓存
 *    logs/       日志
 *    tmp/        临时解包目录
 * 若用户未授予「所有文件访问权限」，则自动回退到应用私有外部目录，
 * 保证功能不崩溃（界面上会提示去授权）。
 */
object Storage {
    const val APP_FOLDER = "MCServerLauncher"

    /** /storage/emulated/0/MCServerLauncher */
    @Suppress("DEPRECATION")
    fun publicRoot(): File = File(Environment.getExternalStorageDirectory(), APP_FOLDER)

    fun hasPublicAccess(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                App.ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    } catch (e: Exception) {
        false
    }

    private fun privateRoot(): File = File(App.ctx.getExternalFilesDir(null), APP_FOLDER)

    /** 实际使用的根目录 */
    fun root(): File {
        val pub = publicRoot()
        val usePublic = hasPublicAccess() || (pub.exists() && pub.canWrite())
        val f = if (usePublic) pub else privateRoot()
        if (!f.exists()) f.mkdirs()
        return f
    }

    fun usingPublic(): Boolean = root().absolutePath == publicRoot().absolutePath

    fun servers(): File = dir("servers")
    fun javaRoot(): File = dir("java")
    fun downloads(): File = dir("downloads")
    fun logs(): File = dir("logs")
    fun temp(): File = dir("tmp")

    private fun dir(name: String): File {
        val f = File(root(), name)
        if (!f.exists()) f.mkdirs()
        return f
    }

    /** 尝试创建公共目录，返回是否可写 */
    fun ensurePublicDir(): Boolean {
        val r = publicRoot()
        if (!r.exists()) r.mkdirs()
        return r.exists() && r.canWrite()
    }

    /** 申请「所有文件访问权限」的 Intent（Android 11+），低版本返回 null */
    fun allFilesAccessIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + App.ctx.packageName)
            )
        } catch (e: Exception) {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    /** 需要申请运行时存储权限（Android 6~10） */
    fun needRuntimePermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                ContextCompat.checkSelfPermission(
                    App.ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
}
