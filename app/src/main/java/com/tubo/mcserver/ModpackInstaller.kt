package com.tubo.mcserver

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.zip.ZipFile

/**
 * 整合包安装（更新日志第 16 条）。
 *
 * 目前完整支持 **Modrinth `.mrpack`**：
 * 1. 下载 mrpack（本质是个 zip，只有元数据，模组单独下载）；
 * 2. 读 `modrinth.index.json` 的 `dependencies` 决定 MC 版本与加载器；
 * 3. 按 `files[].path` 把每个文件下载到实例对应目录（`env.server == unsupported` 的跳过）；
 * 4. 解压 `overrides/`（`client-overrides/` 服务端不需要，跳过）；
 * 5. 用整合包封面做实例图标。
 *
 * CurseForge 整合包的 `manifest.json` 只有 projectID/fileID，必须用 API Key 换直链，
 * 未配置 Key 时无法安装，会明确提示。
 */
object ModpackInstaller {

    private const val API = "https://api.modrinth.com/v2"

    /** 从 Modrinth 安装整合包，返回创建好的实例 */
    fun installFromModrinth(
        projectId: String,
        mcVersionFilter: String,
        instanceName: String,
        iconUrl: String,
        onLog: (String) -> Unit,
        onProgress: (Long, Long) -> Unit,
        shouldCancel: () -> Boolean
    ): Instance {
        onLog(if (mcVersionFilter.isBlank()) "查询整合包版本…" else "查询整合包版本（$mcVersionFilter）…")
        val url = if (mcVersionFilter.isBlank()) {
            "$API/project/$projectId/version"
        } else {
            "$API/project/$projectId/version?game_versions=" +
                    URLEncoder.encode("[\"$mcVersionFilter\"]", "UTF-8")
        }
        val arr = JSONArray(Net.getString(url))
        if (arr.length() == 0) throw Exception("该整合包没有可用的版本")

        // 从新到旧找第一个带 .mrpack 的版本
        var picked: JSONObject? = null
        var mrFile: JSONObject? = null
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val files = v.optJSONArray("files") ?: continue
            for (j in 0 until files.length()) {
                val f = files.getJSONObject(j)
                if (f.optString("filename").endsWith(".mrpack", ignoreCase = true)) {
                    picked = v
                    mrFile = f
                    break
                }
            }
            if (mrFile != null) break
        }
        val file = mrFile ?: throw Exception("该整合包没有提供 .mrpack 文件")
        val version = picked!!
        onLog("版本 ${version.optString("version_number")} · ${file.optString("filename")}")

        // 1) 下载 mrpack
        val tmp = File(Storage.temp(), "modpack-${System.currentTimeMillis()}.mrpack")
        tmp.parentFile?.mkdirs()
        onLog("下载整合包…")
        // 整合包与内部文件一律单线程：包内文件多而碎，多线程收益小、失败率高
        if (!MultiDownloader.download(
                file.getString("url"), tmp,
                threads = 1,
                onProgress = onProgress,
                shouldCancel = shouldCancel
            )
        ) {
            tmp.delete()
            throw Exception("整合包下载失败")
        }

        var zip: ZipFile? = null
        try {
            zip = ZipFile(tmp)
            val entry = zip.getEntry("modrinth.index.json")
                ?: throw Exception("整合包里没有 modrinth.index.json")
            val index = JSONObject(zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) })

            val deps = index.optJSONObject("dependencies") ?: JSONObject()
            val mcVersion = deps.optString("minecraft").ifEmpty { mcVersionFilter }
            val (loader, coreId) = loaderOf(deps)
            onLog("目标版本 $mcVersion · 加载器 $loader")

            // 2) 建实例
            val name = uniqueName(instanceName.ifBlank { index.optString("name", "整合包实例") })
            val inst = InstanceManager.create(name, mcVersion, loader, coreId)
            onLog("已创建实例「${name}」")

            // 3) 图标
            if (iconUrl.isNotBlank()) {
                runCatching {
                    val icon = File(inst.dir(), "icon.png")
                    if (Net.download(iconUrl, icon, { _, _ -> }) { false }) {
                        InstanceManager.save(inst.copy(iconPath = icon.absolutePath))
                    }
                }
            }

            // 4) 下载整合包内的文件
            val files = index.optJSONArray("files") ?: JSONArray()
            var total = 0L
            val plan = ArrayList<Triple<String, String, Long>>()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val env = f.optJSONObject("env")
                if (env?.optString("server") == "unsupported") continue
                val path = f.optString("path")
                val urls = f.optJSONArray("downloads") ?: continue
                val url = urls.optString(0)
                if (path.isEmpty() || url.isEmpty()) continue
                val size = f.optLong("fileSize", 0L)
                total += size
                plan += Triple(path, url, size)
            }
            onLog("需下载 ${plan.size} 个文件（${total / 1024 / 1024} MB）")

            var done = 0L
            plan.forEachIndexed { idx, (path, url, _) ->
                if (shouldCancel()) throw Exception("已取消")
                val dest = File(inst.dir(), path)
                dest.parentFile?.mkdirs()
                onLog("[${idx + 1}/${plan.size}] $path")
                val ok = MultiDownloader.download(
                    url, dest,
                    threads = 1,
                    onProgress = { d, _ -> onProgress(done + d, total) },
                    shouldCancel = shouldCancel
                )
                if (!ok) throw Exception("下载失败: $path")
                done += dest.length()
            }

            // 5) overrides（服务端只需要 overrides 与 server-overrides）
            var extracted = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val rel = when {
                    e.name.startsWith("overrides/") -> e.name.removePrefix("overrides/")
                    e.name.startsWith("server-overrides/") -> e.name.removePrefix("server-overrides/")
                    // 部分整合包（含 PCL2 导出的）把内容放在 .minecraft/ 或 minecraft/ 下
                    e.name.startsWith(".minecraft/") -> e.name.removePrefix(".minecraft/")
                    e.name.startsWith("minecraft/") -> e.name.removePrefix("minecraft/")
                    else -> null
                } ?: continue
                if (rel.isEmpty()) continue
                // 防 zip-slip：不允许写到实例目录之外
                if (rel.contains("..")) continue
                val out = File(inst.dir(), rel)
                out.parentFile?.mkdirs()
                zip.getInputStream(e).use { i ->
                    FileOutputStream(out).use { o -> i.copyTo(o) }
                }
                extracted++
            }
            if (extracted > 0) onLog("解压 overrides：$extracted 个文件")

            // 6) 留一份索引，方便以后导出/复原
            runCatching {
                File(inst.dir(), "modrinth.index.json")
                    .writeText(index.toString(2))
            }

            // 7) 标记为整合包实例
            val saved = inst.copy(modpack = index.optString("name", instanceName))
            InstanceManager.save(saved)
            onLog("整合包安装完成：${saved.name}")

            // 8) 自动下载服务端核心与加载器（否则实例建好了也启动不了）
            onLog("开始下载服务端核心（$coreId $mcVersion）…")
            CoreInstaller.start(saved) { ok, msg ->
                if (!ok) {
                    onLog("核心下载失败：$msg（可到实例设置里手动重试）")
                }
            }
            return saved
        } finally {
            runCatching { zip?.close() }
            runCatching { tmp.delete() }
        }
    }

    /** 从 dependencies 推断加载器与核心 id */
    private fun loaderOf(deps: JSONObject): Pair<String, String> = when {
        deps.has("fabric-loader") -> "FABRIC" to "FABRIC"
        deps.has("quilt-loader") -> "QUILT" to "QUILT"
        deps.has("neoforge") -> "NEOFORGE" to "NEOFORGE"
        deps.has("forge") -> "FORGE" to "FORGE"
        else -> "VANILLA" to "VANILLA"
    }

    private fun uniqueName(base: String): String {
        val clean = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(28)
            .ifEmpty { "整合包实例" }
        if (InstanceManager.get(clean) == null) return clean
        var i = 2
        while (InstanceManager.get("$clean-$i") != null) i++
        return "$clean-$i"
    }
}
