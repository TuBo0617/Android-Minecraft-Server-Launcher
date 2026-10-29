package com.tubo.mcserver

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 下载管理页：统一查看 Java / 服务端核心 / 基岩版 / 模组的下载进度与完整日志。
 */
class DownloadsFragment : Fragment() {

    private lateinit var rv: RecyclerView
    private lateinit var summary: TextView
    private lateinit var empty: TextView
    private val adapter = Adapter()
    private val handler = Handler(Looper.getMainLooper())
    private val expandedIds = HashSet<Long>()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_downloads, c, false)
        rv = v.findViewById(R.id.list)
        summary = v.findViewById(R.id.summary)
        empty = v.findViewById(R.id.empty)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        v.findViewById<Button>(R.id.btn_clear).setOnClickListener {
            DlManager.clearFinished()
            refresh()
        }
        return v
    }

    private fun refresh() {
        val all = DlManager.all()
        summary.text = "下载管理（${all.size} 个任务，${DlManager.runningCount()} 进行中）"
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        adapter.items = all
        adapter.notifyDataSetChanged()
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 800)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(ticker)
    }

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        var items: List<DlTask> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title = v.findViewById<TextView>(R.id.title)
            val state = v.findViewById<TextView>(R.id.state)
            val progress = v.findViewById<ProgressBar>(R.id.progress)
            val detail = v.findViewById<TextView>(R.id.detail)
            val btnDetail = v.findViewById<Button>(R.id.btn_detail)
            val btnCancel = v.findViewById<Button>(R.id.btn_cancel)
            val logScroll = v.findViewById<ScrollView>(R.id.log_scroll)
            val log = v.findViewById<TextView>(R.id.log)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_download, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val t = items[pos]
            h.title.text = t.title
            h.state.text = t.status
            h.state.setTextColor(
                when (t.status) {
                    DlTask.STATUS_DONE -> 0xFF1B5E20.toInt()
                    DlTask.STATUS_FAILED -> 0xFFC62828.toInt()
                    DlTask.STATUS_CANCELLED -> 0xFF888888.toInt()
                    else -> 0xFF1565C0.toInt()
                }
            )
            val p = t.percent
            h.progress.progress = if (p < 0) 0 else p
            val size = if (t.total > 0) "${t.done / 1024 / 1024}MB / ${t.total / 1024 / 1024}MB"
            else "${t.done / 1024}KB"
            h.detail.text = buildString {
                append(size)
                if (p >= 0) append("  ($p%)")
                if (t.message.isNotEmpty()) append("\n").append(t.message)
            }

            val expanded = expandedIds.contains(t.id)
            h.logScroll.visibility = if (expanded) View.VISIBLE else View.GONE
            h.btnDetail.text = if (expanded) "隐藏日志" else "显示日志"
            if (expanded) {
                val text = t.logText()
                if (h.log.text.toString() != text) {
                    h.log.text = text
                    h.logScroll.post { h.logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
            h.btnDetail.setOnClickListener {
                if (expandedIds.contains(t.id)) expandedIds.remove(t.id) else expandedIds.add(t.id)
                notifyItemChanged(pos)
            }
            h.btnCancel.visibility = if (t.finished) View.GONE else View.VISIBLE
            h.btnCancel.setOnClickListener { DlManager.cancel(t) }
        }
    }
}
