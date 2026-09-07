package com.pickup.print.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop as liquidGlassLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberLiquidGlassLayerBackdrop
import com.pickup.print.AppPrefs
import com.pickup.print.ui.basic.CollapsibleTopAppBar
import com.pickup.print.ui.basic.LiquidTopBarButton
import com.pickup.print.ui.basic.ProgressiveBlurTopBar
import com.pickup.print.ui.basic.SharedScrollBehavior
import com.pickup.print.ui.basic.rememberSharedScrollBehavior
import com.pickup.print.ui.theme.PickupTheme
import com.pickup.print.ui.utils.applyThemeAwareSystemBars
import com.pickup.print.ui.utils.isAppDarkTheme
import com.pickup.print.ui.utils.rememberAppStyle
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页面壳层：按应用风格切换顶栏。
 * - LiquidGlass：对齐 Nexio（ProgressiveBlur + CollapsibleTopAppBar + LiquidTopBarButton / ChevronBackward）
 * - HyperOS3：实心 surface + 折叠大标题 + 平面 IconButton / Back
 */
fun ComponentActivity.setPickupContent(
    title: () -> String,
    showBack: Boolean = true,
    endActions: (@Composable (backdrop: Backdrop?, backdropAlpha: Float, shadowAlpha: Float) -> Unit)? = null,
    bottomBar: (@Composable (liquidGlassBackdrop: Backdrop?) -> Unit)? = null,
    content: @Composable (scrollBehavior: SharedScrollBehavior, liquidGlassBackdrop: Backdrop?) -> Unit
) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT
        ),
        navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    )
    applyThemeAwareSystemBars()
    setContent {
        PickupTheme {
            val isDark = isAppDarkTheme()
            LaunchedEffect(isDark) { applyThemeAwareSystemBars() }
            val backgroundColor = MiuixTheme.colorScheme.surface
            val backdrop = rememberLayerBackdrop {
                drawRect(backgroundColor)
                drawContent()
            }
            val appStyle = rememberAppStyle()
            val isLiquidGlass = appStyle == AppPrefs.STYLE_LIQUID_GLASS
            val liquidGlassBackdrop = if (isLiquidGlass) {
                rememberLiquidGlassLayerBackdrop()
            } else {
                null
            }
            val scrollBehavior = rememberSharedScrollBehavior()
            val titleText = title()

            Scaffold(
                topBar = {
                    var topBarBlurAlpha by remember { mutableFloatStateOf(0f) }
                    val useLiquidTopBar = isLiquidGlass && liquidGlassBackdrop != null
                    val topBarContent: @Composable () -> Unit = {
                        CollapsibleTopAppBar(
                            title = titleText,
                            largeTitle = titleText,
                            // 液态玻璃：标题始终顶部居中；HyperOS3：左上大标题，上滑后收折居中
                            showLargeTitle = !useLiquidTopBar,
                            scrollBehavior = scrollBehavior,
                            // 液态玻璃用渐进模糊/渐变遮罩；HyperOS3 用实心底，关掉渐变避免叠色
                            showGradientOverlay = useLiquidTopBar,
                            contentPadding = {},
                            onAlphaChanged = { bd, _ -> topBarBlurAlpha = bd },
                            startAction = if (showBack) {
                                { backdropAlpha, shadowAlpha ->
                                    if (useLiquidTopBar) {
                                        LiquidTopBarButton(
                                            onClick = { finish() },
                                            backdrop = liquidGlassBackdrop!!,
                                            icon = MiuixIcons.ChevronBackward,
                                            contentDescription = "返回",
                                            iconSize = 25.dp,
                                            iconOffset = DpOffset(x = (-2).dp, y = 0.dp),
                                            backdropAlpha = backdropAlpha,
                                            shadowAlpha = shadowAlpha,
                                        )
                                    } else {
                                        IconButton(onClick = { finish() }) {
                                            Icon(
                                                imageVector = MiuixIcons.Back,
                                                contentDescription = "返回",
                                                modifier = Modifier.size(28.dp),
                                            )
                                        }
                                    }
                                }
                            } else null,
                            endAction = if (endActions != null) {
                                { backdropAlpha, shadowAlpha ->
                                    endActions(
                                        if (useLiquidTopBar) liquidGlassBackdrop else null,
                                        backdropAlpha,
                                        shadowAlpha,
                                    )
                                }
                            } else null,
                        )
                    }
                    if (useLiquidTopBar) {
                        ProgressiveBlurTopBar(
                            backdrop = liquidGlassBackdrop!!,
                            blurAlpha = topBarBlurAlpha,
                        ) {
                            topBarContent()
                        }
                    } else {
                        // HyperOS3：实心顶栏底（对齐 Miuix TopAppBar surface）
                        Box(modifier = Modifier.background(MiuixTheme.colorScheme.surface)) {
                            topBarContent()
                        }
                    }
                },
                bottomBar = {
                    bottomBar?.invoke(liquidGlassBackdrop)
                }
            ) { _ ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (liquidGlassBackdrop != null) {
                                    Modifier.liquidGlassLayerBackdrop(liquidGlassBackdrop)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        content(scrollBehavior, liquidGlassBackdrop)
                    }
                }
            }
        }
    }
}
