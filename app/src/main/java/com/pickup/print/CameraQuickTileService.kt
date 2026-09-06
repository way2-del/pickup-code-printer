package com.pickup.print

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 系统快捷开关：取件拍照 */
class CameraQuickTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.qs_camera_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = getString(R.string.qs_camera_subtitle)
            }
            updateTile()
        }
    }

    override fun onClick() {
        unlockAndRun {
            QuickActions.openCamera(applicationContext)
        }
    }
}
