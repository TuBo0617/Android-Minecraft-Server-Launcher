package com.tubo.mcserver.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tubo.mcserver.DownloadSource
import com.tubo.mcserver.DownloadSources
import com.tubo.mcserver.JavaInstall
import com.tubo.mcserver.JdkManager
import com.tubo.mcserver.App
import com.tubo.mcserver.LoaderArgs
import com.tubo.mcserver.PowerKeeper
import com.tubo.mcserver.Prefs
import com.tubo.mcserver.ServerService
import com.tubo.mcserver.Storage
import com.tubo.mcserver.Updater
import com.tubo.mcserver.WebAuth
import com.tubo.mcserver.WebConsole
import com.tubo.mcserver.ui.components.CategoryRail
import com.tubo.mcserver.ui.components.ContentColumn
import com.tubo.mcserver.ui.components.LabeledSlider
import com.tubo.mcserver.ui.components.PlaceholderNote
import com.tubo.mcserver.ui.components.SectionCard
import com.tubo.mcserver.ui.components.SettingRow
import com.tubo.mcserver.ui.theme.AppBackgrounds
import com.tubo.mcserver.ui.theme.BgConfig
import com.tubo.mcserver.ui.theme.BgType
import com.tubo.mcserver.ui.theme.importBackgroundAudio
import com.tubo.mcserver.ui.theme.importBackgroundImage
import com.tubo.mcserver.ui.theme.ZAppTheme
import com.tubo.mcserver.ui.theme.ZPalette
import com.tubo.mcserver.ui.theme.ZThemeType
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class SettingsCategory(val label: String, val icon: ImageVector) {
    GENERAL("通用", Icons.Outlined.Settings),
    DOWNLOAD("下载", Icons.Outlined.Download),
    INDEPENDENT("独立设置", Icons.Outlined.Code),
    PERSONALIZE("个性化", Icons.Outlined.Palette),
    JAVA("Java", Icons.Outlined.Memory),
    ABOUT("关于", Icons.Outlined.Info)
}

/**
 * 设置页（Compose 版）。
 *
 * 覆盖更新日志：第 2 条下载源管理、第 11 条多线程下载、第 14 条个性化（主题）、
 * 第 18 条关于，以及「UI 与菜单调整」第 7 条把 Java 迁入设置。
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    var category by rememberSaveable { mutableStateOf(SettingsCategory.GENERAL) }

    Row(modifier.fillMaxSize()) {
        CategoryRail(
            items = SettingsCategory.entries.toList(),
            selected = category,
            labelOf = { it.label },
            iconOf = { it.icon },
            onSelect = { category = it }
        )
        Box(
            Modifier
                .width(1.dp)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            when (category) {
                SettingsCategory.GENERAL -> GeneralSection()
                SettingsCategory.DOWNLOAD -> DownloadSection()
                SettingsCategory.INDEPENDENT -> IndependentSection()
                SettingsCategory.PERSONALIZE -> PersonalizeSection()
                SettingsCategory.JAVA -> JavaSection()
                SettingsCategory.ABOUT -> AboutSection()
            }
        }
    }
}

// ---------------------------------------------------------------- 通用

@Composable
private fun GeneralSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var maxMemGb by remember { mutableStateOf(Prefs.maxMem / 1024f) }
    var autoEula by remember { mutableStateOf(Prefs.autoEula) }
    var useNogui by remember { mutableStateOf(Prefs.useNogui) }
    var keepAlive by remember { mutableStateOf(Prefs.keepAlive) }
    var webRunning by remember { mutableStateOf(WebConsole.isRunning()) }
    var userCount by remember { mutableStateOf(WebAuth.all().size) }
    var ownerPwd by remember { mutableStateOf<String?>(null) }

    ContentColumn {
        SectionCard(title = "默认启动参数") {
            LabeledSlider(
                label = "默认最大内存",
                valueText = "${"%.1f".format(maxMemGb)} GB",
                value = maxMemGb,
                valueRange = 0.5f..8f,
                onValueChange = {
                    val v = (it * 10).roundToInt() / 10f
                    maxMemGb = v
                    Prefs.maxMem = (v * 1024).roundToInt()
                }
            )
            SettingRow(
                title = "自动接受 EULA",
                subtitle = "启动时自动写入 eula=true"
            ) {
                Switch(checked = autoEula, onCheckedChange = { autoEula = it; Prefs.autoEula = it })
            }
            SettingRow(
                title = "默认使用 nogui",
                subtitle = "服务端不弹图形界面，手机上更省资源"
            ) {
                Switch(checked = useNogui, onCheckedChange = { useNogui = it; Prefs.useNogui = it })
            }
            SettingRow(
                title = "前台服务保活",
                subtitle = "切到后台时用常驻通知保住进程，避免系统把服务端一起杀掉"
            ) {
                Switch(
                    checked = keepAlive,
                    onCheckedChange = {
                        keepAlive = it
                        Prefs.keepAlive = it
                        ServerService.refresh(App.ctx)
                    }
                )
            }
        }

        SectionCard(
            title = "后台保活增强",
            subtitle = "锁屏后仍保持服务端与网页控制台在线"
        ) {
            var batteryOk by remember {
                mutableStateOf(PowerKeeper.isIgnoringBatteryOptimizations(context))
            }
            var rootOk by remember { mutableStateOf<Boolean?>(null) }
            var hardenLog by remember { mutableStateOf<String?>(null) }

            SettingRow(
                title = "电池优化白名单",
                subtitle = if (batteryOk) "已放行，锁屏后不易被冻结" else "未放行，锁屏后系统可能冻结应用"
            ) {
                if (batteryOk) {
                    Text("已开启", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                } else {
                    TextButton(onClick = {
                        PowerKeeper.requestIgnoreBattery(context)
                    }) { Text("去授权") }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            SettingRow(
                title = "Root 加固",
                subtitle = when (rootOk) {
                    null -> "点右侧按钮检测（没有 root 也不影响免 root 方案）"
                    true -> "检测到 root，可一键加入后台白名单"
                    false -> "未检测到 root"
                }
            ) {
                TextButton(onClick = {
                    scope.launch {
                        val has = withContext(Dispatchers.IO) { PowerKeeper.hasRoot() }
                        rootOk = has
                        if (has) {
                            hardenLog = withContext(Dispatchers.IO) {
                                PowerKeeper.hardenWithRoot().joinToString("\n")
                            }
                        }
                    }
                }) { Text(if (rootOk == true) "一键加固" else "检测") }
            }
            hardenLog?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            PlaceholderNote(
                "免 root 方案靠电池优化白名单 + 前台服务；有 root 时会额外设置 appops 与 Doze 白名单，" +
                        "锁屏后网页控制台依然能连上。"
            )
        }

        SectionCard(
            title = "存储",
            subtitle = if (Storage.usingPublic()) "当前使用公共目录，卸载 App 不会丢数据" else "未获得公共目录权限，数据暂存应用私有目录"
        ) {
            Text(
                text = Storage.root().absolutePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                Storage.allFilesAccessIntent()?.let { runCatching { context.startActivity(it) } }
            }) { Text("授予所有文件访问权限") }
        }

        SectionCard(title = "网页控制台") {
            SettingRow(
                title = "启动远程控制台",
                subtitle = if (webRunning) "http://${WebConsole.lanIp()}:${WebConsole.PORT}"
                else "同一 Wi-Fi 下可用电脑浏览器访问"
            ) {
                Switch(
                    checked = webRunning,
                    onCheckedChange = { on ->
                        if (on) WebConsole.start() else WebConsole.stop()
                        webRunning = WebConsole.isRunning()
                        if (on) userCount = WebAuth.all().size
                    }
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            SettingRow(
                title = "所有者账户",
                subtitle = "用户名 owner · 共 $userCount 个账户 · 首次使用请点右侧重置密码获取"
            ) {
                TextButton(onClick = {
                    ownerPwd = WebAuth.resetOwnerPassword()
                    userCount = WebAuth.all().size
                }) { Text("重置密码") }
            }
            ownerPwd?.let { pwd ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "新密码（只显示这一次）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = pwd,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "用 owner + 上面的密码在网页端登录，登录后可在「用户管理」里添加其他用户并分配权限。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            PlaceholderNote("已启用登录鉴权：账号密码在本机校验，登录与改密各有 5 秒冷却，新建用户默认停用且首次登录强制改密。")
        }
    }
}

// ---------------------------------------------------------------- 下载

@Composable
private fun DownloadSection() {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }

    var concurrency by remember { mutableStateOf(Prefs.downloadConcurrency) }
    var threads by remember { mutableStateOf(Prefs.downloadThreads) }
    var limitKb by remember { mutableStateOf(Prefs.downloadSpeedLimitKb) }
    var proxyOn by remember { mutableStateOf(Prefs.proxyEnabled) }
    var proxyHost by remember { mutableStateOf(Prefs.proxyHost) }
    var proxyPort by remember { mutableStateOf(Prefs.proxyPort.toString()) }

    fun testAll() {
        if (testing) return
        scope.launch {
            testing = true
            withContext(Dispatchers.IO) {
                coroutineScope {
                    DownloadSources.all.map { source -> async { DownloadSources.test(source) } }
                        .forEach { it.await() }
                }
            }
            testing = false
            refresh++
        }
    }

    // 进入页面自动检测全部源并测速
    LaunchedEffect(Unit) { testAll() }

    ContentColumn {
        SectionCard(
            title = "下载源",
            subtitle = "点某一行把它指定为当前源；点右上「自动」恢复按延迟挑选"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "共 ${DownloadSources.all.size} 个源，已启用 ${DownloadSources.enabled().size} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = { testAll() }, enabled = !testing) {
                    Text(if (testing) "测速中…" else "全部测速")
                }
            }
            @Suppress("UNUSED_EXPRESSION")
            refresh

            // Java 源排在最前，其余按声明顺序
            val kinds = listOf("termux") + DownloadSources.kinds().filter { it != "termux" }
            kinds.forEach { kind ->
                val sources = DownloadSources.ofKind(kind)
                if (sources.isEmpty()) return@forEach
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = DownloadSources.kindLabel(kind),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    if (DownloadSources.selectedId(kind).isNotEmpty()) {
                        TextButton(onClick = {
                            DownloadSources.clearSelection(kind)
                            refresh++
                        }) { Text("自动") }
                    }
                }
                sources.forEach { source ->
                    SourceRow(source, refresh) { refresh++ }
                }
            }
        }

        SectionCard(title = "多线程下载") {
            LabeledSlider(
                label = "最大并发任务数",
                valueText = "$concurrency 个",
                value = concurrency.toFloat(),
                valueRange = 1f..8f,
                onValueChange = {
                    concurrency = it.roundToInt().coerceIn(1, 8)
                    Prefs.downloadConcurrency = concurrency
                }
            )
            LabeledSlider(
                label = "单任务线程数",
                valueText = "$threads 线程",
                value = threads.toFloat(),
                valueRange = 1f..128f,
                onValueChange = {
                    threads = it.roundToInt().coerceIn(1, 128)
                    Prefs.downloadThreads = threads
                }
            )
            LabeledSlider(
                label = "传输限速",
                valueText = if (limitKb == 0) "不限速" else "$limitKb KB/s",
                value = limitKb.toFloat(),
                valueRange = 0f..20480f,
                onValueChange = {
                    limitKb = (it / 128).roundToInt() * 128
                    Prefs.downloadSpeedLimitKb = limitKb
                }
            )
            SettingRow(title = "使用代理", subtitle = "境内加速下载，仅支持 HTTP 代理") {
                Switch(checked = proxyOn, onCheckedChange = { proxyOn = it; Prefs.proxyEnabled = it })
            }
            if (proxyOn) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = { proxyHost = it; Prefs.proxyHost = it },
                        label = { Text("代理主机") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = proxyPort,
                        onValueChange = { v ->
                            proxyPort = v.filter { it.isDigit() }.take(5)
                            proxyPort.toIntOrNull()?.let { Prefs.proxyPort = it }
                        },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.width(110.dp)
                    )
                }
            }
        }
    }
}

/** 单个下载源：点击整行 = 手动指定为当前源 */
@Composable
private fun SourceRow(source: DownloadSource, refreshToken: Int, onChange: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    refreshToken

    val enabled = DownloadSources.isEnabled(source.id)
    val active = DownloadSources.isActive(source)
    val ms = DownloadSources.latency(source.id)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                DownloadSources.select(source.kind, source.id)
                onChange()
            }
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = source.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = DownloadSources.latencyLabel(source.id),
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        ms == DownloadSources.LATENCY_DEAD -> MaterialTheme.colorScheme.error
                        ms in 1..600 -> MaterialTheme.colorScheme.tertiary
                        ms > 600 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    DownloadSources.setEnabled(source.id, it)
                    onChange()
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
    }
}

// ---------------------------------------------------------------- 个性化

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalizeSection() {
    var fontSize by remember { mutableStateOf(Prefs.consoleFontSize) }
    var consoleTheme by remember { mutableStateOf(Prefs.consoleTheme) }

    ContentColumn {
        SectionCard(title = "主题色板", subtitle = "移植自 ZL2 的 7 套色板，切换后立即生效") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ZThemeType.entries.forEach { type ->
                    val active = ZAppTheme.current == type.name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                            )
                            .clickable { ZAppTheme.set(type) }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val dot = when (type) {
                            ZThemeType.DYNAMIC, ZThemeType.CUSTOM -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(dot)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = type.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (active) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        if (active) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "使用中",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
        BackgroundEditor(
            AppBackgrounds.LAUNCHER,
            "启动器背景",
            "纯色 / 渐变 / 图片，图片可调透明度；改完立即生效"
        )

        BackgroundEditor(
            AppBackgrounds.CONSOLE,
            "终端背景",
            "只作用于控制台日志区，和启动器背景互不影响"
        )

        SectionCard(title = "界面刷新", subtitle = "上限越低越省电，日志刷新也越滞后") {
            var fps by remember { mutableStateOf(Prefs.fpsLimit) }
            LabeledSlider(
                label = "FPS 上限",
                valueText = if (fps == 0) "不限制" else "$fps",
                value = fps.toFloat(),
                valueRange = 0f..240f,
                onValueChange = {
                    fps = it.roundToInt().coerceIn(0, 240)
                    Prefs.fpsLimit = fps
                }
            )
            Spacer(Modifier.height(6.dp))
            PlaceholderNote(
                "Compose 无法真正锁帧，这里换算成「最小刷新间隔」作用在控制台日志轮询上：" +
                        "0 = 默认 400ms，240 = 约 16ms（更跟手但更耗电）。"
            )
        }

        SectionCard(title = "控制台显示", subtitle = "只影响「控制台」页的日志区，改完立即生效") {
            LabeledSlider(
                label = "字号",
                valueText = "${fontSize}sp",
                value = fontSize.toFloat(),
                valueRange = 9f..18f,
                onValueChange = {
                    fontSize = it.roundToInt().coerceIn(9, 18)
                    Prefs.consoleFontSize = fontSize
                }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "配色",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "auto" to "跟随主题",
                    "classic" to "经典绿字",
                    "contrast" to "高对比"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = consoleTheme == key,
                        onClick = {
                            consoleTheme = key
                            Prefs.consoleTheme = key
                        },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            PlaceholderNote("经典绿字/高对比会同时把日志区背景压成近黑色，贴近终端观感。")
        }
    }
}

// ---------------------------------------------------------------- Java

@Composable
private fun JavaSection() {
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf<List<JavaInstall>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var deleteTarget by remember { mutableStateOf<JavaInstall?>(null) }

    LaunchedEffect(refresh) {
        installed = withContext(Dispatchers.IO) { JdkManager.listInstalled() }
    }

    ContentColumn {
        SectionCard(title = "当前 Java") {
            val active = JdkManager.activeBin()
            Text(
                text = active?.absolutePath ?: "未检测到可用 Java，请先下载安装",
                style = MaterialTheme.typography.bodySmall,
                color = if (active == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "设备架构：${JdkManager.arch()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(
            title = "已安装",
            subtitle = "下载进度与完整日志见「下载中心」"
        ) {
            if (installed.isEmpty()) {
                Text(
                    text = "暂无已安装的 Java",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                installed.forEach { j ->
                    SettingRow(
                        title = j.label,
                        subtitle = j.home.absolutePath
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (j.active) {
                                Text(
                                    text = "使用中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                TextButton(onClick = {
                                    JdkManager.select(j.home)
                                    refresh++
                                }) { Text("使用") }
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { JdkManager.reinstall(j) }
                                    refresh++
                                }
                            }) { Text("更新") }
                            TextButton(onClick = { deleteTarget = j }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { refresh++ }) { Text("刷新列表") }
        }

        SectionCard(
            title = "下载安装",
            subtitle = "安装包来自 Termux 软件源；源可在「下载 → 下载源」里切换与测速"
        ) {
            val source = DownloadSources.pick("termux")
            Text(
                text = "当前源：${source?.name ?: "无可用源"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (source == null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
            if (source != null && DownloadSources.isDead(source.id)) {
                Text(
                    text = "该源最近一次测速不可用，建议到「下载源」里换一个镜像",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("17", "21", "25").forEach { ver ->
                    Button(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { JdkManager.install(ver) }
                        }
                    }) { Text("openjdk-$ver") }
                }
            }
            Spacer(Modifier.height(8.dp))
            PlaceholderNote("Java 8 / 11 已从 Termux 仓库移除，只能用 17 / 21 / 25。安装失败时先看「下载中心」里该任务的日志。")
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除 ${target.label}？") },
            text = { Text("将删除该 Java 的整个安装目录，不可恢复。已装好的服务端需要重新指定 Java。") },
            confirmButton = {
                TextButton(onClick = {
                    val t = target
                    deleteTarget = null
                    scope.launch {
                        withContext(Dispatchers.IO) { JdkManager.uninstall(t) }
                        refresh++
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

// ---------------------------------------------------------------- 关于

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateText by remember { mutableStateOf<String?>(null) }
    var updateUrl by remember { mutableStateOf("") }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    ContentColumn {
        SectionCard(title = "MCServerLauncher") {
            Text(
                text = "版本 v0.1 beta",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "在 Android 上直接开一个真正的 Minecraft Java 版服务器，无需 Termux、无需 root。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(title = "作者与仓库") {
            SettingRow(title = "GitHub 主页", subtitle = "tubo") {
                TextButton(onClick = { open("https://github.com/tubo") }) { Text("打开") }
            }
            SettingRow(title = "项目仓库", subtitle = "Android-Minecraft-Server-Launcher") {
                TextButton(onClick = { open("https://github.com/tubo/Android-Minecraft-Server-Launcher") }) { Text("打开") }
            }
            SettingRow(title = "B 站主页") {
                TextButton(onClick = { open("https://space.bilibili.com") }) { Text("打开") }
            }
        }

        SectionCard(title = "更多") {
            SettingRow(
                title = "更新检查",
                subtitle = updateText ?: "从 GitHub Releases 获取最新版本"
            ) {
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = {
                        checking = true
                        updateText = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { Updater.check() }
                            checking = false
                            updateText = when {
                                r.error != null -> r.error
                                r.hasUpdate -> "发现新版本 ${r.latestTag}（当前 ${Updater.CURRENT}）"
                                else -> "已是最新版本（${Updater.CURRENT}）"
                            }
                            updateUrl = r.htmlUrl
                        }
                    }) { Text("检查") }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            SettingRow(title = "版本更新日志", subtitle = "GitHub Releases 页面") {
                TextButton(onClick = { open(updateUrl.ifEmpty { Updater.RELEASES_PAGE }) }) { Text("打开") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            SettingRow(title = "使用教程", subtitle = "仓库 README：安装、装核心、开服与联机") {
                TextButton(onClick = { open("https://github.com/tubo/Android-Minecraft-Server-Launcher#readme") }) {
                    Text("打开")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            SettingRow(title = "启动器原型", subtitle = "界面原型与设计稿尚未公开") {
                TextButton(enabled = false, onClick = { }) { Text("暂无") }
            }
        }
    }
}

// ---------------------------------------------------------------- 背景与取色

/** 预设色板：暗色系为主，挑几个能当背景的深色 + 几个亮色点缀 */
private val PRESET_COLORS = listOf(
    0xFF0E1116.toInt(), 0xFF161A21.toInt(), 0xFF1F2430.toInt(), 0xFF2B3140.toInt(),
    0xFF3A2E4F.toInt(), 0xFF243B4A.toInt(), 0xFF1F3B33.toInt(), 0xFF4A2E2E.toInt(),
    0xFF5B4636.toInt(), 0xFF0B3D2E.toInt(), 0xFF2C2C54.toInt(), 0xFF4A3B2A.toInt()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackgroundEditor(scopeKey: String, title: String, subtitle: String) {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf(AppBackgrounds.get(scopeKey)) }

    fun update(next: BgConfig) {
        cfg = next
        AppBackgrounds.set(scopeKey, next)
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = importBackgroundImage(ctx, uri)
        if (path != null) {
            update(cfg.copy(type = BgType.IMAGE, imagePath = path))
        }
    }

    // 背景音乐只对启动器有意义，终端背景不提供
    var audioOn by remember { mutableStateOf(Prefs.bgAudioOn) }
    var audioPath by remember { mutableStateOf(Prefs.bgAudioPath) }
    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = importBackgroundAudio(ctx, uri)
        if (path != null) {
            audioPath = path
            Prefs.bgAudioPath = path
            audioOn = true
            Prefs.bgAudioOn = true
        }
    }

    SectionCard(title = title, subtitle = subtitle) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BgType.entries.forEach { t ->
                FilterChip(
                    selected = cfg.type == t,
                    onClick = {
                        // 选「图片」但还没图时直接拉起选择器，省一步
                        if (t == BgType.IMAGE && cfg.imagePath.isEmpty()) {
                            pickImage.launch(arrayOf("image/*"))
                        } else {
                            update(cfg.copy(type = t))
                        }
                    },
                    label = { Text(t.label) }
                )
            }
        }

        if (cfg.type == BgType.COLOR || cfg.type == BgType.GRADIENT) {
            Spacer(Modifier.height(12.dp))
            ColorPickerRow("主色", cfg.color1) { update(cfg.copy(color1 = it)) }
        }
        if (cfg.type == BgType.GRADIENT) {
            Spacer(Modifier.height(10.dp))
            ColorPickerRow("副色", cfg.color2) { update(cfg.copy(color2 = it)) }
            Spacer(Modifier.height(6.dp))
            LabeledSlider(
                label = "渐变角度",
                valueText = "${cfg.angle}°",
                value = cfg.angle.toFloat(),
                valueRange = 0f..360f,
                onValueChange = { update(cfg.copy(angle = it.roundToInt().coerceIn(0, 360))) }
            )
        }
        if (cfg.type == BgType.IMAGE) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickImage.launch(arrayOf("image/*")) }) {
                    Text(if (cfg.imagePath.isEmpty()) "选择图片" else "更换图片")
                }
                if (cfg.imagePath.isNotEmpty()) {
                    TextButton(onClick = { update(cfg.copy(imagePath = "")) }) {
                        Text("清除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LabeledSlider(
                label = "图片透明度",
                valueText = "${cfg.opacity}%",
                value = cfg.opacity.toFloat(),
                valueRange = 0f..100f,
                onValueChange = { update(cfg.copy(opacity = it.roundToInt().coerceIn(0, 100))) }
            )
        }
        if (cfg.type != BgType.NONE) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { update(BgConfig(type = BgType.NONE)) }) { Text("恢复默认背景") }
        }

        if (scopeKey == AppBackgrounds.LAUNCHER) {
            Spacer(Modifier.height(10.dp))
            SettingRow(
                title = "背景音乐",
                subtitle = when {
                    audioPath.isEmpty() -> "未选择音频（默认关闭）"
                    audioOn -> "循环播放中"
                    else -> "已选择，当前关闭"
                }
            ) {
                Switch(
                    checked = audioOn,
                    onCheckedChange = {
                        audioOn = it
                        Prefs.bgAudioOn = it
                    }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickAudio.launch(arrayOf("audio/*")) }) {
                    Text(if (audioPath.isEmpty()) "选择音频" else "更换音频")
                }
                if (audioPath.isNotEmpty()) {
                    TextButton(onClick = {
                        audioPath = ""
                        audioOn = false
                        Prefs.bgAudioPath = ""
                        Prefs.bgAudioOn = false
                    }) { Text("清除", color = MaterialTheme.colorScheme.error) }
                }
            }
            Spacer(Modifier.height(4.dp))
            PlaceholderNote("只支持本地音频文件，循环播放；播放会占用少量电量，默认关闭。")
        }
    }
}

/** 预设色板 + 十六进制输入 */
@Composable
private fun ColorPickerRow(label: String, value: Int, onChange: (Int) -> Unit) {
    val hex = String.format(Locale.US, "#%06X", value and 0xFFFFFF)
    var text by remember(value) { mutableStateOf(hex) }

    Text(
        text = "$label  $hex",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PRESET_COLORS.take(6).forEach { c ->
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .clickable { onChange(c) }
                    .then(
                        if (c == value) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else Modifier
                    )
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PRESET_COLORS.drop(6).forEach { c ->
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .clickable { onChange(c) }
                    .then(
                        if (c == value) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        else Modifier
                    )
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            val clean = t.trim().removePrefix("#")
            if (clean.length == 6) {
                clean.toIntOrNull(16)?.let { onChange(0xFF000000.toInt() or it) }
            }
        },
        label = { Text("自定义颜色 #RRGGBB") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth()
    )
}

// ---------------------------------------------------------------- 独立设置

/**
 * 第 10 条：每个加载器一套独立的启动命令，各自开关，互不干扰。
 *
 * 模板里可以用 `<java>` 和 `<jar>` 占位符，启动时会替换成该实例的真实路径。
 * 优先级：实例自己的自定义命令 > 这里的模板 > 内置默认。
 */
@Composable
private fun IndependentSection() {
    ContentColumn {
        SectionCard(
            title = "加载器独立启动命令",
            subtitle = "支持 <java> / <jar> 占位符；实例自己的自定义命令优先"
        ) {
            LoaderArgs.LOADERS.forEach { (key, label) ->
                var on by remember(key) { mutableStateOf(LoaderArgs.enabled(key)) }
                var text by remember(key) { mutableStateOf(LoaderArgs.args(key)) }

                SettingRow(
                    title = label,
                    subtitle = if (on) "已启用（有内容时生效）" else "关闭，走内置默认命令"
                ) {
                    Switch(
                        checked = on,
                        onCheckedChange = {
                            on = it
                            LoaderArgs.setEnabled(key, it)
                        }
                    )
                }
                if (on) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            LoaderArgs.setArgs(key, it)
                        },
                        label = { Text("完整启动命令") },
                        placeholder = { Text(LoaderArgs.defaultHint(key)) },
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
            PlaceholderNote(
                "没有总开关：每个加载器独立生效，关掉的那个走内置默认命令。" +
                        "内存、nogui 等参数由这条命令自己决定，不再叠加默认值。"
            )
        }
    }
}
