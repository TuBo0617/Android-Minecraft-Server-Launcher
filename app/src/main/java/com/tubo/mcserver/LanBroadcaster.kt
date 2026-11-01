package com.tubo.mcserver

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 模拟 Minecraft「对局域网开放」的组播通告。
 *
 * ## 为什么需要它
 *
 * Terracotta 开房前会**先找本地 Minecraft 服务端**：它监听组播 `224.0.2.60:4445`，
 * 解析 `[MOTD]...[/MOTD][AD]端口[/AD]`，端口 5 秒内没再收到通告就当作服务端不存在。
 * 找不到就一直停在 `host-scanning`，永远拿不到房间码。
 *
 * 这个通告原本只有客户端「对局域网开放」时才会发，**专用服务端不会发**，
 * 所以由启动器替它发。不发的话，开房会卡死在扫描阶段。
 */
object LanBroadcaster {

    private const val GROUP_V4 = "224.0.2.60"
    private const val GROUP_V6 = "ff75:230::60"
    private const val PORT = 4445

    /** 官方会过滤掉这条 MOTD（那是陶瓦大厅的假服务端），不能撞 */
    private const val MOTD = "MCServerLauncher"

    /** 扫描端 5 秒过期，1.5 秒发一轮留足余量 */
    private const val INTERVAL_MS = 1500L

    @Volatile private var running = false
    @Volatile private var gamePort = 25565
    private var worker: Thread? = null

    fun start(port: Int) {
        stop()
        gamePort = port
        running = true
        worker = Thread({ loop() }, "lan-broadcast").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        worker?.let { runCatching { it.interrupt() } }
        worker = null
    }

    fun isRunning(): Boolean = running
    fun port(): Int = gamePort

    private fun loop() {
        val payload = "[MOTD]$MOTD[/MOTD][AD]$gamePort[/AD]".toByteArray(Charsets.UTF_8)
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket()
            while (running) {
                for (addr in targets()) {
                    // 单个地址失败（比如 IPv6 走 IPv4 socket）不影响其它地址
                    runCatching {
                        sock.send(DatagramPacket(payload, payload.size, addr, PORT))
                    }
                }
                Thread.sleep(INTERVAL_MS)
            }
        } catch (_: InterruptedException) {
            // 正常停止
        } catch (_: Throwable) {
            // 发不出去就算了，不能影响主流程
        } finally {
            runCatching { sock?.close() }
        }
    }

    /**
     * 广播目标。
     *
     * 除了组播地址，还**逐个单播到本机每个地址的 4445**：
     * Terracotta 是在每个本地 IP 上各绑一个 4445 的 socket 收包，
     * 单播比组播在模拟器 / 部分机型上可靠得多（组播常被系统或路由丢掉）。
     */
    private fun targets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching { out += InetAddress.getByName(GROUP_V4) }
        runCatching { out += InetAddress.getByName(GROUP_V6) }
        runCatching { out += InetAddress.getByName("127.0.0.1") }
        runCatching { out += InetAddress.getByName("::1") }
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp) continue
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress) out += addr
                }
            }
        }
        return out.toList()
    }
}
