package com.pickup.print.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import com.pickup.print.CollectionCategory
import com.pickup.print.CollectionItem
import com.pickup.print.CollectionKind
import com.pickup.print.ImageUtils
import com.pickup.print.PrintLayoutResolver
import com.pickup.print.PrintPrefs
import com.pickup.print.TeaCupTemplates
import com.pickup.print.ui.basic.OverlayDropdownMenu
import com.pickup.print.ui.basic.SharedScrollBehavior
import com.pickup.print.ui.components.MiniLabelPreview
import com.pickup.print.ui.utils.overScrollVertical
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.NativeMiuixTextField
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通用收集页：分类切换对齐 Nexio「选择学校」页（学校导入 / 通用工具）同款 chip。
 */
@Composable
fun CollectionScreen(
    scrollBehavior: SharedScrollBehavior?,
    liquidBackdrop: Backdrop?,
    printPrefs: PrintPrefs,
    categories: List<CollectionCategory>,
    selectedCategoryId: String?,
    items: List<CollectionItem>,
    printerConnected: Boolean,
    showManage: Boolean,
    onShowManageChange: (Boolean) -> Unit,
    onSelectCategory: (String) -> Unit,
    onAddCategory: (name: String, kind: CollectionKind) -> Unit,
    onRenameCategory: (id: String, name: String) -> Unit,
    onDeleteCategory: (id: String) -> Unit,
    onMoveCategory: (id: String, towardStart: Boolean) -> Unit,
    onAddItem: (
        title: String,
        subtitle: String?,
        note: String?,
        drinkName: String?,
        shopPreset: String?,
    ) -> Unit,
    onDeleteItem: (CollectionItem) -> Unit,
    onPrintItem: (CollectionItem) -> Unit,
    onTakePhoto: () -> Unit,
    onImagePicked: (android.net.Uri) -> Unit,
    onEditTeaLayout: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
    val selectedIndex = categories.indexOfFirst { it.id == selectedCategoryId }.coerceAtLeast(0)
    val selectedCategory = categories.getOrNull(selectedIndex)
    val filtered = remember(items, selectedCategoryId) {
        if (selectedCategoryId == null) items else items.filter { it.categoryId == selectedCategoryId }
    }

    var showAdd by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<CollectionItem?>(null) }

    val density = LocalDensity.current
    val topBarHeightDp = with(density) { (scrollBehavior?.currentHeightPx ?: 0f).toDp() }
    val gridState = rememberLazyStaggeredGridState()
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    Scaffold(topBar = {}) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding() + topBarHeightDp)
        ) {
            // 对齐 Nexio EducationalImport：学校导入 / 通用工具 chip（管理入口在顶栏 endAction）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEachIndexed { index, category ->
                        val isSelected = index == selectedIndex
                        Surface(
                            modifier = Modifier
                                .clip(ContinuousRoundedRectangle(20.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    onSelectCategory(category.id)
                                },
                            color = if (isSelected) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .height(35.dp)
                                    .clip(ContinuousCapsule()),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = category.name,
                                    fontSize = 14.sp,
                                    color = if (isSelected) {
                                        MiuixTheme.colorScheme.onPrimary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantActions
                                    },
                                )
                            }
                        }
                    }
                }
            }

            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(if (isTablet) 4 else 2),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical()
                    .scrollEndHaptic(hapticFeedbackType = HapticFeedbackType.TextHandleMove)
                    .then(
                        scrollBehavior?.let { Modifier.nestedScroll(it.nestedScrollConnection) }
                            ?: Modifier
                    ),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 88.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalItemSpacing = 12.dp,
            ) {
                items(filtered, key = { it.id }) { item ->
                    val enter = remember { Animatable(0.85f) }
                    LaunchedEffect(item.id) {
                        enter.animateTo(1f, tween(280))
                    }
                    Box(
                        modifier = Modifier.graphicsLayer {
                            scaleX = enter.value
                            scaleY = enter.value
                        }
                    ) {
                        CollectionItemCard(
                            item = item,
                            category = categories.find { it.id == item.categoryId },
                            printPrefs = printPrefs,
                            timeLabel = timeFmt.format(Date(item.createdAt)),
                            onClick = { detailItem = item },
                        )
                    }
                }
                item(key = "add_tile") {
                    NewCollectionCard(
                        kindLabel = selectedCategory?.kind?.label ?: "条目",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            showAdd = true
                        },
                    )
                }
            }
        }
    }

    if (showManage) {
        CategoryManageDialog(
            categories = categories,
            liquidBackdrop = liquidBackdrop,
            onDismiss = { onShowManageChange(false) },
            onAdd = { name, kind ->
                onAddCategory(name, kind)
            },
            onRename = onRenameCategory,
            onDelete = onDeleteCategory,
            onMove = onMoveCategory,
        )
    }

    if (showAdd && selectedCategory != null) {
        AddCollectionItemDialog(
            category = selectedCategory,
            liquidBackdrop = liquidBackdrop,
            printerConnected = printerConnected,
            onDismiss = { showAdd = false },
            onConfirm = { title, subtitle, note, drink, shop ->
                onAddItem(title, subtitle, note, drink, shop)
                showAdd = false
            },
            onTakePhoto = onTakePhoto,
            onImagePicked = onImagePicked,
            onEditTeaLayout = onEditTeaLayout,
        )
    }

    detailItem?.let { item ->
        ItemDetailDialog(
            item = item,
            category = categories.find { it.id == item.categoryId },
            liquidBackdrop = liquidBackdrop,
            printerConnected = printerConnected,
            onDismiss = { detailItem = null },
            onPrint = {
                onPrintItem(item)
                detailItem = null
            },
            onDelete = {
                onDeleteItem(item)
                detailItem = null
            },
        )
    }
}

@Composable
private fun CollectionItemCard(
    item: CollectionItem,
    category: CollectionCategory?,
    printPrefs: PrintPrefs,
    timeLabel: String,
    onClick: () -> Unit,
) {
    val resolved = remember(item, category, printPrefs) {
        PrintLayoutResolver.resolve(printPrefs, category, item)
    }
    Card(
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            MiniLabelPreview(
                config = resolved.config,
                code = resolved.code,
                remark = resolved.remark,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = item.title.ifBlank { "(无标题)" },
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(item.subtitle, item.drinkName, category?.name)
                .distinct()
                .joinToString(" · ")
            if (sub.isNotBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = sub,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = timeLabel,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun NewCollectionCard(
    kindLabel: String,
    onClick: () -> Unit,
) {
    Card(
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "+",
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "添加$kindLabel",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun CategoryManageDialog(
    categories: List<CollectionCategory>,
    liquidBackdrop: Backdrop?,
    onDismiss: () -> Unit,
    onAdd: (String, CollectionKind) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (String, Boolean) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf(CollectionKind.GENERIC) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }
    var kindTick by remember { mutableIntStateOf(0) }

    val kindItems = remember(newKind, kindTick) {
        CollectionKind.entries.map { kind ->
            DropdownItem(
                text = kind.label,
                summary = when (kind) {
                    CollectionKind.PICKUP -> "上门取件码排版与打印"
                    CollectionKind.TEA_CUP -> "支持杯贴识别与打印"
                    CollectionKind.TICKET -> "车票 / 行程凭证"
                    CollectionKind.GENERIC -> "任意收藏条目"
                },
                selected = kind == newKind,
                onClick = {
                    newKind = kind
                    kindTick++
                },
            )
        }
    }
    val kindEntry = remember(kindItems) { DropdownEntry(items = kindItems) }

    OverlayDialog(
        show = true,
        title = "管理分类",
        onDismissRequest = onDismiss,
        liquidGlassBackdrop = liquidBackdrop,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            categories.forEach { cat ->
                Card(cornerRadius = 14.dp, insideMargin = PaddingValues(12.dp)) {
                    if (editingId == cat.id) {
                        NativeMiuixTextField(
                            value = editingName,
                            onValueChange = { editingName = it },
                            label = "分类名称",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                text = "取消",
                                onClick = { editingId = null },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                text = "保存",
                                onClick = {
                                    onRename(cat.id, editingName)
                                    editingId = null
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    } else {
                        Text(
                            text = cat.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                        Text(
                            text = cat.kind.label,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(text = "↑", onClick = { onMove(cat.id, true) })
                            TextButton(text = "↓", onClick = { onMove(cat.id, false) })
                            TextButton(
                                text = "重命名",
                                onClick = {
                                    editingId = cat.id
                                    editingName = cat.name
                                },
                            )
                            TextButton(
                                text = "删除",
                                onClick = { onDelete(cat.id) },
                                enabled = categories.size > 1,
                            )
                        }
                    }
                }
            }

            Text(
                text = "新建分类",
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            OverlayDropdownMenu(
                title = "类型",
                summary = newKind.label,
                entry = kindEntry,
                liquidGlassBackdrop = liquidBackdrop,
                collapseOnSelection = true,
            )
            NativeMiuixTextField(
                value = newName,
                onValueChange = { newName = it },
                label = "分类名称（如：奶茶、车票）",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = "添加分类",
                onClick = {
                    if (newName.isNotBlank()) {
                        onAdd(newName.trim(), newKind)
                        newName = ""
                    }
                },
                enabled = newName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            TextButton(
                text = "完成",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AddCollectionItemDialog(
    category: CollectionCategory,
    liquidBackdrop: Backdrop?,
    printerConnected: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (title: String, subtitle: String?, note: String?, drink: String?, shop: String?) -> Unit,
    onTakePhoto: () -> Unit,
    onImagePicked: (android.net.Uri) -> Unit,
    onEditTeaLayout: () -> Unit,
) {
    val isTea = category.kind == CollectionKind.TEA_CUP
    var title by remember { mutableStateOf("") }
    var subtitle by remember { mutableStateOf(if (isTea) "古茗" else "") }
    var note by remember { mutableStateOf("") }
    var drink by remember { mutableStateOf("") }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImagePicked(uri)
    }

    val shopItems = remember(subtitle) {
        TeaCupTemplates.presets.map { preset ->
            DropdownItem(
                text = preset.shopName,
                summary = preset.hint,
                selected = preset.shopName == subtitle ||
                    (preset.shopName == "自定义" && TeaCupTemplates.presets.none { it.shopName == subtitle }),
                onClick = {
                    subtitle = if (preset.shopName == "自定义") "" else preset.shopName
                },
            )
        }
    }
    val shopEntry = remember(shopItems) { DropdownEntry(items = shopItems) }

    OverlayDialog(
        show = true,
        title = "添加 · ${category.name}",
        onDismissRequest = onDismiss,
        liquidGlassBackdrop = liquidBackdrop,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isTea) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = "拍照识别",
                        onClick = onTakePhoto,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    TextButton(
                        text = "相册识别",
                        onClick = { pickLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                    )
                }
                OverlayDropdownMenu(
                    title = "奶茶店模板",
                    summary = subtitle.ifBlank { "选择或自定义" },
                    entry = shopEntry,
                    liquidGlassBackdrop = liquidBackdrop,
                    collapseOnSelection = true,
                )
                if (subtitle.isBlank() || TeaCupTemplates.presets.none { it.shopName == subtitle }) {
                    NativeMiuixTextField(
                        value = subtitle,
                        onValueChange = { subtitle = it },
                        label = "店名",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            NativeMiuixTextField(
                value = title,
                onValueChange = { title = it },
                label = when (category.kind) {
                    CollectionKind.PICKUP -> "上门取件码"
                    CollectionKind.TEA_CUP -> "取茶号 / 取餐号"
                    CollectionKind.TICKET -> "车次 / 票号"
                    CollectionKind.GENERIC -> "标题"
                },
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!isTea) {
                NativeMiuixTextField(
                    value = subtitle,
                    onValueChange = { subtitle = it },
                    label = when (category.kind) {
                        CollectionKind.TICKET -> "线路 / 站点（可选）"
                        else -> "副标题（可选）"
                    },
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                NativeMiuixTextField(
                    value = drink,
                    onValueChange = { drink = it },
                    label = "饮品名（可选）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            NativeMiuixTextField(
                value = note,
                onValueChange = { note = it },
                label = "备注（可选）",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isTea) {
                TextButton(
                    text = "编辑杯贴排版",
                    onClick = onEditTeaLayout,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "收入收集",
                    onClick = {
                        onConfirm(
                            title.trim(),
                            subtitle.trim().takeIf { it.isNotBlank() },
                            note.trim().takeIf { it.isNotBlank() },
                            drink.trim().takeIf { it.isNotBlank() },
                            if (isTea) subtitle.trim().takeIf { it.isNotBlank() } else null,
                        )
                    },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
            if (isTea) {
                Text(
                    text = if (printerConnected) "添加后可在卡片中打印杯贴" else "打印前请先在「我的」连接打印机",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun ItemDetailDialog(
    item: CollectionItem,
    category: CollectionCategory?,
    liquidBackdrop: Backdrop?,
    printerConnected: Boolean,
    onDismiss: () -> Unit,
    onPrint: () -> Unit,
    onDelete: () -> Unit,
) {
    val bmp = remember(item.thumbPath) { ImageUtils.loadBitmap(item.thumbPath) }
    val canPrint = true
    OverlayDialog(
        show = true,
        title = item.title,
        onDismissRequest = onDismiss,
        liquidGlassBackdrop = liquidBackdrop,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            listOfNotNull(
                item.subtitle?.let { "副标题：$it" },
                item.drinkName?.let { "饮品：$it" },
                item.note?.let { "备注：$it" },
                category?.let { "分类：${it.name}" },
            ).forEach {
                Text(text = it, fontSize = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canPrint) {
                    TextButton(
                        text = if (printerConnected) "打印杯贴" else "未连打印机",
                        onClick = onPrint,
                        enabled = printerConnected,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                TextButton(
                    text = "删除",
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(
                text = "关闭",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
