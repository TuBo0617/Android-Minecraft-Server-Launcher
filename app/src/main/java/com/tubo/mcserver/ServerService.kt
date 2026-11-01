package com.tubo.mcserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 前台服务保活（更新日志第 17 条）。
 *
 * 服务端跑在应用进程里，切到后台后系统随时可能回收 → 服务端跟着停。
 * 用前台服务把进程提到「可见优先级」，通知栏常驻显示正在运行的实例与端口。
 *
 * 可在「设置 → 通用 → 前台服务保活」关掉。
 */
class ServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            ServerController.stopAll()
            return START_NOT_STICKY
        }
        if (!ServerController.isRunning()) {
            stopSelf()
            return START_NOT_STICKY
        }
        updateNotification()
        return START_STICKY
    }

    private fun updateNotification() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val names = ServerController.runningNames()
        val title = when {
            names.isEmpty() -> "服务端运行中"
            names.size == 1 -> "${names[0]} 运行中"
            else -> "${names.size} 个服务端运行中"
        }
        val detail = names.joinToString("  ") { n ->
            val port = ServerController.portOf(n)
            if (port > 0) "$n:$port" else n
        }.ifEmpty { "点击回到控制台" }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAll = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "停止全部", stopAll)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "服务端运行状态", NotificationManager.IMPORTANCE_LOW)
        ch.description = "显示正在运行的服务器实例与端口"
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL_ID = "mcserver_running"
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP_ALL = "com.tubo.mcserver.STOP_ALL"

        /** 启动/刷新前台服务；失败不影响服务端本身 */
        fun refresh(ctx: Context) {
            if (!ServerController.isRunning()) {
                stop(ctx)
                return
            }
            runCatching {
                val i = Intent(ctx, ServerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ServerService::class.java)) }
        }
    }
}
