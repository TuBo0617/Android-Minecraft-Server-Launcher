package com.tubo.mcserver

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一个下载任务的完整状态：名称、类型、进度、状态、完整日志。
 */
class DlTask(
    val id: Long,
    val title: String,
    val kind: String          // java / server / mod / bds / other
) {
    @Volatile var done: Long = 0
    @Volatile var total: Long = 0
    @Volatile var status: String = STATUS_PENDING
    @Volatile var message: String = ""
    @Volatile var cancelRequested: Boolean = false

    val logs: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())

    val percent: Int
        get() = if (total > 0) (done * 100L / total).toInt().coerceIn(0, 100) else -1

    val finished: Boolean
        get() = status == STATUS_DONE || status == STATUS_FAILED || status == STATUS_CANCELLED

    fun log(line: String) {
        logs.add(line)
        synchronized(logs) { while (logs.size > 800) logs.removeAt(0) }
        DlManager.notifyChanged()
    }

    fun logAll(text: String) {
        text.split("\n").forEach { if (it.isNotBlank()) log(it) }
    }

    fun logText(): String = synchronized(logs) { logs.joinToString("\n") }

    companion object {
        const val STATUS_PENDING = "等待中"
        const val STATUS_RUNNING = "进行中"
        const val STATUS_DONE = "已完成"
        const val STATUS_FAILED = "失败"
        const val STATUS_CANCELLED = "已取消"
    }
}

/**
 * 全局下载任务表。所有耗时下载（Java / 服务端核心 / 基岩版 / 模组）
 * 都登记到这里，「下载管理」页统一查看进度与日志。
 */
object DlManager {
    private var seq = 0L
    private val tasks = CopyOnWriteArrayList<DlTask>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pendingNotify = false

    fun create(title: String, kind: String): DlTask {
        val t = DlTask(++seq, title, kind)
        tasks.add(0, t)          // 新的在前
        notifyChanged()
        return t
    }

    fun all(): List<DlTask> = tasks.toList()

    fun runningCount(): Int = tasks.count { it.status == DlTask.STATUS_RUNNING || it.status == DlTask.STATUS_PENDING }

    fun isRunning(): Boolean = runningCount() > 0

    fun addListener(l: () -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** 合并高频回调，最多每帧刷一次 UI */
    fun notifyChanged() {
        if (pendingNotify) return
        pendingNotify = true
        main.post {
            pendingNotify = false
            listeners.toList().forEach { runCatching { it() } }
        }
    }

    fun cancel(t: DlTask) {
        if (t.finished) return
        t.cancelRequested = true
        t.log("已请求取消…")
        notifyChanged()
    }

    fun clearFinished() {
        tasks.removeAll { it.finished }
        notifyChanged()
    }

    fun start(t: DlTask) {
        t.status = DlTask.STATUS_RUNNING
        t.done = 0
        t.total = 0
        t.message = ""
        notifyChanged()
    }

    fun progress(t: DlTask, done: Long, total: Long) {
        t.done = done
        t.total = total
        notifyChanged()
    }

    fun success(t: DlTask, msg: String) {
        t.status = DlTask.STATUS_DONE
        t.message = msg
        t.log(msg)
        notifyChanged()
    }

    fun fail(t: DlTask, msg: String) {
        t.status = DlTask.STATUS_FAILED
        t.message = msg
        t.log(msg)
        notifyChanged()
    }

    fun cancelled(t: DlTask) {
        t.status = DlTask.STATUS_CANCELLED
        t.message = "已取消"
        notifyChanged()
    }
}
