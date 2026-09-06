package com.pickup.print

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

object SystemBars {
    /**
     * Android 15+ 强制边到边：给根布局补上状态栏 / 导航栏 inset，避免顶栏遮挡。
     */
    fun apply(activity: Activity, root: View, alsoBottom: Boolean = true) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowCompat.getInsetsController(activity.window, root)?.isAppearanceLightStatusBars = true
        WindowCompat.getInsetsController(activity.window, root)?.isAppearanceLightNavigationBars = true

        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = initialLeft + bars.left,
                top = initialTop + bars.top,
                right = initialRight + bars.right,
                bottom = if (alsoBottom) initialBottom + bars.bottom else initialBottom
            )
            insets
        }
        root.requestApplyInsets()
    }
}
