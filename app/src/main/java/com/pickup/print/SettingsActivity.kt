package com.pickup.print

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pickup.print.ui.basic.SharedScrollBehavior
import com.pickup.print.ui.setPickupContent
import com.pickup.print.ui.utils.PageListDefaults
import com.pickup.print.ui.utils.overScrollVertical
import com.pickup.print.ui.utils.pageListContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

class SettingsActivity : ComponentActivity() {

    private val accessibilitySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (PickupCaptureAccessibilityService.isUsable(this)) {
            KeepAliveService.start(this)
            toast("无障碍已就绪，可用快捷开关「取件截屏」")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setPickupContent(title = { "设置" }, showBack = true) { scrollBehavior, _ ->
            SettingsScreen(
                scrollBehavior = scrollBehavior,
                onPrintLayout = {
                    startActivity(
                        Intent(this, PrintLayoutActivity::class.java)
                            .putExtra(PrintLayoutActivity.EXTRA_CATEGORY, PrintCategory.PICKUP.id)
                    )
                },
                onAccessibility = {
                    accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onAddQsTiles = { QuickTileHelper.requestAddTiles(this) },
                onKeepAlive = { enableKeepAlive() },
                onPreferences = {
                    startActivity(Intent(this, PreferenceSettingsActivity::class.java))
                },
                onAbout = {
                    startActivity(Intent(this, AboutActivity::class.java))
                },
            )
        }
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun SettingsScreen(
    scrollBehavior: SharedScrollBehavior?,
    onPrintLayout: () -> Unit,
    onAccessibility: () -> Unit,
    onAddQsTiles: () -> Unit,
    onKeepAlive: () -> Unit,
    onPreferences: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val a11ySummary = remember(refreshTick) {
        val a11yOn = PickupCaptureAccessibilityService.isEnabledInSettings(context)
        val a11yLive = PickupCaptureAccessibilityService.isRunning()
        when {
            a11yLive -> "已开启且在线（截屏可用）"
            a11yOn -> "已开启，重连中…"
            else -> "未开启（快捷开关「取件截屏」必需）"
        }
    }
    val keepSummary = remember(refreshTick) {
        val keepOn = KeepAliveService.running
        val battOk = KeepAliveService.isIgnoringBatteryOptimizations(context)
        when {
            keepOn && battOk -> "已开启 · 电池无限制"
            keepOn -> "已开启 · 建议设电池无限制"
            else -> "未开启"
        }
    }

    Scaffold(topBar = {}) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .scrollEndHaptic()
                .then(
                    if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                    else Modifier
                ),
            contentPadding = pageListContentPadding(scrollBehavior, paddingValues),
            verticalArrangement = Arrangement.spacedBy(PageListDefaults.SectionSpacing)
        ) {
            item {
                SmallTitle(
                    text = "功能",
                    modifier = Modifier.offset(x = PageListDefaults.SmallTitleOffsetX)
                )
                Card(
                    cornerRadius = 20.dp,
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        ArrowPreference(
                            title = "取件码打印编排",
                            summary = "上门取件码纸张、字号与元素（杯贴请到「我的」）",
                            onClick = onPrintLayout
                        )
                        ArrowPreference(
                            title = "开启无障碍（截屏用）",
                            summary = a11ySummary,
                            onClick = onAccessibility
                        )
                        ArrowPreference(
                            title = "添加系统快捷开关",
                            summary = "取件拍照 / 取件截屏",
                            onClick = onAddQsTiles
                        )
                        ArrowPreference(
                            title = "开启保活通知",
                            summary = keepSummary,
                            onClick = onKeepAlive
                        )
                    }
                }
            }

            item {
                SmallTitle(
                    text = "其他",
                    modifier = Modifier.offset(x = PageListDefaults.SmallTitleOffsetX)
                )
                Card(
                    cornerRadius = 20.dp,
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        ArrowPreference(
                            title = "应用偏好设置",
                            summary = "主题外观、隐藏后台",
                            onClick = onPreferences
                        )
                        ArrowPreference(
                            title = "关于应用",
                            summary = "版本信息与致谢",
                            onClick = onAbout
                        )
                    }
                }
            }
        }
    }
}
