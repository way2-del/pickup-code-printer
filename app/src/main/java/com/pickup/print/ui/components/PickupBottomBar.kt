package com.pickup.print.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.pickup.print.AppPrefs
import com.pickup.print.ui.utils.rememberAppStyle
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.Months
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主界面底部导航 — LiquidGlass 手机端：Tab 左对齐 + 添加钮右对齐（分开布局）。
 */
@Composable
fun PickupBottomBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    liquidGlassBackdrop: Backdrop?,
    addButton: @Composable () -> Unit = {},
) {
    val hapticFeedback = LocalHapticFeedback.current
    val onSelect: (Int) -> Unit = { idx ->
        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
        onTabSelected(idx)
    }
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
    val appStyle = rememberAppStyle()
    val isLiquidGlass =
        appStyle == AppPrefs.STYLE_LIQUID_GLASS && liquidGlassBackdrop != null

    if (isTablet && isLiquidGlass) {
        Box(modifier = Modifier.fillMaxSize()) {
            LiquidNavigationRail(
                selectedTab = selectedTab,
                onTabSelected = { onSelect(it) },
                backdrop = liquidGlassBackdrop!!,
                isShiftMode = false,
            )
        }
        return
    }

    if (isTablet) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                color = MiuixTheme.colorScheme.surface,
                defaultWindowInsetsPadding = false,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                NavigationRailItem(
                    selected = selectedTab == 0,
                    onClick = { onSelect(0) },
                    icon = MiuixIcons.Album,
                    label = "识别",
                )
                Spacer(modifier = Modifier.height(12.dp))
                NavigationRailItem(
                    selected = selectedTab == 1,
                    onClick = { onSelect(1) },
                    icon = MiuixIcons.Months,
                    label = "记录",
                )
                Spacer(modifier = Modifier.height(12.dp))
                NavigationRailItem(
                    selected = selectedTab == 2,
                    onClick = { onSelect(2) },
                    icon = MiuixIcons.ContactsCircle,
                    label = "我的",
                )
            }
        }
        return
    }

    if (isLiquidGlass) {
        val iconTint = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.8f)
        var liquidSelectedTab by remember { mutableIntStateOf(selectedTab) }
        LaunchedEffect(selectedTab) { liquidSelectedTab = selectedTab }
        // Tab 左对齐、打印钮右对齐（分开占满底栏，避免居中挤在一起）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
        ) {
            LiquidBottomTabs(
                selectedTabIndex = { liquidSelectedTab },
                onTabSelected = { onSelect(it) },
                backdrop = liquidGlassBackdrop!!,
                tabsCount = 3,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .fillMaxWidth(0.63f)
                    .height(56.dp)
            ) {
                LiquidBottomTab({ onSelect(0) }) {
                    Image(
                        modifier = Modifier.size(24.dp),
                        imageVector = MiuixIcons.Album,
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(iconTint)
                    )
                    Text("识别", fontSize = 11.sp, color = iconTint)
                }
                LiquidBottomTab({ onSelect(1) }) {
                    Image(
                        modifier = Modifier.size(24.dp),
                        imageVector = MiuixIcons.Months,
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(iconTint)
                    )
                    Text("记录", fontSize = 11.sp, color = iconTint)
                }
                LiquidBottomTab({ onSelect(2) }) {
                    Image(
                        modifier = Modifier.size(24.dp),
                        imageVector = MiuixIcons.ContactsCircle,
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(iconTint)
                    )
                    Text("我的", fontSize = 11.sp, color = iconTint)
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp),
            ) {
                addButton()
            }
        }
        return
    }

    NavigationBar(
        modifier = Modifier.height(74.dp),
        mode = NavigationBarDisplayMode.IconAndText,
        color = MiuixTheme.colorScheme.surface,
    ) {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = { onSelect(0) },
            icon = MiuixIcons.Album,
            label = "识别",
        )
        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = { onSelect(1) },
            icon = MiuixIcons.Months,
            label = "记录",
        )
        NavigationBarItem(
            selected = selectedTab == 2,
            onClick = { onSelect(2) },
            icon = MiuixIcons.ContactsCircle,
            label = "我的",
        )
    }
}
