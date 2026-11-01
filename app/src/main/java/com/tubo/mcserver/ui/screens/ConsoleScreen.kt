package com.tubo.mcserver.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tubo.mcserver.ConsoleStore
import com.tubo.mcserver.InstanceManager
import com.tubo.mcserver.ui.theme.AppBackgrounds
import com.tubo.mcserver.ui.theme.BackgroundBox
import com.tubo.mcserver.Prefs
import com.tubo.mcserver.ServerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 控制台标签页：`instanceName == null` 表示独立终端（无实例也能敲命令） */
private class ConsoleTab(
    val key: String,
    title: String,
    val instanceName: String?
) {
    var title by mutableStateOf(title)
}

/**
 * 控制台（更新日志第 5、6、19 条）。
 *
 * - 每个实例一个标签页，切换实例不会丢日志（[ConsoleStore] 按 key 分开存）；
 * - 顶部标签栏 + 可折叠侧边栏（三横线），标签长按可重命名；
 * - 三横线菜单里的「+」新建独立终端；
 * - 没有实例在运行时，输入框直接执行本机 shell 命令。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(modifier: Modifier = Modifier) {
    val tabs = remember { mutableStateListOf<ConsoleTab>() }
    var selectedKey by remember { mutableStateOf("") }
    var sidebar by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var renaming by remember { mutableStateOf<ConsoleTab?>(null) }
    var shellSeq by remember { mutableStateOf(1) }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { InstanceManager.list() }
        list.forEach { tabs.add(ConsoleTab(it.name, it.name, it.name)) }
        if (tabs.isEmpty()) {
            tabs.add(ConsoleTab("${ConsoleStore.SHELL_KEY}1", "终端 1", null))
            shellSeq = 2
        }
        selectedKey = Prefs.currentInstance.takeIf { n -> tabs.any { it.key == n } }
            ?: tabs.firstOrNull()?.key.orEmpty()
    }

    // 轮询刷新：日志来自后台线程，用固定节拍触发重组
    LaunchedEffect(Unit) {
        while (true) {
            // FPS 上限换算成刷新间隔；0 表示不限制，走默认 400ms
            val fps = Prefs.fpsLimit
            delay(if (fps > 0) (1000L / fps).coerceIn(16L, 500L) else 400L)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick

    val selected = tabs.firstOrNull { it.key == selectedKey } ?: tabs.firstOrNull()

    // 联机面板（第 13 条）：浮在控制台上方，不打断正在看的日志
    var showMultiplayer by remember { mutableStateOf(false) }

    fun newShellTab() {
        val t = ConsoleTab("${ConsoleStore.SHELL_KEY}${shellSeq}", "终端 $shellSeq", null)
        shellSeq++
        tabs.add(t)
        selectedKey = t.key
    }

    fun closeTab(tab: ConsoleTab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        if (tabs.isEmpty()) {
            newShellTab()
            return
        }
        if (selectedKey == tab.key) {
            selectedKey = tabs[idx.coerceAtMost(tabs.size - 1)].key
        }
    }

    Row(modifier.fillMaxSize()) {
        if (sidebar) {
            Surface(
                modifier = Modifier.width(168.dp).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(Modifier.fillMaxSize().padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { sidebar = false }) {
                            Icon(
                                Icons.Outlined.Menu,
                                contentDescription = "收起侧边栏",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "控制台",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { newShellTab() }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "新建终端",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    tabs.forEach { tab ->
                        TabItem(
                            tab = tab,
                            active = tab.key == selectedKey,
                            onClick = { selectedKey = tab.key },
                            onLongClick = { renaming = tab },
                            onClose = { closeTab(tab) }
                        )
                    }
                }
            }
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            )
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            // 工具栏
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!sidebar) {
                    IconButton(onClick = { sidebar = true }) {
                        Icon(Icons.Outlined.Menu, contentDescription = "展开侧边栏")
                    }
                }
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tabs.forEach { tab ->
                        TabChip(
                            tab = tab,
                            active = tab.key == selectedKey,
                            onClick = { selectedKey = tab.key },
                            onLongClick = { renaming = tab }
                        )
                    }
                }
                // 第 13 条：联机面板浮在控制台上方（底部弹层），不用切页面
                IconButton(onClick = { showMultiplayer = true }) {
                    Icon(Icons.Outlined.Groups, contentDescription = "联机")
                }
                Spacer(Modifier.width(6.dp))
                val runningName = selected?.instanceName
                val running = runningName != null && ServerController.isRunning(runningName)
                if (running) {
                    IconButton(onClick = { runningName?.let { ServerController.stop(it) } }) {
                        Icon(
                            Icons.Outlined.Stop,
                            contentDescription = "停止",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    IconButton(
                        enabled = selected?.instanceName != null,
                        onClick = {
                            val tab = selected ?: return@IconButton
                            val inst = tab.instanceName?.let { InstanceManager.get(it) }
                            if (inst == null) {
                                ConsoleStore.append(tab.key, "[提示] 实例不存在")
                            } else {
                                Prefs.currentInstance = inst.name
                                val msg = ServerController.start(inst)
                                ConsoleStore.append(tab.key, msg)
                            }
                        }
                    ) {
                        Icon(
                            Icons.Outlined.PlayArrow,
                            contentDescription = "启动",
                            tint = if (selected?.instanceName != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            }
                        )
                    }
                }
                IconButton(onClick = { selected?.let { ConsoleStore.clear(it.key) } }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "清空",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // 输出区
            val lines = remember(selectedKey, tick) {
                selected?.let { ConsoleStore.lines(it.key) } ?: emptyList()
            }
            val listState = rememberLazyListState()
            LaunchedEffect(lines.size) {
                if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
            }

            val consoleBg = remember(AppBackgrounds.version) {
                AppBackgrounds.get(AppBackgrounds.CONSOLE)
            }
            BackgroundBox(
                config = consoleBg,
                fallback = when (Prefs.consoleTheme) {
                    "classic" -> Color(0xFF0B120B)
                    "contrast" -> Color(0xFF000000)
                    else -> MaterialTheme.colorScheme.surfaceContainerLowest
                },
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                if (lines.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = selected?.instanceName?.let { "点右上角 ▶ 启动该实例" } ?: "输入命令后回车执行",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        items(lines) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = Prefs.consoleFontSize.sp
                                ),
                                color = colorOf(line, Prefs.consoleTheme),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // 输入区
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(
                            if (selected?.instanceName == null) "执行本机命令，例如 ls /storage/emulated/0"
                            else "服务器指令，例如 op Steve / stop"
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val tab = selected ?: return@IconButton
                        val cmd = input.trim()
                        if (cmd.isEmpty()) return@IconButton
                        input = ""
                        val instName = tab.instanceName
                        if (instName != null) {
                            if (ServerController.isRunning(instName)) {
                                ConsoleStore.append(tab.key, "> $cmd")
                                ServerController.sendCommand(instName, cmd)
                            } else {
                                ConsoleStore.append(tab.key, "[提示] 该实例没有在运行，无法发送服务器指令")
                            }
                        } else {
                            runShell(tab.key, cmd)
                        }
                    }
                ) {
                    Icon(Icons.Outlined.Send, contentDescription = "发送", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (showMultiplayer) {
        ModalBottomSheet(
            onDismissRequest = { showMultiplayer = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            MultiplayerScreen(Modifier.fillMaxWidth().fillMaxHeight(0.92f))
        }
    }

    renaming?.let { tab ->
        RenameTabDialog(
            current = tab.title,
            onDismiss = { renaming = null },
            onConfirm = {
                tab.title = it.ifBlank { tab.title }
                renaming = null
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabItem(
    tab: ConsoleTab,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClose: () -> Unit
) {
    val bg = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val fg = if (active) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Menu,
            contentDescription = null,
            tint = fg.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = tab.title,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = fg.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabChip(
    tab: ConsoleTab,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Text(
            text = tab.title,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun RenameTabDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名标签页") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 独立终端：直接跑本机 shell 命令 */
private fun runShell(key: String, cmd: String) {
    Thread {
        ConsoleStore.append(key, "> $cmd")
        try {
            val p = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            p.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    ConsoleStore.append(key, line)
                }
            }
            val code = p.waitFor()
            ConsoleStore.append(key, "[退出码 $code]")
        } catch (e: Exception) {
            ConsoleStore.append(key, "执行失败: ${e.message}")
        }
    }.apply { isDaemon = true }.start()
}

/**
 * 日志配色。
 * - auto：跟随主题
 * - classic：经典终端绿字
 * - contrast：高对比（黑底亮色）
 */
@Composable
private fun colorOf(line: String, scheme: String): androidx.compose.ui.graphics.Color {
    val lower = line.lowercase()
    val isError = lower.contains("error") || lower.contains("exception") ||
            lower.contains("caused by") || lower.contains("失败") || lower.contains("cannot")
    val isWarn = lower.contains("warn") || lower.contains("警告")
    val isOk = lower.contains("done") || lower.contains("started") ||
            lower.contains("完成") || lower.contains("成功")

    return when (scheme) {
        "classic" -> when {
            isError -> Color(0xFFFF6B6B)
            isWarn -> Color(0xFFFFD166)
            isOk -> Color(0xFF7CFFB2)
            else -> Color(0xFF9BE59B)
        }
        "contrast" -> when {
            isError -> Color(0xFFFF5252)
            isWarn -> Color(0xFFFFD600)
            isOk -> Color(0xFF00E676)
            else -> Color(0xFFF2F2F2)
        }
        else -> when {
            isError -> MaterialTheme.colorScheme.error
            isWarn -> MaterialTheme.colorScheme.tertiary
            isOk -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
    }
}
