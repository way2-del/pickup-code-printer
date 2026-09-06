package com.pickup.print

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.util.concurrent.Executor

/**
 * 用无障碍 takeScreenshot 截取当前前台页面。
 * 截屏前先通过全局动作收起通知下拉面板，避免截到通知栏。
 */
class PickupCaptureAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private var capturePending = false

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CAPTURE) {
                beginCaptureAfterShadeCollapsed()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val filter = IntentFilter(ACTION_CAPTURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(captureReceiver, filter)
        }
        Log.i(TAG, "a11y connected")
        KeepAliveService.start(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        try {
            unregisterReceiver(captureReceiver)
        } catch (_: Exception) {
        }
        instance = null
        super.onDestroy()
    }

    /**
     * 先收起通知下拉面板，确认收起后再截屏。
     */
    private fun beginCaptureAfterShadeCollapsed() {
        if (capturePending) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "无障碍截屏需要 Android 11+", Toast.LENGTH_LONG).show()
            CaptureBus.emitFailure("需要 Android 11+")
            return
        }
        capturePending = true
        dismissNotificationShade()
        // 再补一次，部分机型第一次只缩一半
        mainHandler.postDelayed({
            dismissNotificationShade()
            mainHandler.postDelayed({
                takeScreenshotNow()
            }, WAIT_AFTER_DISMISS_MS)
        }, FIRST_DISMISS_GAP_MS)
    }

    private fun dismissNotificationShade() {
        var ok = false
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                ok = performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                Log.i(TAG, "DISMISS_NOTIFICATION_SHADE=$ok")
            } catch (e: Exception) {
                Log.w(TAG, "dismiss shade failed", e)
            }
        }
        if (!ok) {
            // 旧系统 / 个别 ROM：用返回键尝试关掉面板
            try {
                ok = performGlobalAction(GLOBAL_ACTION_BACK)
                Log.i(TAG, "GLOBAL_ACTION_BACK fallback=$ok")
            } catch (_: Exception) {
            }
        }
        // 再尝试 StatusBarManager（无障碍进程里权限通常更够用）
        try {
            val statusBarService = getSystemService("statusbar")
            if (statusBarService != null) {
                val clazz = Class.forName("android.app.StatusBarManager")
                for (name in listOf("collapsePanels", "collapse")) {
                    try {
                        clazz.getMethod(name).invoke(statusBarService)
                        Log.i(TAG, "StatusBarManager.$name ok")
                        break
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "StatusBarManager collapse failed", e)
        }
        try {
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (_: Exception) {
        }
    }

    private fun takeScreenshotNow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            capturePending = false
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    capturePending = false
                    val hardware = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware.close()
                    if (bitmap == null) {
                        CaptureBus.emitFailure("截屏位图为空")
                        return
                    }
                    lastBitmap = bitmap
                    CaptureBus.emitSuccess(bitmap)
                    AppLauncher.bringMainToFront(this@PickupCaptureAccessibilityService, fromCapture = true)
                }

                override fun onFailure(errorCode: Int) {
                    capturePending = false
                    val msg = when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "截屏内部错误"
                        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "无障碍权限不足"
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截屏太频繁，请稍后再试"
                        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "显示屏无效"
                        else -> "截屏失败 code=$errorCode"
                    }
                    CaptureBus.emitFailure(msg)
                    Toast.makeText(this@PickupCaptureAccessibilityService, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    companion object {
        private const val TAG = "PickupA11y"
        const val ACTION_CAPTURE = "com.pickup.print.ACTION_A11Y_CAPTURE"
        private const val FIRST_DISMISS_GAP_MS = 180L
        /** 通知面板收起动画约 250–400ms，再多留余量 */
        private const val WAIT_AFTER_DISMISS_MS = 650L

        @Volatile
        var instance: PickupCaptureAccessibilityService? = null
            private set

        @Volatile
        var lastBitmap: Bitmap? = null

        fun isRunning(): Boolean = instance != null

        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, PickupCaptureAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it)?.equals(expected) == true ||
                    it.contains("${context.packageName}/.PickupCaptureAccessibilityService") ||
                    it.contains("${context.packageName}/${PickupCaptureAccessibilityService::class.java.name}")
            }
        }

        fun isUsable(context: Context): Boolean =
            isRunning() || isEnabledInSettings(context)

        fun requestCapture(context: Context): Boolean {
            if (instance == null) {
                if (isEnabledInSettings(context)) {
                    Toast.makeText(context, "无障碍正在重连，请再点一次截取", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                }
                return false
            }
            context.sendBroadcast(Intent(ACTION_CAPTURE).setPackage(context.packageName))
            return true
        }
    }
}
