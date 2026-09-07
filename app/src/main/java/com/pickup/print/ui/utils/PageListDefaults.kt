package com.pickup.print.ui.utils

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pickup.print.ui.basic.SharedScrollBehavior

/**
 * 对齐课表 App「AI 文本导入」二级页列表边距：
 * top = Scaffold(paddingValues).top + CollapsibleTopAppBar.currentHeightPx
 * （空 topBar 时 Scaffold top 为状态栏 inset；currentHeightPx 不含状态栏）
 *
 * 键盘弹起时 bottom 改为 ime + 小间距，把输入区顶到键盘上方；
 * 收起后恢复 [PageListDefaults.Bottom]，布局不变。
 */
object PageListDefaults {
    val Horizontal: Dp = 16.dp
    val Bottom: Dp = 120.dp
    val SectionSpacing: Dp = 12.dp
    /** SmallTitle 相对列表 16dp 边距回拉，使标题文字落在 28dp（与大标题对齐） */
    val SmallTitleOffsetX: Dp = (-16).dp
    /** 键盘上方预留，避免输入框贴边 */
    val ImeExtraBottom: Dp = 12.dp
}

@Composable
fun pageListContentPadding(
    scrollBehavior: SharedScrollBehavior?,
    scaffoldPadding: PaddingValues,
    horizontal: Dp = PageListDefaults.Horizontal,
    bottom: Dp = PageListDefaults.Bottom,
): PaddingValues {
    val topBarHeightDp = with(LocalDensity.current) {
        (scrollBehavior?.currentHeightPx ?: 0f).toDp()
    }
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val effectiveBottom = if (imeBottom > 0.dp) {
        imeBottom + PageListDefaults.ImeExtraBottom
    } else {
        bottom
    }
    return PaddingValues(
        start = horizontal,
        end = horizontal,
        top = scaffoldPadding.calculateTopPadding() + topBarHeightDp,
        bottom = effectiveBottom,
    )
}
