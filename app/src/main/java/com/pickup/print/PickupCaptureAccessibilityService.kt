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
 * 比 MediaProjection 更不容易被「录屏隐私保护」糊成马赛克。
 */
class PickupCaptureAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CAPTURE) {
                requestScreenshot()
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
        // 无障碍重连后顺带确保保活服务在
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

    private fun requestScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "无障碍截屏需要 Android 11+", Toast.LENGTH_LONG).show()
            CaptureBus.emitFailure("需要 Android 11+")
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
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
                    // 截屏成功立刻回 App（不等悬浮窗超时）
                    AppLauncher.bringMainToFront(this@PickupCaptureAccessibilityService, fromCapture = true)
                }

                override fun onFailure(errorCode: Int) {
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

        @Volatile
        var instance: PickupCaptureAccessibilityService? = null
            private set

        @Volatile
        var lastBitmap: Bitmap? = null

        fun isRunning(): Boolean = instance != null

        /** 系统设置里是否已开启（即使进程刚被杀、服务尚未重连，也算已开启）。 */
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
