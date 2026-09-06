package com.pickup.print

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

/** 拍照 / 截屏快捷动作（通知栏与系统快捷开关共用）。 */
object QuickActions {

    fun openCamera(context: Context) {
        val intent = Intent(context, CameraCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(CameraCaptureActivity.EXTRA_FROM_QUICK, true)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开相机：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerScreenshot(context: Context) {
        if (!PickupCaptureAccessibilityService.isUsable(context)) {
            Toast.makeText(context, "请先开启无障碍服务后再截屏", Toast.LENGTH_LONG).show()
            try {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
            return
        }
        val ok = PickupCaptureAccessibilityService.requestCapture(context)
        if (!ok) {
            Toast.makeText(context, "截屏未就绪，请稍后再试", Toast.LENGTH_SHORT).show()
        }
    }
}
