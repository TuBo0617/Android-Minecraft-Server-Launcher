package com.tubo.mcserver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.io.File

class SettingsFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_settings, c, false)
        val mem = v.findViewById<EditText>(R.id.max_mem)
        val eula = v.findViewById<CheckBox>(R.id.auto_eula)
        val nogui = v.findViewById<CheckBox>(R.id.use_nogui)
        val env = v.findViewById<TextView>(R.id.env_status)
        val archInfo = v.findViewById<TextView>(R.id.arch_info)
        val javaCurrent = v.findViewById<TextView>(R.id.java_current)
        val storageInfo = v.findViewById<TextView>(R.id.storage_info)
        val webAddr = v.findViewById<TextView>(R.id.web_addr)

        mem.setText(Prefs.maxMem.toString())
        eula.isChecked = Prefs.autoEula
        nogui.isChecked = Prefs.useNogui

        archInfo.text = "设备架构: ${JdkManager.arch()}"

        fun refreshJava() {
            val bin = JdkManager.activeBin()
            javaCurrent.text = if (bin != null) {
                "当前 Java: ${bin.absolutePath}"
            } else {
                "当前 Java: 未检测到（请到「Java」页下载安装）"
            }
        }
        refreshJava()

        storageInfo.text = buildString {
            append("数据根目录: ")
            append(Storage.root().absolutePath)
            append("\n目标位置: ")
            append(Storage.publicRoot().absolutePath)
            append("\nJava 运行目录: ")
            append(JdkManager.runtimeRoot().absolutePath)
            append("（Android 禁止从 /sdcard 执行程序）")
            if (!Storage.usingPublic()) append("\n⚠ 未获得公共目录权限，当前使用应用私有目录")
        }

        v.findViewById<Button>(R.id.save).setOnClickListener {
            Prefs.maxMem = mem.text.toString().toIntOrNull() ?: 1024
            Prefs.autoEula = eula.isChecked
            Prefs.useNogui = nogui.isChecked
            Toast.makeText(ctx(), "已保存", Toast.LENGTH_SHORT).show()
        }

        v.findViewById<Button>(R.id.btn_web).setOnClickListener {
            val msg = if (WebConsole.isRunning()) { WebConsole.stop(); "已停止" }
                      else WebConsole.start()
            webAddr.text = if (WebConsole.isRunning()) {
                "访问地址: http://${WebConsole.lanIp()}:${WebConsole.PORT}\n(需在同一 Wi-Fi)"
            } else "未启动"
            Toast.makeText(ctx(), msg, Toast.LENGTH_LONG).show()
        }

        v.findViewById<Button>(R.id.detect).setOnClickListener {
            Thread {
                val sb = StringBuilder()
                val candidates = ArrayList<String>()
                JdkManager.activeBin()?.let { candidates.add(it.absolutePath) }
                JdkManager.listInstalled().forEach { candidates.add(it.bin.absolutePath) }
                if (candidates.isEmpty()) candidates.add("（未检测到任何 Java）")
                for (p in candidates.distinct()) {
                    val f = File(p)
                    sb.append(if (f.exists()) "[✓] $p\n" else "[✗] $p\n")
                }
                sb.append("\n存储目录: ")
                sb.append(if (Storage.usingPublic()) "公共目录 ✓" else "应用私有目录（未授权）")
                activity?.runOnUiThread {
                    env.text = sb.toString()
                    refreshJava()
                }
            }.start()
        }
        return v
    }

    private fun ctx() = requireContext()
}
