package com.pickup.print

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 系统快捷开关：取件截屏 */
class ScreenshotQuickTileService : TileService() {

    override fun onStartListening() {
        val ready = PickupCaptureAccessibilityService.isUsable(this)
        qsTile?.apply {
            state = if (ready) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            label = getString(R.string.qs_screenshot_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (ready) {
                    getString(R.string.qs_screenshot_subtitle)
                } else {
                    getString(R.string.qs_screenshot_need_a11y)
                }
            }
            updateTile()
        }
    }

    override fun onClick() {
        unlockAndRun {
            QuickActions.triggerScreenshot(applicationContext)
        }
    }
}
