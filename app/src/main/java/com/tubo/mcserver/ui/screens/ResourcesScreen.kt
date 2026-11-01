package com.tubo.mcserver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tubo.mcserver.CurseForgeRepo
import com.tubo.mcserver.DlManager
import com.tubo.mcserver.Instance
import com.tubo.mcserver.InstanceManager
import com.tubo.mcserver.ModEntry
import com.tubo.mcserver.ModpackInstaller
import com.tubo.mcserver.ModrinthRepo
import com.tubo.mcserver.Prefs
import com.tubo.mcserver.ui.components.CategoryRail
import com.tubo.mcserver.ui.components.PlaceholderNote
import com.tubo.mcserver.ui.components.RemoteImage
import com.tubo.mcserver.ui.components.SectionCard
import com.tubo.mcserver.ui.theme.ZPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val PAGE_SIZE = 20

private enum class ResSection(val label: String, val icon: ImageVector, val type: String) {
    MODS("模组", Icons.Outlined.Extension, "mod"),
    PACKS("资源包", Icons.Outlined.Image, "resourcepack"),
    MODPACKS("整合包", Icons.Outlined.Inventory2, "modpack"),
    INSTALLED("已安装", Icons.Outlined.Folder, "")
}

/**
 * 资源中心（更新日志第 15、16、24 条）。
 *
 * 左侧分类（模组 / 资源包 / 整合包 / 已安装），顶部 Java/基岩 切换，
 * 数据源支持 Modrinth 与 CurseForge（后者无 key 时走 MCIM 镜像）。
 * 下载直接落到当前实例的 mods / resourcepacks 目录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourcesScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf(ResSection.MODS) }
    var platform by remember { mutableStateOf("java") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ModEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf("modrinth") }
    var refresh by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<ModEntry?>(null) }
    var page by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }

    val inst: Instance? = remember(refresh) {
        Prefs.currentInstance.takeIf { it.isNotEmpty() }?.let { InstanceManager.get(it) }
    }

    fun fetchPage(p: Int, append: Boolean) {
        val target = inst
        if (target == null) {
            error = "请先在「实例」页选中一个实例"
            return
        }
        if (section == ResSection.INSTALLED) return
        loading = true
        if (!append) {
            error = null
            results = emptyList()
        }
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    if (source == "curseforge") {
                        CurseForgeRepo.search(
                            query = query,
                            type = section.type,
                            mcVersion = target.mcVersion,
                            loader = target.loader,
                            pageSize = PAGE_SIZE,
                            index = p * PAGE_SIZE
                        )
                    } else {
                        ModrinthRepo.search(
                            query = query,
                            mcVersion = target.mcVersion,
                            type = section.type,
                            limit = PAGE_SIZE,
                            offset = p * PAGE_SIZE
                        )
                    }
                }
            }
            loading = false
            list.onSuccess { loaded ->
                results = if (append) results + loaded else loaded
                hasMore = loaded.size >= PAGE_SIZE
                page = p
            }.onFailure { error = "检索失败: ${it.message}" }
        }
    }

    fun doSearch() {
        hasMore = true
        fetchPage(0, append = false)
    }

    fun download(item: ModEntry) {
        // 整合包：与当前选中的实例无关，装完自己新建一个
        if (item.type == "modpack") {
            if (item.source == "curseforge") {
                error = "CurseForge 整合包需要 API Key 才能换直链，请先用 Modrinth 源"
                return
            }
            val task = DlManager.create("整合包 ${item.title}", "modpack")
            DlManager.submit(task) { t ->
                try {
                    val created = ModpackInstaller.installFromModrinth(
                        projectId = item.id,
                        mcVersionFilter = "",
                        instanceName = item.title,
                        iconUrl = item.iconUrl,
                        onLog = { line -> t.log(line) },
                        onProgress = { d, total -> DlManager.progress(t, d, total) },
                        shouldCancel = { t.cancelRequested }
                    )
                    DlManager.success(t, "已创建实例「${created.name}」，可在「实例」页启动")
                    Prefs.currentInstance = created.name
                    refresh++
                } catch (e: Exception) {
                    DlManager.fail(t, "安装失败: ${e.message}")
                }
            }
            return
        }

        val target = inst ?: run {
            error = "请先在「实例」页选中一个实例"
            return
        }
        val dir = when (item.type) {
            "resourcepack" -> target.resourcePacksDir()
            "modpack" -> File(target.dir(), "modpacks").apply { mkdirs() }
            else -> target.modsDir()
        }
        val task = DlManager.create("${item.title}", item.type)
        DlManager.submit(task) { t ->
            try {
                val name = if (item.source == "curseforge") {
                    CurseForgeRepo.install(
                        modId = item.id,
                        destDir = dir,
                        mcVersion = target.mcVersion,
                        onProgress = { d, total -> DlManager.progress(t, d, total) },
                        onLog = { line -> t.log(line) },
                        shouldCancel = { t.cancelRequested }
                    )
                } else {
                    ModrinthRepo.install(
                        slug = item.id,
                        mcVersion = target.mcVersion,
                        type = item.type,
                        destDir = dir,
                        loader = target.loader,
                        onProgress = { d, total -> DlManager.progress(t, d, total) },
                        onLog = { line -> t.log(line) },
                        shouldCancel = { t.cancelRequested }
                    )
                }
                DlManager.success(t, "已安装 $name")
                refresh++
            } catch (e: Exception) {
                DlManager.fail(t, "失败: ${e.message}")
            }
        }
    }

    // 详情页：选 MC 版本 / 加载器 / 具体版本后安装
    detail?.let { entry ->
        ModDetailScreen(
            entry = entry,
            inst = inst,
            onBack = { detail = null },
            onInstalled = { refresh++ },
            modifier = modifier
        )
        return
    }

    Row(modifier.fillMaxSize()) {
        CategoryRail(
            items = ResSection.entries.toList(),
            selected = section,
            labelOf = { it.label },
            iconOf = { it.icon },
            onSelect = {
                section = it
                results = emptyList()
                error = null
            }
        )
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        )

        Column(Modifier.weight(1f).fillMaxHeight()) {
            // 平台切换 + 搜索
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = platform == "java",
                    onClick = { platform = "java" },
                    label = { Text("Java 版") }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = platform == "bedrock",
                    onClick = { platform = "bedrock" },
                    label = { Text("基岩版") }
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = inst?.let { "${it.name} · ${it.mcVersion}" } ?: "未选择实例",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (inst == null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            if (platform == "bedrock") {
                // 基岩版只走 CurseForge，分类与下载目录都在 BedrockBrowser 里处理
                BedrockBrowser(inst, Modifier.fillMaxSize())
                return@Column
            }

            when (section) {
                ResSection.INSTALLED -> InstalledSection(inst, refresh) { refresh++ }
                else -> {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("搜索${section.label}…") },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { doSearch() }, enabled = !loading) {
                                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("搜索")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { source = "modrinth" }) {
                                Text(
                                    "Modrinth",
                                    color = if (source == "modrinth") MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { source = "curseforge" }) {
                                Text(
                                    "CurseForge",
                                    color = if (source == "curseforge") MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (loading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                        }

                        error?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }

                        if (results.isEmpty() && !loading) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (error == null) "输入关键词后点搜索" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            val listState = rememberLazyListState()
                            // 划到底部自动加载下一页
                            LaunchedEffect(listState, results.size, hasMore, loading) {
                                snapshotFlow {
                                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                }.collect { last ->
                                    if (!loading && hasMore && results.isNotEmpty() &&
                                        last >= results.size - 3
                                    ) {
                                        fetchPage(page + 1, append = true)
                                    }
                                }
                            }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(results) { item ->
                                    ResultCard(item) { detail = item }
                                }
                                if (loading || !hasMore) {
                                    item {
                                        Box(
                                            Modifier.fillMaxWidth().padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (loading) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                Text(
                                                    "没有更多了",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(item: ModEntry, onClick: () -> Unit) {
    Surface(
        color = ZPalette.cardBackground,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(url = item.iconUrl, size = 46.dp, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${downloadsLabel(item.downloads)} · ${item.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(10.dp))
            Button(onClick = onClick) { Text("选择版本") }
        }
    }
}

/** 已安装的模组 / 资源包：启用（去掉 .disabled）、禁用（加后缀）、删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledSection(inst: Instance?, refreshToken: Int, onChanged: () -> Unit) {
    if (inst == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("请先在「实例」页选中一个实例", color = MaterialTheme.colorScheme.error)
        }
        return
    }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("mod") }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(tab, refreshToken) {
        files = withContext(Dispatchers.IO) {
            val dir = if (tab == "mod") inst.modsDir() else inst.resourcePacksDir()
            dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(selected = tab == "mod", onClick = { tab = "mod" }, label = { Text("模组") })
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = tab == "pack",
                onClick = { tab = "pack" },
                label = { Text("资源包") }
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${files.size} 个文件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有安装${if (tab == "mod") "模组" else "资源包"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(files) { f ->
                val enabled = !f.name.endsWith(".disabled", ignoreCase = true)
                Surface(
                    color = ZPalette.cardBackground,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = f.name.removeSuffix(".disabled"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${f.length() / 1024} KB" + if (enabled) "" else " · 已禁用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { on ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        val target = if (on) {
                                            File(f.parentFile, f.name.removeSuffix(".disabled"))
                                        } else {
                                            File(f.parentFile, f.name + ".disabled")
                                        }
                                        f.renameTo(target)
                                    }
                                    onChanged()
                                }
                            }
                        )
                        IconButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { f.delete() }
                                onChanged()
                            }
                        }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun downloadsLabel(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM 下载".format(n / 1_000_000.0)
    n >= 1_000 -> "%.0fK 下载".format(n / 1_000.0)
    else -> "$n 下载"
}
