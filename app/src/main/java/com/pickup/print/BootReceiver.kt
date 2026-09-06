package com.pickup.print

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** 开机后拉起保活（无障碍由系统自动重连，一般无需用户再开）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        // 稍晚启动，等系统就绪
        Handler(Looper.getMainLooper()).postDelayed({
            KeepAliveService.start(context.applicationContext)
            // 开机后挂快捷通知（拍照 / 截屏）
        }, 8_000)
    }
}
