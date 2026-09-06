package com.pickup.print

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executor
import java.util.function.Consumer

object QuickTileHelper {

    fun requestAddTiles(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) {
            val sbm = context.getSystemService(StatusBarManager::class.java)
            if (sbm == null) {
                showManualHint(context)
                return
            }
            val executor = Executor { runnable ->
                Handler(Looper.getMainLooper()).post(runnable)
            }
            val result = Consumer<Int> { /* TILE_ADD_REQUEST_* */ }
            requestOne(
                context, sbm, executor, result,
                CameraQuickTileService::class.java,
                context.getString(R.string.qs_camera_label),
                R.drawable.ic_qs_camera
            )
            Handler(Looper.getMainLooper()).postDelayed({
                requestOne(
                    context, sbm, executor, result,
                    ScreenshotQuickTileService::class.java,
                    context.getString(R.string.qs_screenshot_label),
                    R.drawable.ic_qs_screenshot
                )
            }, 900)
            Toast.makeText(context, "请在系统弹窗中确认添加快捷开关", Toast.LENGTH_LONG).show()
        } else {
            showManualHint(context)
        }
    }

    private fun requestOne(
        context: Context,
        sbm: StatusBarManager,
        executor: Executor,
        result: Consumer<Int>,
        clazz: Class<*>,
        label: String,
        iconRes: Int
    ) {
        try {
            sbm.requestAddTileService(
                ComponentName(context, clazz),
                label,
                Icon.createWithResource(context, iconRes),
                executor,
                result
            )
        } catch (e: Exception) {
            Toast.makeText(context, "添加失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showManualHint(context: Context) {
        Toast.makeText(
            context,
            "请下拉状态栏 → 编辑 → 添加「取件拍照」「取件截屏」",
            Toast.LENGTH_LONG
        ).show()
        try {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }
}
