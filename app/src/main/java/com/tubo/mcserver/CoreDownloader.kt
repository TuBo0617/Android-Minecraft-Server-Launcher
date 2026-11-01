package com.tubo.mcserver

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 服务端核心下载器。
 *
 * 各接口的**真实**行为（2026-10 逐个实测，不是照文档猜的）：
 *
 * - Mojang：`version_manifest_v2.json` → 版本 json → `downloads.server.url`，直接给 jar。
 * - Fabric：`/v2/versions/loader/{game}` 返回数组，**loader 版本在 `loader.version`**（不在顶层），
 *   且 `.../server/json` **已经没有 `downloads` 字段**了 —— 所以改用官方
 *   `fabric-installer`，`server -dir <dir> -mcversion X -loader Y -downloadMinecraft`
 *   产出 `fabric-server-launch.jar` + `libraries/`。
 * - Quilt：同上，用 `quilt-installer install server X Y --install-dir=... --download-server --create-scripts`，
 *   产出 `quilt-server-launch.jar`。
 * - PaperMC：v2 已 410 下线，统一走 `fill.papermc.io/v3`；构建列表是数组，
 *   下载地址在 `downloads["server:default"].url`，`channel` 用 `STABLE`。
 * - Purpur：`/v2/purpur/{version}` 的 `builds.latest`，再 `/latest/download`。
 * - BungeeCord：官方 Jenkins 最后一次成功构建。
 * - Forge / NeoForge：下载安装器并执行 `--installServer`。
 * - GitHub Release：按「关键字 + MC 版本」挑资产。
 * - Spigot / CraftBukkit / Legacy：需要 BuildTools 本地构建，明确告知而不是静默失败。
 */
object CoreDownloader {

    private const val FILL_FALLBACK = "https://fill.papermc.io/v3"
    private const val FABRIC_MAVEN = "https://maven.fabricmc.net"
    private const val QUILT_MAVEN = "https://maven.quiltmc.org/repository/release"

    fun download(
        inst: Instance,
        coreId: String,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        shouldCancel: () -> Boolean
    ): Pair<Boolean, String> {
        val opt = ServerCoreCatalog.find(coreId)
            ?: return Pair(false, "未知的服务端核心：$coreId")
        if (inst.mcVersion.isBlank()) return Pair(false, "实例没有设置 Minecraft 版本")
        return try {
            when (opt.source) {
                CoreSource.MOJANG -> mojang(inst, onProgress, onLog, shouldCancel)
                CoreSource.FABRIC_META -> fabric(inst, onProgress, onLog, shouldCancel)
                CoreSource.QUILT_META -> quilt(inst, onProgress, onLog, shouldCancel)
                CoreSource.PAPER_API -> papermc(inst, opt, onProgress, onLog, shouldCancel)
                CoreSource.PURPUR_API -> purpur(inst, onProgress, onLog, shouldCancel)
                CoreSource.BUNGEECORD_CI -> bungeeCord(inst, onProgress, onLog, shouldCancel)
                CoreSource.GITHUB_RELEASE -> github(inst, opt, onProgress, onLog, shouldCancel)
                CoreSource.FORGE_MAVEN -> forgeInstaller(inst, onProgress, onLog, shouldCancel)
                CoreSource.NEOFORGE_MAVEN -> neoForgeInstaller(inst, onProgress, onLog, shouldCancel)
                CoreSource.SPIGOT_BUILDTOOLS -> Pair(
                    false,
                    "${opt.name} 需要本地 BuildTools 构建（会编译整个服务端），启动器暂不代跑。" +
                            "建议改用 Paper / Purpur，插件兼容。"
                )
            }
        } catch (e: Exception) {
            val detail = e.message ?: e.javaClass.simpleName
            onLog("失败: ${e.javaClass.simpleName}: $detail")
            Pair(false, "错误: $detail")
        }
    }

    // ------------------------------------------------------------ 原版

    private fun mojang(
        inst: Instance,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        shouldCancel: () -> Boolean
    ): Pair<Boolean, String> {
        val base = DownloadSources.baseUrlOf("mojang", "https://piston-meta.mojang.com")
        onLog("查询 Mojang 版本清单（${inst.mcVersion}）…")
        val versions = Net.getJson("$base/mc/game/version_manifest_v2.json").getJSONArray("versions")
        var meta = ""
        for (i in 0 until versions.length()) {
            val o = versions.getJSONObject(i)
            if (o.optString("id") == inst.mcVersion) {
                meta = o.optString("url"); break
            }
        }
        if (meta.isEmpty()) return Pair(false, "Mojang 清单里没有版本 ${inst.mcVersion}")
        val url = Net.getJson(meta).getJSONObject("downloads").getJSONObject("server").getString("url")
        return saveTo(inst, url, onProgress, onLog, shouldCancel)
    }

    // ------------------------------------------------------------ Fabric / Quilt（安装器）

    /**
     * 选 loader 版本。
     * Fabric v2 与 Quilt v3 的响应元素都是 `{ loader: { version, stable }, ... }`，
     * 版本号在 `loader.version` 里 —— 早期顶层还有 `version`，现在没有了。
     */
    private fun pickLoaderVersion(arr: JSONArray, preferStable: Boolean): String {
        if (arr.length() == 0) return ""
        if (preferStable) {
            for (i in 0 until arr.length()) {
                val l = arr.optJSONObject(i)?.optJSONObject("loader") ?: continue
                if (l.optBoolean("stable", false)) {
                    val v = l.optString("version")
                    if (v.isNotEmpty()) return v
                }
            }
        }
        return arr.optJSONObject(0)?.optJSONObject("loader")?.optString("version").orEmpty()
    }

    private fun fabric(
        inst: Instance,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        shouldCancel: () -> Boolean
    ): Pair<Boolean, String> {
        val base = DownloadSources.baseUrlOf("fabric", "https://meta.fabricmc.net")
        onLog("查询 Fabric loader（${inst.mcVersion}）…")
        val arr = JSONArray(Net.getString("$base/v2/versions/loader/${inst.mcVersion}"))
        if (arr.length() == 0) {
            return Pair(false, "Fabric 还没有 ${inst.mcVersion} 的 loader，换个核心或降版本")
        }
        val loader = pickLoaderVersion(arr, preferStable = true)
        if (loader.isEmpty()) return Pair(false, "Fabric 没有可用的 loader 版本")
        onLog("Fabric loader $loader")

        val installerVer = mavenLatest("$FABRIC_MAVEN/net/fabricmc/fabric-installer/maven-metadata.xml")
            ?: return Pair(false, "取不到 Fabric 安装器版本")
        val url = "$FABRIC_MAVEN/net/fabricmc/fabric-installer/$installerVer/fabric-installer-$installerVer.jar"

        val args = listOf(
            "server",
            "-dir", inst.dir().absolutePath,
            "-mcversion", inst.mcVersion,
            "-loader", loader,
            "-downloadMinecraft"
        )
        return installWithInstaller(
            inst, url, "fabric-installer.jar", args,
            expectJar = inst.serverJar().name,
            onProgress = onProgress, onLog = onLog, shouldCancel = shouldCancel
        )
    }

    private fun quilt(
        inst: Instance,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        shouldCancel: () -> Boolean
    ): Pair<Boolean, String> {
        val base = DownloadSources.baseUrlOf("quilt", "https://meta.quiltmc.org")
        onLog("查询 Quilt loader（${inst.mcVersion}）…")
        val arr = JSONArray(Net.getString("$base/v3/versions/loader/${inst.mcVersion}"))
        if (arr.length() == 0) {
            return Pair(false, "Quilt 还没有 ${inst.mcVersion} 的 loader，换个核心或降版本")
        }
        val loader = pickLoaderVersion(arr, preferStable = false)
        if (loader.isEmpty()) return Pair(false, "Quilt 没有可用的 loader 版本")
        onLog("Quilt loader $loader")

        val installerVer = mavenLatest("$QUILT_MAVEN/org/quiltmc/quilt-installer/maven-metadata.xml")
            ?: return Pair(false, "取不到 Quilt 安装器版本")
        val url = "$QUILT_MAVEN/org/quiltmc/quilt-installer/$installerVer/quilt-installer-$installerVer.jar"

        val args = listOf(
            "install", "server", inst.mcVersion, loader,
            "--install-dir=${inst.dir().absolutePath}",
            "--download-server",
            "--create-scripts"
        )
        return installWithInstaller(
            inst, url, "quilt-installer.jar", args,
            expectJar = inst.serverJar().name,
            onProgress = onProgress, onLog = onLog, shouldCancel = shouldCancel
        )
    }

    // ------------------------------------------------------------ PaperMC 系

    /**
     * paper / folia / velocity / waterfall 统一走 PaperMC fill v3。
     *
     * v2（`api.papermc.io/v2`）已返回 410，之前所有 Paper 系核心都装不上的原因就在这里。
     * v3 的构建列表是**数组**，下载地址在 `downloads["server:default"].url`。
     */
    private fun papermc(
        inst: Instance,
        opt: ServerCoreOption,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        shouldCancel: () -> Boolean
    ): Pair<Boolean, String> {
        val base = DownloadSources.baseUrlOf("paper", FILL_FALLBACK).trimEnd('/')
        val project = opt.project.ifEmpty { "paper" }

        val version = resolveProjectVersion(base, project, inst.mcVersion, onLog)
            ?: return Pair(false, "${opt.name} 没有 ${inst.mcVersion} 的构建（可在设置里换核心）")

        onLog("查询 $project $version 构建列表…")
        val arr = JSONArray(Net.getString("$base/projects/$project/versions/$version/builds"))
        if (arr.length() == 0) return Pair(false, "$project 没有 $version 的可用构建")

        // 优先 STABLE，其次退到第一个（v3 数组默认新构建在前）
        var picked: JSONObject = arr.getJSONObject(0)
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            if (b.optString("channel").equals("STABLE", true)) { picked = b; break }
        }

        val buildNo = picked.optInt("id", -1)
        val downloads = picked.optJSONObject("downloads")
            ?: return Pair(false, "构建 $buildNo 没有下载信息")
        val file = downloads.optJSONObject("server:default")
            ?: downloads.optJSONObject("server:mojang")
            ?: return Pair(false, "构建 $buildNo 没有 server 下载项")
        val url = file.optString("url")
        if (url.isEmpty()) return Pair(false, "构建 $buildNo 缺少下载地址")

        onLog("${opt.name} $version 构建 $buildNo（${file.optString("name")}）")
        return saveTo(inst, url, onProgress, onLog, shouldCancel)
    }

    /**
     * 解析该核心要用的版本号。
     * 代理端（velocity / waterfall）的版本与 MC 版本无关，取最新的 SUPPORTED 版本；
     * 其它核心要求 `versions` 列表里确实包含当前 MC 版本，否则返回 null。
     */
    private fun resolveProjectVersion(
        base: String,
        project: String,
        mcVersion: String,
        onLog: (String) -> Unit
    ): String? {
        val root = Net.getJson("$base/projects/$project/versions")
        val arr = root.optJSONArray("versions") ?: return mcVersion
        val ids = ArrayList<String>()
        var firstSupported = ""
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i)?.optJSONObject("version") ?: continue
            val id = v.optString("id")
            if (id.isEmpty()) continue
            ids += id
            val status = v.optJSONObject("support")?.optString("status") ?: ""
            if (firstSupported.isEmpty() && status.equals("SUPPORTED", true)) firstSupported = id
        }
        if (ids.isEmpty()) return mcVersion

        if (ids.contains(mcVersion)) return mcVersion
        if (project == "velocity" || project == "waterfall") {
            val pick = firstSupported.ifEmpty { ids.first() }
            onLog("$project 与 MC 版本无关，使用 $pick")
            return pick
        }
        return null
    }

    // ------------------------------------------------------------ Purpur

    private fun purpur(
        inst: Instance,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        shouldCancel: () -> Boolean
    ): Pair<Boolean, String> {
        val base = DownloadSources.baseUrlOf("purpur", "https://api.purpurmc.org/v2").trimEnd('/')
        onLog("查询 Purpur ${inst.mcVersion}…")
        val meta = Net.getJson("$base/purpur/${inst.mcVersion}")
        // builds: { latest: "2062", all: [...] }
        val build = meta.optJSONObject("builds")?.optString("latest").orEmpty()
        if (build.isEmpty()) return Pair(false, "Purpur 没有 ${inst.mcVersion} 的构建")
        onLog("Purpur 构建 $build")
        return saveTo(inst, "$base/purpur/${inst.mcVersion}/latest/download", onProgress, onLog, shouldCancel)
    }

    // ------------------------------------------------------------ BungeeCord

    private fun bungeeCord(
        inst: Instance,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        shouldCancel: () -> Boolean
    ): Pair<Boolean, String> {
        onLog("取 BungeeCord 最后一次成功构建…")
        // ci.md-5.net 会 301 到 hub.spigotmc.org，直接用最终地址省一次跳转
        val url = "https://hub.spigotmc.org/jenkins/job/BungeeCord/lastSuccessfulBuild/" +
                "artifact/bootstrap/target/BungeeCord.jar"
        return saveTo(inst, url, onProgress, onLog, shouldCancel)
    }

    // ------------------------------------------------------------ GitHub

    /**
     * GitHub Release：按「关键字 + MC 版本」挑资产。
     * 仓库里常常同时有多个版本，优先匹配 mcVersion，其次退回第一个 jar。
     */
    private fun github(
        inst: Instance,
        opt: ServerCoreOption,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        shouldCancel: () -> Boolean
    ): Pair<Boolean, String> {
        if (opt.repo.isEmpty()) return Pair(false, "${opt.name} 缺少仓库信息")
        val base = DownloadSources.baseUrlOf("github", "https://api.github.com").trimEnd('/')
        onLog("查询 ${opt.name} 发布列表（${opt.repo}）…")
        val releases = JSONArray(
            Net.getString(
                "$base/repos/${opt.repo}/releases?per_page=30",
                headers = mapOf("Accept" to "application/vnd.github+json")
            )
        )
        if (releases.length() == 0) return Pair(false, "${opt.name} 没有可用发布")

        data class Asset(val name: String, val url: String)

        val all = ArrayList<Asset>()
        for (i in 0 until releases.length()) {
            val assets = releases.getJSONObject(i).optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val name = a.optString("name")
                if (!name.endsWith(".jar", ignoreCase = true)) continue
                if (name.contains("sources", true) || name.contains("javadoc", true)) continue
                all += Asset(name, a.optString("browser_download_url"))
            }
        }
        if (all.isEmpty()) return Pair(false, "${opt.name} 的发布里没有 jar 资产")

        val keyword = opt.assetKeyword
        val hit = all.firstOrNull { a ->
            (keyword.isEmpty() || a.name.contains(keyword, true)) &&
                    a.name.contains(inst.mcVersion, true)
        } ?: all.firstOrNull { a ->
            keyword.isEmpty() || a.name.contains(keyword, true)
        } ?: all.first()

        onLog("选中资产 ${hit.name}")
        return saveTo(inst, hit.url, onProgress, onLog, shouldCancel)
    }

    // ------------------------------------------------------------ Forge / NeoForge

    private fun forgeInstaller(
        inst: Instance,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        shouldCancel: () -> Boolean
    ): Pair<Boolean, String> {
        onLog("查询 Forge 推荐版本（${inst.mcVersion}）…")
        val promos = Net.getJson("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")
            .getJSONObject("promos")
        val forgeVer = promos.optString("${inst.mcVersion}-recommended").ifEmpty {
            promos.optString("${inst.mcVersion}-latest")
        }
        if (forgeVer.isEmpty()) return Pair(false, "Forge 没有 ${inst.mcVersion} 的构建")
        val url = "https://maven.minecraftforge.net/net/minecraftforge/forge/" +
                "${inst.mcVersion}-$forgeVer/forge-${inst.mcVersion}-$forgeVer-installer.jar"
        onLog("Forge $forgeVer")
        return installWithInstaller(
            inst, url, "forge-installer.jar",
            args = listOf("--installServer"),
            expectJar = "run.sh",
            onProgress = onProgress, onLog = onLog, shouldCancel = shouldCancel
        )
    }

    private fun neoForgeInstaller(
        inst: Instance,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        shouldCancel: () -> Boolean
    ): Pair<Boolean, String> {
        onLog("查询 NeoForge 版本（${inst.mcVersion}）…")
        val xml = Net.getString("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
        // NeoForge 版本号与 MC 版本对应：1.21.1 -> 21.1.x
        val prefix = inst.mcVersion.removePrefix("1.") + "."
        val versions = Regex("<version>([^<]+)</version>")
            .findAll(xml)
            .map { it.groupValues[1] }
            .filter { it.startsWith(prefix) }
            .toList()
        if (versions.isEmpty()) return Pair(false, "NeoForge 没有 ${inst.mcVersion} 的构建")
        val ver = versions.last()
        val url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/$ver/neoforge-$ver-installer.jar"
        onLog("NeoForge $ver")
        return installWithInstaller(
            inst, url, "neoforge-installer.jar",
            args = listOf("--installServer"),
            expectJar = "run.sh",
            onProgress = onProgress, onLog = onLog, shouldCancel = shouldCancel
        )
    }

    // ------------------------------------------------------------ 安装器通用流程

    /**
     * 下载安装器 → 用实例的 Java 执行 → 检查产物。
     *
     * @param expectJar 期望产出的文件名；`run.sh` 表示 Forge/NeoForge 这类没有单 jar 的安装
     */
    private fun installWithInstaller(
        inst: Instance,
        url: String,
        fileName: String,
        args: List<String>,
        expectJar: String,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        shouldCancel: () -> Boolean
    ): Pair<Boolean, String> {
        val java = JdkManager.activeBin()
            ?: return Pair(false, "安装核心需要先装 Java，请到「设置 → Java」下载 openjdk-17/21/25")

        val installer = File(inst.dir(), fileName)
        onLog("下载安装器 $fileName")
        if (!fetch(url, installer, onProgress, onLog, shouldCancel)) {
            return Pair(false, if (shouldCancel()) "已取消" else "安装器下载失败")
        }

        onLog("执行安装器：java -jar $fileName ${args.joinToString(" ")}")
        val ok = runInstaller(inst, java, installer, args, onLog, shouldCancel)
        if (!ok) return Pair(false, "安装器执行失败，日志见上方")

        val produced = File(inst.dir(), expectJar)
        val ok2 = if (expectJar == "run.sh") {
            produced.isFile || File(inst.dir(), "libraries").isDirectory
        } else {
            produced.isFile
        }
        if (!ok2) {
            return Pair(false, "安装器执行完成，但没有生成 $expectJar，请查看日志")
        }

        runCatching { installer.delete() }
        val msg = "已安装 $expectJar"
        onLog(msg)
        return Pair(true, msg)
    }

    private fun runInstaller(
        inst: Instance,
        java: File,
        installer: File,
        args: List<String>,
        onLog: (String) -> Unit,
        shouldCancel: () -> Boolean
    ): Boolean {
        var p: Process? = null
        return try {
            val cmd = ArrayList<String>()
            cmd += java.absolutePath
            cmd += "-jar"
            cmd += installer.absolutePath
            cmd += args

            val pb = ProcessBuilder(cmd)
            pb.directory(inst.dir())
            pb.redirectErrorStream(true)
            val home = JdkManager.javaHomeOf(java)
            if (home != null) {
                val libs = JdkManager.libPathOf(home)
                if (libs.isNotEmpty()) pb.environment()["LD_LIBRARY_PATH"] = libs
            }
            p = pb.start()
            val proc = p
            proc.inputStream.bufferedReader(StandardCharsets.UTF_8).use { r ->
                while (true) {
                    if (shouldCancel()) {
                        proc.destroy()
                        return false
                    }
                    val line = r.readLine() ?: break
                    if (line.isNotBlank()) onLog(line)
                }
            }
            proc.waitFor() == 0
        } catch (e: Exception) {
            onLog("安装器异常: ${e.message}")
            false
        } finally {
            runCatching { p?.destroy() }
        }
    }

    /** 从 maven-metadata.xml 取 <latest>，退回 <release> */
    private fun mavenLatest(metadataUrl: String): String? {
        val xml = Net.getString(metadataUrl)
        Regex("<latest>([^<]+)</latest>").find(xml)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        Regex("<release>([^<]+)</release>").find(xml)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        return Regex("<version>([^<]+)</version>").findAll(xml)
            .map { it.groupValues[1] }
            .lastOrNull()
    }

    // ------------------------------------------------------------ 公共

    /** 下载到实例的 serverJar() */
    private fun saveTo(
        inst: Instance,
        url: String,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        shouldCancel: () -> Boolean
    ): Pair<Boolean, String> {
        val target = inst.serverJar()
        if (!fetch(url, target, onProgress, onLog, shouldCancel)) {
            return Pair(
                false,
                if (shouldCancel()) "已取消"
                else "下载失败（${url.substringAfter("//").substringBefore("/")}）"
            )
        }
        val msg = "已下载 ${target.name}（${target.length() / 1024 / 1024} MB）"
        onLog(msg)
        return Pair(true, msg)
    }

    private fun fetch(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        shouldCancel: () -> Boolean
    ): Boolean {
        onLog("下载地址: $url")
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        val ok = MultiDownloader.download(
            url = url,
            dest = dest,
            onProgress = onProgress,
            shouldCancel = shouldCancel
        )
        if (ok) return true
        if (shouldCancel()) return false

        // 分块下载失败（部分 CDN 对 Range 支持不好），退回单线程再试一次
        onLog("分块下载失败，改用单线程重试…")
        if (dest.exists()) dest.delete()
        return Net.download(url, dest, onProgress, shouldCancel)
    }
}
