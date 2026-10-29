package com.tubo.mcserver

import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

object ServerController {
    var process: Process? = null
        private set
    var onLine: ((String) -> Unit)? = null
    var onExit: ((Int) -> Unit)? = null

    private val logBuffer = java.util.Collections.synchronizedList(java.util.ArrayList<String>())
    fun recentLog(): String = synchronized(logBuffer) { logBuffer.joinToString("\n") }

    fun emit(line: String) {
        logBuffer.add(line)
        if (logBuffer.size > 600) logBuffer.removeAt(0)
        onLine?.invoke(line)
    }

    fun isRunning(): Boolean = process?.isAlive == true

    fun start(inst: Instance): String {
        if (isRunning()) return "服务器已在运行"
        val dir = inst.dir()

        if (inst.isBedrock()) {
            val bin = inst.serverJar()
            if (!bin.exists()) return "缺少 BDS: ${bin.name}，请先下载核心"
            val proot = BedrockManager.ensureProot { emit("[init] $it") }
                ?: return "proot 准备失败，无法启动基岩版"
            val rootfs = File(JdkManager.prefixDir(), "glibc-rootfs")
            val cmd = if (rootfs.exists()) {
                listOf(proot.absolutePath, "-r", rootfs.absolutePath,
                    "-b", dir.absolutePath + ":/server",
                    "/server/bedrock_server")
            } else {
                emit("[提示] 未找到 glibc rootfs (${rootfs.absolutePath})。")
                emit("基岩版 BDS 是 glibc 二进制，需在 proot 内挂载一个 Debian/Ubuntu rootfs 才能运行。")
                emit("当前先尝试直接执行，大概率因缺少 glibc 而失败。")
                listOf(bin.absolutePath)
            }
            return launchProcess(cmd, dir)
        }

        val java = JdkManager.activeBin()
            ?: return "未检测到可用的 Java\n请到「Java」页下载安装 openjdk-17 / 21 / 25"
        if (!java.exists()) return "Java 不可用: ${java.absolutePath}（文件不存在）"
        val jar = inst.serverJar()
        if (!jar.exists()) return "缺少核心文件 ${jar.name}，请先到「下载核心」页下载"

        if (Prefs.autoEula) File(dir, "eula.txt").writeText("eula=true\n")

        val cmd = ArrayList<String>()
        cmd += java.absolutePath
        cmd += "-Xmx${Prefs.maxMem}M"
        cmd += "-Xms256M"
        cmd += "-Dterminal.jline=false"
        cmd += "-Dfml.queryResult=confirm"
        cmd += "-jar"
        cmd += jar.absolutePath
        if (Prefs.useNogui) cmd += "nogui"
        return launchProcess(cmd, dir)
    }

    private fun launchProcess(cmd: List<String>, dir: File): String {
        return try {
            val pb = ProcessBuilder(cmd)
            pb.directory(dir)
            pb.redirectErrorStream(true)
            process = pb.start()
            val p = process!!
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
                        emit(sb.substring(0, idx).trimEnd('\r'))
                        sb.delete(0, idx + 1)
                    }
                }
                if (sb.isNotEmpty()) emit(sb.toString())
                val code = p.waitFor()
                emit("---- 进程退出，code=$code ----")
                onExit?.invoke(code)
                process = null
            }.start()
            "启动中: ${cmd.joinToString(" ")}"
        } catch (e: Exception) {
            "启动失败: ${e.message}"
        }
    }

    fun sendCommand(cmd: String) {
        val p = process ?: return
        try {
            val os = DataOutputStream(p.outputStream)
            os.write((cmd + "\n").toByteArray(StandardCharsets.UTF_8))
            os.flush()
        } catch (e: Exception) {
            onLine?.invoke("写入命令失败: ${e.message}")
        }
    }

    fun stop() {
        if (!isRunning()) return
        sendCommand("stop")
        Thread {
            try { Thread.sleep(6000) } catch (_: Exception) {}
            process?.takeIf { it.isAlive }?.destroy()
        }.start()
    }
}
