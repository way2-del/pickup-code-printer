package com.pickup.print

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * 仅负责悬浮球 UI；真正截屏交给无障碍 takeScreenshot。
 */
class FloatingCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var capturing = false
    private var pollCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startAsForeground()
                showBubble()
                KeepAliveService.start(this)
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val channelId = "capture_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "悬浮截屏", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("取件码悬浮截屏")
            .setContentText("点悬浮球截取当前页面（无障碍截屏）")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPi)
            .addAction(0, "关闭", stopPi)
            .setOngoing(true)
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
    }

    private fun showBubble() {
        if (bubbleView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_capture_bubble, null)
        bubbleView = view

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 280
        }

        var downX = 0f
        var downY = 0f
        var paramX = 0
        var paramY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    paramX = params.x
                    paramY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = paramX + dx
                    params.y = paramY + dy
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) captureViaAccessibility()
                    true
                }
                else -> false
            }
        }

        view.findViewById<View>(R.id.btnCloseOverlay)?.setOnClickListener { stopSelf() }
        windowManager?.addView(view, params)
        Toast.makeText(this, "悬浮球已开启：切到取件码页后点一下截取", Toast.LENGTH_LONG).show()
    }

    private fun captureViaAccessibility() {
        if (capturing) return
        if (!PickupCaptureAccessibilityService.isUsable(this)) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        capturing = true
        pollCount = 0
        PickupCaptureAccessibilityService.lastBitmap = null
        bubbleView?.visibility = View.INVISIBLE
        mainHandler.postDelayed({
            val ok = PickupCaptureAccessibilityService.requestCapture(this)
            if (!ok) {
                bubbleView?.visibility = View.VISIBLE
                capturing = false
                return@postDelayed
            }
            pollForResult()
        }, 150)
    }

    private fun pollForResult() {
        pollCount++
        if (PickupCaptureAccessibilityService.lastBitmap != null) {
            bubbleView?.visibility = View.VISIBLE
            capturing = false
            Toast.makeText(this, "已截屏，正在返回…", Toast.LENGTH_SHORT).show()
            AppLauncher.bringMainToFront(this, fromCapture = true)
            return
        }
        if (pollCount >= 20) { // ~3s
            bubbleView?.visibility = View.VISIBLE
            capturing = false
            Toast.makeText(this, "截屏超时，请再试一次", Toast.LENGTH_SHORT).show()
            return
        }
        mainHandler.postDelayed({ pollForResult() }, 150)
    }

    override fun onDestroy() {
        try {
            bubbleView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        bubbleView = null
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.pickup.print.FLOAT_START"
        const val ACTION_STOP = "com.pickup.print.FLOAT_STOP"
        private const val NOTIFICATION_ID = 10086

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingCaptureService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, FloatingCaptureService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
