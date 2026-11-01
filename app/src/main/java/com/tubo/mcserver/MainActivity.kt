package com.tubo.mcserver

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.remember
import com.tubo.mcserver.ui.shell.AppShell
import com.tubo.mcserver.ui.theme.ZAppTheme
import com.tubo.mcserver.ui.theme.ZTheme
import com.tubo.mcserver.ui.theme.zThemeTypeOf

/**
 * 应用入口。
 *
 * 外壳已迁移到 Compose（[AppShell] + [ZTheme]），旧 XML 布局 `activity_main.xml`
 * 与底部导航栏不再使用。存储权限引导流程保持不变。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val themeName = ZAppTheme.current
            ZTheme(themeType = zThemeTypeOf(themeName)) {
                AppShell()
            }
        }

        askStorageIfNeeded()
        askNotificationIfNeeded()
    }

    /** Android 13+ 需要显式申请通知权限，否则前台服务的常驻通知不展示 */
    private fun askNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
    }

    /** 首次进入时引导用户把数据目录放到 /storage/emulated/0/MCServerLauncher */
    private fun askStorageIfNeeded() {
        if (Storage.hasPublicAccess()) {
            Storage.ensurePublicDir()
            return
        }
        if (Storage.needRuntimePermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ),
                    REQ_STORAGE
                )
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Prefs.storageAsked) return
            Prefs.storageAsked = true
            AlertDialog.Builder(this)
                .setTitle("需要存储权限")
                .setMessage(
                    "启动器会把服务器、Java、下载缓存全部保存到：\n" +
                            "${Storage.publicRoot().absolutePath}\n\n" +
                            "请授予「所有文件访问权限」，否则数据会暂存到应用私有目录。"
                )
                .setPositiveButton("去授权") { _, _ ->
                    Storage.allFilesAccessIntent()?.let {
                        runCatching { startActivity(it) }
                    }
                }
                .setNegativeButton("暂不", null)
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!ok) {
                Toast.makeText(
                    this,
                    "未授予通知权限：服务端仍在运行，只是通知栏不显示状态",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        if (requestCode == REQ_STORAGE) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (ok) "存储权限已授予" else "未授权，数据将存到应用私有目录",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        private const val REQ_STORAGE = 1001
        private const val REQ_NOTIFY = 1002
    }
}
