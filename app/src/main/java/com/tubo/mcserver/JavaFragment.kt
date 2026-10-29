package com.tubo.mcserver

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * Java 管理页：
 *  - 自动检测 <存储根>/java 下所有已安装的 Java，点击切换当前使用
 *  - 一键下载安装 openjdk 17/21/25
 *  - 下载进度与 Termux 完整安装日志可通过按钮显示/隐藏
 */
class JavaFragment : Fragment() {

    private lateinit var storagePath: TextView
    private lateinit var javaList: LinearLayout
    private lateinit var empty: TextView
    private lateinit var spinner: Spinner
    private lateinit var toggle: Button
    private lateinit var progressBox: View
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private val handler = Handler(Looper.getMainLooper())
    private var expanded = false
    private var lastLogLength = -1
    private var lastStateKey = ""

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_java, c, false)
        storagePath = v.findViewById(R.id.storage_path)
        javaList = v.findViewById(R.id.java_list)
        empty = v.findViewById(R.id.java_empty)
        spinner = v.findViewById(R.id.jdk_version)
        toggle = v.findViewById(R.id.btn_toggle)
        progressBox = v.findViewById(R.id.progress_box)
        progress = v.findViewById(R.id.progress)
        progressText = v.findViewById(R.id.progress_text)
        logText = v.findViewById(R.id.log_text)
        logScroll = v.findViewById(R.id.log_scroll)

        spinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Java 17（推荐，兼容性最好）", "Java 21（1.20.5+ 推荐）", "Java 25（最新）")
        )

        v.findViewById<Button>(R.id.btn_grant).setOnClickListener { onGrant() }
        v.findViewById<Button>(R.id.btn_download).setOnClickListener { onDownload() }
        toggle.setOnClickListener { setExpanded(!expanded) }

        refreshStorageInfo()
        refreshInstalled()
        setExpanded(false)
        return v
    }

    private fun onGrant() {
        if (Storage.hasPublicAccess()) {
            Storage.ensurePublicDir()
            Toast.makeText(requireContext(), "已具备存储权限", Toast.LENGTH_SHORT).show()
            refreshStorageInfo(); refreshInstalled()
        } else {
            val intent = Storage.allFilesAccessIntent()
            if (intent != null) {
                runCatching { startActivity(intent) }
                Toast.makeText(requireContext(), "请在设置中允许「所有文件访问」", Toast.LENGTH_LONG).show()
            } else {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                    ), 2001
                )
            }
        }
    }

    private fun refreshStorageInfo() {
        storagePath.text = buildString {
            append("数据目录: ")
            append(Storage.root().absolutePath)
            append("\nJava 运行目录: ")
            append(JdkManager.runtimeRoot().absolutePath)
            append("\n说明: Android 禁止从 /sdcard 执行程序，故 Java 解包在此私有目录；")
            append("服务器、核心、下载包仍保存在数据目录。")
            if (!Storage.usingPublic()) {
                append("\n⚠ 未获得公共目录写入权限，暂存在应用私有目录。请点下方按钮授权。")
            }
        }
    }

    private fun refreshInstalled() {
        javaList.removeAllViews()
        val installed = JdkManager.listInstalled()
        empty.visibility = if (installed.isEmpty()) View.VISIBLE else View.GONE
        val active = JdkManager.activeBin()?.absolutePath
        for (j in installed) {
            val item = layoutInflater.inflate(R.layout.item_java, javaList, false)
            item.findViewById<TextView>(R.id.jname).text = j.label
            item.findViewById<TextView>(R.id.jpath).text = j.bin.absolutePath
            val state = item.findViewById<TextView>(R.id.jstate)
            val isActive = active != null && j.bin.absolutePath == active
            state.text = when {
                isActive -> "● 当前使用中"
                !j.exists -> "文件缺失"
                else -> "点击设为当前使用"
            }
            state.setTextColor(if (isActive) 0xFF1B5E20.toInt() else 0xFF888888.toInt())
            item.setOnClickListener {
                JdkManager.select(j.home)
                Toast.makeText(requireContext(), "已切换到 ${j.label}", Toast.LENGTH_SHORT).show()
                refreshInstalled()
            }
            javaList.addView(item)
        }
    }

    private fun onDownload() {
        if (!Storage.usingPublic() && !Storage.hasPublicAccess()) {
            Toast.makeText(requireContext(), "建议先授予存储权限", Toast.LENGTH_SHORT).show()
        }
        val ver = when (spinner.selectedItemPosition) {
            1 -> "21"
            2 -> "25"
            else -> "17"
        }
        if (DlManager.all().any { it.kind == "java" && (it.status == DlTask.STATUS_RUNNING || it.status == DlTask.STATUS_PENDING) }) {
            Toast.makeText(requireContext(), "已有 Java 下载任务在进行，见「下载」页", Toast.LENGTH_SHORT).show()
            return
        }
        JdkManager.install(ver)
        Toast.makeText(requireContext(), "已开始下载 Java $ver，进度见下方", Toast.LENGTH_SHORT).show()
        setExpanded(true)
    }

    private fun setExpanded(v: Boolean) {
        expanded = v
        progressBox.visibility = if (v) View.VISIBLE else View.GONE
        toggle.text = if (v) "隐藏下载进度 / 完整日志" else "显示下载进度 / 完整日志"
        if (v) {
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        } else handler.removeCallbacks(ticker)
    }

    private fun latestJavaTask(): DlTask? =
        DlManager.all().firstOrNull { it.kind == "java" }

    private fun refreshTask() {
        val t = latestJavaTask()
        if (t == null) {
            progress.progress = 0
            progressText.text = "暂无下载任务"
            return
        }
        val p = t.percent
        progress.progress = if (p < 0) 0 else p
        val sizeInfo = if (t.total > 0) {
            "${t.done / 1024 / 1024}MB / ${t.total / 1024 / 1024}MB"
        } else {
            "${t.done / 1024}KB"
        }
        progressText.text = "${t.title} · ${t.status}  $sizeInfo${if (p >= 0) "  ($p%)" else ""}\n${t.message}"
        val text = t.logText()
        if (text.length != lastLogLength) {
            lastLogLength = text.length
            logText.text = text
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
        val key = "${t.id}:${t.status}"
        if (key != lastStateKey) {
            lastStateKey = key
            if (t.finished) refreshInstalled()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshTask()
            handler.postDelayed(this, 600)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStorageInfo()
        refreshInstalled()
        if (expanded) {
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }
        if (latestJavaTask()?.finished == false) setExpanded(true)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(ticker)
    }
}
