/** 超级岛按钮动作：去打印 / 重新识别。 */
package com.pickup.print.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.pickup.print.MainActivity
import com.pickup.print.PickupCaptureAccessibilityService
import com.pickup.print.QuickActions

class IslandActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_GO_PRINT -> {
                val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
                IslandNotificationHelper.cancel(context)
                val open = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    if (code.isNotBlank() && code != "未识别" && code != "识别失败" && code != "识别中") {
                        putExtra(MainActivity.EXTRA_PICKUP_CODE, code)
                    }
                    putExtra(MainActivity.EXTRA_OPEN_PRINT, true)
                }
                context.startActivity(open)
            }
            ACTION_RECAPTURE -> {
                Toast.makeText(context, "正在重新截屏识别…", Toast.LENGTH_SHORT).show()
                IslandNotificationHelper.showRecognizingIsland(context)
                if (!PickupCaptureAccessibilityService.isUsable(context)) {
                    Toast.makeText(context, "请先开启无障碍截屏服务", Toast.LENGTH_LONG).show()
                    QuickActions.triggerScreenshot(context)
                    return
                }
                PickupCaptureAccessibilityService.requestCapture(context, skipShadeDismiss = true)
            }
        }
    }

    companion object {
        const val ACTION_GO_PRINT = "com.pickup.print.ISLAND_GO_PRINT"
        const val ACTION_RECAPTURE = "com.pickup.print.ISLAND_RECAPTURE"
        const val EXTRA_CODE = "code"
    }
}
