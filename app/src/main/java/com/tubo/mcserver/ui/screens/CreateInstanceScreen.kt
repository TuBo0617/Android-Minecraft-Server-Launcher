package com.tubo.mcserver.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tubo.mcserver.CoreKind
import com.tubo.mcserver.CoreSupport
import com.tubo.mcserver.InstanceManager
import com.tubo.mcserver.ServerCoreCatalog
import com.tubo.mcserver.ServerCoreOption
import com.tubo.mcserver.VersionRepo
import com.tubo.mcserver.ui.components.SectionCard
import com.tubo.mcserver.ui.theme.ZPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 新建实例（整页，不是弹窗）。
 *
 * 一屏内把「名称 + MC 版本 + 服务端核心」定完，创建后直接进安装流程；
 * 不支持当前版本的加载器置灰，需要 BuildTools 的核心直接禁用。
 */
@Composable
fun CreateInstanceScreen(
    onBack: () -> Unit,
    onCreate: (name: String, mcVersion: String, coreId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var versions by remember { mutableStateOf<List<String>>(emptyList()) }
    var version by remember { mutableStateOf("") }
    var versionMenu by remember { mutableStateOf(false) }
    var loadingVersions by remember { mutableStateOf(true) }
    var coreId by remember { mutableStateOf("VANILLA") }
    // 平台：java / bedrock。基岩版只有官方 BDS 一个选择，版本随官方最新
    var platform by remember { mutableStateOf("java") }
    var bds by remember { mutableStateOf<com.tubo.mcserver.BedrockManager.Bds?>(null) }
    var loadingBds by remember { mutableStateOf(true) }
    var supportTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) {
            runCatching {
                VersionRepo.fetchVersions()
                    .filter { it.type == "release" }
                    .map { it.id }
                    .take(80)
            }.getOrDefault(emptyList())
        }
        versions = list
        version = list.firstOrNull() ?: "1.20.1"
        loadingVersions = false

        bds = withContext(Dispatchers.IO) { com.tubo.mcserver.BedrockManager.latest() }
        loadingBds = false

        // 逐个查各核心支持的版本，查完刷新一次
        withContext(Dispatchers.IO) {
            CoreSupport.loadAll(ServerCoreCatalog.allOptions().map { it.id })
        }
        supportTick++
    }

    @Suppress("UNUSED_EXPRESSION")
    supportTick

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "新建实例",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
            }

            SectionCard(title = "版本类型", subtitle = "Java 版可选各种核心；基岩版只有官方 BDS") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = platform == "java",
                        onClick = { platform = "java" },
                        label = { Text("Java 版") }
                    )
                    androidx.compose.material3.FilterChip(
                        selected = platform == "bedrock",
                        onClick = { platform = "bedrock" },
                        label = { Text("基岩版") }
                    )
                }
            }

            if (platform == "bedrock") {
                SectionCard(
                    title = "基岩版服务端（BDS）",
                    subtitle = "官方 Linux 版，运行在 proot + glibc rootfs 里"
                ) {
                    when {
                        loadingBds -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在获取最新版本…", style = MaterialTheme.typography.bodySmall)
                        }
                        bds == null -> Text(
                            "拿不到 BDS 下载地址（微软接口不可达），可以稍后到实例设置里重试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        else -> Column {
                            Text(
                                text = "版本 ${bds!!.version}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "首次启动会自动下载约 30MB 的 glibc rootfs，之后复用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else SectionCard(title = "游戏版本", subtitle = "决定核心可选项；换版本会自动灰掉不支持的加载器") {
                Box {
                    OutlinedTextField(
                        value = if (loadingVersions) "" else version,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(if (loadingVersions) "正在获取版本…" else "Minecraft 版本") },
                        trailingIcon = {
                            if (loadingVersions) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                TextButton(onClick = { versionMenu = true }) { Text("选择") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = versionMenu,
                        onDismissRequest = { versionMenu = false }
                    ) {
                        versions.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(v) },
                                onClick = {
                                    version = v
                                    versionMenu = false
                                }
                            )
                        }
                    }
                }
            }

            if (platform == "java") SectionCard(
                title = "服务端核心",
                subtitle = "创建后自动下载；灰色项表示该核心不支持当前版本"
            ) {
                val options = ServerCoreCatalog.allOptions()
                options.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        row.forEach { opt ->
                            Box(Modifier.weight(1f)) {
                                CoreTile(
                                    option = opt,
                                    selected = coreId == opt.id,
                                    supported = CoreSupport.supports(opt.id, version),
                                    installable = CoreSupport.installable(opt.id),
                                    onClick = { coreId = opt.id }
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                val summary = if (platform == "bedrock") {
                    bds?.let { "基岩版 ${it.version} · BDS" } ?: "基岩版 · BDS（版本未获取）"
                } else if (version.isBlank()) {
                    "请选择版本"
                } else {
                    "$version · ${coreName(coreId)}"
                }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                enabled = name.isNotBlank() &&
                        (platform == "bedrock" || version.isNotBlank()),
                onClick = {
                    val trimmed = name.trim()
                    val err = InstanceManager.validateName(trimmed)
                    if (err != null) {
                        nameError = err
                        return@Button
                    }
                    if (InstanceManager.get(trimmed) != null) {
                        nameError = "已存在同名实例"
                        return@Button
                    }
                    if (platform == "bedrock") {
                        onCreate(trimmed, bds?.version ?: "1.21", "BDS")
                    } else {
                        onCreate(trimmed, version, coreId)
                    }
                }
            ) {
                Text("创建并安装")
            }
        }
    }
}

@Composable
private fun CoreTile(
    option: ServerCoreOption,
    selected: Boolean,
    supported: Boolean,
    installable: Boolean,
    onClick: () -> Unit
) {
    val enabled = supported && installable
    val bg = when {
        selected && enabled -> MaterialTheme.colorScheme.secondaryContainer
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f)
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
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (selected && enabled) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                } else if (!enabled) {
                    Icon(
                        Icons.Outlined.Cancel,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (!installable) "需 BuildTools，启动器内不可用" else option.note,
                style = MaterialTheme.typography.labelSmall,
                color = if (!enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = kindLabel(option.kind),
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

private fun coreName(coreId: String): String =
    ServerCoreCatalog.find(coreId)?.name ?: coreId

private fun kindLabel(kind: CoreKind): String = when (kind) {
    CoreKind.VANILLA -> "原版"
    CoreKind.PLUGIN_SERVER -> "插件服务端"
    CoreKind.MODDED_SERVER -> "模组服务端"
    CoreKind.HYBRID -> "模组 + 插件"
    CoreKind.PROXY -> "代理端"
}
