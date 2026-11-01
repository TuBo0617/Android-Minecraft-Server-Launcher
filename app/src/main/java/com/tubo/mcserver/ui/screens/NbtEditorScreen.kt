package com.tubo.mcserver.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tubo.mcserver.Instance
import com.tubo.mcserver.Nbt
import com.tubo.mcserver.ServerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * NBT 编辑器（更新日志第 22 条）。
 *
 * 用「面包屑 + 一层层进入」而不是可折叠树：
 * 手机屏幕窄，树形缩进两三级就挤成一团，逐层进入反而清楚。
 *
 * 只允许改标量（Byte/Short/Int/Long/Float/Double/String），
 * 列表和 Compound 只能浏览——手改结构很容易把存档写坏。
 */
@Composable
fun NbtEditorPanel(
    inst: Instance,
    file: File,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    var doc by remember(file) { mutableStateOf<Nbt.Document?>(null) }
    var path by remember(file) { mutableStateOf<List<String>>(emptyList()) }
    var dirty by remember(file) { mutableStateOf(false) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var message by remember(file) { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<String, Nbt.Tag>?>(null) }

    LaunchedEffect(file) {
        error = null
        withContext(Dispatchers.IO) {
            runCatching { Nbt.read(file) }
        }.onSuccess { doc = it }
            .onFailure { error = "读取失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /** 沿路径找到当前 Compound；路径失效时回退到根 */
    fun current(): Nbt.Tag.TCompound? {
        var node: Nbt.Tag = doc?.root ?: return null
        for (name in path) {
            node = (node as? Nbt.Tag.TCompound)?.map?.get(name) as? Nbt.Tag ?: return doc?.root
        }
        return node as? Nbt.Tag.TCompound
    }

    fun save() {
        val d = doc ?: return
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { Nbt.write(file, d) }.exceptionOrNull()?.message
            }
            if (err == null) {
                dirty = false
                message = "已保存（原文件备份为 ${file.name}.bak）"
            } else {
                message = "保存失败：$err"
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (dirty) "有未保存的修改" else "只读浏览，点标量可编辑",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dirty) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(enabled = dirty, onClick = { save() }) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("保存")
            }
        }

        // ---- 面包屑 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "root",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { path = emptyList() }
            )
            path.forEachIndexed { index, name ->
                Text(
                    text = " / ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index == path.lastIndex) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { path = path.take(index + 1) }
                )
            }
        }

        // 服务端运行中改 NBT 是白改：它退出/自动保存时会把内存里的数据写回来
        if (ServerController.isRunning(inst.name)) {
            Text(
                text = "⚠ 服务端正在运行，此时保存会被它在下次写入时覆盖。请先停止服务端再改。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        val node = current()
        if (node == null) {
            if (error == null) {
                Text(
                    text = "正在读取…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            val entries = node.map.entries.toList()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(entries, key = { it.key }) { (name, tag) ->
                    val enterable = tag is Nbt.Tag.TCompound
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enterable || Nbt.isEditable(tag)) {
                                when {
                                    enterable -> path = path + name
                                    Nbt.isEditable(tag) -> editing = name to tag
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${Nbt.typeName(tag.id)} · ${Nbt.preview(tag)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (Nbt.isEditable(tag)) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (enterable) {
                            Icon(
                                Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 编辑标量 ----
    editing?.let { (name, tag) ->
        var text by remember(name, tag) { mutableStateOf(Nbt.asText(tag)) }
        var invalid by remember(name, tag) { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("$name（${Nbt.typeName(tag.id)}）") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; invalid = false },
                        singleLine = tag !is Nbt.Tag.TString,
                        isError = invalid,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (invalid) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "格式不对：${Nbt.typeName(tag.id)} 需要对应的数字/文本",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (Nbt.fromText(tag, text)) {
                        dirty = true
                        editing = null
                    } else {
                        invalid = true
                    }
                }) { Text("应用") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
}
