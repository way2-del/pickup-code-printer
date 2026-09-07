package com.pickup.print

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.Backdrop
import com.pickup.print.ui.basic.OverlayDropdownMenu
import com.pickup.print.ui.basic.SharedScrollBehavior
import com.pickup.print.ui.setPickupContent
import com.pickup.print.ui.utils.PageListDefaults
import com.pickup.print.ui.utils.overScrollVertical
import com.pickup.print.ui.utils.pageListContentPadding
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.NativeMiuixTextField
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.math.roundToInt

private data class PaperSizePreset(
    val widthMm: Float,
    val heightMm: Float,
    val builtIn: Boolean = true,
) {
    val label: String get() = "${widthMm.toInt()}×${heightMm.toInt()} mm"
}

private val BuiltInPaperSizes = listOf(
    PaperSizePreset(48f, 40f),
    PaperSizePreset(40f, 30f),
    PaperSizePreset(50f, 30f),
    PaperSizePreset(50f, 40f),
)

private fun kindLabel(kind: PrintLayoutEngine.ElementBox.Kind): String = when (kind) {
    PrintLayoutEngine.ElementBox.Kind.TITLE -> "标题"
    PrintLayoutEngine.ElementBox.Kind.CODE -> "取件码"
    PrintLayoutEngine.ElementBox.Kind.REMARK -> "备注"
    PrintLayoutEngine.ElementBox.Kind.BARCODE -> "一维码"
}

private fun sizeRange(kind: PrintLayoutEngine.ElementBox.Kind): ClosedFloatingPointRange<Float> = when (kind) {
    PrintLayoutEngine.ElementBox.Kind.CODE -> 4f..18f
    PrintLayoutEngine.ElementBox.Kind.BARCODE -> 4f..14f
    else -> 2f..8f
}

private fun sizeStep(kind: PrintLayoutEngine.ElementBox.Kind): Float =
    if (kind == PrintLayoutEngine.ElementBox.Kind.CODE) 0.5f else 0.2f

class PrintLayoutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PrintPrefs(this)
        val category = PrintCategory.fromId(intent.getStringExtra(EXTRA_CATEGORY))
        val title = when (category) {
            PrintCategory.PICKUP -> "取件码排版"
            PrintCategory.TEA_CUP -> "奶茶杯贴排版"
        }
        setPickupContent(title = { title }, showBack = true) { scrollBehavior, liquidBackdrop ->
            PrintLayoutScreen(
                scrollBehavior = scrollBehavior,
                liquidBackdrop = liquidBackdrop,
                prefs = prefs,
                initial = prefs.loadLayout(category),
                sampleTitleFallback = when (category) {
                    PrintCategory.PICKUP -> "上门取件码"
                    PrintCategory.TEA_CUP -> "取茶号"
                },
                onSave = { cfg ->
                    prefs.saveLayout(cfg, category)
                    Toast.makeText(this, "已保存${category.label}布局", Toast.LENGTH_SHORT).show()
                    finish()
                },
            )
        }
    }

    companion object {
        const val EXTRA_CATEGORY = "print_category"
    }
}

@Composable
fun PrintLayoutScreen(
    scrollBehavior: SharedScrollBehavior?,
    liquidBackdrop: Backdrop?,
    prefs: PrintPrefs,
    initial: PrintLayoutConfig,
    onSave: (PrintLayoutConfig) -> Unit,
    sampleTitleFallback: String = "上门取件码",
) {
    var widthMm by remember { mutableFloatStateOf(initial.paperWidthMm) }
    var heightMm by remember { mutableFloatStateOf(initial.paperHeightMm) }
    var orientation by remember { mutableStateOf(initial.orientation) }
    var preset by remember { mutableStateOf(initial.preset) }
    var showTitle by remember { mutableStateOf(initial.showTitle) }
    var showCode by remember { mutableStateOf(initial.showCode) }
    var showRemark by remember { mutableStateOf(initial.showRemark) }
    var showBarcode by remember { mutableStateOf(initial.showBarcode) }
    var rowOrder by remember { mutableStateOf(initial.rowOrder) }
    var textAligns by remember { mutableStateOf(initial.textAligns) }
    var fontSizes by remember { mutableStateOf(initial.fontSizes) }
    var codeFontMm by remember { mutableFloatStateOf(initial.codeFontMm) }
    var selectedKind by remember { mutableStateOf<PrintLayoutEngine.ElementBox.Kind?>(null) }
    var selectedHint by remember { mutableStateOf("点选元素可调大小；拖到另一行可交换") }
    var previewView by remember { mutableStateOf<LabelPreviewView?>(null) }
    var customPapers by remember { mutableStateOf(prefs.loadCustomPaperSizes()) }
    var showCustomPaperDialog by remember { mutableStateOf(false) }
    var customWidthText by remember { mutableStateOf(widthMm.toInt().toString()) }
    var customHeightText by remember { mutableStateOf(heightMm.toInt().toString()) }
    var sizeInputText by remember { mutableStateOf("") }

    val sampleCode = "8842"
    val titleText = initial.titleText.ifBlank { sampleTitleFallback }

    fun currentConfig(): PrintLayoutConfig {
        val order = previewView?.currentOrder()?.ifEmpty { rowOrder } ?: rowOrder
        val codeFont = fontSizes[PrintLayoutEngine.ElementBox.Kind.CODE.name] ?: codeFontMm
        return PrintLayoutConfig(
            paperWidthMm = widthMm.coerceIn(20f, 80f),
            paperHeightMm = heightMm.coerceIn(15f, 100f),
            orientation = orientation,
            preset = preset,
            textAligns = textAligns,
            showTitle = showTitle,
            showCode = showCode,
            showRemark = showRemark,
            showBarcode = showBarcode,
            titleText = titleText,
            codeFontMm = codeFont.coerceIn(4f, 18f),
            fontSizes = fontSizes,
            rowOrder = order
        )
    }

    fun refreshPreview() {
        previewView?.setPreview(currentConfig(), sampleCode, "备注示例")
    }

    fun resolveSelectedSize(kind: PrintLayoutEngine.ElementBox.Kind): Float {
        fontSizes[kind.name]?.let { return it }
        previewView?.elementOf(kind)?.let { el ->
            return if (kind == PrintLayoutEngine.ElementBox.Kind.BARCODE) {
                el.h.toFloat()
            } else {
                el.fontMm.toFloat()
            }
        }
        return when (kind) {
            PrintLayoutEngine.ElementBox.Kind.CODE -> codeFontMm
            PrintLayoutEngine.ElementBox.Kind.BARCODE -> 8f
            PrintLayoutEngine.ElementBox.Kind.TITLE -> 3.2f
            PrintLayoutEngine.ElementBox.Kind.REMARK -> 2.6f
        }
    }

    fun setSelectedSize(kind: PrintLayoutEngine.ElementBox.Kind, raw: Float, syncInput: Boolean = true) {
        val range = sizeRange(kind)
        val value = (raw * 10f).roundToInt() / 10f
        val coerced = value.coerceIn(range.start, range.endInclusive)
        fontSizes = fontSizes + (kind.name to coerced)
        if (kind == PrintLayoutEngine.ElementBox.Kind.CODE) {
            codeFontMm = coerced
        }
        if (syncInput) sizeInputText = formatMm(coerced)
        refreshPreview()
    }

    LaunchedEffect(widthMm, heightMm, orientation, showTitle, showCode, showRemark, showBarcode, fontSizes, rowOrder) {
        refreshPreview()
    }

    LaunchedEffect(selectedKind, fontSizes, previewView) {
        val kind = selectedKind ?: return@LaunchedEffect
        sizeInputText = formatMm(resolveSelectedSize(kind))
    }

    val allPaperSizes = remember(customPapers) {
        BuiltInPaperSizes + customPapers.map { (w, h) -> PaperSizePreset(w, h, builtIn = false) }
    }

    val sizeEntry = remember(widthMm, heightMm, customPapers) {
        DropdownEntry(
            items = buildList {
                allPaperSizes.forEach { size ->
                    add(
                        DropdownItem(
                            text = size.label + if (!size.builtIn) " · 自定义" else "",
                            summary = "宽 ${size.widthMm.toInt()}mm · 高 ${size.heightMm.toInt()}mm",
                            selected = widthMm == size.widthMm && heightMm == size.heightMm,
                            onClick = {
                                widthMm = size.widthMm
                                heightMm = size.heightMm
                            },
                        )
                    )
                }
                add(
                    DropdownItem(
                        text = "自定义…",
                        summary = "输入尺寸并保存到列表",
                        selected = false,
                        onClick = {
                            customWidthText = widthMm.toInt().toString()
                            customHeightText = heightMm.toInt().toString()
                            showCustomPaperDialog = true
                        },
                    )
                )
            }
        )
    }
    val orientationEntry = remember(orientation) {
        DropdownEntry(
            items = PrintOrientation.entries.map { dir ->
                DropdownItem(
                    text = dir.label,
                    summary = if (dir == PrintOrientation.PORTRAIT) "按纸张竖向打印" else "按纸张横向旋转 90° 打印",
                    selected = orientation == dir,
                    onClick = { orientation = dir },
                )
            }
        )
    }
    val sizeSummary = remember(widthMm, heightMm, customPapers) {
        allPaperSizes.find { it.widthMm == widthMm && it.heightMm == heightMm }?.let {
            it.label + if (!it.builtIn) " · 自定义" else ""
        } ?: "${widthMm.toInt()}×${heightMm.toInt()} mm"
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
                    text = "预览",
                    modifier = Modifier.offset(x = PageListDefaults.SmallTitleOffsetX)
                )
                Card(cornerRadius = 20.dp, insideMargin = PaddingValues(12.dp)) {
                    AndroidView(
                        factory = { ctx ->
                            LabelPreviewView(ctx).also { v ->
                                previewView = v
                                v.setEditable(true, object : LabelPreviewView.Listener {
                                    override fun onOrderChanged(order: List<String>) {
                                        rowOrder = order
                                        refreshPreview()
                                    }

                                    override fun onSelectionChanged(kind: PrintLayoutEngine.ElementBox.Kind?) {
                                        selectedKind = kind
                                        selectedHint = when (kind) {
                                            null -> "点选元素可调大小；拖到另一行可交换"
                                            else -> "已选中：${kindLabel(kind)}"
                                        }
                                    }
                                })
                                v.setPreview(currentConfig(), sampleCode, "备注示例")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        update = { refreshPreview() }
                    )
                    Text(selectedHint, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }

            selectedKind?.let { kind ->
                item(key = "element-size-$kind") {
                    val range = sizeRange(kind)
                    val current = resolveSelectedSize(kind).coerceIn(range.start, range.endInclusive)
                    val unitLabel = if (kind == PrintLayoutEngine.ElementBox.Kind.BARCODE) "高度" else "字号"
                    SmallTitle(
                        text = "元素大小 · ${kindLabel(kind)}",
                        modifier = Modifier.offset(x = PageListDefaults.SmallTitleOffsetX)
                    )
                    Card(cornerRadius = 20.dp, insideMargin = PaddingValues(16.dp)) {
                        Text(
                            text = "$unitLabel ${formatMm(current)} mm",
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(
                                text = "缩小",
                                onClick = { setSelectedSize(kind, current - sizeStep(kind)) },
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                text = "放大",
                                onClick = { setSelectedSize(kind, current + sizeStep(kind)) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary()
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Slider(
                            value = current,
                            onValueChange = { setSelectedSize(kind, it) },
                            valueRange = range,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        NativeMiuixTextField(
                            value = sizeInputText,
                            onValueChange = { raw ->
                                sizeInputText = raw.filter { it.isDigit() || it == '.' || it == ',' }
                                    .replace(',', '.')
                                sizeInputText.toFloatOrNull()?.let {
                                    setSelectedSize(kind, it, syncInput = false)
                                }
                            },
                            label = "$unitLabel（mm）",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            item {
                SmallTitle(
                    text = "纸张",
                    modifier = Modifier.offset(x = PageListDefaults.SmallTitleOffsetX)
                )
                Card(cornerRadius = 20.dp, insideMargin = PaddingValues(0.dp)) {
                    OverlayDropdownMenu(
                        title = "预设尺寸",
                        summary = sizeSummary,
                        entry = sizeEntry,
                        liquidGlassBackdrop = liquidBackdrop,
                        collapseOnSelection = true,
                    )
                    OverlayDropdownMenu(
                        title = "打印方向",
                        summary = orientation.label,
                        entry = orientationEntry,
                        liquidGlassBackdrop = liquidBackdrop,
                        collapseOnSelection = true,
                    )
                }
            }

            item {
                SmallTitle(
                    text = "显示元素",
                    modifier = Modifier.offset(x = PageListDefaults.SmallTitleOffsetX)
                )
                Card(cornerRadius = 20.dp, insideMargin = PaddingValues(0.dp)) {
                    SwitchPreference(title = "显示标题", checked = showTitle, onCheckedChange = {
                        showTitle = it
                    })
                    SwitchPreference(title = "显示取件码", checked = showCode, onCheckedChange = {
                        showCode = it
                    })
                    SwitchPreference(title = "显示备注", checked = showRemark, onCheckedChange = {
                        showRemark = it
                    })
                    SwitchPreference(title = "显示条码", checked = showBarcode, onCheckedChange = {
                        showBarcode = it
                    })
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        text = "恢复顺序",
                        onClick = {
                            rowOrder = emptyList()
                            selectedKind = null
                            previewView?.resetOrder()
                            refreshPreview()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = "保存",
                        onClick = { onSave(currentConfig()) },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    if (showCustomPaperDialog) {
        OverlayDialog(
            show = true,
            title = "自定义纸张尺寸",
            onDismissRequest = { showCustomPaperDialog = false },
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                NativeMiuixTextField(
                    value = customWidthText,
                    onValueChange = { customWidthText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = "宽度（mm）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                NativeMiuixTextField(
                    value = customHeightText,
                    onValueChange = { customHeightText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = "高度（mm）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = "取消",
                        onClick = { showCustomPaperDialog = false },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = "保存到列表",
                        onClick = {
                            val w = customWidthText.toFloatOrNull()
                            val h = customHeightText.toFloatOrNull()
                            if (w == null || h == null) return@TextButton
                            customPapers = prefs.addCustomPaperSize(w, h)
                            widthMm = w.coerceIn(20f, 80f)
                            heightMm = h.coerceIn(15f, 100f)
                            showCustomPaperDialog = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    }
}

private fun formatMm(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    return if (rounded == rounded.toInt().toFloat()) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}
