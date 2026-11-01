package com.tubo.mcserver

import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务端进程管理（支持多实例并行）。
 *
 * - 每个实例一个独立进程与会话，互不影响；
 * - 启动前自动挑端口：优先用实例 `server.properties` 里的 `server-port`，
 *   被占用（别的实例在跑 / 端口已被监听）就顺延，并写回该实例的配置文件；
 * - 日志出现 `Done (x.xxxs)! For help, type` 时判定启动完成，自动打印连接地址。
 */
object ServerController {

    private const val DEFAULT_PORT = 25565

    class Session(val name: String) {
        @Volatile var process: Process? = null
        @Volatile var port: Int = 0
        @Volatile var ready: Boolean = false
        val logs: MutableList<String> = Collections.synchronizedList(ArrayList())

        val isRunning: Boolean get() = process?.isAlive == true

        fun log(line: String) {
            logs.add(line)
            synchronized(logs) { while (logs.size > 1500) logs.removeAt(0) }
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /** 最近一次启动的实例名（网页控制台等单实例视角用） */
    @Volatile
    var activeInstance: String = ""
        private set

    /** 兼容旧回调：只带一行文本 */
    var onLine: ((String) -> Unit)? = null
    var onExit: ((String, Int) -> Unit)? = null

    // ------------------------------------------------------------ 查询

    fun sessionOf(name: String): Session? = sessions[name]

    fun isRunning(name: String): Boolean = sessions[name]?.isRunning == true

    fun isRunning(): Boolean = sessions.values.any { it.isRunning }

    fun runningNames(): List<String> = sessions.values.filter { it.isRunning }.map { it.name }

    fun portOf(name: String): Int = sessions[name]?.port ?: 0

    fun isReady(name: String): Boolean = sessions[name]?.ready == true

    /** 当前活跃会话的日志；没有活跃会话时合并所有会话 */
    fun recentLog(): String {
        val s = sessions[activeInstance]
        if (s != null) return synchronized(s.logs) { s.logs.joinToString("\n") }
        val all = ArrayList<String>()
        sessions.values.forEach { x -> synchronized(x.logs) { all += x.logs } }
        return all.joinToString("\n")
    }

    /** 往当前活跃会话写一行（网页控制台等外部调用） */
    fun emit(line: String) {
        val s = sessions[activeInstance] ?: return
        pushLine(s, line, checkReady = false)
    }

    // ------------------------------------------------------------ 启动

    fun start(inst: Instance): String {
        val s = sessions.getOrPut(inst.name) { Session(inst.name) }
        if (s.isRunning) return "${inst.name} 已在运行"
        activeInstance = inst.name
        s.ready = false
        s.port = 0
        pushLine(s, "==== 启动 ${inst.name} ====", checkReady = false)

        val dir = inst.dir()

        // 端口：基岩版固定 19132，Java 版自动分配并写回配置
        if (inst.isBedrock()) {
            s.port = 19132
        } else {
            val port = allocatePort(inst)
            s.port = port
            applyPort(inst, port)
            pushLine(s, "分配端口: $port", checkReady = false)
        }

        if (inst.isBedrock()) {
            BedrockManager.unsupportedReason()?.let { return fail(s, it) }
            val bin = inst.serverJar()
            if (!bin.exists()) return fail(s, "缺少 BDS: ${bin.name}，请先下载核心")

            // 准备环境可能要下 30MB 的 rootfs，绝不能占着主线程（start 由按钮直接调用）
            Thread({
                try {
                    val proot = BedrockManager.ensureProot { pushLine(s, "[init] $it", false) }
                    if (proot == null) {
                        fail(s, "proot 准备失败，无法启动基岩版")
                        return@Thread
                    }

                    val rootfs = BedrockManager.ensureRootfs(
                        log = { pushLine(s, "[init] $it", false) },
                        onProgress = { done, total ->
                            if (total > 0 && done % (4 * 1024 * 1024) < 64 * 1024) {
                                pushLine(s, "[init] rootfs ${done * 100 / total}%", false)
                            }
                        }
                    )
                    if (rootfs == null) {
                        fail(s, "glibc rootfs 准备失败，基岩版无法启动（详见上面的日志）")
                        return@Thread
                    }

                    // /storage/emulated/0 是 FUSE，不支持可执行位，
                    // BDS 二进制必须拷到应用私有目录再绑进 rootfs
                    val privateBin = File(App.ctx.filesDir, "bedrock").apply { mkdirs() }
                    val exe = File(privateBin, "bedrock_server")
                    if (!exe.exists() || exe.length() != bin.length()) {
                        pushLine(s, "[init] 拷贝 BDS 到应用私有目录（外部存储无法执行）…", false)
                        bin.copyTo(exe, overwrite = true)
                    }
                    exe.setExecutable(true, false)
                    if (!exe.canExecute()) {
                        fail(s, "BDS 拷贝后仍不可执行：${exe.absolutePath}")
                        return@Thread
                    }

                    val prefix = JdkManager.prefixDir()
                    val loader = BedrockManager.findProotLoader()
                    if (loader == null) {
                        pushLine(s, "[提示] 没找到 proot loader，若启动失败请检查 proot 是否装好", false)
                    } else {
                        val elf = runCatching {
                            val b = ByteArray(4)
                            java.io.FileInputStream(loader).use { it.read(b) }
                            b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte()
                        }.getOrDefault(false)
                        pushLine(
                            s,
                            "[init] proot loader: ${loader.absolutePath} " +
                                    "(${loader.length()}B, elf=$elf, exec=${loader.canExecute()})",
                            false
                        )
                    }
                    val tmpDir = File(App.ctx.filesDir, "proot-tmp").apply { mkdirs() }

                    // usrmerge 软链接链是 proot 能否 exec 的关键，先体检再启动
                    BedrockManager.checkAndRepairRootfs().forEach {
                        pushLine(s, "[init] $it", false)
                    }

                    val cmd = listOf(
                        proot.absolutePath,
                        "-r", rootfs.absolutePath,
                        "-b", dir.absolutePath + ":/server",
                        // 文件级绑定放在目录绑定之后，覆盖掉外部存储里那个不可执行的
                        "-b", exe.absolutePath + ":/server/bedrock_server",
                        // glibc 需要 /dev/urandom 等设备节点，rootfs 里只有空目录
                        "-b", "/dev:/dev",
                        "-b", "/proc:/proc",
                        "-b", "/sys:/sys",
                        "-w", "/server",
                        "/server/bedrock_server"
                    )
                    // proot 是 Termux 的构建，默认路径全指向 /data/data/com.termux，
                    // 我们的 prefix 在应用私有目录，必须用环境变量纠正
                    val baseEnv = mapOf(
                        "PROOT_TMP_DIR" to tmpDir.absolutePath,
                        "PROOT_NO_SECCOMP" to "1",
                        "LD_LIBRARY_PATH" to File(prefix, "lib").absolutePath,
                        "HOME" to "/server"
                    )
                    val withLoader = if (loader != null) {
                        baseEnv + ("PROOT_LOADER" to loader.absolutePath)
                    } else baseEnv

                    // 先自检：跑一次 /bin/sh，把真实错误抓出来，而不是让用户对着
                    // "No such file or directory" 猜
                    var env = withLoader
                    val (ok, out) = BedrockManager.probeProot(proot, rootfs, env)
                    if (!ok) {
                        pushLine(s, "[init] 带 loader 自检失败：${out.take(500)}", false)
                        val (ok2, out2) = BedrockManager.probeProot(proot, rootfs, baseEnv)
                        if (!ok2) {
                            pushLine(s, "[init] 不带 loader 也失败：${out2.take(500)}", false)
                            fail(s, "proot 环境自检未通过，基岩版无法启动（错误见上）")
                            return@Thread
                        }
                        pushLine(s, "[init] 改用不带 PROOT_LOADER 的方式，自检通过", false)
                        env = baseEnv
                    } else {
                        pushLine(s, "[init] proot 自检通过", false)
                    }

                    // 工作目录设成 rootfs：否则 proot 会拿外部路径去 guest 里 chdir 而告警
                    launch(s, cmd, rootfs, env)
                } catch (e: Throwable) {
                    fail(s, "基岩版启动失败：${e.message ?: e.javaClass.simpleName}")
                }
            }, "bedrock-prep").start()

            return "正在准备基岩版运行环境（首次约 30MB，进度见控制台）…"
        }

        // 按 MC 版本自动挑 Java（第 4、12 条）：实例独立指定优先，否则挑满足要求的最低版本
        val override = if (inst.javaOverride) inst.javaHome else ""
        val pick = JdkManager.pickFor(inst.mcVersion, override)
        val java = pick.bin
            ?: return fail(s, "未检测到可用的 Java\n请到「设置 → Java」页下载安装 openjdk-17 / 21 / 25")
        if (!java.exists()) return fail(s, "Java 不可用: ${java.absolutePath}（文件不存在）")
        pick.warning?.let { pushLine(s, "[警告] $it", false) }
        pushLine(
            s,
            "[java] 选用 Java ${pick.major}（该版本要求 Java ${JavaRequirement.requiredMajor(inst.mcVersion)}）",
            false
        )

        // Forge / NeoForge 走安装器生成的 run.sh
        if (inst.isInstallerCore() && inst.installScript().isFile) {
            if (Prefs.autoEula) File(dir, "eula.txt").writeText("eula=true\n")
            val sh = inst.installScript()
            sh.setExecutable(true)
            return launch(s, listOf("sh", sh.absolutePath), dir)
        }

        val jar = inst.serverJar()
        if (!inst.hasServerJar()) {
            return fail(s, "缺少核心文件 ${jar.name}，请到「实例设置 → 服务端核心」里下载")
        }

        if (Prefs.autoEula) File(dir, "eula.txt").writeText("eula=true\n")

        // 实例独立内存设置优先于全局
        val maxMb = if (inst.memoryOverride) inst.maxMemMb else Prefs.maxMem
        val minMb = if (inst.memoryOverride) inst.minMemMb.coerceAtMost(maxMb) else 256
        val nogui = if (inst.noguiOverride) inst.nogui else Prefs.useNogui

        val cmd = ArrayList<String>()
        // 优先级：实例自定义命令 > 设置里的加载器模板 > 内置默认
        val template = LoaderArgs.effective(inst)
        if (inst.argsOverride && inst.customArgs.isNotBlank()) {
            cmd += splitArgs(inst.customArgs)
        } else if (template != null) {
            // 模板里支持 <java> / <jar> 占位符，否则每个实例都要手写一遍绝对路径
            val expanded = template
                .replace("<java>", java.absolutePath)
                .replace("<jar>", jar.absolutePath)
            pushLine(s, "[args] 使用「${inst.loader}」的独立启动命令模板", false)
            cmd += splitArgs(expanded)
        } else {
            cmd += java.absolutePath
            cmd += "-Xmx${maxMb}M"
            cmd += "-Xms${minMb}M"
            cmd += "-Dterminal.jline=false"
            cmd += "-Dfml.queryResult=confirm"
            cmd += "-jar"
            cmd += jar.absolutePath
            if (nogui) cmd += "nogui"
        }
        return launch(s, cmd, dir)
    }

    private fun fail(s: Session, msg: String): String {
        pushLine(s, msg, false)
        return msg
    }

    /** 把自定义命令行切成参数列表，支持双引号包裹 */
    private fun splitArgs(raw: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quote = false
        raw.trim().forEach { c ->
            when {
                c == '"' -> quote = !quote
                c.isWhitespace() && !quote -> {
                    if (sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) }
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun launch(
        s: Session,
        cmd: List<String>,
        dir: File,
        env: Map<String, String> = emptyMap()
    ): String {
        return try {
            val pb = ProcessBuilder(cmd)
            pb.directory(dir)
            pb.redirectErrorStream(true)
            env.forEach { (k, v) -> pb.environment()[k] = v }
            // Termux 构建的 java 需要显式给库搜索路径（libjli.so 在 <jdk>/lib/jli）
            if (!env.containsKey("LD_LIBRARY_PATH")) cmd.firstOrNull()?.let { exe ->
                val home = JdkManager.javaHomeOf(File(exe))
                if (home != null) {
                    val libs = JdkManager.libPathOf(home)
                    if (libs.isNotEmpty()) pb.environment()["LD_LIBRARY_PATH"] = libs
                }
            }
            s.process = pb.start()
            val p = s.process!!
            pushLine(s, "启动中: ${cmd.joinToString(" ")}", false)

            // 提到前台，避免切后台被系统回收
            if (Prefs.keepAlive) ServerService.refresh(App.ctx)

            Thread {
                val buf = ByteArray(4096)
                val ins: InputStream = p.inputStream
                val sb = StringBuilder()
                while (true) {
                    val n = ins.read(buf)
                    if (n == -1) break
                    sb.append(String(buf, 0, n, StandardCharsets.UTF_8))
                    while (sb.contains("\n")) {
                        val idx = sb.indexOf("\n")
                        pushLine(s, sb.substring(0, idx).trimEnd('\r'), checkReady = true)
                        sb.delete(0, idx + 1)
                    }
                }
                if (sb.isNotEmpty()) pushLine(s, sb.toString(), checkReady = true)
                val code = p.waitFor()
                s.ready = false
                pushLine(s, "---- 进程退出，code=$code ----", false)
                onExit?.invoke(s.name, code)
                s.process = null
                // 全部退出就撤掉前台服务
                ServerService.refresh(App.ctx)
            }.apply { isDaemon = true }.start()

            return "启动中（端口 ${s.port}）"
        } catch (e: Exception) {
            pushLine(s, "启动失败: ${e.message}", false)
            return "启动失败: ${e.message}"
        }
    }

    /** 统一的行处理：会话日志 + 控制台缓冲区 + 就绪检测 */
    private fun pushLine(s: Session, line: String, checkReady: Boolean) {
        s.log(line)
        ConsoleStore.append(s.name, line)
        onLine?.invoke(line)

        // 正版验证连不上微软服务时 BDS 会自己退出，这里提前给出可操作的提示
        if (line.contains("Could not connect to Minecraft services")) {
            val tip = "[提示] 正版验证（online-mode）连不上微软服务，基岩版会自行退出。" +
                    "到「实例设置 → 服务端核心」关掉正版验证即可（局域网自建服一般不需要）"
            s.log(tip)
            ConsoleStore.append(s.name, tip)
        }

        if (checkReady && !s.ready && isDoneLine(line)) {
            s.ready = true
            val ip = WebConsole.lanIp()
            val bedrock = InstanceManager.get(s.name)?.isBedrock() == true
            val addr = if (bedrock) {
                "服务器已就绪 → 基岩版地址 $ip:${s.port}（UDP）"
            } else {
                "服务器已就绪 → 连接地址 $ip:${s.port}"
            }
            s.log(addr)
            ConsoleStore.append(s.name, addr)
            if (!bedrock && s.port != DEFAULT_PORT) {
                val tip = "（该实例使用端口 ${s.port}，客户端请在「多人游戏 → 直接连接」里填写 $ip:${s.port}）"
                s.log(tip)
                ConsoleStore.append(s.name, tip)
            }
            if (bedrock) {
                val tip = "（基岩版在游戏内「服务器 → 添加服务器」填 $ip，端口 ${s.port}，协议为 UDP）"
                s.log(tip)
                ConsoleStore.append(s.name, tip)
            }
        }
    }

    /** 启动完成的标志行：Java 版是 `Done (x.xxxs)!`，基岩版是 `Server started.` */
    private fun isDoneLine(line: String): Boolean {
        val l = line.trim()
        if (l.contains("Server started")) return true
        return (l.contains("Done (") && l.contains("For help, type")) ||
                l.contains("Done (") && l.contains("For help")
    }

    // ------------------------------------------------------------ 端口

    /** 读该实例配置的端口 */
    private fun configuredPort(inst: Instance): Int {
        val f = File(inst.dir(), "server.properties")
        if (!f.isFile) return DEFAULT_PORT
        return runCatching {
            f.readLines().firstOrNull { it.trimStart().startsWith("server-port=") }
                ?.substringAfter('=')?.trim()?.toIntOrNull()
        }.getOrNull() ?: DEFAULT_PORT
    }

    /**
     * 挑一个可用端口：
     * 优先实例自身配置，其次顺延；跳过别的实例已占用和系统已监听的端口。
     */
    private fun allocatePort(inst: Instance): Int {
        val usedByOthers = sessions.values
            .filter { it.name != inst.name && it.isRunning && it.port > 0 }
            .map { it.port }
            .toSet()

        var port = configuredPort(inst).coerceIn(1024, 65535)
        var guard = 0
        while (guard < 400) {
            if (port !in usedByOthers && isPortFree(port)) return port
            port = if (port >= 65535) 25565 else port + 1
            guard++
        }
        return port
    }

    private fun isPortFree(port: Int): Boolean = try {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        ss.close()
        true
    } catch (e: Exception) {
        false
    }

    /** 把端口写回 server.properties（保留其它行与注释） */
    private fun applyPort(inst: Instance, port: Int) {
        val f = File(inst.dir(), "server.properties")
        runCatching {
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                f.writeText("server-port=$port\n")
                return@runCatching
            }
            val lines = f.readLines().toMutableList()
            var found = false
            for (i in lines.indices) {
                if (lines[i].trimStart().startsWith("server-port=")) {
                    lines[i] = "server-port=$port"
                    found = true
                    break
                }
            }
            if (!found) lines.add("server-port=$port")
            f.writeText(lines.joinToString("\n").trimEnd() + "\n")
        }
    }

    // ------------------------------------------------------------ 命令 / 停止

    fun sendCommand(name: String, cmd: String) {
        val s = sessions[name] ?: return
        val p = s.process ?: return
        try {
            val os = DataOutputStream(p.outputStream)
            os.write((cmd + "\n").toByteArray(StandardCharsets.UTF_8))
            os.flush()
        } catch (e: Exception) {
            pushLine(s, "写入命令失败: ${e.message}", false)
        }
    }

    /** 兼容旧调用：发给当前活跃会话 */
    fun sendCommand(cmd: String) {
        if (activeInstance.isNotEmpty()) sendCommand(activeInstance, cmd)
    }

    fun stop(name: String) {
        val s = sessions[name] ?: return
        if (!s.isRunning) return
        sendCommand(name, "stop")
        Thread {
            runCatching { Thread.sleep(8000) }
            s.process?.takeIf { it.isAlive }?.destroy()
        }.apply { isDaemon = true }.start()
    }

    /** 停当前活跃实例；没有活跃实例时停全部 */
    fun stop() {
        if (activeInstance.isNotEmpty() && isRunning(activeInstance)) {
            stop(activeInstance)
        } else {
            sessions.values.filter { it.isRunning }.forEach { stop(it.name) }
        }
    }

    fun stopAll() {
        sessions.values.filter { it.isRunning }.forEach { stop(it.name) }
    }
}
