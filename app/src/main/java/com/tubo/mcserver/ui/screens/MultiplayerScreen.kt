package com.tubo.mcserver.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tubo.mcserver.LanBroadcaster
import com.tubo.mcserver.Prefs
import com.tubo.mcserver.ServerController
import com.tubo.mcserver.TcState
import com.tubo.mcserver.Terracotta
import com.tubo.mcserver.WebConsole
import com.tubo.mcserver.ui.components.PlaceholderNote
import com.tubo.mcserver.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 联机页（更新日志第 13 条）。
 *
 * 两种方式：
 * 1. 局域网直连 —— 同一 Wi-Fi 下直接连 `IP:端口`；
 * 2. 陶瓦联机（Terracotta）—— 不同网络也能进同一个虚拟局域网，靠房间码互通。
 *
 * 所有 Terracotta 调用都走 IO 线程：native 在等 VPN 时会持锁，
 * 主线程一旦调进去就会和 VPN 服务互相等死。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiplayerScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var roomCode by remember { mutableStateOf("") }
    var guestUrl by remember { mutableStateOf("") }
    var joinInput by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("未启动") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var rawState by remember { mutableStateOf("") }
    var logText by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var shareNames by remember { mutableStateOf(ServerController.runningNames()) }
    var copiedLabel by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<String>>(emptyList()) }
    // 第 13 条：联机界面强调色，0 表示跟随主题
    var accent by remember { mutableStateOf(Prefs.multiplayerAccent) }

    fun copyText(label: String, text: String) {
        if (text.isBlank()) return
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        }
        copiedLabel = label
    }

    // 「已复制」提示 1.6 秒后自动消失
    LaunchedEffect(copiedLabel) {
        if (copiedLabel != null) {
            delay(1600)
            copiedLabel = null
        }
    }

    // 授权 VPN 后继续执行原动作
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingAction
        pendingAction = null
        if (result.resultCode == Activity.RESULT_OK || VpnService.prepare(ctx) == null) {
            action?.invoke()
        } else {
            error = "没有授予 VPN 权限，无法使用联机功能"
        }
    }

    fun ensureVpnThen(action: () -> Unit) {
        val intent = VpnService.prepare(ctx)
        if (intent == null) {
            action()
        } else {
            pendingAction = action
            runCatching { vpnLauncher.launch(intent) }
                .onFailure { error = "无法请求 VPN 权限：${it.message}" }
        }
    }

    fun start(join: Boolean) {
        if (busy) return

        // 开房必须先有本地服务端：陶瓦靠扫描局域网广播来定位要共享的端口，
        // 没有服务端就永远停在 host-scanning，拿不到房间码。
        var port = 0
        if (!join) {
            val name = shareNames.firstOrNull()
            if (name == null) {
                error = "请先在「控制台」启动服务端，再回来创建房间——" +
                        "陶瓦需要检测到本地服务端才能开房"
                return
            }
            port = ServerController.portOf(name)
        }

        busy = true
        error = null
        statusText = if (join) "正在加入…" else "正在初始化…"
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                Terracotta.ensureInit(ctx)?.let { return@withContext it }
                if (join) Terracotta.join(joinInput) else Terracotta.host(port)
            }
            busy = false
            if (msg != null) {
                error = msg
                statusText = "未启动"
            } else {
                statusText = if (join) "正在加入…" else "正在创建房间…"
            }
        }
    }

    // 状态轮询：全部在 IO 线程读，主线程只负责刷新文案
    LaunchedEffect(Unit) {
        while (true) {
            delay(700)
            // 实例运行状态可能变化，先刷新，供「创建房间」判断能否开房
            shareNames = ServerController.runningNames()
            // 服务端停了就停止局域网广播，避免房间里的人连到已关闭的端口
            if (shareNames.isEmpty() && LanBroadcaster.isRunning()) {
                withContext(Dispatchers.IO) { LanBroadcaster.stop() }
            }
            if (!Terracotta.isInitialized()) continue
            val st: TcState = withContext(Dispatchers.IO) { Terracotta.state() }
            rawState = Terracotta.rawState()
            if (st.room.isNotEmpty()) roomCode = st.room
            if (st.url.isNotEmpty()) guestUrl = st.url
            members = st.profiles
            statusText = describeState(st)
            if (st.state == "exception" && error == null) {
                error = "联机出错：${explainError(st.errorType)}"
            }
            Terracotta.lastError()?.let { if (error == null) error = it }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(title = "局域网直连", subtitle = "同一 Wi-Fi 下最简单，不需要任何额外组件") {
            val ip = WebConsole.lanIp()
            if (shareNames.isEmpty()) {
                Text(
                    text = "还没有服务端在运行，先去「控制台」启动一个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                shareNames.forEach { name ->
                    val port = ServerController.portOf(name)
                    val addr = "$ip:$port"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { copyText("连接地址", addr) }
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = addr,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (copiedLabel == "连接地址") "已复制" else "复制",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "$name · 客户端「多人游戏 → 直接连接」填这个地址",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        SectionCard(
            title = "陶瓦联机",
            subtitle = "不同网络也能连；房间码发给朋友即可，底层走 EasyTier"
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).height(14.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(
                    text = "状态：$statusText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 开房前明确告诉用户要共享哪个服务端——陶瓦是「共享一个本地服务端」，
            // 不是凭空建一个房间，这一点不写清楚很容易点了没反应还不知道为什么
            run {
                val shareName = shareNames.firstOrNull()
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (shareName != null) {
                        "将共享：$shareName · 端口 ${ServerController.portOf(shareName)}"
                    } else {
                        "没有运行中的服务端，无法创建房间"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (shareName != null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
            }

            if (roomCode.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // 整块可点：点一下就复制房间码
                val accentColor = if (accent == 0) MaterialTheme.colorScheme.primary
                else Color(accent)
                Surface(
                    color = if (accent == 0) MaterialTheme.colorScheme.primaryContainer
                    else accentColor.copy(alpha = 0.22f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { copyText("房间码", roomCode) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = roomCode,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 22.sp
                            ),
                            color = if (accent == 0) MaterialTheme.colorScheme.onPrimaryContainer
                            else accentColor,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (copiedLabel == "房间码") "已复制" else "复制",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (accent == 0) MaterialTheme.colorScheme.onPrimaryContainer
                            else accentColor
                        )
                    }
                }
                Text(
                    text = "点一下房间码即可复制，发给朋友让他粘贴到下面「加入房间」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (members.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "房间内玩家（${members.size}）：" + members.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            if (guestUrl.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { copyText("连接地址", guestUrl) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = guestUrl,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (copiedLabel == "连接地址") "已复制" else "复制",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Text(
                    text = "已进入房主的虚拟局域网，点上面地址可复制，客户端「直接连接」填它",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = { ensureVpnThen { start(join = false) } }
                ) { Text("创建房间") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { Terracotta.leave() }
                            roomCode = ""
                            guestUrl = ""
                            members = emptyList()
                            statusText = "已退出"
                        }
                    }
                ) { Text("退出") }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = joinInput,
                onValueChange = { joinInput = it.trim() },
                label = { Text("房间码") },
                placeholder = { Text("粘贴朋友发来的房间码") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = !busy && joinInput.isNotEmpty(),
                onClick = { ensureVpnThen { start(join = true) } }
            ) { Text("加入房间") }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        SectionCard(title = "诊断", subtitle = "联机没反应时，把这里的内容发我") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val st = withContext(Dispatchers.IO) { Terracotta.state() }
                            rawState = Terracotta.rawState()
                            logText = withContext(Dispatchers.IO) { Terracotta.dumpLogs() }
                            if (st.state.isEmpty() && logText.isBlank()) {
                                error = "还没初始化，先点一次「创建房间」"
                            }
                        }
                    }
                ) { Text("刷新诊断") }
                OutlinedButton(
                    onClick = {
                        val sb = StringBuilder()
                        sb.appendLine("state=$rawState")
                        sb.appendLine("lastError=${Terracotta.lastError() ?: ""}")
                        sb.appendLine("initialized=${Terracotta.isInitialized()}")
                        sb.appendLine("--- terracotta log ---")
                        sb.appendLine(logText)
                        runCatching {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("terracotta", sb.toString()))
                        }
                        error = null
                    }
                ) { Text("复制") }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                Text(
                    text = buildString {
                        if (rawState.isNotBlank()) {
                            appendLine("原始状态：")
                            appendLine(rawState)
                        }
                        if (logText.isNotBlank()) {
                            appendLine()
                            append(logText.takeLast(4000))
                        }
                        if (rawState.isBlank() && logText.isBlank()) append("（点「刷新诊断」获取）")
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionCard(title = "界面颜色", subtitle = "只影响联机页的强调色（房间码、状态）") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    0 to "跟随主题",
                    0xFF4FC3F7.toInt() to "天蓝",
                    0xFF81C784.toInt() to "青绿",
                    0xFFFFB74D.toInt() to "琥珀",
                    0xFFBA68C8.toInt() to "紫罗兰",
                    0xFFE57373.toInt() to "珊瑚"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = accent == value,
                        onClick = {
                            accent = value
                            Prefs.multiplayerAccent = value
                        },
                        label = { Text(label) }
                    )
                }
            }
        }

        SectionCard(title = "说明") {
            PlaceholderNote(
                "陶瓦联机会建立一个本地 VPN（虚拟局域网），因此需要授权 VPN 权限；" +
                        "它只用于玩家之间互通，不会改变你正常的上网流量。\n" +
                        "开房前必须先在「控制台」启动服务端：陶瓦是「把你本地运行的服务端共享出去」，" +
                        "它靠局域网广播找到这个服务端，找不到就会一直停在扫描阶段。\n" +
                        "房主拿到房间码后发给朋友，朋友粘贴房间码加入；" +
                        "加入成功后客户端连接页会显示应填的地址。"
            )
        }
    }
}

/** 把官方 state 翻成人话（取值见 TerracottaState.java 的 @JsonType） */
private fun describeState(st: TcState): String = when (st.state) {
    "" -> "未启动"
    "bootstrap", "launching", "preparing", "uninitialized" -> "正在初始化…"
    "waiting" -> "正在申请房间…"
    "host-scanning" -> "扫描中"
    "host-starting" -> "正在建立房间…"
    "host-ok" -> "房间已就绪"
    "guest-connecting" -> "正在连接房主…"
    "guest-starting" -> "正在打通网络…"
    "guest-ok" -> "已加入房间"
    "exception" -> "出错：${explainError(st.errorType)}"
    "fatal" -> "致命错误"
    else -> st.state
}

private fun explainError(type: String): String = when (type) {
    "PING_HOST_FAIL" -> "连不上房主（对方网络不可达，或房间已关闭）"
    "PING_HOST_RST" -> "被房主拒绝连接"
    "GUEST_ET_CRASH" -> "访客侧 EasyTier 崩溃"
    "HOST_ET_CRASH" -> "房主侧 EasyTier 崩溃"
    "PING_SERVER_RST" -> "被联机服务器拒绝（房间码可能已过期）"
    "SCAFFOLDING_INVALID_RESPONSE" -> "房间码无效"
    else -> if (type.isBlank()) "未知错误" else type
}
