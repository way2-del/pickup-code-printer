package com.pickup.print.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.dothantech.printer.IDzPrinter.PrinterAddress
import com.kyant.backdrop.Backdrop
import com.pickup.print.KeepAliveService
import com.pickup.print.MainActivity
import com.pickup.print.PickupCaptureAccessibilityService
import com.pickup.print.PrinterManager
import com.pickup.print.ui.basic.OverlayDropdownMenu
import com.pickup.print.ui.basic.SharedScrollBehavior
import com.pickup.print.ui.utils.PageListDefaults
import com.pickup.print.ui.utils.overScrollVertical
import com.pickup.print.ui.utils.pageListContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 「我的」：打印机连接 + 原设置页能力（两类排版分开）。 */
@Composable
fun MineScreen(
    scrollBehavior: SharedScrollBehavior?,
    liquidBackdrop: Backdrop?,
    status: String,
    devices: List<PrinterAddress>,
    defaultKey: String?,
    connectedAddress: PrinterAddress?,
    onRefreshPrinters: () -> Unit,
    onDisconnect: () -> Unit,
    onConnect: (PrinterAddress) -> Unit,
    onSetDefault: (PrinterAddress) -> Unit,
    onPrintLayoutPickup: () -> Unit,
    onPrintLayoutTea: () -> Unit,
    onAccessibility: () -> Unit,
    onAddQsTiles: () -> Unit,
    onKeepAlive: () -> Unit,
    onPreferences: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
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

    val printerItems = remember(devices, defaultKey, connectedAddress) {
        if (devices.isEmpty()) {
            listOf(
                DropdownItem(
                    text = "暂无设备",
                    summary = "点下方刷新搜索",
                    selected = false,
                    enabled = false,
                    onClick = null,
                )
            )
        } else {
            devices.map { device ->
                val key = MainActivity.printerKey(device)
                val name = PrinterManager.displayName(device)
                val isDef = key == defaultKey
                val isConn = connectedAddress != null && MainActivity.printerKey(connectedAddress) == key
                DropdownItem(
                    text = if (isDef) "★ $name" else name,
                    summary = buildString {
                        append(device.macAddress ?: "")
                        if (isConn) append(" · 已连接")
                        if (isDef) append(" · 默认")
                    },
                    selected = isConn || (connectedAddress == null && isDef),
                    onClick = { onConnect(device) },
                )
            }
        }
    }
    val printerEntry = remember(printerItems) { DropdownEntry(items = printerItems) }

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
                    text = "打印机",
                    modifier = Modifier.offset(x = PageListDefaults.SmallTitleOffsetX)
                )
                Card(cornerRadius = 20.dp, insideMargin = PaddingValues(0.dp)) {
                    OverlayDropdownMenu(
                        title = "选择打印机",
                        summary = status,
                        entry = printerEntry,
                        liquidGlassBackdrop = liquidBackdrop,
                        collapseOnSelection = true,
                        bottomAction = {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    text = "刷新",
                                    onClick = onRefreshPrinters,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    text = "断开",
                                    onClick = onDisconnect,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    text = "设默认",
                                    onClick = {
                                        val addr = connectedAddress ?: devices.firstOrNull() ?: return@TextButton
                                        onSetDefault(addr)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    )
                }

                SmallTitle(
                    text = "打印编排",
                    modifier = Modifier.offset(x = PageListDefaults.SmallTitleOffsetX)
                )
                Card(
                    cornerRadius = 20.dp,
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        ArrowPreference(
                            title = "取件码排版",
                            summary = "上门取件码纸张与元素",
                            onClick = onPrintLayoutPickup
                        )
                        ArrowPreference(
                            title = "奶茶杯贴排版",
                            summary = "杯贴复原模板 · 与取件码分开",
                            onClick = onPrintLayoutTea
                        )
                    }
                }

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
