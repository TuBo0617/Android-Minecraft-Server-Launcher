package com.tubo.mcserver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

class ConsoleFragment : Fragment() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var cmd: EditText

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_console, c, false)
        log = v.findViewById(R.id.log)
        scroll = v.findViewById(R.id.scroll)
        cmd = v.findViewById(R.id.cmd)

        v.findViewById<Button>(R.id.btn_start).setOnClickListener {
            val inst = InstanceManager.get(Prefs.currentInstance)
            if (inst == null) { Toast.makeText(ctx(), "请先在「实例」页选择实例", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val msg = ServerController.start(inst)
            appendLine(msg)
        }
        v.findViewById<Button>(R.id.btn_stop).setOnClickListener { ServerController.stop() }
        v.findViewById<Button>(R.id.btn_clear).setOnClickListener { log.text = "" }
        v.findViewById<Button>(R.id.btn_send).setOnClickListener {
            val c = cmd.text.toString().trim()
            if (c.isNotEmpty()) { appendLine("> $c"); ServerController.sendCommand(c); cmd.setText("") }
        }
        return v
    }

    private fun ctx() = requireContext()

    override fun onResume() {
        super.onResume()
        ServerController.onLine = { line -> appendLine(line) }
        ServerController.onExit = { /* no-op */ }
        val inst = InstanceManager.get(Prefs.currentInstance)
        appendLine("当前实例: ${inst?.name ?: "未选择"}  |  Java: ${JdkManager.activeBin()?.absolutePath ?: "未检测到"}  |  内存: ${Prefs.maxMem}M")
        appendLine("若首次启动，请先在「下载核心」下载 server.jar，并确认 Java 环境就绪。")
    }

    override fun onPause() {
        super.onPause()
        ServerController.onLine = null
    }

    private fun appendLine(s: String) {
        if (!isAdded) return
        activity?.runOnUiThread {
            log.append(s + "\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
