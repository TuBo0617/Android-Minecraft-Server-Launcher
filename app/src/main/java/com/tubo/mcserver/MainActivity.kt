package com.tubo.mcserver

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

class MainActivity : AppCompatActivity() {

    private val tabIds = intArrayOf(
        R.id.nav_instances, R.id.nav_core, R.id.nav_mods, R.id.nav_console,
        R.id.nav_java, R.id.nav_downloads, R.id.nav_settings
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabIds.forEach { id ->
            findViewById<TextView>(id).setOnClickListener { selectTab(id) }
        }
        selectTab(R.id.nav_instances)
        askStorageIfNeeded()
    }

    private fun selectTab(id: Int) {
        val f: Fragment = when (id) {
            R.id.nav_instances -> InstancesFragment()
            R.id.nav_core -> DownloadFragment()
            R.id.nav_mods -> ModsFragment()
            R.id.nav_console -> ConsoleFragment()
            R.id.nav_java -> JavaFragment()
            R.id.nav_downloads -> DownloadsFragment()
            else -> SettingsFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, f)
            .commit()
        tabIds.forEach { tid ->
            findViewById<TextView>(tid).setTextColor(
                if (tid == id) 0xFF1B5E20.toInt() else 0xFF999999.toInt()
            )
        }
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
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
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
                .setMessage("启动器会把服务器、Java、下载缓存全部保存到：\n${Storage.publicRoot().absolutePath}\n\n请授予「所有文件访问权限」，否则数据会暂存到应用私有目录。")
                .setPositiveButton("去授权") { _, _ ->
                    Storage.allFilesAccessIntent()?.let {
                        runCatching { startActivity(it) }
                    }
                }
                .setNegativeButton("暂不", null)
                .show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Toast.makeText(this, if (ok) "存储权限已授予" else "未授权，数据将存到应用私有目录", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_STORAGE = 1001
    }
}
