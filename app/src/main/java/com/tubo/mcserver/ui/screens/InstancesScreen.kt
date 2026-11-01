package com.tubo.mcserver.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tubo.mcserver.CoreDownloader
import com.tubo.mcserver.DlManager
import com.tubo.mcserver.JdkManager
import com.tubo.mcserver.hasCompatible
import com.tubo.mcserver.JavaRequirement
import com.tubo.mcserver.Instance
import com.tubo.mcserver.InstanceManager
import com.tubo.mcserver.Prefs
import com.tubo.mcserver.ServerCoreCatalog
import com.tubo.mcserver.VersionRepo
import com.tubo.mcserver.ui.theme.ZPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 卡片固定高度与间距，拖拽换位时用它们换算目标索引 */
private val CardHeight = 104.dp
private val CardSpacing = 10.dp

/**
 * 实例列表页（Compose 版）。
 *
 * 覆盖更新日志第 1、21 条：星标置顶、编辑实例、拖拽重排序、未下载核心的警告提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancesScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: (String) -> Unit = {},
    onOpenCreate: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    var instances by remember { mutableStateOf<List<Instance>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }
    var onlyModpack by remember { mutableStateOf(false) }

    LaunchedEffect(reloadToken) {
        instances = withContext(Dispatchers.IO) { InstanceManager.list() }
        loaded = true
    }

    fun reload() {
        reloadToken++
    }

    val shown = if (onlyModpack) instances.filter { it.modpack.isNotEmpty() } else instances

    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            // 顶部筛选：全部 / 整合包
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = !onlyModpack,
                    onClick = { onlyModpack = false },
                    label = { Text("全部") }
                )
                FilterChip(
                    selected = onlyModpack,
                    onClick = { onlyModpack = true },
                    label = { Text("整合包") }
                )
                Text(
                    text = "${shown.size} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(Modifier.weight(1f)) {
        if (loaded && shown.isEmpty()) {
            EmptyState(Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(CardSpacing)
            ) {
                itemsIndexed(shown, key = { _, item -> item.name }) { index, inst ->
                    DraggableInstanceCard(
                        inst = inst,
                        index = index,
                        itemCount = shown.size,
                        isCurrent = inst.name == Prefs.currentInstance,
                        onClick = {
                            Prefs.currentInstance = inst.name
                            reload()
                        },
                        onStar = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    InstanceManager.setPinned(inst, !inst.pinned)
                                }
                                reload()
                            }
                        },
                        onEdit = { onOpenSettings(inst.name) },
                        onMove = { from, to ->
                            // 筛选状态下索引与全量列表不一致，禁止拖拽重排
                            if (!onlyModpack) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { InstanceManager.reorder(from, to) }
                                    reload()
                                }
                            }
                        }
                    )
                }
            }
        }
            }
        }

        FloatingActionButton(
            onClick = onOpenCreate,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(Icons.Filled.Add, contentDescription = "新建实例")
        }
    }
}

// ---------------------------------------------------------------- 卡片

@Composable
private fun DraggableInstanceCard(
    inst: Instance,
    index: Int,
    itemCount: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onStar: () -> Unit,
    onEdit: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit
) {
    val density = LocalDensity.current
    var dragOffset by remember(inst.name) { mutableStateOf(0f) }
    val stepPx = with(density) { (CardHeight + CardSpacing).toPx() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(CardHeight)
            .graphicsLayer { translationY = dragOffset }
            .zIndex(if (dragOffset != 0f) 1f else 0f),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = ZPalette.cardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InstanceIcon(inst)
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = inst.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isCurrent) {
                        Spacer(Modifier.width(6.dp))
                        CurrentBadge()
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${inst.mcVersion} · ${loaderLabel(inst)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                // Java 适配（第 4 条）：没装到够用的版本时提前警告，别等点了启动才失败
                val javaOk = remember(inst.mcVersion, JdkManager.revision) {
                    JdkManager.hasCompatible(inst.mcVersion)
                }
                when {
                    !inst.hasServerJar() -> Text(
                        text = "⚠ 未下载服务端核心，可在实例设置中补齐",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    !javaOk -> Text(
                        text = "⚠ 缺少适配的 Java（需要 ${JavaRequirement.describe(inst.mcVersion)}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    else -> Text(
                        text = "核心已就绪",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1
                    )
                }
            }

            IconButton(onClick = onStar) {
                Icon(
                    imageVector = if (inst.pinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (inst.pinned) "取消星标" else "星标置顶",
                    tint = if (inst.pinned) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "编辑实例设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .pointerInput(inst.name, itemCount, stepPx) {
                        detectDragGesturesAfterLongPress(
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            },
                            onDragEnd = {
                                val delta = (dragOffset / stepPx).roundToInt()
                                dragOffset = 0f
                                if (delta != 0) {
                                    val target = (index + delta).coerceIn(0, itemCount - 1)
                                    if (target != index) onMove(index, target)
                                }
                            },
                            onDragCancel = { dragOffset = 0f }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.DragHandle,
                    contentDescription = "长按拖拽排序",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InstanceIcon(inst: Instance) {
    val path = inst.iconFile()?.absolutePath
    val bitmap: ImageBitmap? = remember(path) {
        path?.let {
            runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = inst.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CurrentBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text = "当前",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "还没有服务器实例",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "点右下角「+」新建一个实例，选好 MC 版本与服务端核心即可开服",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------- 工具

private fun loaderLabel(inst: Instance): String = when (inst.loader.uppercase()) {
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
