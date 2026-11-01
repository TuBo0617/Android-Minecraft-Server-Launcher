package com.tubo.mcserver

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager

/**
 * 后台保活增强（更新日志第 17 条）。
 *
 * 两层手段：
 * 1. **免 root**：申请电池优化白名单（系统会放行后台运行与网络）；
 * 2. **有 root**：额外用 `appops` / `deviceidle` 把应用钉在活跃状态，
 *    锁屏后网页控制台也能继续响应。
 *
 * 所有命令都带超时，拿不到 root 就安静地失败，绝不阻塞主流程。
 */
object PowerKeeper {

    /** 探测 root：能跑通 `su -c id` 且输出含 uid=0 */
    fun hasRoot(): Boolean = runCatching {
        exec("su", "-c", "id", timeoutMs = 2500).contains("uid=0")
    }.getOrDefault(false)

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }.getOrDefault(false)
    }

    /** 弹系统白名单授权框；没有 root 时的主要手段 */
    fun requestIgnoreBattery(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 23) return false
        val intent = Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS").apply {
            data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * 有 root 时的一键加固。返回每条命令的结果，供界面展示。
     *
     * - `RUN_IN_BACKGROUND` / `RUN_ANY_IN_BACKGROUND`：允许后台运行
     * - `deviceidle whitelist`：加入 Doze 白名单
     * - `set-standby-bucket active`：把 App Standby 桶设为活跃
     */
    fun hardenWithRoot(): List<String> {
        val pkg = runCatching { App.ctx.packageName }.getOrDefault("")
        if (pkg.isEmpty()) return listOf("拿不到包名")
        val cmds = listOf(
            "cmd appops set $pkg RUN_IN_BACKGROUND allow",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "dumpsys deviceidle whitelist +$pkg",
            "am set-standby-bucket $pkg active"
        )
        return cmds.map { c ->
            val out = runCatching { exec("su", "-c", c, timeoutMs = 5000) }
                .getOrElse { "失败：${it.message ?: it.javaClass.simpleName}" }
            "$c → ${out.ifBlank { "ok" }.trim().take(120)}"
        }
    }

    /** 简单的外部命令执行，带超时；超时直接 destroy */
    private fun exec(vararg cmd: String, timeoutMs: Long): String {
        val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
        val p = pb.start()
        val out = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { out.appendLine(it) }
                }
            }
        }.apply { isDaemon = true; start() }

        val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { p.destroyForcibly() }
            return ""
        }
        reader.join(500)
        return out.toString()
    }
}
