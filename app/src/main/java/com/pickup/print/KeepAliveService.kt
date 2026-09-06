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
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * 前台服务 + 通知栏快捷入口（拍照 / 截屏）。
 * 截屏收起通知面板由无障碍服务完成，此处只发起请求。
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
                openCamera()
                return START_STICKY
            }
            ACTION_SCREENSHOT -> {
                startAsForeground()
                triggerScreenshot()
                return START_STICKY
            }
            else -> {
                startAsForeground()
                running = true
                return START_STICKY
            }
        }
    }

    private fun openCamera() {
        val intent = Intent(this, CameraCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(CameraCaptureActivity.EXTRA_FROM_QUICK, true)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开相机：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerScreenshot() {
        if (!PickupCaptureAccessibilityService.isUsable(this)) {
            Toast.makeText(this, "请先开启无障碍服务后再截屏", Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
            return
        }
        val ok = PickupCaptureAccessibilityService.requestCapture(this)
        if (!ok) {
            Toast.makeText(this, "截屏未就绪，请稍后再试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAsForeground() {
        val channelId = CHANNEL_ID
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "快捷入口",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "通知栏拍照 / 截屏快捷入口"
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
        val camera = PendingIntent.getService(
            this, 5,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_CAMERA),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val screenshot = PendingIntent.getService(
            this, 6,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_SCREENSHOT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 4,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("取件码快捷入口")
            .setContentText("点通知回 App · 或用下方按钮拍照 / 截屏")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .addAction(0, "拍照", camera)
            .addAction(0, "截屏", screenshot)
            .addAction(0, "关闭", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
