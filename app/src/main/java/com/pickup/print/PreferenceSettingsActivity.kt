package com.pickup.print

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.pickup.print.island.IslandNotificationHelper
import com.pickup.print.shizuku.ShizukuManager
import com.pickup.print.ui.basic.OverlayDropdownMenu
import com.pickup.print.ui.basic.SharedScrollBehavior
import com.pickup.print.ui.setPickupContent
import com.pickup.print.ui.utils.PageListDefaults
import com.pickup.print.ui.utils.applyThemeAwareSystemBars
import com.pickup.print.ui.utils.overScrollVertical
import com.pickup.print.ui.utils.pageListContentPadding
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 应用偏好设置（对齐课表偏好：主题 + 应用风格 + 隐藏后台 + 超级岛）。 */
class PreferenceSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setPickupContent(title = { "应用偏好设置" }, showBack = true) { scrollBehavior, liquidBackdrop ->
            PreferenceSettingsScreen(
                scrollBehavior = scrollBehavior,
                liquidGlassBackdrop = liquidBackdrop,
            )
        }
    }
}

@Composable
fun PreferenceSettingsScreen(
    scrollBehavior: SharedScrollBehavior?,
    liquidGlassBackdrop: Backdrop? = null,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var themeMode by remember { mutableStateOf(AppPrefs.getThemeMode(context)) }
    var appStyle by remember { mutableStateOf(AppPrefs.getAppStyle(context)) }
    var hideBackground by remember { mutableStateOf(AppPrefs.isHideBackground(context)) }
    val islandSupported = remember { IslandNotificationHelper.isIslandSupported(context) }
    var islandEnabled by remember { mutableStateOf(AppPrefs.isIslandEnabled(context)) }
    var islandExpandGlow by remember { mutableStateOf(AppPrefs.isIslandExpandGlowEnabled(context)) }
    var shizukuRunning by remember { mutableStateOf(false) }
    var shizukuAuthorized by remember { mutableStateOf(false) }
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    LaunchedEffect(islandSupported, islandEnabled) {
        if (islandSupported) {
            shizukuRunning = ShizukuManager.isShizukuRunning()
            shizukuAuthorized = ShizukuManager.checkSelfPermission()
        }
    }

    fun applyTheme(mode: String) {
        themeMode = mode
        AppPrefs.setThemeMode(context, mode)
        activity?.applyThemeAwareSystemBars()
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
                    text = "外观",
                    modifier = Modifier.offset(x = PageListDefaults.SmallTitleOffsetX)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ThemeSelectionBox(
                        label = "浅色模式",
                        drawableRes = R.drawable.theme_preview_light,
                        selected = !effectiveDark,
                        onClick = onClick@{
                            if (themeMode == "system" && !systemDark) return@onClick
                            applyTheme("light")
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeSelectionBox(
                        label = "深色模式",
                        drawableRes = R.drawable.theme_preview_night,
                        selected = effectiveDark,
                        onClick = onClick@{
                            if (themeMode == "system" && systemDark) return@onClick
                            applyTheme("dark")
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Card(
                    cornerRadius = 20.dp,
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    SwitchPreference(
                        title = "自动切换深色模式",
                        checked = themeMode == "system",
                        onCheckedChange = { on ->
                            if (on) applyTheme("system") else applyTheme("light")
                        }
                    )
                }
            }

            item {
                Card(
                    cornerRadius = 20.dp,
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    val appStyleEntry = DropdownEntry(
                        items = listOf(
                            DropdownItem(
                                text = "HyperOS3",
                                selected = appStyle == AppPrefs.STYLE_HYPEROS3,
                                onClick = {
                                    appStyle = AppPrefs.STYLE_HYPEROS3
                                    AppPrefs.setAppStyle(context, AppPrefs.STYLE_HYPEROS3)
                                }
                            ),
                            DropdownItem(
                                text = "LiquidGlass",
                                selected = appStyle == AppPrefs.STYLE_LIQUID_GLASS,
                                onClick = {
                                    appStyle = AppPrefs.STYLE_LIQUID_GLASS
                                    AppPrefs.setAppStyle(context, AppPrefs.STYLE_LIQUID_GLASS)
                                }
                            ),
                        )
                    )
                    OverlayDropdownMenu(
                        title = "应用风格",
                        entry = appStyleEntry,
                        collapseOnSelection = true,
                        liquidGlassBackdrop = liquidGlassBackdrop,
                    )
                }
            }

            item {
                SmallTitle(
                    text = "应用设置",
                    modifier = Modifier.offset(x = PageListDefaults.SmallTitleOffsetX)
                )
                Card(
                    cornerRadius = 20.dp,
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    SwitchPreference(
                        title = "隐藏后台",
                        summary = "返回桌面时，隐藏应用的最近任务卡片",
                        checked = hideBackground,
                        onCheckedChange = {
                            hideBackground = it
                            AppPrefs.setHideBackground(context, it)
                        }
                    )
                }
            }

            if (islandSupported) {
                item {
                    SmallTitle(
                        text = "小米超级岛",
                        modifier = Modifier.offset(x = PageListDefaults.SmallTitleOffsetX)
                    )
                    Card(
                        cornerRadius = 20.dp,
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(0.dp)
                    ) {
                        SwitchPreference(
                            title = "小米超级岛",
                            summary = if (islandEnabled) {
                                "已开启，识别与打印将以超级岛样式显示"
                            } else {
                                "关闭后不推送超级岛"
                            },
                            checked = islandEnabled,
                            onCheckedChange = {
                                islandEnabled = it
                                AppPrefs.setIslandEnabled(context, it)
                            }
                        )
                        AnimatedVisibility(
                            visible = islandEnabled,
                            enter = expandVertically(animationSpec = tween(250)) + fadeIn(animationSpec = tween(200)),
                            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(150))
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                SwitchPreference(
                                    title = "小米超级岛光效",
                                    summary = "在超级岛展开态显示流动光效",
                                    checked = islandExpandGlow,
                                    onCheckedChange = {
                                        islandExpandGlow = it
                                        AppPrefs.setIslandExpandGlowEnabled(context, it)
                                    }
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .padding(bottom = 16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Shizuku 状态",
                                            fontSize = 14.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text(
                                            text = when {
                                                !shizukuRunning -> "未运行"
                                                !shizukuAuthorized -> "未授权"
                                                else -> "已就绪"
                                            },
                                            fontSize = 14.sp,
                                            color = when {
                                                !shizukuRunning -> Color(0xFFFF6B6B)
                                                !shizukuAuthorized -> Color(0xFFFFB347)
                                                else -> Color(0xFF4CAF50)
                                            }
                                        )
                                    }
                                    if (!shizukuRunning) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "请安装并启动 Shizuku 应用（超级岛需特权绕过）",
                                            fontSize = 13.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                                        )
                                    } else if (!shizukuAuthorized) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        TextButton(
                                            text = "授权 Shizuku",
                                            onClick = {
                                                IslandNotificationHelper.requestShizukuPermission { granted ->
                                                    shizukuAuthorized = granted
                                                    if (!granted) {
                                                        Toast.makeText(
                                                            context,
                                                            "Shizuku 授权失败",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    TextButton(
                                        text = "测试小米超级岛",
                                        onClick = {
                                            IslandNotificationHelper.sendTestIslandNotification(context)
                                            Toast.makeText(context, "已发送超级岛测试通知", Toast.LENGTH_SHORT).show()
                                            shizukuRunning = ShizukuManager.isShizukuRunning()
                                            shizukuAuthorized = ShizukuManager.checkSelfPermission()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSelectionBox(
    label: String,
    drawableRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .squircleBorder(
                    width = 3.dp,
                    color = if (selected) MiuixTheme.colorScheme.primary else Color.Transparent,
                    cornerRadius = 26.dp
                )
                .padding(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .squircleClip(20.dp)
            ) {
                Image(
                    painter = painterResource(drawableRes),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = if (selected) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}
