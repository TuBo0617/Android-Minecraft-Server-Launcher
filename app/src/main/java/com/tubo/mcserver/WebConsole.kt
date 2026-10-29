package com.tubo.mcserver

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder

object WebConsole {
    private var server: ServerSocket? = null
    private var thread: Thread? = null
    const val PORT = 8080

    fun isRunning(): Boolean = server?.isClosed == false

    fun start(): String {
        if (isRunning()) return "已在运行"
        return try {
            server = ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"))
            thread = Thread {
                while (!server!!.isClosed) {
                    try {
                        val sock = server!!.accept()
                        Thread {
                            try {
                                handle(sock.getInputStream(), sock.getOutputStream())
                            } catch (_: Exception) {}
                            finally { runCatching { sock.close() } }
                        }.start()
                    } catch (_: Exception) { break }
                }
            }.also { it.isDaemon = true; it.start() }
            "远程控制台已启动: http://${lanIp()}:$PORT"
        } catch (e: Exception) {
            "启动失败: ${e.message}"
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    fun lanIp(): String {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            for (iface in ifs) {
                for (addr in iface.inetAddresses) {
                    val a = addr.hostAddress ?: continue
                    if (!addr.isLoopbackAddress && a.contains('.') && !a.startsWith("169.254")) {
                        return a
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun handle(input: java.io.InputStream, out: java.io.OutputStream) {
        val reader = java.io.BufferedReader(java.io.InputStreamReader(input))
        val reqLine = reader.readLine() ?: return
        val parts = reqLine.split(" ")
        if (parts.size < 2) return
        val path = parts[1]

        when {
            path == "/" -> respond(out, 200, "text/html", PAGE.toByteArray())
            path.startsWith("/log") -> respond(out, 200, "text/plain; charset=utf-8",
                ServerController.recentLog().toByteArray())
            path.startsWith("/status") -> {
                val s = if (ServerController.isRunning()) "running" else "stopped"
                respond(out, 200, "text/plain", s.toByteArray())
            }
            path.startsWith("/start") -> {
                val inst = InstanceManager.get(Prefs.currentInstance)
                val msg = if (inst != null) ServerController.start(inst) else "未选择实例"
                ServerController.emit("[web] $msg")
                respond(out, 200, "text/plain", msg.toByteArray())
            }
            path.startsWith("/stop") -> {
                ServerController.stop()
                respond(out, 200, "text/plain", "stopping".toByteArray())
            }
            path.startsWith("/cmd") -> {
                val q = if (path.contains("?")) path.substring(path.indexOf('?') + 1) else ""
                val v = q.split("&").firstOrNull { it.startsWith("c=") }?.removePrefix("c=") ?: ""
                val cmd = URLDecoder.decode(v, "UTF-8")
                if (cmd.isNotEmpty()) {
                    ServerController.emit("[web] > $cmd")
                    ServerController.sendCommand(cmd)
                }
                respond(out, 200, "text/plain", "ok".toByteArray())
            }
            else -> respond(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun respond(out: java.io.OutputStream, code: Int, mime: String, body: ByteArray) {
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $mime; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private val PAGE = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MC 远程控制台</title>
<style>
 body{background:#101410;color:#B9F6CA;font-family:monospace;margin:0;padding:8px}
 #log{white-space:pre-wrap;font-size:12px;min-height:60vh;border:1px solid #333;padding:8px}
 .bar{display:flex;gap:6px;margin:6px 0;flex-wrap:wrap}
 button{background:#1B5E20;color:#fff;border:0;padding:8px 14px;border-radius:4px}
 input{flex:1;background:#000;color:#B9F6CA;border:1px solid #333;padding:8px}
 h3{margin:4px 0}
</style></head><body>
<h3>MC 服务器远程控制台 <span id=st style=color:#888></span></h3>
<div class=bar>
 <button onclick="act('/start')">启动</button>
 <button onclick="act('/stop')">停止</button>
 <button onclick="load()">刷新</button>
</div>
<div id=log>连接中...</div>
<div class=bar>
 <input id=c placeholder="输入命令，如 op Steve / stop">
 <button onclick="send()">发送</button>
</div>
<script>
function load(){fetch('/log').then(r=>r.text()).then(t=>{document.getElementById('log').textContent=t;
 window.scrollTo(0,document.body.scrollHeight)})}
function act(p){fetch(p).then(()=>load())}
function send(){var v=document.getElementById('c').value;if(!v)return;
 fetch('/cmd?c='+encodeURIComponent(v)).then(()=>{document.getElementById('c').value='';load()})}
document.getElementById('c').addEventListener('keydown',e=>{if(e.key=='Enter')send()});
setInterval(load,2000);load();
</script></body></html>
""".trimIndent()
}
