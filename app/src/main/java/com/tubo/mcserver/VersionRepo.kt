package com.tubo.mcserver

import java.io.File

data class McVersion(
    val id: String,
    val type: String,       // release / snapshot / old_beta / old_alpha
    val metaUrl: String
)

object VersionRepo {
    private const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    fun fetchVersions(): List<McVersion> {
        val arr = Net.getJson(MANIFEST).getJSONArray("versions")
        val out = ArrayList<McVersion>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += McVersion(
                id = o.getString("id"),
                type = o.getString("type"),
                metaUrl = o.getString("url")
            )
        }
        return out
    }

    /** 取 vanilla server.jar 的真实下载地址 */
    private fun vanillaServerUrl(metaUrl: String): String {
        val o = Net.getJson(metaUrl)
        return o.getJSONObject("downloads").getJSONObject("server").getString("url")
    }

    /** 取 fabric server launcher jar 地址 */
    private fun fabricServerUrl(mcVersion: String): String {
        val loaders = org.json.JSONArray(Net.getString("https://meta.fabricmc.net/v2/versions/loader/$mcVersion"))
        var chosen = ""
        for (i in 0 until loaders.length()) {
            val l = loaders.getJSONObject(i)
            if (l.optBoolean("stable", false)) { chosen = l.getString("version"); break }
        }
        if (chosen.isEmpty() && loaders.length() > 0) chosen = loaders.getJSONObject(0).getString("version")
        if (chosen.isEmpty()) throw Exception("没有可用的 Fabric loader（版本 $mcVersion）")
        val profile = Net.getJson("https://meta.fabricmc.net/v2/versions/loader/$mcVersion/$chosen/server/json")
        return profile.getJSONObject("downloads").getJSONObject("server").getString("url")
    }

    /**
     * 下载核心到实例目录。
     * @return Pair(成功?, 提示信息)
     */
    fun downloadServer(
        inst: Instance,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit = {},
        shouldCancel: () -> Boolean = { false }
    ): Pair<Boolean, String> {
        return try {
            val target = inst.serverJar()
            target.parentFile?.mkdirs()
            val url = when (inst.loader) {
                "FABRIC" -> {
                    onLog("查询 Fabric loader（${inst.mcVersion}）…")
                    fabricServerUrl(inst.mcVersion)
                }
                else -> {
                    onLog("查询 Mojang 版本清单（${inst.mcVersion}）…")
                    val manifest = Net.getJson(MANIFEST).getJSONArray("versions")
                    var meta = ""
                    for (i in 0 until manifest.length()) {
                        if (manifest.getJSONObject(i).getString("id") == inst.mcVersion) {
                            meta = manifest.getJSONObject(i).getString("url"); break
                        }
                    }
                    if (meta.isEmpty()) return Pair(false, "未找到版本 ${inst.mcVersion}")
                    vanillaServerUrl(meta)
                }
            }
            onLog("下载地址: $url")
            val ok = Net.download(url, target, onProgress, shouldCancel)
            if (ok) {
                val msg = "已下载 ${target.name} (${target.length() / 1024 / 1024} MB)"
                onLog(msg)
                Pair(true, msg)
            } else {
                Pair(false, if (shouldCancel()) "已取消" else "下载失败")
            }
        } catch (e: Exception) {
            Pair(false, "错误: ${e.message}")
        }
    }
}
