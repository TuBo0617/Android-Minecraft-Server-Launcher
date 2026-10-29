package com.tubo.mcserver

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class InstancesFragment : Fragment() {
    private lateinit var rv: RecyclerView
    private val items = ArrayList<Instance>()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val v = i.inflate(R.layout.fragment_instances, c, false)
        rv = v.findViewById(R.id.list)
        rv.layoutManager = LinearLayoutManager(requireContext())
        v.findViewById<ExtendedFloatingActionButton>(R.id.fab).setOnClickListener { showCreateDialog() }
        return v
    }

    override fun onResume() { super.onResume(); reload() }

    private fun reload() {
        items.clear(); items.addAll(InstanceManager.list())
        rv.adapter = Adapter(items)
    }

    private fun showCreateDialog() {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 30, 40, 10) }
        val name = EditText(ctx).apply { hint = "实例名 (如 survival1)" }
        val verSpinner = Spinner(ctx)
        box.addView(name)
        box.addView(android.widget.TextView(ctx).apply { text = "MC 版本："; textSize = 13f })
        box.addView(verSpinner)
        val loading = ProgressBar(ctx)
        box.addView(loading)

        Thread {
            val versions = try { VersionRepo.fetchVersions().filter { it.type == "release" }.map { it.id }.take(50) }
                           catch (e: Exception) { listOf("1.20.1", "1.21.4", "1.21") }
            activity?.runOnUiThread {
                loading.visibility = View.GONE
                verSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, versions)
            }
        }.start()

        val loaders = arrayOf("Vanilla (原版 Java)", "Fabric (模组加载器)", "Bedrock (基岩版，需 proot)")
        var loaderIdx = 0
        AlertDialog.Builder(ctx)
            .setTitle("新建服务器实例")
            .setView(box)
            .setSingleChoiceItems(loaders, 0) { _, which -> loaderIdx = which }
            .setPositiveButton("创建并下载核心") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) { Toast.makeText(ctx, "名字不能为空", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val v = verSpinner.selectedItem?.toString() ?: "1.20.1"
                val loader = when (loaderIdx) { 1 -> "FABRIC"; 2 -> "BEDROCK"; else -> "VANILLA" }
                val inst = InstanceManager.create(n, v, loader)
                Prefs.currentInstance = n
                reload()
                Toast.makeText(ctx, "开始下载 $v 核心，进度见「下载」页", Toast.LENGTH_SHORT).show()
                val task = DlManager.create(
                    if (loader == "BEDROCK") "基岩版 BDS" else "服务端核心 $v ($loader)",
                    if (loader == "BEDROCK") "bds" else "server"
                )
                Thread {
                    DlManager.start(task)
                    if (loader == "BEDROCK") {
                        val (ok, m) = BedrockManager.downloadBds(
                            inst,
                            { d, t -> DlManager.progress(task, d, t) },
                            { line -> task.log(line) },
                            { task.cancelRequested }
                        )
                        if (ok) {
                            task.log("准备 proot 运行环境…")
                            BedrockManager.ensureProot { line -> task.log(line) }
                            DlManager.success(task, m)
                        } else DlManager.fail(task, m)
                    } else {
                        val (ok, msg) = VersionRepo.downloadServer(
                            inst,
                            { d, t -> DlManager.progress(task, d, t) },
                            { line -> task.log(line) },
                            { task.cancelRequested }
                        )
                        if (ok) DlManager.success(task, msg) else {
                            if (task.cancelRequested) DlManager.cancelled(task) else DlManager.fail(task, msg)
                        }
                    }
                    activity?.runOnUiThread { reload() }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    inner class Adapter(val data: List<Instance>) : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name = v.findViewById<android.widget.TextView>(R.id.name)
            val meta = v.findViewById<android.widget.TextView>(R.id.meta)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_instance, p, false))
        override fun getItemCount() = data.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val it = data[pos]
            val cur = if (it.name == Prefs.currentInstance) "  ●当前" else ""
            h.name.text = it.name + cur
            val hasJar = if (it.serverJar().exists()) "核心已就绪" else "未下载核心"
            h.meta.text = "${it.mcVersion} · ${it.loader} · $hasJar"
            h.itemView.setOnClickListener { _ ->
                Prefs.currentInstance = it.name
                Toast.makeText(requireContext(), "已切换到 ${it.name}", Toast.LENGTH_SHORT).show()
                reload()
            }
            h.itemView.setOnLongClickListener { _ ->
                AlertDialog.Builder(requireContext())
                    .setTitle("删除 ${it.name}?")
                    .setMessage("将删除整个服务器目录(含世界、模组)")
                    .setPositiveButton("删除") { _, _ -> InstanceManager.delete(it); reload() }
                    .setNegativeButton("取消", null).show()
                true
            }
        }
    }
}
