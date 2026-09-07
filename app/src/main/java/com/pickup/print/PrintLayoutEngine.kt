package com.pickup.print

/**
 * 根据纸张尺寸与预设，计算各元素在标签上的毫米坐标。
 * 行几何固定；[PrintLayoutConfig.rowOrder] 只决定各行放哪种内容。
 */
object PrintLayoutEngine {

    data class ElementBox(
        val text: String,
        val x: Double,
        val y: Double,
        val w: Double,
        val h: Double,
        val fontMm: Double,
        val kind: Kind
    ) {
        enum class Kind { TITLE, CODE, REMARK, BARCODE }
    }

    fun buildElements(
        config: PrintLayoutConfig,
        code: String,
        remark: String?
    ): List<ElementBox> {
        val slots = buildPresetElements(config, code, remark)
        if (slots.isEmpty()) return emptyList()

        val defaultKinds = slots.map { it.kind }
        val order = resolveOrder(config.rowOrder, defaultKinds)
        val contentByKind = slots.associateBy { it.kind }

        val ordered = order.mapIndexed { index, kind ->
            val slot = slots[index]
            val content = contentByKind[kind] ?: slot
            val font = config.fontSizeFor(kind)?.toDouble() ?: content.fontMm
            // 行高按字号本身；条码可用 fontSizes[BARCODE] 覆盖高度
            val boxH = when (kind) {
                ElementBox.Kind.BARCODE ->
                    config.fontSizeFor(kind)?.toDouble()?.coerceAtLeast(4.0) ?: maxOf(slot.h, 6.0)
                else -> font
            }
            ElementBox(
                text = content.text,
                x = slot.x,
                y = slot.y,
                w = slot.w,
                h = boxH,
                fontMm = if (kind == ElementBox.Kind.BARCODE) boxH * 0.25 else font,
                kind = kind
            )
        }
        return reflowByFont(ordered, config.layoutWidthMm.toDouble(), config.layoutHeightMm.toDouble())
    }

    /**
     * 按字号确定行高后垂直居中整块内容；超高则等比缩小字号，避免顶部裁切。
     */
    private fun reflowByFont(
        elements: List<ElementBox>,
        pageW: Double,
        pageH: Double
    ): List<ElementBox> {
        if (elements.isEmpty()) return elements
        val margin = 2.5
        val contentW = pageW - margin * 2
        var sized = elements.map { el ->
            val w = if (el.kind == ElementBox.Kind.BARCODE) (contentW - 4).coerceAtLeast(8.0) else contentW
            val x = if (el.kind == ElementBox.Kind.BARCODE) margin + 2 else margin
            el.copy(x = x, w = w)
        }

        val avail = (pageH - margin * 2).coerceAtLeast(1.0)
        val gaps = (sized.size - 1).coerceAtLeast(0)
        val minGap = 1.0
        var sumH = sized.sumOf { it.h }
        var need = sumH + minGap * gaps
        if (need > avail && need > 0) {
            val scale = avail / need
            sized = sized.map { el ->
                if (el.kind == ElementBox.Kind.BARCODE) {
                    el.copy(h = (el.h * scale).coerceAtLeast(4.0))
                } else {
                    val f = (el.fontMm * scale).coerceAtLeast(2.0)
                    el.copy(fontMm = f, h = f)
                }
            }
            sumH = sized.sumOf { it.h }
        }

        // 上下与行间均分剩余空间，整块居中且顶边不低于 margin
        val free = (avail - sumH).coerceAtLeast(0.0)
        val piece = free / (sized.size + 1)
        var y = margin + piece
        return sized.map { el ->
            val out = el.copy(y = y)
            y += el.h + piece
            out
        }
    }

    fun defaultRowOrder(config: PrintLayoutConfig, code: String, remark: String?): List<ElementBox.Kind> =
        buildPresetElements(config, code, remark).map { it.kind }

    fun resolveOrder(
        saved: List<String>,
        defaultKinds: List<ElementBox.Kind>
    ): List<ElementBox.Kind> {
        if (defaultKinds.isEmpty()) return emptyList()
        if (saved.isEmpty()) return defaultKinds
        val byName = defaultKinds.associateBy { it.name }
        val ordered = saved.mapNotNull { byName[it] }.distinct()
        val missing = defaultKinds.filter { it !in ordered }
        return ordered + missing
    }

    fun swapOrder(
        order: List<String>,
        a: ElementBox.Kind,
        b: ElementBox.Kind,
        defaultKinds: List<ElementBox.Kind>
    ): List<String> {
        val current = resolveOrder(order, defaultKinds).toMutableList()
        val i = current.indexOf(a)
        val j = current.indexOf(b)
        if (i < 0 || j < 0 || i == j) return current.map { it.name }
        current[i] = b
        current[j] = a
        return current.map { it.name }
    }

    private fun buildPresetElements(
        config: PrintLayoutConfig,
        code: String,
        remark: String?
    ): List<ElementBox> {
        val width = config.layoutWidthMm.toDouble()
        val height = config.layoutHeightMm.toDouble()
        val margin = 2.0
        val contentW = width - margin * 2
        val clean = code.trim()
        val remarkText = remark?.trim().orEmpty()
        val out = mutableListOf<ElementBox>()

        when (config.preset) {
            LayoutPreset.TITLE_TOP -> {
                var y = margin
                if (config.showTitle) {
                    out += ElementBox(config.titleText, margin, y, contentW, 5.5, 3.2, ElementBox.Kind.TITLE)
                    y += 6.0
                }
                if (config.showRemark && remarkText.isNotEmpty()) {
                    out += ElementBox(remarkText, margin, y, contentW, 4.5, 2.6, ElementBox.Kind.REMARK)
                    y += 5.0
                }
                if (config.showCode) {
                    val font = resolveCodeFont(config, clean, height - y - if (config.showBarcode) 12.0 else 2.0)
                    val codeH = (height - y - if (config.showBarcode) 11.0 else margin).coerceAtLeast(8.0)
                    out += ElementBox(clean, margin, y, contentW, codeH, font, ElementBox.Kind.CODE)
                }
                if (config.showBarcode) {
                    out += ElementBox(
                        clean, margin + 2, height - 10.0, contentW - 4, 8.0, 2.0, ElementBox.Kind.BARCODE
                    )
                }
            }

            LayoutPreset.CENTER_CODE -> {
                if (config.showTitle) {
                    out += ElementBox(config.titleText, margin, margin, contentW, 5.0, 3.0, ElementBox.Kind.TITLE)
                }
                if (config.showCode) {
                    val font = config.codeFontMm.toDouble().coerceIn(6.0, 16.0)
                    val codeH = font + 4
                    val y = (height - codeH) / 2.0
                    out += ElementBox(clean, margin, y, contentW, codeH, font, ElementBox.Kind.CODE)
                }
                if (config.showRemark && remarkText.isNotEmpty()) {
                    out += ElementBox(
                        remarkText, margin, height - (if (config.showBarcode) 16.0 else 7.0),
                        contentW, 4.0, 2.5, ElementBox.Kind.REMARK
                    )
                }
                if (config.showBarcode) {
                    out += ElementBox(
                        clean, margin + 2, height - 10.0, contentW - 4, 8.0, 2.0, ElementBox.Kind.BARCODE
                    )
                }
            }

            LayoutPreset.COMPACT -> {
                var y = 1.5
                if (config.showTitle) {
                    out += ElementBox(config.titleText, margin, y, contentW, 4.0, 2.6, ElementBox.Kind.TITLE)
                    y += 4.2
                }
                if (config.showCode) {
                    val font = resolveCodeFont(config, clean, 10.0).coerceAtMost(8.0)
                    out += ElementBox(clean, margin, y, contentW, 9.0, font, ElementBox.Kind.CODE)
                    y += 9.5
                }
                if (config.showRemark && remarkText.isNotEmpty()) {
                    out += ElementBox(remarkText, margin, y, contentW, 3.5, 2.2, ElementBox.Kind.REMARK)
                }
                if (config.showBarcode) {
                    out += ElementBox(
                        clean, margin + 1, height - 8.5, contentW - 2, 6.5, 1.8, ElementBox.Kind.BARCODE
                    )
                }
            }

            LayoutPreset.CODE_BARCODE -> {
                if (config.showTitle) {
                    out += ElementBox(config.titleText, margin, margin, contentW, 4.5, 2.8, ElementBox.Kind.TITLE)
                }
                if (config.showCode) {
                    val top = if (config.showTitle) 7.0 else margin
                    val font = resolveCodeFont(config, clean, height * 0.35)
                    out += ElementBox(clean, margin, top, contentW, height * 0.4, font, ElementBox.Kind.CODE)
                }
                if (config.showRemark && remarkText.isNotEmpty()) {
                    out += ElementBox(
                        remarkText, margin, height - 16.0, contentW, 4.0, 2.4, ElementBox.Kind.REMARK
                    )
                }
                if (config.showBarcode) {
                    out += ElementBox(
                        clean, margin + 2, height - 10.5, contentW - 4, 8.5, 2.0, ElementBox.Kind.BARCODE
                    )
                }
            }
        }
        return out
    }

    private fun resolveCodeFont(config: PrintLayoutConfig, code: String, availH: Double): Double {
        val base = when {
            code.length <= 4 -> 12.0
            code.length <= 6 -> 10.0
            code.length <= 8 -> 8.0
            else -> 6.5
        }
        val preferred = config.codeFontMm.toDouble().takeIf { it > 0 } ?: base
        return preferred.coerceIn(4.0, availH.coerceAtLeast(4.0))
    }
}
