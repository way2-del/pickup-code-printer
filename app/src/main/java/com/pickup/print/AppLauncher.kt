package com.pickup.print

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object AppLauncher {

    private const val CHANNEL = "bring_front"
    private const val NOTI_ID = 10087

    /** 尽量把主界面拉回前台（兼容小米后台限制）。 */
    fun bringMainToFront(context: Context, fromCapture: Boolean = true) {
        val appCtx = context.applicationContext
        try {
            val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            am.appTasks?.firstOrNull()?.moveToFront()
        } catch (_: Exception) {
        }

        val intent = Intent(appCtx, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            if (fromCapture) putExtra(MainActivity.EXTRA_FROM_CAPTURE, true)
        }
        try {
            appCtx.startActivity(intent)
        } catch (_: Exception) {
            postFallbackNotification(appCtx, intent)
        }

        // 再补一次，规避部分机型第一次被拦截
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                appCtx.startActivity(intent)
            } catch (_: Exception) {
                postFallbackNotification(appCtx, intent)
            }
        }, 350)
    }

    private fun postFallbackNotification(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "识别完成", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pi = PendingIntent.getActivity(
            context, 88, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("取件码已截取")
            .setContentText("点此返回 App 查看识别结果")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTI_ID, n)
        } catch (_: Exception) {
        }
    }
}
