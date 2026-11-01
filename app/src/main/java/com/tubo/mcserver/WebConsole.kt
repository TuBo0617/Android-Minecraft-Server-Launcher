package com.tubo.mcserver

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * 局域网网页控制台。
 *
 * 已接入账户体系（更新日志「网页控制台用户权限模块」）：
 * - 未登录只能看到登录页，账号密码在**本机**校验；
 * - 登录成功后有 5 秒冷却，期间再次提交直接拒绝；
 * - 首次登录（新建用户）强制改密；改密同样有 5 秒冷却；
 * - 所有操作按权限放行，管理员可管理用户。
 */
object WebConsole {

    private var server: ServerSocket? = null
    private var thread: Thread? = null
    const val PORT = 8080

    private const val LOGIN_COOLDOWN_MS = 5000L
    private const val RESET_COOLDOWN_MS = 5000L
    private const val SESSION_TTL_MS = 12 * 60 * 60 * 1000L
    private const val COOKIE = "mcs_sid"

    private data class Session(val userId: String, var expiresAt: Long)

    private val sessions = HashMap<String, Session>()
    private val rnd = SecureRandom()
    @Volatile private var lastLoginAt = 0L
    @Volatile private var lastResetAt = 0L

    fun isRunning(): Boolean = server?.isClosed == false

    fun start(): String {
        if (isRunning()) return "已在运行"
        WebAuth.ensureOwner()
        return try {
            server = ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"))
            thread = Thread {
                while (server?.isClosed == false) {
                    try {
                        val sock = server!!.accept()
                        Thread {
                            try {
                                sock.getInputStream().use { input ->
                                    sock.getOutputStream().use { out ->
                                        handle(input, out)
                                    }
                                }
                            } catch (_: Exception) {
                            } finally {
                                runCatching { sock.close() }
                            }
                        }.apply { isDaemon = true }.start()
                    } catch (_: Exception) {
                        break
                    }
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
        sessions.clear()
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
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }

    // ------------------------------------------------------------ 请求解析

    private class Req(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String
    ) {
        fun param(k: String): String = query[k] ?: form[k] ?: ""
        val form: Map<String, String> by lazy { parseForm(body) }
        fun cookie(name: String): String? = headers["cookie"]
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
    }

    private fun handle(input: InputStream, out: OutputStream) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val reqLine = reader.readLine() ?: return
        val parts = reqLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val rawPath = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                line.substring(idx + 1).trim()
        }

        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) {
            val buf = CharArray(len)
            var read = 0
            while (read < len) {
                val n = reader.read(buf, read, len - read)
                if (n <= 0) break
                read += n
            }
            String(buf, 0, read)
        } else ""

        val path = rawPath.substringBefore('?')
        val query = parseForm(rawPath.substringAfter('?', ""))
        val req = Req(method, path, query, headers, body)

        route(req, out)
    }

    // ------------------------------------------------------------ 路由

    private fun route(req: Req, out: OutputStream) {
        val session = currentUser(req)

        when {
            req.path == "/login" && req.method == "POST" -> handleLogin(req, out)
            req.path == "/logout" -> {
                req.cookie(COOKIE)?.let { sessions.remove(it) }
                redirect(out, "/", clearCookie = true)
            }
            req.path == "/reset" && req.method == "POST" -> handleReset(req, out, session)
            else -> {
                if (session == null) {
                    html(out, loginPage(error = null))
                    return
                }
                val user = WebAuth.byId(session.userId)
                if (user == null || !user.enabled) {
                    sessions.remove(req.cookie(COOKIE))
                    html(out, loginPage("账户已被停用"))
                    return
                }
                when {
                    req.path == "/" -> {
                        if (user.mustReset) html(out, forceResetPage(user))
                        else html(out, consolePage(user))
                    }
                    req.path == "/reset" -> html(out, resetPage(user, null))
                    req.path == "/admin" -> {
                        if (!user.isAdmin) text(out, 403, "无权限")
                        else html(out, adminPage(user, null, null))
                    }
                    req.path.startsWith("/admin/") -> handleAdmin(req, out, user)
                    req.path == "/api/log" -> {
                        if (!user.can(WebAuth.PERM_VIEW_LOG)) text(out, 403, "无权限")
                        else text(out, 200, ServerController.recentLog())
                    }
                    req.path == "/api/status" -> {
                        val s = if (ServerController.isRunning()) "running" else "stopped"
                        text(out, 200, s)
                    }
                    req.path == "/api/cmd" -> {
                        if (!user.can(WebAuth.PERM_SEND_CMD)) text(out, 403, "无权限")
                        else {
                            val c = req.param("c")
                            if (c.isNotEmpty()) {
                                ServerController.emit("[web:${user.username}] > $c")
                                ServerController.sendCommand(c)
                            }
                            text(out, 200, "ok")
                        }
                    }
                    req.path == "/api/start" -> {
                        if (!user.can(WebAuth.PERM_START)) text(out, 403, "无权限")
                        else {
                            val inst = InstanceManager.get(Prefs.currentInstance)
                            val msg = if (inst != null) ServerController.start(inst) else "未选择实例"
                            ServerController.emit("[web:${user.username}] $msg")
                            text(out, 200, msg)
                        }
                    }
                    req.path == "/api/stop" -> {
                        if (!user.can(WebAuth.PERM_STOP)) text(out, 403, "无权限")
                        else {
                            ServerController.stop()
                            text(out, 200, "stopping")
                        }
                    }
                    else -> text(out, 404, "not found")
                }
            }
        }
    }

    private fun currentUser(req: Req): Session? {
        val sid = req.cookie(COOKIE) ?: return null
        val s = sessions[sid] ?: return null
        if (s.expiresAt < System.currentTimeMillis()) {
            sessions.remove(sid)
            return null
        }
        s.expiresAt = System.currentTimeMillis() + SESSION_TTL_MS
        return s
    }

    // ------------------------------------------------------------ 登录 / 改密

    private fun handleLogin(req: Req, out: OutputStream) {
        val now = System.currentTimeMillis()
        if (now - lastLoginAt < LOGIN_COOLDOWN_MS) {
            val wait = (LOGIN_COOLDOWN_MS - (now - lastLoginAt)) / 1000 + 1
            html(out, loginPage("登录太频繁，请 ${wait} 秒后再试"))
            return
        }
        val username = req.param("username")
        val password = req.param("password")
        val user = WebAuth.verify(username, password)
        if (user == null) {
            html(out, loginPage("用户名或密码不正确，或账户未启用"))
            return
        }
        lastLoginAt = now
        val sid = newToken()
        sessions[sid] = Session(user.id, now + SESSION_TTL_MS)
        redirect(out, "/", cookie = sid)
    }

    private fun handleReset(req: Req, out: OutputStream, session: Session?) {
        if (session == null) {
            html(out, loginPage("请先登录"))
            return
        }
        val user = WebAuth.byId(session.userId) ?: run {
            html(out, loginPage("账户不存在"))
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastResetAt < RESET_COOLDOWN_MS) {
            val wait = (RESET_COOLDOWN_MS - (now - lastResetAt)) / 1000 + 1
            val page = if (user.mustReset) forceResetPage(user, "操作太频繁，请 ${wait} 秒后再试")
            else resetPage(user, "操作太频繁，请 ${wait} 秒后再试")
            html(out, page)
            return
        }

        val old = req.param("old")
        val neu = req.param("new")
        val confirm = req.param("confirm")
        if (neu != confirm) {
            val page = if (user.mustReset) forceResetPage(user, "两次输入的新密码不一致")
            else resetPage(user, "两次输入的新密码不一致")
            html(out, page)
            return
        }

        val err = WebAuth.changePassword(user.id, old, neu, checkOld = !user.mustReset)
        if (err != null) {
            val page = if (user.mustReset) forceResetPage(user, err) else resetPage(user, err)
            html(out, page)
            return
        }

        lastResetAt = now
        html(out, messagePage("密码已更新", "请使用新密码重新登录", "/logout"))
    }

    private fun handleAdmin(req: Req, out: OutputStream, user: WebUser) {
        if (!user.isAdmin && !user.can(WebAuth.PERM_MANAGE_USERS)) {
            text(out, 403, "无权限")
            return
        }
        var notice: String? = null
        var error: String? = null

        when (req.path) {
            "/admin/add" -> {
                val (u, err) = WebAuth.add(
                    username = req.param("username"),
                    password = req.param("password"),
                    isAdmin = req.param("isAdmin") == "1",
                    permissions = req.form.keys.filter { it.startsWith("perm_") }
                        .map { it.removePrefix("perm_") }.toSet()
                )
                if (err != null) error = err else notice = "已创建用户 ${u?.username}，默认关闭，需手动开启"
            }
            "/admin/delete" -> {
                val ok = WebAuth.delete(req.param("id"))
                if (ok) notice = "已删除" else error = "删除失败（至少要保留一个管理员）"
            }
            "/admin/toggle" -> {
                val id = req.param("id")
                val enable = req.param("enabled") == "1"
                WebAuth.setEnabled(id, enable)
                notice = if (enable) "已启用" else "已停用"
            }
            "/admin/admin" -> {
                WebAuth.setAdmin(req.param("id"), req.param("isAdmin") == "1")
                notice = "已更新角色"
            }
            "/admin/perms" -> {
                val id = req.param("id")
                val perms = req.form.keys.filter { it.startsWith("perm_") }
                    .map { it.removePrefix("perm_") }.toSet()
                WebAuth.setPermissions(id, perms)
                notice = "已更新权限"
            }
            "/admin/resetpw" -> {
                val id = req.param("id")
                val u = WebAuth.byId(id)
                if (u == null) {
                    error = "用户不存在"
                } else {
                    val pwd = WebAuth.generatePassword()
                    val err = WebAuth.changePassword(id, null, pwd, checkOld = false)
                    if (err != null) error = err
                    else notice = "${u.username} 的新密码：$pwd（请立即告知对方）"
                }
            }
        }
        html(out, adminPage(user, notice, error))
    }

    // ------------------------------------------------------------ 页面

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private val CSS = """
body{background:#0d100d;color:#cfe8d2;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;margin:0;padding:16px}
h2{margin:0 0 12px;font-size:18px}
.card{background:#141a14;border:1px solid #24301f;border-radius:10px;padding:16px;margin-bottom:14px}
label{display:block;font-size:13px;color:#8fa88f;margin:8px 0 4px}
input[type=text],input[type=password]{width:100%;box-sizing:border-box;background:#0a0d0a;border:1px solid #2b3a26;color:#d8f0da;border-radius:6px;padding:9px}
button{background:#1f7a3d;color:#fff;border:0;border-radius:6px;padding:9px 16px;font-size:14px;cursor:pointer}
button.ghost{background:#1b241b;color:#a8c8ab}
button.danger{background:#8c2b2b}
.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
.muted{color:#7f957f;font-size:12px}
.err{background:#3a1616;color:#ffb4b4;padding:9px;border-radius:6px;margin-bottom:10px;font-size:13px}
.ok{background:#123a1c;color:#a7f3bd;padding:9px;border-radius:6px;margin-bottom:10px;font-size:13px}
#log{white-space:pre-wrap;font-family:ui-monospace,Consolas,monospace;font-size:12px;height:52vh;overflow:auto;background:#080a08;border:1px solid #22301d;border-radius:8px;padding:10px}
.tag{font-size:11px;background:#22301d;color:#9dc7a3;border-radius:4px;padding:2px 6px}
table{width:100%;border-collapse:collapse;font-size:13px}
td,th{text-align:left;padding:6px 4px;border-bottom:1px solid #1e2a1a}
""".trimIndent()

    private fun shell(title: String, body: String, head: String = ""): String = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title><style>$CSS</style>$head</head><body>
$body
</body></html>
""".trimIndent()

    private fun loginPage(error: String?): String {
        val err = if (error != null) "<div class=err>${esc(error)}</div>" else ""
        return shell(
            "登录 · MC 控制台",
            """
<h2>MC 服务器远程控制台</h2>
<div class=card>
$err
<form method=post action=/login>
<label>用户名</label><input type=text name=username autofocus>
<label>密码</label><input type=password name=password>
<div style="margin-top:14px"><button>登录</button></div>
</form>
<div class=muted style="margin-top:10px">账号密码在本机校验；登录成功后 5 秒内不能重复登录。</div>
</div>
"""
        )
    }

    private fun consolePage(user: WebUser): String {
        val canStart = user.can(WebAuth.PERM_START)
        val canStop = user.can(WebAuth.PERM_STOP)
        val canCmd = user.can(WebAuth.PERM_SEND_CMD)
        val canLog = user.can(WebAuth.PERM_VIEW_LOG)
        val adminLink = if (user.isAdmin || user.can(WebAuth.PERM_MANAGE_USERS)) {
            "<a href=/admin><button class=ghost>用户管理</button></a>"
        } else ""
        val role = if (user.isAdmin) "管理员" else "自定义权限"

        val cmdBlock = if (canCmd) {
            "<div class=row style=\"margin-top:10px\">" +
                "<input type=text id=c placeholder=\"输入指令，例如 op Steve / stop\" style=\"flex:1\">" +
                "<button onclick=\"sendCmd()\">发送</button></div>"
        } else ""

        return shell(
            "控制台",
            """
<div class=row style="justify-content:space-between;margin-bottom:10px">
  <div><b>${esc(user.username)}</b> <span class=tag>$role</span></div>
  <div class=row>
    $adminLink
    <a href=/reset><button class=ghost>重置密码</button></a>
    <a href=/logout><button class=ghost>退出</button></a>
  </div>
</div>
<div class=card>
  <div class=row style="margin-bottom:10px">
    ${if (canStart) "<button onclick=\"act('/api/start')\">启动</button>" else ""}
    ${if (canStop) "<button class=danger onclick=\"act('/api/stop')\">停止</button>" else ""}
    ${if (canLog) "<button class=ghost onclick=\"loadLog()\">刷新</button>" else ""}
    <span id=st class=muted></span>
  </div>
  <div id=log>${if (canLog) "连接中..." else "没有查看日志的权限"}</div>
  $cmdBlock
</div>
<script>
function act(p){fetch(p,{method:'POST'}).then(function(){setTimeout(loadLog,300)})}
function loadLog(){fetch('/api/log').then(function(r){return r.text()}).then(function(t){
  var el=document.getElementById('log'); if(!el) return;
  el.textContent=t; el.scrollTop=el.scrollHeight;
  fetch('/api/status').then(function(r){return r.text()}).then(function(s){
    var st=document.getElementById('st'); if(st) st.textContent = (s=='running'?'● 运行中':'○ 已停止');
  })
})}
function sendCmd(){var i=document.getElementById('c');if(!i)return;var v=i.value.trim();if(!v)return;
 fetch('/api/cmd?c='+encodeURIComponent(v),{method:'POST'}).then(function(){i.value='';setTimeout(loadLog,300)})}
var ci=document.getElementById('c'); if(ci) ci.addEventListener('keydown',function(e){if(e.key=='Enter')sendCmd()});
setInterval(loadLog,2000); loadLog();
</script>
"""
        )
    }

    private fun resetPage(user: WebUser, error: String?): String {
        val err = if (error != null) "<div class=err>${esc(error)}</div>" else ""
        return shell(
            "重置密码",
            """
<h2>重置密码</h2>
<div class=card>
$err
<form method=post action=/reset>
<label>原密码</label><input type=password name=old>
<label>新密码</label><input type=password name=new>
<label>确认新密码</label><input type=password name=confirm>
<div class=muted style="margin-top:8px">至少 8 位，需包含大写字母、小写字母、数字和符号，不支持中文。</div>
<div style="margin-top:14px" class=row><button>保存</button><a href=/><button type=button class=ghost onclick="location.href='/'">返回</button></a></div>
</form>
</div>
"""
        )
    }

    private fun forceResetPage(user: WebUser, error: String? = null): String {
        val err = if (error != null) "<div class=err>${esc(error)}</div>" else ""
        return shell(
            "首次登录需改密",
            """
<h2>首次登录，请先设置新密码</h2>
<div class=card>
$err
<form method=post action=/reset>
<label>新密码</label><input type=password name=new>
<label>确认新密码</label><input type=password name=confirm>
<div class=muted style="margin-top:8px">至少 8 位，需包含大写字母、小写字母、数字和符号，不支持中文。</div>
<div style="margin-top:14px"><button>设置并继续</button></div>
</form>
</div>
"""
        )
    }

    private fun adminPage(user: WebUser, notice: String?, error: String?): String {
        val noticeText = notice
        val errorText = error
        val banner = buildString {
            if (noticeText != null) append("<div class=ok>${esc(noticeText)}</div>")
            if (errorText != null) append("<div class=err>${esc(errorText)}</div>")
        }
        val permBoxes = WebAuth.ALL_PERMISSIONS.entries.joinToString("") { (k, label) ->
            "<label style='display:inline-block;margin-right:12px'><input type=checkbox name=perm_$k> ${esc(label)}</label>"
        }

        val rows = WebAuth.all().joinToString("") { u ->
            val role = if (u.isAdmin) "管理员" else "自定义"
            val state = if (u.enabled) "已启用" else "已停用"
            val toggleLabel = if (u.enabled) "停用" else "启用"
            val toggleVal = if (u.enabled) "0" else "1"
            val perms = if (u.isAdmin) "全部"
            else u.permissions.joinToString("、") { WebAuth.ALL_PERMISSIONS[it] ?: it }.ifEmpty { "无" }
            """
<tr>
  <td>${esc(u.username)}<div class=muted>${esc(perms)}</div></td>
  <td>$role</td>
  <td>$state</td>
  <td class=row>
    <form method=post action=/admin/toggle style="display:inline">
      <input type=hidden name=id value="${esc(u.id)}">
      <input type=hidden name=enabled value="$toggleVal">
      <button class=ghost>$toggleLabel</button>
    </form>
    <form method=post action=/admin/resetpw style="display:inline">
      <input type=hidden name=id value="${esc(u.id)}">
      <button class=ghost>重置密码</button>
    </form>
    <form method=post action=/admin/delete style="display:inline" onsubmit="return confirm('确认删除该用户？')">
      <input type=hidden name=id value="${esc(u.id)}">
      <button class=danger>删除</button>
    </form>
  </td>
</tr>"""
        }

        return shell(
            "用户管理",
            """
<div class=row style="justify-content:space-between;margin-bottom:10px">
  <div><b>用户管理</b> <span class=tag>${esc(user.username)}</span></div>
  <div class=row><a href=/><button class=ghost>返回控制台</button></a><a href=/logout><button class=ghost>退出</button></a></div>
</div>
$banner
<div class=card>
<table>
<tr><th>用户</th><th>角色</th><th>状态</th><th>操作</th></tr>
$rows
</table>
</div>
<div class=card>
<h2>新增用户</h2>
<form method=post action=/admin/add>
<label>用户名（字母/数字/_/-，3~24 位）</label><input type=text name=username>
<label>初始密码</label><input type=password name=password>
<div class=muted>至少 8 位，含大写、小写、数字、符号；不支持中文。用户首次登录会被要求改密。</div>
<label style="margin-top:10px"><input type=checkbox name=isAdmin value=1> 设为管理员（拥有全部权限）</label>
<div style="margin-top:8px">$permBoxes</div>
<div style="margin-top:14px"><button>创建</button></div>
<div class=muted style="margin-top:8px">新建用户默认是「停用」状态，需要在上表里手动启用。</div>
</form>
</div>
"""
        )
    }

    private fun messagePage(title: String, text2: String, link: String): String = shell(
        title,
        """
<h2>${esc(title)}</h2>
<div class=card><div class=ok>${esc(text2)}</div>
<div style="margin-top:12px"><a href="$link"><button>继续</button></a></div></div>
"""
    )

    // ------------------------------------------------------------ 响应

    private fun html(out: OutputStream, body: String) =
        respond(out, 200, "text/html; charset=utf-8", body.toByteArray())

    private fun text(out: OutputStream, code: Int, body: String) =
        respond(out, code, "text/plain; charset=utf-8", body.toByteArray())

    private fun redirect(out: OutputStream, to: String, cookie: String? = null, clearCookie: Boolean = false) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 302 Found\r\n")
        sb.append("Location: $to\r\n")
        if (cookie != null) {
            sb.append("Set-Cookie: $COOKIE=$cookie; Path=/; HttpOnly; SameSite=Lax\r\n")
        }
        if (clearCookie) {
            sb.append("Set-Cookie: $COOKIE=; Path=/; Max-Age=0\r\n")
        }
        sb.append("Content-Length: 0\r\nConnection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    private fun respond(out: OutputStream, code: Int, mime: String, body: ByteArray) {
        val head = "HTTP/1.1 $code OK\r\n" +
                "Content-Type: $mime\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun newToken(): String {
        val b = ByteArray(24)
        rnd.nextBytes(b)
        return b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

/** 解析 `a=1&b=2` 形式的表单/查询串 */
private fun parseForm(s: String): Map<String, String> {
    if (s.isBlank()) return emptyMap()
    val out = HashMap<String, String>()
    s.split('&').forEach { kv ->
        if (kv.isEmpty()) return@forEach
        val k = kv.substringBefore('=')
        val v = if (kv.contains('=')) kv.substringAfter('=') else ""
        runCatching {
            out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
    }
    return out
}
