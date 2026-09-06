package com.pickup.print

/**
 * 根据纸张尺寸与预设，计算各元素在标签上的毫米坐标。
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
        val base = buildPresetElements(config, code, remark)
        if (config.customPositions.isEmpty()) return base
        return base.map { el ->
            val pos = config.customPositions[el.kind.name] ?: return@map el
            el.copy(x = pos.xMm.toDouble(), y = pos.yMm.toDouble())
        }
    }

    private fun buildPresetElements(
        config: PrintLayoutConfig,
        code: String,
        remark: String?
    ): List<ElementBox> {
        val width = config.paperWidthMm.toDouble()
        val height = config.paperHeightMm.toDouble()
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
