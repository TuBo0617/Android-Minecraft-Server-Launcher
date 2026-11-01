package com.tubo.mcserver.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tubo.mcserver.BackupManager
import com.tubo.mcserver.ConfigFile
import com.tubo.mcserver.ConfigManager
import com.tubo.mcserver.BedrockManager
import com.tubo.mcserver.CoreInstaller
import com.tubo.mcserver.CoreKind
import com.tubo.mcserver.CoreSupport
import com.tubo.mcserver.DlManager
import com.tubo.mcserver.Exporter
import com.tubo.mcserver.Instance
import com.tubo.mcserver.InstanceManager
import com.tubo.mcserver.JdkManager
import com.tubo.mcserver.Prefs
import com.tubo.mcserver.ServerCoreCatalog
import com.tubo.mcserver.Nbt
import com.tubo.mcserver.PlayerManager
import com.tubo.mcserver.ServerController
import com.tubo.mcserver.ServerCoreOption
import com.tubo.mcserver.Storage
import com.tubo.mcserver.WorldManager
import com.tubo.mcserver.ui.components.CategoryRail
import com.tubo.mcserver.ui.components.PlaceholderNote
import com.tubo.mcserver.ui.components.SectionCard
import com.tubo.mcserver.ui.components.SettingRow
import com.tubo.mcserver.ui.theme.ZPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * 实例设置（整页，不是弹窗）。
 *
 * 左侧二级侧边栏切换分类，右侧内容区渲染对应面板：
 * 基础 / 服务端核心 / 内存与 Java / 配置文件 / 世界管理 / 导出。
 * 服务端核心选择直接内嵌在这里，不再单独占一个一级页面。
 */
@Composable
fun InstanceSettingsScreen(
    instanceName: String,
    onBack: () -> Unit,
    onRenamed: (String) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inst by remember(instanceName) { mutableStateOf(InstanceManager.get(instanceName)) }
    var section by remember { mutableStateOf(InstanceSection.BASIC) }
    val scope = rememberCoroutineScope()

    /** 改一处就落盘一处：之前只改内存，退出页面改动就丢了 */
    fun persist(updated: Instance) {
        inst = updated
        scope.launch {
            withContext(Dispatchers.IO) { InstanceManager.save(updated) }
        }
    }

    val current = inst
    if (current == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("实例不存在", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onBack) { Text("返回实例列表") }
            }
        }
        return
    }

    Row(modifier.fillMaxSize()) {
        CategoryRail(
            items = InstanceSection.entries.toList(),
            selected = section,
            labelOf = { it.label },
            iconOf = { it.icon },
            onSelect = { section = it }
        )
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        )

        Column(Modifier.weight(1f).fillMaxHeight()) {
            // 顶部标题区：返回 + 实例名 + 当前核心/版本
            Row(
                modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = current.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${current.mcVersion} · ${coreLabel(current)}" +
                                if (current.hasServerJar()) "" else " · 核心未安装",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (current.hasServerJar()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (section) {
                    InstanceSection.BASIC -> BasicSection(
                        inst = current,
                        onSave = ::persist,
                        onRenamed = onRenamed,
                        onDeleted = onDeleted
                    )
                    InstanceSection.CORE -> CoreSection(
                        inst = current,
                        onSave = ::persist
                    )
                    InstanceSection.MEMORY -> MemorySection(
                        inst = current,
                        onSave = ::persist
                    )
                    InstanceSection.PLAYERS -> PlayersSection(current)
                    InstanceSection.CONFIG -> ConfigSection(current)
                    InstanceSection.WORLD -> WorldSection(
                        inst = current,
                        onSave = ::persist
                    )
                    InstanceSection.EXPORT -> ExportSection(current)
                }
            }
        }
    }
}

private enum class InstanceSection(val label: String, val icon: ImageVector) {
    BASIC("基础", Icons.Outlined.Info),
    CORE("服务端核心", Icons.Outlined.Dns),
    MEMORY("内存与 Java", Icons.Outlined.Memory),
    PLAYERS("玩家", Icons.Outlined.People),
    CONFIG("配置文件", Icons.Outlined.Tune),
    WORLD("世界管理", Icons.Outlined.Public),
    EXPORT("导出", Icons.Outlined.IosShare)
}

// ---------------------------------------------------------------- 基础

@Composable
private fun BasicSection(
    inst: Instance,
    onSave: (Instance) -> Unit,
    onRenamed: (String) -> Unit,
    onDeleted: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(inst.name) { mutableStateOf(inst.name) }
    var nameError by remember(inst.name) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var iconTick by remember { mutableStateOf(0) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = File(inst.dir(), "icon.png")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    dest.isFile
                }.getOrDefault(false)
            }
            if (ok) {
                onSave(inst.copy(iconPath = File(inst.dir(), "icon.png").absolutePath))
                iconTick++
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(title = "实例图标") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InstanceIconView(inst, 72.dp, iconTick)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "1:1 图片，可从相册或文件选择",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("选择图片")
                        }
                        if (inst.iconPath.isNotEmpty()) {
                            TextButton(onClick = { onSave(inst.copy(iconPath = "")) }) { Text("恢复默认") }
                        }
                    }
                }
            }
        }

        SectionCard(title = "实例名称") {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = InstanceManager.validateName(it)
                },
                label = { Text("名称") },
                singleLine = true,
                isError = nameError != null,
                modifier = Modifier.fillMaxWidth()
            )
            if (nameError != null) {
                Text(
                    text = nameError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "改名会同步移动实例目录，下载中心进度不受影响",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    val trimmed = name.trim()
                    val err = InstanceManager.validateName(trimmed)
                    if (err != null) {
                        nameError = err
                    } else if (trimmed != inst.name) {
                        scope.launch {
                            val renamed = withContext(Dispatchers.IO) {
                                if (InstanceManager.get(trimmed) != null) null
                                else InstanceManager.rename(inst.name, trimmed)
                            }
                            if (renamed == null) {
                                nameError = "已存在同名实例"
                            } else {
                                if (Prefs.currentInstance == inst.name) Prefs.currentInstance = trimmed
                                onRenamed(trimmed)
                            }
                        }
                    }
                }) {
                    Text("完成")
                }
            }
        }

        SectionCard(title = "版本信息") {
            SettingRow("Minecraft 版本", trailing = {
                Text(inst.mcVersion.ifEmpty { "未设置" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            SettingRow("服务端核心", subtitle = inst.core.ifEmpty { "未选择" }, trailing = {
                Text(coreLabel(inst), color = MaterialTheme.colorScheme.onSurfaceVariant)
            })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            SettingRow("核心状态", trailing = {
                Icon(
                    imageVector = if (inst.hasServerJar()) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                    contentDescription = null,
                    tint = if (inst.hasServerJar()) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            SettingRow("目录", subtitle = inst.dir().absolutePath)
        }

        SectionCard(title = "危险操作") {
            SettingRow(
                title = "删除实例",
                subtitle = "连同世界、模组、备份一起删除，不可恢复"
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("删除实例", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${inst.name}？") },
            text = { Text("将删除整个服务器目录（含世界、模组、备份），不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleted()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun InstanceIconView(inst: Instance, size: Dp, tick: Int) {
    val file = inst.iconFile()
    val bmp = remember(file?.absolutePath, file?.lastModified(), tick) {
        file?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = inst.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

// ---------------------------------------------------------------- 服务端核心

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoreSection(inst: Instance, onSave: (Instance) -> Unit) {
    // 基岩版没有「Java 核心」概念，单独一套界面
    if (inst.isBedrock()) {
        BedrockCoreSection(inst)
        return
    }

    var catId by remember { mutableStateOf(ServerCoreCatalog.categories.first().id) }
    var supportTick by remember { mutableStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 自定义 Jar：从文件管理器选一个 .jar，拷进实例目录后作为服务端核心
    val pickJar = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val name = withContext(Dispatchers.IO) { copyJarInto(ctx, uri, inst) }
            busy = false
            if (name != null) {
                onSave(inst.copy(customJar = name, core = "custom", loader = "CUSTOM"))
            } else {
                errorMsg = "读取 Jar 失败：文件可能已被删除或没有读取权限"
            }
        }
    }

    // 进页面先把各核心支持的版本查好，用于置灰
    LaunchedEffect(inst.mcVersion) {
        withContext(Dispatchers.IO) {
            CoreSupport.loadAll(ServerCoreCatalog.allOptions().map { it.id })
        }
        supportTick++
    }
    @Suppress("UNUSED_EXPRESSION")
    supportTick

    val cat = ServerCoreCatalog.categories.firstOrNull { it.id == catId }
        ?: ServerCoreCatalog.categories.first()
    val selected = ServerCoreCatalog.find(inst.core)
    val installed = inst.hasServerJar()

    fun install(target: Instance, option: ServerCoreOption) {
        if (busy) return
        busy = true
        CoreInstaller.start(target, option.id) { ok, msg ->
            busy = false
            if (!ok) errorMsg = msg
        }
    }

    /** 卸载：删掉核心 jar，标记为未安装（自定义 Jar 同时清掉标记） */
    fun uninstallCurrent() {
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { inst.serverJar().delete() }
            }
            busy = false
            if (inst.customJar.isNotEmpty()) onSave(inst.copy(customJar = ""))
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (inst.mcVersion.isBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "这个实例没有 MC 版本，任何核心都下不了。请回实例列表重新创建并选好版本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = cat.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = (if (inst.customJar.isNotEmpty()) "自定义 Jar：${inst.customJar}"
                    else selected?.name ?: "未选择核心") + " · " + inst.mcVersion.ifEmpty { "未设置版本" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected != null || inst.customJar.isNotEmpty()) {
                if (installed) {
                    OutlinedButton(enabled = !busy, onClick = { confirmUninstall = true }) {
                        Text("已安装 · 卸载")
                    }
                } else {
                    if (selected != null) {
                        Button(
                            enabled = !busy && inst.mcVersion.isNotBlank(),
                            onClick = { install(inst, selected) }
                        ) { Text(if (busy) "安装中…" else "下载核心") }
                    }
                }
            }
        }

        // 分类切换：横向芯片（比嵌套二级侧边栏更适合手机宽度）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ServerCoreCatalog.categories.forEach { c ->
                FilterChip(
                    selected = c.id == cat.id,
                    onClick = { catId = c.id },
                    label = { Text(c.title) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 自定义 Jar（更新日志第 19 条）：不依赖核心目录，直接跑用户自己的包
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = "自定义 Jar",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = inst.customJar.ifEmpty {
                        "从文件管理器选一个服务端 jar，适合插件服自带核心或第三方构建。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            pickJar.launch(
                                arrayOf("application/java-archive", "application/octet-stream", "*/*")
                            )
                        }
                    ) { Text(if (inst.customJar.isEmpty()) "选择 Jar" else "更换 Jar") }

                    if (inst.customJar.isNotEmpty()) {
                        TextButton(
                            enabled = !busy,
                            onClick = { onSave(inst.copy(customJar = "", core = "", loader = "VANILLA")) }
                        ) {
                            Text("清除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(170.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(ServerCoreCatalog.optionsOf(cat), key = { it.id }) { option ->
                val supported = CoreSupport.supports(option.id, inst.mcVersion)
                val installable = CoreSupport.installable(option.id)
                CoreOptionCard(
                    option = option,
                    selected = inst.core == option.id,
                    installed = inst.core == option.id && installed,
                    enabled = supported && installable,
                    disabledHint = if (!installable) "需 BuildTools，启动器内不可用"
                    else "不支持 ${inst.mcVersion}",
                    onClick = {
                        val updated = inst.copy(core = option.id, loader = option.loader)
                        onSave(updated)
                        // 选中后直接进下载流程，避免「选了但没装」的困惑
                        if (inst.core != option.id || !inst.hasServerJar()) {
                            install(updated, option)
                        }
                    }
                )
            }
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text("卸载服务端核心？") },
            text = {
                Text(
                    "将删除 ${inst.serverJar().name}。世界、配置、模组都不受影响，" +
                            "之后可以重新下载或改用其它核心。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    uninstallCurrent()
                }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text("取消") }
            }
        )
    }

    errorMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMsg = null },
            title = { Text("安装失败") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { errorMsg = null }) { Text("知道了") } },
            dismissButton = {
                TextButton(onClick = {
                    errorMsg = null
                    selected?.let { install(inst, it) }
                }) { Text("重试") }
            }
        )
    }
}

@Composable
private fun CoreOptionCard(
    option: ServerCoreOption,
    selected: Boolean,
    installed: Boolean,
    enabled: Boolean,
    disabledHint: String,
    onClick: () -> Unit
) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f)
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> ZPalette.cardBackground
    }
    val titleColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        color = bg,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (installed) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                    contentDescription = if (installed) "已安装" else "未安装",
                    tint = when {
                        installed -> MaterialTheme.colorScheme.tertiary
                        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (!enabled) disabledHint else option.note,
                style = MaterialTheme.typography.bodySmall,
                color = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                else if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = kindLabel(option.kind),
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

// ---------------------------------------------------------------- 内存与 Java

@Composable
private fun MemorySection(inst: Instance, onSave: (Instance) -> Unit) {
    val javas = remember { JdkManager.listInstalled() }
    var javaMenu by remember { mutableStateOf(false) }
    var minGb by remember(inst.name) { mutableStateOf((inst.minMemMb / 1024f).coerceIn(0.5f, 8f)) }
    var maxGb by remember(inst.name) { mutableStateOf((inst.maxMemMb / 1024f).coerceIn(0.5f, 8f)) }
    var nogui by remember(inst.name) { mutableStateOf(inst.nogui) }
    var args by remember(inst.name) { mutableStateOf(inst.customArgs) }

    val javaLabel = javas.firstOrNull { it.home.absolutePath == inst.javaHome }?.label ?: "自动（跟随全局）"

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(title = "独立内存") {
            SettingRow(
                title = "启用实例独立设置",
                subtitle = "关闭时沿用全局设置；开启后只影响本实例"
            ) {
                Switch(
                    checked = inst.memoryOverride,
                    onCheckedChange = { onSave(inst.copy(memoryOverride = it)) }
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            LabeledValue("最小内存", "%.1f GB".format(minGb))
            Slider(
                value = minGb,
                onValueChange = { v ->
                    minGb = (v * 10).roundToInt() / 10f
                    if (minGb > maxGb) maxGb = minGb
                },
                onValueChangeFinished = {
                    onSave(inst.copy(minMemMb = (minGb * 1024).roundToInt(), memoryOverride = true))
                },
                valueRange = 0.5f..8f,
                steps = 74
            )
            LabeledValue("最大内存", "%.1f GB".format(maxGb))
            Slider(
                value = maxGb,
                onValueChange = { v ->
                    maxGb = (v * 10).roundToInt() / 10f
                    if (maxGb < minGb) minGb = maxGb
                },
                onValueChangeFinished = {
                    onSave(inst.copy(maxMemMb = (maxGb * 1024).roundToInt(), memoryOverride = true))
                },
                valueRange = 0.5f..8f,
                steps = 74
            )
            Text(
                text = "精度 0.1 GB；最小内存不能大于最大内存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(title = "Java 运行环境") {
            SettingRow(
                title = "指定 Java 版本",
                subtitle = "不指定时按 MC 版本自动挑选"
            ) {
                Box {
                    OutlinedButton(onClick = { javaMenu = true }) {
                        Text(javaLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = javaMenu, onDismissRequest = { javaMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("自动（跟随全局）") },
                            onClick = {
                                javaMenu = false
                                onSave(inst.copy(javaOverride = false, javaHome = ""))
                            }
                        )
                        javas.forEach { j ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        j.label + if (j.exists) "" else "（缺失）",
                                        color = if (j.exists) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    javaMenu = false
                                    onSave(inst.copy(javaOverride = true, javaHome = j.home.absolutePath))
                                }
                            )
                        }
                    }
                }
            }
            if (javas.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                PlaceholderNote("还没有安装 Java。到「设置 → Java」里下载 openjdk-17 / 21 / 25。")
            }
        }

        SectionCard(title = "启动参数") {
            SettingRow(title = "nogui 参数", subtitle = "服务端不弹图形界面，手机上更省资源") {
                Switch(
                    checked = nogui,
                    onCheckedChange = {
                        nogui = it
                        onSave(inst.copy(nogui = it, noguiOverride = true))
                    }
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            SettingRow(title = "自定义启动命令", subtitle = "开启后完全接管启动参数") {
                Switch(
                    checked = inst.argsOverride,
                    onCheckedChange = { onSave(inst.copy(argsOverride = it)) }
                )
            }
            if (inst.argsOverride) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text("java 命令") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onSave(inst.copy(customArgs = args, argsOverride = true)) }) {
                    Icon(Icons.Outlined.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("保存命令")
                }
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

// ---------------------------------------------------------------- 基岩版核心

@Composable
private fun BedrockCoreSection(inst: Instance) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf<BedrockManager.Bds?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var rootfsReady by remember { mutableStateOf(BedrockManager.hasRootfs()) }
    var installed by remember { mutableStateOf(File(inst.dir(), "bedrock_server").isFile) }

    LaunchedEffect(inst.name) {
        latest = withContext(Dispatchers.IO) { BedrockManager.latest() }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(
            title = "基岩版服务端（BDS）",
            subtitle = "官方 Linux 版，通过 proot + glibc rootfs 运行"
        ) {
            SettingRow(
                title = "实例版本",
                subtitle = inst.mcVersion.ifEmpty { "未知" }
            ) {
                Text(
                    text = if (installed) "已安装" else "未安装",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (installed) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            SettingRow(
                title = "官方最新版本",
                subtitle = latest?.version ?: "正在获取…"
            ) {}
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            var onlineMode by remember(inst.name) { mutableStateOf(BedrockManager.onlineMode(inst)) }
            SettingRow(
                title = "正版验证（online-mode）",
                subtitle = if (onlineMode) {
                    "需要连微软服务；境内常连不上，服务端会等约 2 分钟后自行退出"
                } else {
                    "离线模式：局域网 / 朋友之间联机用这个"
                }
            ) {
                Switch(
                    checked = onlineMode,
                    onCheckedChange = {
                        onlineMode = it
                        BedrockManager.setOnlineMode(inst, it)
                        msg = if (it) "已开启正版验证（重启生效）" else "已关闭正版验证（重启生效）"
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && latest != null,
                    onClick = {
                        busy = true
                        msg = null
                        CoreInstaller.start(inst, "BDS") { ok, m ->
                            busy = false
                            msg = m
                            if (ok) installed = true
                        }
                    }
                ) { Text(if (busy) "处理中…" else if (installed) "更新 BDS" else "下载 BDS") }

                if (installed) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { File(inst.dir(), "bedrock_server").delete() }
                                }
                                installed = false
                                busy = false
                                msg = "已删除 BDS"
                            }
                        }
                    ) { Text("卸载") }
                }
            }
            msg?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        SectionCard(title = "运行环境", subtitle = "首次约 30MB，之后复用") {
            SettingRow(
                title = "glibc rootfs",
                subtitle = if (rootfsReady) "已就绪" else "未准备（首次启动会自动下载）"
            ) {
                Text(
                    text = if (rootfsReady) "OK" else "缺失",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (rootfsReady) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    msg = null
                    scope.launch {
                        val dir = withContext(Dispatchers.IO) {
                            BedrockManager.ensureRootfs(
                                log = { line -> android.util.Log.i("bedrock", line) }
                            )
                        }
                        rootfsReady = dir != null
                        busy = false
                        msg = if (dir != null) "rootfs 已就绪" else "rootfs 准备失败，请检查网络"
                    }
                }
            ) { Text(if (busy) "处理中…" else "立即准备") }
            Spacer(Modifier.height(6.dp))
            PlaceholderNote(
                "BDS 是 glibc 二进制，Android 的 bionic 无法直接执行，必须套一层 proot + Ubuntu rootfs。"
            )
        }

        PlaceholderNote(
            "基岩版端口固定 19132（UDP）。客户端在「服务器」页添加时填本机 IP 即可。" +
                    "首次启动会在控制台打印 rootfs 下载进度。"
        )
    }
}

// ---------------------------------------------------------------- 玩家

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayersSection(inst: Instance) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(0) }
    var kind by remember { mutableStateOf(PlayerManager.Kind.WHITELIST) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    val running = ServerController.isRunning(inst.name)
    val online = PlayerManager.onlineMode(inst)
    val players = remember(inst.name, kind, token, running) { PlayerManager.list(inst, kind) }

    fun apply(add: Boolean, name: String) {
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                if (add) PlayerManager.add(inst, kind, name)
                else PlayerManager.remove(inst, kind, name)
            }
            message = when {
                err != null -> err
                add -> if (running) "已添加 $name" else "已添加 $name（下次启动生效）"
                else -> "已移除 $name"
            }
            if (err == null && add) input = ""
            // 走指令时服务端要过一会儿才把名单写回文件，等一下再刷新
            if (running) withContext(Dispatchers.IO) { Thread.sleep(600) }
            token++
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionCard(
            title = "玩家管理",
            subtitle = if (running) "服务端运行中：通过控制台指令操作，立即生效"
            else "服务端未运行：直接写入名单文件，下次启动生效"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerManager.Kind.entries.forEach { k ->
                    FilterChip(
                        selected = kind == k,
                        onClick = { kind = k; message = null },
                        label = { Text(k.title) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            if (!running && online) {
                Text(
                    text = "正版模式（online-mode=true）下新增玩家要查 UUID，建议先启动服务端；移除不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.trim() },
                    label = { Text("玩家名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = input.isNotEmpty(),
                    onClick = { apply(true, input) }
                ) { Text("添加") }
            }

            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(10.dp))
            if (players.isEmpty()) {
                PlaceholderNote(
                    when (kind) {
                        PlayerManager.Kind.WHITELIST ->
                            "白名单为空。开启「强制白名单」后，只有名单里的玩家能进服。"
                        PlayerManager.Kind.OPS ->
                            "还没有管理员。管理员可在游戏内使用管理指令。"
                        PlayerManager.Kind.BANNED -> "没有封禁记录。"
                    }
                )
            } else {
                players.forEach { p ->
                    SettingRow(
                        title = p.name,
                        subtitle = if (p.note.isNotEmpty()) "${p.note} · ${p.uuid.take(8)}…" else p.uuid
                    ) {
                        TextButton(onClick = { apply(false, p.name) }) {
                            Text("移除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        PlaceholderNote(
            "白名单、管理员、封禁三种名单分别存在 whitelist.json / ops.json / banned-players.json，" +
                    "也可以到「配置文件」里手改。改动即时生效，无需重启。"
        )
    }
}

// ---------------------------------------------------------------- 配置文件

@Composable
private fun ConfigSection(inst: Instance) {
    var token by remember { mutableStateOf(0) }
    val files = remember(inst.name, token) { ConfigManager.readAll(inst) }
    val edits = remember(inst.name, token) { mutableStateMapOf<String, String>() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (files.isEmpty()) {
            PlaceholderNote("实例目录下还没有配置文件。先启动一次服务端生成 server.properties，再回来编辑。")
        }
        files.forEach { cfg ->
            ConfigFileCard(
                cfg = cfg,
                edits = edits,
                onSave = {
                    cfg.entries.forEach { e ->
                        if (!e.isComment) {
                            edits["${cfg.name}|${e.key}"]?.let { e.value = it }
                        }
                    }
                    ConfigManager.write(cfg)
                    token++
                }
            )
        }
    }
}

@Composable
private fun ConfigFileCard(
    cfg: ConfigFile,
    edits: MutableMap<String, String>,
    onSave: () -> Unit
) {
    val items = cfg.entries.filter { !it.isComment }
    SectionCard(title = cfg.name, subtitle = cfg.path) {
        if (items.isEmpty()) {
            Text("没有可编辑的配置项", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items.forEach { e ->
            val k = "${cfg.name}|${e.key}"
            val display = ConfigManager.displayName(e.key)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = display,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = e.key,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = edits[k] ?: e.value,
                    onValueChange = { edits[k] = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(150.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onSave) {
            Icon(Icons.Outlined.Save, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("保存 ${cfg.name}")
        }
    }
}

// ---------------------------------------------------------------- 世界管理

@Composable
private fun WorldSection(inst: Instance, onSave: (Instance) -> Unit) {
    val scope = rememberCoroutineScope()
    var backups by remember(inst.name) { mutableStateOf(BackupManager.list(inst)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var worldSize by remember(inst.name) { mutableStateOf(0L) }
    var confirmDeleteWorld by remember { mutableStateOf(false) }
    var nbtFile by remember { mutableStateOf<File?>(null) }
    var copySource by remember { mutableStateOf<File?>(null) }

    // 打开某个 NBT 文件时整块让位给编辑器
    nbtFile?.let { target ->
        NbtEditorPanel(inst, target, onClose = { nbtFile = null })
        return
    }

    val ctx = LocalContext.current
    val importWorld = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val tmp = File(Storage.temp(), "world-import.zip")
                        ctx.contentResolver.openInputStream(uri)?.use { i ->
                            tmp.outputStream().use { o -> i.copyTo(o) }
                        }
                        val r = WorldManager.importZip(inst, tmp)
                        tmp.delete()
                        r
                    }.getOrDefault(false)
                }
                busy = false
                message = if (ok) "世界已导入（重启服务端生效）" else "导入失败，请确认选的是世界存档 zip"
                worldSize = withContext(Dispatchers.IO) { WorldManager.totalSize(inst) }
            }
        }
    }

    LaunchedEffect(inst.name) {
        worldSize = withContext(Dispatchers.IO) { WorldManager.totalSize(inst) }
    }

    fun refresh() {
        backups = BackupManager.list(inst)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(title = "备份", subtitle = "备份保存在实例目录的 backups/ 下") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("立即备份整个实例", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "命名规则：年-月-日-时-分-秒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { BackupManager.create(inst) }
                            message = if (f == null) "备份失败" else "已备份：${f.name}"
                            busy = false
                            refresh()
                        }
                    }
                ) { Text(if (busy) "备份中…" else "备份") }
            }
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        SectionCard(title = "备份列表（${backups.size}）") {
            if (backups.isEmpty()) {
                Text(
                    "还没有备份",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            backups.forEach { b ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = BackupManager.prettyName(b.name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val desc = BackupManager.description(b)
                        Text(
                            text = if (desc.isEmpty()) BackupManager.sizeLabel(b) else "$desc · ${BackupManager.sizeLabel(b)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { renameTarget = b }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "重命名", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { BackupManager.restore(inst, b) }
                            message = if (ok) "已还原 ${b.name}" else "还原失败"
                        }
                    }) {
                        Icon(Icons.Outlined.Restore, contentDescription = "还原", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { copySource = b }) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "复制到其他实例",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { BackupManager.delete(b) }
                            refresh()
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }
        }

        SectionCard(title = "世界文件", subtitle = "主世界 / 下界 / 末地一起处理") {
            SettingRow(
                title = "世界大小",
                subtitle = if (WorldManager.worlds(inst).isEmpty()) "还没有生成世界" else "共 ${WorldManager.worlds(inst).size} 个维度"
            ) {
                Text(
                    WorldManager.humanSize(worldSize),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            SettingRow("世界目录", subtitle = File(inst.dir(), "world").absolutePath)

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val f = withContext(Dispatchers.IO) {
                                val dest = File(
                                    Storage.downloads(),
                                    "${inst.name}-world-${BackupManager.timestamp()}.zip"
                                )
                                if (WorldManager.exportZip(inst, dest)) dest else null
                            }
                            busy = false
                            message = f?.let { "已导出到 ${it.absolutePath}" }
                                ?: "导出失败：还没有世界存档"
                        }
                    }
                ) { Text("导出世界") }

                OutlinedButton(
                    enabled = !busy,
                    onClick = { importWorld.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
                ) { Text("导入世界") }

                OutlinedButton(
                    enabled = !busy,
                    onClick = { confirmDeleteWorld = true }
                ) { Text("删除世界", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(6.dp))
            PlaceholderNote("导入会解压覆盖到实例目录，建议先备份；删除世界不可恢复。")
        }

        SectionCard(
            title = "NBT 编辑器",
            subtitle = "改玩家数据 / 世界规则；只开放标量字段，保存前自动备份"
        ) {
            val nbtFiles = remember(inst.name, worldSize) { Nbt.listFiles(inst) }
            if (nbtFiles.isEmpty()) {
                PlaceholderNote("还没有 NBT 文件。启动一次服务端生成世界后，这里会出现 level.dat 与 playerdata。")
            } else {
                nbtFiles.take(30).forEach { f ->
                    SettingRow(
                        title = Nbt.labelOf(f, inst),
                        subtitle = "${f.length() / 1024} KB"
                    ) {
                        TextButton(onClick = { nbtFile = f }) { Text("编辑") }
                    }
                }
                if (nbtFiles.size > 30) {
                    PlaceholderNote("只列出前 30 个（共 ${nbtFiles.size} 个），其余可用文件管理器访问。")
                }
            }
        }
    }

    if (confirmDeleteWorld) {
        AlertDialog(
            onDismissRequest = { confirmDeleteWorld = false },
            title = { Text("删除全部世界？") },
            text = { Text("将删除主世界、下界、末地三份存档，不可恢复。建议先导出或备份。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteWorld = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { WorldManager.deleteWorlds(inst) }
                        message = if (ok) "世界已删除，下次启动会重新生成" else "删除失败"
                        worldSize = withContext(Dispatchers.IO) { WorldManager.totalSize(inst) }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteWorld = false }) { Text("取消") }
            }
        )
    }

    copySource?.let { src ->
        val others = remember(inst.name) { InstanceManager.list().filter { it.name != inst.name } }
        AlertDialog(
            onDismissRequest = { copySource = null },
            title = { Text("复制备份到…") },
            text = {
                if (others.isEmpty()) {
                    Text("没有其它实例可复制。先新建一个实例再回来。")
                } else {
                    Column {
                        Text(
                            text = "将把「${BackupManager.prettyName(src.name)}」复制到所选实例的备份目录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        others.take(8).forEach { other ->
                            TextButton(
                                onClick = {
                                    val target = other
                                    copySource = null
                                    scope.launch {
                                        val dest = withContext(Dispatchers.IO) {
                                            BackupManager.copyTo(src, target)
                                        }
                                        message = if (dest != null) {
                                            "已复制到 ${target.name}（${dest.name}）"
                                        } else {
                                            "复制失败"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(other.name) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { copySource = null }) { Text("关闭") }
            }
        )
    }

    renameTarget?.let { target ->
        RenameBackupDialog(
            current = target.name.removeSuffix(".zip"),
            onDismiss = { renameTarget = null },
            onConfirm = { newName, desc ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val renamed = BackupManager.rename(target, newName)
                        if (renamed != null) BackupManager.writeDesc(renamed, desc)
                    }
                    renameTarget = null
                    refresh()
                }
            }
        )
    }
}

@Composable
private fun RenameBackupDialog(current: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名备份") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("备份名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("简介（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, desc) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------------------------------------------------------- 导出

@Composable
private fun ExportSection(inst: Instance) {
    val scope = rememberCoroutineScope()
    var includeCore by remember { mutableStateOf(false) }
    var includeMods by remember { mutableStateOf(true) }
    var includePacks by remember { mutableStateOf(true) }
    var includeWorld by remember { mutableStateOf(true) }
    var includeBackups by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun runExport(mrpack: Boolean) {
        busy = true
        result = null
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                val opts = Exporter.Options(
                    includeCore = includeCore,
                    includeMods = includeMods,
                    includeResourcePacks = includePacks,
                    includeWorld = includeWorld,
                    includeBackups = includeBackups
                )
                if (mrpack) Exporter.exportMrpack(inst, opts) else Exporter.export(inst, opts)
            }
            result = when {
                f != null -> "已导出到 ${f.absolutePath}"
                mrpack -> "导出失败：实例缺少 MC 版本时无法生成 mrpack"
                else -> "导出失败"
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(title = "导出内容") {
            SettingRow("服务端核心", subtitle = "带上核心 jar，接收方无需再下载") {
                Switch(checked = includeCore, onCheckedChange = { includeCore = it })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            SettingRow("模组") {
                Switch(checked = includeMods, onCheckedChange = { includeMods = it })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            SettingRow("资源包") {
                Switch(checked = includePacks, onCheckedChange = { includePacks = it })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            SettingRow("世界存档") {
                Switch(checked = includeWorld, onCheckedChange = { includeWorld = it })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            SettingRow("备份") {
                Switch(checked = includeBackups, onCheckedChange = { includeBackups = it })
            }
        }

        SectionCard(
            title = "导出",
            subtitle = "输出到 ${com.tubo.mcserver.Storage.downloads().absolutePath}"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { runExport(mrpack = false) }) {
                    Text(if (busy) "导出中…" else "导出 zip")
                }
                OutlinedButton(
                    enabled = !busy && inst.mcVersion.isNotBlank(),
                    onClick = { runExport(mrpack = true) }
                ) { Text("导出 mrpack") }
            }
            result?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(10.dp))
            PlaceholderNote(
                "zip 是通用包，接收方解压即可用。\n" +
                        "mrpack 是 Modrinth 整合包格式，可被 PCL2 / HMCL / Modrinth App 识别；" +
                        "由于启动器没有记录模组的下载直链与哈希，模组会整体打进 overrides/ 而不是在线补齐，" +
                        "所以包会偏大但内容完整。"
            )
        }
    }
}

// ---------------------------------------------------------------- 工具

private fun coreLabel(inst: Instance): String {
    val opt = ServerCoreCatalog.find(inst.core)
    if (opt != null) return opt.name
    return when (inst.loader.uppercase()) {
        "VANILLA" -> "原版"
        "FABRIC" -> "Fabric"
        "FORGE" -> "Forge"
        "NEOFORGE", "NEOFORGED" -> "NeoForge"
        "QUILT" -> "Quilt"
        "PAPER" -> "Paper"
        "PURPUR" -> "Purpur"
        "FOLIA" -> "Folia"
        "SPIGOT" -> "Spigot"
        "BUKKIT" -> "Bukkit"
        "VELOCITY" -> "Velocity"
        "BUNGEECORD" -> "BungeeCord"
        "BEDROCK" -> "基岩版"
        else -> inst.loader
    }
}

private fun kindLabel(kind: CoreKind): String = when (kind) {
    CoreKind.VANILLA -> "原版"
    CoreKind.PLUGIN_SERVER -> "插件服务端"
    CoreKind.MODDED_SERVER -> "模组服务端"
    CoreKind.HYBRID -> "模组 + 插件"
    CoreKind.PROXY -> "代理端"
}

/**
 * 把用户从文件管理器选的 Jar 拷进实例目录，返回落地后的文件名。
 *
 * 用 SAF 拿到的 `Uri` 只能流式读，不能当路径用，所以必须真正拷贝一份——
 * 服务端启动时需要的是一个稳定的本地文件路径。
 */
private fun copyJarInto(ctx: Context, uri: Uri, inst: Instance): String? {
    val raw = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: "custom-server.jar"

    // 去掉路径分隔符和奇怪字符，避免写盘越界或文件名非法
    val safe = raw.substringAfterLast('/')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .let { if (it.endsWith(".jar", ignoreCase = true)) it else "$it.jar" }
        .ifEmpty { "custom-server.jar" }

    return runCatching {
        inst.dir().mkdirs()
        val dest = File(inst.dir(), safe)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        } ?: return null
        if (dest.length() == 0L) {
            dest.delete()
            return null
        }
        safe
    }.getOrNull()
}
