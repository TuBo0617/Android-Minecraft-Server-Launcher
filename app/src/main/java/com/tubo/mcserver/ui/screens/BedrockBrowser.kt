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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.tubo.mcserver.MultiDownloader
import com.tubo.mcserver.ui.components.PlaceholderNote
import com.tubo.mcserver.ui.components.RemoteImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 基岩版资源中心（更新日志第 15 条）。
 *
 * Modrinth 没有基岩版内容，所以这里只走 CurseForge（gameId 78022）。
 * 基岩版没有「加载器」概念，分类由官方 classId 决定：
 * 附加包 / 材质包 / 地图 / 脚本 / 皮肤。
 *
 * 下载目录按类别落到基岩服务端约定的位置：
 * 附加包与脚本 → `behavior_packs/`，材质包 → `resource_packs/`，地图 → `worlds/`，皮肤 → `skins/`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BedrockBrowser(inst: Instance?, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()

    var classId by remember { mutableStateOf(CurseForgeRepo.BEDROCK_CLASSES.first().first) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ModEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun search() {
        loading = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { CurseForgeRepo.searchBedrock(query, classId) }
            }
            loading = false
            r.onSuccess { results = it }
                .onFailure { error = "检索失败：${it.message ?: it.javaClass.simpleName}" }
        }
    }

    // 切换分类时自动拉一次，不用每次手动点搜索
    LaunchedEffect(classId) { search() }

    fun subDirOf(id: Int): String = when (id) {
        6929 -> "resource_packs"
        6913 -> "worlds"
        6925 -> "skins"
        else -> "behavior_packs"
    }

    fun install(item: ModEntry) {
        val target = inst
        if (target == null) {
            error = "请先在「实例」页选中一个实例"
            return
        }
        val dir = File(target.dir(), subDirOf(classId)).apply { mkdirs() }
        message = null
        val task = DlManager.create("基岩 ${item.title}", "bedrock")
        DlManager.submit(task) { t ->
            try {
                val file = CurseForgeRepo.pickFile(item.id, null)
                    ?: throw Exception("该项目没有可下载文件")
                val url = file.downloadUrl
                    ?: throw Exception("作者关闭了第三方下载，无法直连")
                val dest = File(dir, file.fileName)
                val ok = MultiDownloader.download(
                    url, dest,
                    threads = 1,
                    onProgress = { done, total -> DlManager.progress(t, done, total) },
                    shouldCancel = { t.cancelRequested }
                )
                if (!ok) throw Exception("下载失败")
                DlManager.success(t, "已下载到 ${dest.absolutePath}")
            } catch (e: Exception) {
                DlManager.fail(t, e.message ?: "安装失败")
            }
        }
        message = "已加入下载中心：${item.title}"
    }

    Column(modifier.fillMaxSize()) {
        // 分类
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CurseForgeRepo.BEDROCK_CLASSES.forEach { (id, label) ->
                FilterChip(
                    selected = classId == id,
                    onClick = { classId = id },
                    label = { Text(label) }
                )
            }
        }

        // 搜索
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索基岩版资源") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = !loading, onClick = { search() }) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("搜索")
                }
            }
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

        val destHint = inst?.let { File(it.dir(), subDirOf(classId)).path } ?: "未选择实例"
        Text(
            text = "下载到：$destHint",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )

        if (results.isEmpty() && !loading && error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Spacer(Modifier.height(24.dp))
                PlaceholderNote("没有结果。基岩版资源只能从 CurseForge 取，换个关键词试试。")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(results, key = { it.id }) { item ->
                    Surface(
                        color = com.tubo.mcserver.ui.theme.ZPalette.cardBackground,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RemoteImage(
                                url = item.iconUrl,
                                size = 46.dp,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                enabled = inst != null,
                                onClick = { install(item) }
                            ) { Text("下载") }
                        }
                    }
                }
            }
        }
    }
}
