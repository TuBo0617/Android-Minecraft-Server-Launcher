package com.tubo.mcserver.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tubo.mcserver.DlManager
import com.tubo.mcserver.DlTask
import com.tubo.mcserver.ui.components.AppCard
import com.tubo.mcserver.ui.components.PlaceholderNote

/**
 * 下载中心（Compose 版）。
 *
 * 之前是旧 XML 页面，进度条写死绿色 `@color/accent = #4CAF50`，
 * 和 Compose 主题的 7 套色板完全脱节。重写后进度、状态色全部走 `MaterialTheme`，
 * 换主题立即跟着变。
 */
@Composable
fun DownloadsScreen(modifier: Modifier = Modifier) {
    // DlManager 是普通对象，用自增 tick 驱动重组（和日志区一个思路）
    var tick by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(Unit) {
        // 显式标注类型：`{ tick++ }` 会被推断成 () -> Int，不能直接当 () -> Unit 用
        val listener: () -> Unit = { tick++ }
        DlManager.addListener(listener)
        onDispose { DlManager.removeListener(listener) }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick

    val tasks = DlManager.all()
    val running = tasks.count { !it.finished }
    val finished = tasks.size - running

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "进行中 $running · 已完成 $finished",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                enabled = finished > 0,
                onClick = { DlManager.clearFinished() }
            ) { Text("清空已完成") }
        }

        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                PlaceholderNote("还没有下载任务。下载 Java、服务端核心、基岩版 BDS 或模组时会出现在这里。")
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(tasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    expanded = expanded == task.id,
                    onToggleLog = { expanded = if (expanded == task.id) null else task.id }
                )
            }
        }
    }
}

@Composable
private fun TaskCard(task: DlTask, expanded: Boolean, onToggleLog: () -> Unit) {
    val statusColor = when (task.status) {
        DlTask.STATUS_DONE -> MaterialTheme.colorScheme.tertiary
        DlTask.STATUS_FAILED -> MaterialTheme.colorScheme.error
        DlTask.STATUS_CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        DlTask.STATUS_RUNNING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    AppCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 状态圆点，颜色跟随主题
            Box(
                Modifier
                    .size(8.dp)
                    .background(statusColor, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(task.status)
                        if (task.percent >= 0) append(" · ${task.percent}%")
                        if (task.message.isNotBlank()) append(" · ${task.message}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!task.finished) {
                TextButton(onClick = { DlManager.cancel(task) }) { Text("取消") }
            }
        }

        if (!task.finished) {
            Spacer(Modifier.height(8.dp))
            if (task.percent >= 0) {
                LinearProgressIndicator(
                    progress = { task.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            } else {
                // 长度未知（例如服务器没给 Content-Length）时用不确定进度
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { DlManager.moveUp(task) }) {
                    Icon(
                        Icons.Outlined.KeyboardArrowUp,
                        contentDescription = "提高优先级",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = { DlManager.moveDown(task) }) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "降低优先级",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (task.threads > 1) "${task.threads} 线程" else "单线程",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 日志：默认收起，点标题行展开
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleLog() }
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (expanded) "收起日志" else "查看日志（${task.logs.size}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = task.logText().takeLast(3000).ifBlank { "（暂无日志）" },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
        }
    }
}
