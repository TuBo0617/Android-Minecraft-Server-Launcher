package com.tubo.mcserver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.ToggleButton

class ModsFragment : Fragment() {
    private lateinit var rv: RecyclerView
    private lateinit var query: EditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var type = "mod"
    private val items = ArrayList<ModEntry>()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_mods, c, false)
        rv = v.findViewById(R.id.list)
        query = v.findViewById(R.id.query)
        progress = v.findViewById(R.id.progress)
        status = v.findViewById(R.id.status)
        rv.layoutManager = LinearLayoutManager(requireContext())

        v.findViewById<Button>(R.id.search).setOnClickListener { doSearch() }
        v.findViewById<ToggleButton>(R.id.tab_mod).setOnCheckedChangeListener { _, checked ->
            if (checked) { type = "mod"; v.findViewById<ToggleButton>(R.id.tab_plugin).isChecked = false }
        }
        v.findViewById<ToggleButton>(R.id.tab_plugin).setOnCheckedChangeListener { _, checked ->
            if (checked) { type = "plugin"; v.findViewById<ToggleButton>(R.id.tab_mod).isChecked = false }
        }
        return v
    }

    private fun current(): Instance? {
        val inst = InstanceManager.get(Prefs.currentInstance)
        if (inst == null) status.text = "请先在「实例」页新建并选中实例"
        return inst
    }

    private fun doSearch() {
        val inst = current() ?: return
        val q = query.text.toString().trim().ifEmpty { "world" }
        status.text = "搜索中: $q (适配 ${inst.mcVersion}, $type)…"
        progress.visibility = View.VISIBLE
        Thread {
            try {
                val res = ModrinthRepo.search(q, inst.mcVersion, type)
                requireActivity().runOnUiThread {
                    items.clear(); items.addAll(res)
                    rv.adapter = Adapter(items)
                    status.text = "找到 ${res.size} 个结果 (安装到 ${inst.name}/${if (type == "plugin") "plugins" else "mods"})"
                    progress.visibility = View.GONE
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    status.text = "搜索失败: ${e.message}"
                    progress.visibility = View.GONE
                }
            }
        }.start()
    }

    inner class Adapter(val data: List<ModEntry>) : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title = v.findViewById<TextView>(R.id.title)
            val desc = v.findViewById<TextView>(R.id.desc)
            val btn = v.findViewById<Button>(R.id.install)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_mod, p, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val m = data[pos]
            h.title.text = "${m.title}  (↓${m.downloads})"
            h.desc.text = m.description
            h.btn.setOnClickListener {
                val inst = current() ?: return@setOnClickListener
                h.btn.isEnabled = false
                val task = DlManager.create(
                    "${if (type == "plugin") "插件" else "模组"} ${m.title}",
                    "mod"
                )
                Thread {
                    DlManager.start(task)
                    try {
                        val dir = if (type == "plugin") inst.pluginsDir() else inst.modsDir()
                        val fname = ModrinthRepo.install(
                            m.slug, inst.mcVersion, type, dir,
                            { d, t -> DlManager.progress(task, d, t) },
                            { line -> task.log(line) },
                            { task.cancelRequested }
                        )
                        DlManager.success(task, "已安装: $fname")
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "已安装: $fname", Toast.LENGTH_LONG).show()
                            h.btn.text = "已装"
                        }
                    } catch (e: Exception) {
                        if (task.cancelRequested) DlManager.cancelled(task) else DlManager.fail(task, "安装失败: ${e.message}")
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "安装失败: ${e.message}", Toast.LENGTH_LONG).show()
                            h.btn.isEnabled = true
                        }
                    }
                }.start()
            }
        }
    }
}
