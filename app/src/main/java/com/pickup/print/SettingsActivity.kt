package com.pickup.print

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pickup.print.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val accessibilitySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshStatus()
        if (PickupCaptureAccessibilityService.isUsable(this)) {
            KeepAliveService.start(this)
            refreshStatus()
            toast("无障碍已就绪，可用快捷开关「取件截屏」")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPrintLayout.setOnClickListener {
            startActivity(Intent(this, PrintLayoutActivity::class.java))
        }
        binding.btnEnableAccessibility.setOnClickListener {
            accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnAddQsTiles.setOnClickListener {
            QuickTileHelper.requestAddTiles(this)
        }
        binding.btnKeepAlive.setOnClickListener { enableKeepAlive() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun enableKeepAlive() {
        KeepAliveService.start(this)
        maybeAskBatteryWhitelist()
        try {
            val miui = Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
            if (miui.resolveActivity(packageManager) != null) {
                startActivity(miui)
            }
        } catch (_: Exception) {
        }
        refreshStatus()
        toast("已开启保活；拍照/截屏请用系统快捷开关")
    }

    private fun maybeAskBatteryWhitelist() {
        if (KeepAliveService.isIgnoringBatteryOptimizations(this)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshStatus() {
        val a11yOn = PickupCaptureAccessibilityService.isEnabledInSettings(this)
        val a11yLive = PickupCaptureAccessibilityService.isRunning()
        binding.tvAccessibilityStatus.text = when {
            a11yLive -> "状态：已开启且在线（截屏可用）"
            a11yOn -> "状态：已开启，重连中…"
            else -> "状态：未开启（快捷开关「取件截屏」必需）"
        }
        binding.tvAccessibilityStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    a11yLive -> R.color.accent
                    a11yOn -> R.color.accent
                    else -> R.color.danger
                }
            )
        )

        binding.tvQuickEntryStatus.text =
            "下拉控制中心可添加「取件拍照 / 取件截屏」；也可点上方按钮请求添加"
        binding.tvQuickEntryStatus.setTextColor(
            ContextCompat.getColor(this, R.color.muted)
        )

        val keepOn = KeepAliveService.running
        val battOk = KeepAliveService.isIgnoringBatteryOptimizations(this)
        binding.tvKeepAliveStatus.text = when {
            keepOn && battOk -> "状态：已开启 · 电池无限制"
            keepOn -> "状态：已开启 · 建议设电池无限制"
            else -> "状态：未开启"
        }
        binding.tvKeepAliveStatus.setTextColor(
            ContextCompat.getColor(this, if (keepOn) R.color.accent else R.color.muted)
        )
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
