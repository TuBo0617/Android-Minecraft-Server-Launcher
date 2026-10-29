package com.tubo.mcserver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class DownloadFragment : Fragment() {
    private lateinit var rv: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var swipe: SwipeRefreshLayout
    private val versions = ArrayList<McVersion>()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_download, c, false)
        rv = v.findViewById(R.id.list)
        progress = v.findViewById(R.id.progress)
        status = v.findViewById(R.id.status)
        swipe = v.findViewById(R.id.swipe)
        rv.layoutManager = LinearLayoutManager(requireContext())
        swipe.setOnRefreshListener { load() }
        return v
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun current(): Instance? {
        val cur = Prefs.currentInstance
        if (cur.isEmpty()) {
            status.text = "请先在「实例」页新建并选中一个实例"
            return null
        }
        val inst = InstanceManager.get(cur)
        if (inst == null) status.text = "未找到当前实例: $cur"
        return inst
    }

    private fun load() {
        val inst = current()
        if (inst == null) { swipe.isRefreshing = false; return }
        status.text = "实例: ${inst.name} (${inst.mcVersion}/${inst.loader})"
        status.append("\n正在获取版本列表…")
        Thread {
            try {
                val list = VersionRepo.fetchVersions()
                // 只显示 release，便于查找
                val releases = list.filter { it.type == "release" }
                val shown = ArrayList<McVersion>()
                shown += releases.take(40)
                shown += list.firstOrNull { it.id == inst.mcVersion }?.let { listOf(it) } ?: emptyList()
                requireActivity().runOnUiThread {
                    versions.clear(); versions.addAll(shown.distinctBy { it.id })
                    rv.adapter = Adapter(versions)
                    status.append("\n共 ${versions.size} 个近期 release 版本")
                    swipe.isRefreshing = false
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    status.append("\n加载失败: ${e.message}")
                    swipe.isRefreshing = false
                }
            }
        }.start()
    }

    inner class Adapter(val data: List<McVersion>) : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title = v.findViewById<TextView>(R.id.title)
            val sub = v.findViewById<TextView>(R.id.sub)
            val action = v.findViewById<Button>(R.id.action)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_version, p, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val v = data[pos]
            h.title.text = v.id
            h.sub.text = v.type
            h.action.text = "下载"
            h.action.setOnClickListener {
                val inst = current() ?: return@setOnClickListener
                // 以列表中选中的版本为准下载；若版本与实例版本不同，提示
                if (v.id != inst.mcVersion) {
                    Toast.makeText(requireContext(), "提示: 下载 $v.id 到 ${inst.name}(实例版本 ${inst.mcVersion})", Toast.LENGTH_LONG).show()
                }
                progress.visibility = View.VISIBLE
                h.action.isEnabled = false
                val target = inst.copy(mcVersion = v.id)
                val task = DlManager.create("服务端核心 ${v.id} (${target.loader})", "server")
                Toast.makeText(requireContext(), "已开始下载，进度见「下载」页", Toast.LENGTH_SHORT).show()
                Thread {
                    DlManager.start(task)
                    val (ok, msg) = VersionRepo.downloadServer(
                        target,
                        { done, total ->
                            DlManager.progress(task, done, total)
                            requireActivity().runOnUiThread {
                                if (total > 0) {
                                    progress.isIndeterminate = false
                                    progress.max = 100
                                    progress.progress = (done * 100 / total).toInt()
                                } else progress.isIndeterminate = true
                            }
                        },
                        { line -> task.log(line) },
                        { task.cancelRequested }
                    )
                    if (ok) DlManager.success(task, msg) else {
                        if (task.cancelRequested) DlManager.cancelled(task) else DlManager.fail(task, msg)
                    }
                    requireActivity().runOnUiThread {
                        progress.visibility = View.GONE
                        h.action.isEnabled = true
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
        }
    }
}
