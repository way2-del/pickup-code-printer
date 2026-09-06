package com.pickup.print

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * 前台保活服务。拍照 / 截屏请优先用系统快捷开关；
 * 通知仅作保活与回 App 入口（避免折叠后再点 Action）。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CAMERA -> {
                startAsForeground()
                QuickActions.openCamera(this)
                return START_STICKY
            }
            ACTION_SCREENSHOT -> {
                startAsForeground()
                QuickActions.triggerScreenshot(this)
                return START_STICKY
            }
            else -> {
                startAsForeground()
                running = true
                return START_STICKY
            }
        }
    }

    private fun startAsForeground() {
        val channelId = CHANNEL_ID
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "保活通知",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持后台可用；拍照/截屏请用系统快捷开关"
                    setShowBadge(false)
                }
            )
        }

        val openApp = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 4,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("取件码打印运行中")
            .setContentText("拍照/截屏请用下拉快捷开关 · 点此回 App")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .addAction(0, "关闭", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        running = true
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.pickup.print.KEEPALIVE_STOP"
        const val ACTION_CAMERA = "com.pickup.print.KEEPALIVE_CAMERA"
        const val ACTION_SCREENSHOT = "com.pickup.print.KEEPALIVE_SCREENSHOT"
        private const val CHANNEL_ID = "quick_entry"
        private const val NOTIFICATION_ID = 10088

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP)
            )
        }

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val pm = context.getSystemService(PowerManager::class.java) ?: return true
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    }
}
