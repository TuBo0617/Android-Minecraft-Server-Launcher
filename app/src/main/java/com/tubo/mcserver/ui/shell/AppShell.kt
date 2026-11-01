package com.tubo.mcserver.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tubo.mcserver.CoreInstaller
import com.tubo.mcserver.InstanceManager
import com.tubo.mcserver.Prefs
import com.tubo.mcserver.ServerController
import com.tubo.mcserver.ServerCoreCatalog
import com.tubo.mcserver.ui.screens.ConsoleScreen
import com.tubo.mcserver.ui.screens.CreateInstanceScreen
import com.tubo.mcserver.ui.screens.InstanceSettingsScreen
import com.tubo.mcserver.ui.screens.DownloadsScreen
import com.tubo.mcserver.ui.screens.InstancesScreen
import com.tubo.mcserver.ui.screens.MultiplayerScreen
import com.tubo.mcserver.ui.screens.ResourcesScreen
import com.tubo.mcserver.ui.screens.SettingsScreen
import com.tubo.mcserver.ui.theme.AppBackgrounds
import com.tubo.mcserver.ui.theme.BackgroundBox
import com.tubo.mcserver.ui.theme.ZPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用外壳：左侧可折叠侧边栏 + 右侧内容区。
 *
 * 内容区按 [AppRoute] 切换；已迁移的页面走 Compose 原生实现，
 * 其余暂时由 [LegacyScreen] 桥接旧 Fragment。
 *
 * 实例设置与新建实例都是**整页**，进入后占满内容区，自带返回按钮。
 */
@Composable
fun AppShell(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(Prefs.sidebarExpanded) }
    var route by rememberSaveable { mutableStateOf(AppRoute.INSTANCES) }
    var settingsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }

    // 背景音乐（第 20 条）：默认关闭，循环播放，跟随 audioRevision 重建
    DisposableEffect(Prefs.audioRevision) {
        var player: android.media.MediaPlayer? = null
        if (Prefs.bgAudioOn && Prefs.bgAudioPath.isNotEmpty()) {
            player = runCatching {
                android.media.MediaPlayer().apply {
                    setDataSource(Prefs.bgAudioPath)
                    isLooping = true
                    setVolume(0.6f, 0.6f)
                    prepare()
                    start()
                }
            }.getOrNull()
        }
        onDispose { runCatching { player?.release() } }
    }

    // 启动器背景（第 20 条）：无背景时仍用主题底色
    val bg = remember(AppBackgrounds.version) { AppBackgrounds.get(AppBackgrounds.LAUNCHER) }
    BackgroundBox(
        config = bg,
        fallback = ZPalette.appBackground,
        modifier = modifier.fillMaxSize()
    ) {
        Row(Modifier.fillMaxSize().systemBarsPadding()) {
            ZSidebar(
                expanded = expanded,
                current = route,
                onToggle = {
                    expanded = !expanded
                    Prefs.sidebarExpanded = expanded
                },
                onSelect = {
                    settingsFor = null
                    creating = false
                    route = it
                }
            )

            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            )

            Column(Modifier.weight(1f).fillMaxHeight()) {
                val target = settingsFor
                if (target == null && !creating) {
                    TopBar(title = route.label)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        target != null -> InstanceSettingsScreen(
                            instanceName = target,
                            onBack = { settingsFor = null },
                            onRenamed = { settingsFor = it },
                            onDeleted = {
                                // 之前这里只做了返回导航，根本没删目录
                                val name = target
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        ServerController.stop(name)
                                        InstanceManager.get(name)?.let { InstanceManager.delete(it) }
                                    }
                                    if (Prefs.currentInstance == name) Prefs.currentInstance = ""
                                    settingsFor = null
                                    route = AppRoute.INSTANCES
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        creating -> CreateInstanceScreen(
                            onBack = { creating = false },
                            onCreate = { name, ver, core ->
                                scope.launch {
                                    val inst = withContext(Dispatchers.IO) {
                                        // BDS 不是 Java 核心，loader 要落成 BEDROCK
                                        val loader = when {
                                            core == "BDS" -> "BEDROCK"
                                            else -> ServerCoreCatalog.find(core)?.loader ?: core
                                        }
                                        InstanceManager.create(name, ver, loader, core)
                                    }
                                    Prefs.currentInstance = inst.name
                                    creating = false
                                    route = AppRoute.INSTANCES
                                    // 建完直接开始装核心
                                    CoreInstaller.start(inst)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        route == AppRoute.INSTANCES -> InstancesScreen(
                            modifier = Modifier.fillMaxSize(),
                            onOpenSettings = { settingsFor = it },
                            onOpenCreate = { creating = true }
                        )

                        route == AppRoute.CONSOLE -> ConsoleScreen(Modifier.fillMaxSize())

                        route == AppRoute.RESOURCES -> ResourcesScreen(Modifier.fillMaxSize())

                        route == AppRoute.MULTIPLAYER -> MultiplayerScreen(Modifier.fillMaxSize())

                        route == AppRoute.DOWNLOADS -> DownloadsScreen(Modifier.fillMaxSize())

                        route == AppRoute.SETTINGS -> SettingsScreen(Modifier.fillMaxSize())

                        else -> LegacyScreen(route, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(title: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}
