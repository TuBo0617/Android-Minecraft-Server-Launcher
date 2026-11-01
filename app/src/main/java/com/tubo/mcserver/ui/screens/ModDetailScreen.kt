package com.tubo.mcserver.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tubo.mcserver.CurseForgeRepo
import com.tubo.mcserver.DlManager
import com.tubo.mcserver.Instance
import com.tubo.mcserver.ModEntry
import com.tubo.mcserver.ModpackInstaller
import com.tubo.mcserver.ModrinthRepo
import com.tubo.mcserver.MultiDownloader
import com.tubo.mcserver.PackVersion
import com.tubo.mcserver.Prefs
import com.tubo.mcserver.ui.components.PlaceholderNote
import com.tubo.mcserver.ui.components.RemoteImage
import com.tubo.mcserver.ui.theme.ZPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 资源详情页：选 MC 版本 + 加载器 + 具体版本号，再安装（更新日志第 15 条）。
 *
 * 之前点卡片只能装「默认最新」，没法挑版本；现在把版本列表完整摊开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModDetailScreen(
    entry: ModEntry,
    inst: Instance?,
    onBack: () -> Unit,
    onInstalled: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var versions by remember(entry.id) { mutableStateOf<List<PackVersion>>(emptyList()) }
    var loading by remember(entry.id) { mutableStateOf(true) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    var mcVersion by remember(entry.id) { mutableStateOf(inst?.mcVersion.orEmpty()) }
    var loader by remember(entry.id) { mutableStateOf("") }
    var mcMenu by remember { mutableStateOf(false) }
    var loaderMenu by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(entry.id) {
        loading = true
        val list = withContext(Dispatchers.IO) {
            runCatching {
                if (entry.source == "curseforge") CurseForgeRepo.versions(entry.id)
                else ModrinthRepo.versions(entry.id)
            }
        }
        loading = false
        list.onSuccess { loaded ->
            versions = loaded
            val opts = loaded.flatMap { it.gameVersions }.distinct()
            // 实例版本在该资源里不存在时，退回「全部版本」，否则列表会是空的
            if (mcVersion.isNotEmpty() && mcVersion !in opts) mcVersion = ""
            if (mcVersion.isEmpty() && inst != null && inst.mcVersion in opts) {
                mcVersion = inst.mcVersion
            }
        }.onFailure { error = "拉取版本失败: ${it.message}" }

        // 默认加载器跟随实例，但要取版本列表里的**真实大小写**
        // （Modrinth 用 fabric，CurseForge 用 Fabric，否则筛选会空）
        if (loader.isEmpty()) {
            val want = when (inst?.loader?.uppercase()) {
                "FABRIC" -> "fabric"
                "QUILT" -> "quilt"
                "FORGE" -> "forge"
                "NEOFORGE" -> "neoforge"
                else -> ""
            }
            if (want.isNotEmpty()) {
                val all = versions.flatMap { it.loaders }.distinct()
                loader = all.firstOrNull { it.equals(want, ignoreCase = true) } ?: want
            }
        }
    }

    val mcOptions = remember(versions) {
        versions.flatMap { it.gameVersions }.distinct().sortedDescending()
    }
    val loaderOptions = remember(versions) {
        versions.flatMap { it.loaders }.distinct().sorted()
    }
    val filtered = versions.filter { v ->
        (mcVersion.isEmpty() || v.gameVersions.contains(mcVersion)) &&
                (loader.isEmpty() || v.loaders.isEmpty() ||
                        v.loaders.any { it.equals(loader, ignoreCase = true) })
    }

    fun install(v: PackVersion) {
        val target = inst ?: run {
            error = "请先在「实例」页选中一个实例"
            return
        }
        if (entry.type == "modpack") {
            error = "整合包请直接回上一页点「安装」，会自动创建实例"
            return
        }
        installing = v.id
        val dir = if (entry.type == "resourcepack") target.resourcePacksDir() else target.modsDir()
        val task = DlManager.create(v.fileName, entry.type)
        DlManager.submit(task) { t ->
            try {
                val dest = File(dir, v.fileName)
                dest.parentFile?.mkdirs()
                if (!MultiDownloader.download(
                        v.url, dest,
                        onProgress = { d, tot -> DlManager.progress(t, d, tot) },
                        shouldCancel = { t.cancelRequested }
                    )
                ) {
                    throw Exception("下载失败")
                }
                DlManager.success(t, "已安装 ${v.fileName}")
                installing = null
                onInstalled()
            } catch (e: Exception) {
                DlManager.fail(t, "失败: ${e.message}")
                installing = null
            }
        }
    }

    fun installModpack(v: PackVersion) {
        // 整合包不依赖当前实例：版本取自所选版本的 game_versions
        installing = entry.id
        val filter = v.gameVersions.firstOrNull() ?: mcVersion
        val task = DlManager.create("整合包 ${entry.title}", "modpack")
        DlManager.submit(task) { t ->
            try {
                val created = ModpackInstaller.installFromModrinth(
                    projectId = entry.id,
                    mcVersionFilter = filter,
                    instanceName = entry.title,
                    iconUrl = entry.iconUrl,
                    onLog = { line -> t.log(line) },
                    onProgress = { d, total -> DlManager.progress(t, d, total) },
                    shouldCancel = { t.cancelRequested }
                )
                DlManager.success(t, "已创建实例「${created.name}」")
                Prefs.currentInstance = created.name
                installing = null
                onInstalled()
            } catch (e: Exception) {
                DlManager.fail(t, "安装失败: ${e.message}")
                installing = null
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
            RemoteImage(url = entry.iconUrl, size = 40.dp, modifier = Modifier.padding(end = 10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${entry.source} · ${typeLabel(entry.type)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // MC 版本
                Box {
                    OutlinedButton(onClick = { mcMenu = true }) {
                        Text(mcVersion.ifEmpty { "MC 版本" }, maxLines = 1)
                    }
                    DropdownMenu(expanded = mcMenu, onDismissRequest = { mcMenu = false }) {
                        DropdownMenuItem(text = { Text("全部版本") }, onClick = {
                            mcMenu = false; mcVersion = ""
                        })
                        mcOptions.forEach { v ->
                            DropdownMenuItem(text = { Text(v) }, onClick = {
                                mcMenu = false; mcVersion = v
                            })
                        }
                    }
                }
                // 加载器（资源包没有加载器概念）
                if (loaderOptions.isNotEmpty()) {
                    Box {
                        OutlinedButton(onClick = { loaderMenu = true }) {
                            Text(loader.ifEmpty { "加载器" }, maxLines = 1)
                        }
                        DropdownMenu(expanded = loaderMenu, onDismissRequest = { loaderMenu = false }) {
                            DropdownMenuItem(text = { Text("全部加载器") }, onClick = {
                                loaderMenu = false; loader = ""
                            })
                            loaderOptions.forEach { l ->
                                DropdownMenuItem(text = { Text(l) }, onClick = {
                                    loaderMenu = false; loader = l
                                })
                            }
                        }
                    }
                }
            }
            Text(
                text = if (loading) "正在拉取版本…" else "共 ${filtered.size} 个版本可安装",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp)
            )
            val hint = when {
                entry.type == "modpack" -> "整合包会新建一个实例，不影响当前选中的实例"
                inst != null -> "安装到：${inst.name} · ${inst.mcVersion}"
                else -> "未选择实例，无法安装"
            }
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.type != "modpack" && inst == null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
            filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PlaceholderNote("没有匹配当前筛选的版本，换个 MC 版本或加载器试试")
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered) { v ->
                    Surface(color = ZPalette.cardBackground, shape = MaterialTheme.shapes.small) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = v.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = buildString {
                                        if (v.gameVersions.isNotEmpty()) append(v.gameVersions.take(3).joinToString(" "))
                                        if (v.loaders.isNotEmpty()) {
                                            if (isNotEmpty()) append(" · ")
                                            append(v.loaders.joinToString("/"))
                                        }
                                    }.ifEmpty { v.fileName },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            if (entry.type == "modpack") {
                                Button(onClick = { installModpack(v) }, enabled = installing == null) {
                                    Text("装整合包")
                                }
                            } else {
                                Button(onClick = { install(v) }, enabled = installing == null) {
                                    Text(if (installing == v.id) "安装中…" else "安装")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun typeLabel(type: String): String = when (type) {
    "mod" -> "模组"
    "resourcepack" -> "资源包"
    "modpack" -> "整合包"
    "plugin" -> "插件"
    else -> type
}
