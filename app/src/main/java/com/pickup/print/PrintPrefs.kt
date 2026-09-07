package com.pickup.print

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class LayoutPreset(val id: String, val label: String) {
    TITLE_TOP("title_top", "上标题 · 中取件码 · 下条码"),
    CENTER_CODE("center_code", "居中大号取件码"),
    COMPACT("compact", "紧凑（小纸）"),
    CODE_BARCODE("code_barcode", "取件码 + 条码");

    companion object {
        fun fromId(id: String?): LayoutPreset =
            entries.find { it.id == id } ?: TITLE_TOP
    }
}

enum class TextHAlign(val id: String, val label: String) {
    LEFT("left", "左对齐"),
    CENTER("center", "居中"),
    RIGHT("right", "右对齐");

    companion object {
        fun fromId(id: String?): TextHAlign =
            entries.find { it.id == id } ?: CENTER
    }
}

/** 打印方向：布局仍按竖向排版，横向时逻辑宽高互换并旋转 90° 打印。 */
enum class PrintOrientation(val id: String, val label: String, val degrees: Int) {
    PORTRAIT("portrait", "竖向", 0),
    LANDSCAPE("landscape", "横向", 90);

    companion object {
        fun fromId(id: String?): PrintOrientation =
            entries.find { it.id == id } ?: PORTRAIT
    }
}

data class PrintLayoutConfig(
    val paperWidthMm: Float = 48f,
    val paperHeightMm: Float = 40f,
    val orientation: PrintOrientation = PrintOrientation.PORTRAIT,
    val preset: LayoutPreset = LayoutPreset.TITLE_TOP,
    /** key = ElementBox.Kind.name（仅文字元素：TITLE/CODE/REMARK） */
    val textAligns: Map<String, TextHAlign> = emptyMap(),
    val showTitle: Boolean = true,
    val showCode: Boolean = true,
    val showRemark: Boolean = true,
    val showBarcode: Boolean = true,
    val titleText: String = "上门取件码",
    /** 兼容旧配置：未单独设 CODE 字号时用作默认 */
    val codeFontMm: Float = 10f,
    /** key = ElementBox.Kind.name → 字号 mm */
    val fontSizes: Map<String, Float> = emptyMap(),
    /** 行内容顺序（Kind.name），几何位固定，只交换内容 */
    val rowOrder: List<String> = emptyList()
) {
    /** 排版用宽度：横向时与纸张高互换，间距随此自适应。 */
    val layoutWidthMm: Float
        get() = if (orientation == PrintOrientation.LANDSCAPE) paperHeightMm else paperWidthMm

    /** 排版用高度：横向时与纸张宽互换。 */
    val layoutHeightMm: Float
        get() = if (orientation == PrintOrientation.LANDSCAPE) paperWidthMm else paperHeightMm

    fun alignFor(kind: PrintLayoutEngine.ElementBox.Kind): TextHAlign =
        textAligns[kind.name] ?: TextHAlign.CENTER

    fun fontSizeFor(kind: PrintLayoutEngine.ElementBox.Kind): Float? {
        fontSizes[kind.name]?.let { return it }
        if (kind == PrintLayoutEngine.ElementBox.Kind.CODE) return codeFontMm
        return null
    }
}

class PrintPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var defaultPrinterKey: String?
        get() = prefs.getString(KEY_DEFAULT_PRINTER, null)
        set(value) = prefs.edit().putString(KEY_DEFAULT_PRINTER, value).apply()

    var defaultPrinterName: String?
        get() = prefs.getString(KEY_DEFAULT_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEFAULT_NAME, value).apply()

    fun setDefaultPrinter(key: String?, name: String?) {
        prefs.edit()
            .putString(KEY_DEFAULT_PRINTER, key)
            .putString(KEY_DEFAULT_NAME, name)
            .apply()
    }

    fun loadLayout(category: PrintCategory = PrintCategory.PICKUP): PrintLayoutConfig {
        val p = keyPrefix(category)
        val defaults = defaultLayout(category)
        return PrintLayoutConfig(
            paperWidthMm = prefs.getFloat(p + KEY_W, defaults.paperWidthMm),
            paperHeightMm = prefs.getFloat(p + KEY_H, defaults.paperHeightMm),
            orientation = PrintOrientation.fromId(
                prefs.getString(p + KEY_ORIENTATION, defaults.orientation.id)
            ),
            preset = LayoutPreset.fromId(prefs.getString(p + KEY_PRESET, defaults.preset.id)),
            textAligns = loadAligns(prefs.getString(p + KEY_ALIGNS, null)),
            showTitle = prefs.getBoolean(p + KEY_SHOW_TITLE, defaults.showTitle),
            showCode = prefs.getBoolean(p + KEY_SHOW_CODE, defaults.showCode),
            showRemark = prefs.getBoolean(p + KEY_SHOW_REMARK, defaults.showRemark),
            showBarcode = prefs.getBoolean(p + KEY_SHOW_BARCODE, defaults.showBarcode),
            titleText = prefs.getString(p + KEY_TITLE, defaults.titleText) ?: defaults.titleText,
            codeFontMm = prefs.getFloat(p + KEY_CODE_FONT, defaults.codeFontMm),
            fontSizes = loadFontSizes(prefs.getString(p + KEY_FONTS, null)),
            rowOrder = loadRowOrder(prefs.getString(p + KEY_ROW_ORDER, null))
        )
    }

    fun saveLayout(config: PrintLayoutConfig, category: PrintCategory = PrintCategory.PICKUP) {
        val p = keyPrefix(category)
        prefs.edit()
            .putFloat(p + KEY_W, config.paperWidthMm)
            .putFloat(p + KEY_H, config.paperHeightMm)
            .putString(p + KEY_ORIENTATION, config.orientation.id)
            .putString(p + KEY_PRESET, config.preset.id)
            .remove(KEY_ALIGN_LEGACY)
            .remove(KEY_POSITIONS_LEGACY)
            .putString(p + KEY_ALIGNS, saveAligns(config.textAligns))
            .putBoolean(p + KEY_SHOW_TITLE, config.showTitle)
            .putBoolean(p + KEY_SHOW_CODE, config.showCode)
            .putBoolean(p + KEY_SHOW_REMARK, config.showRemark)
            .putBoolean(p + KEY_SHOW_BARCODE, config.showBarcode)
            .putString(p + KEY_TITLE, config.titleText)
            .putFloat(
                p + KEY_CODE_FONT,
                config.fontSizes[PrintLayoutEngine.ElementBox.Kind.CODE.name] ?: config.codeFontMm
            )
            .putString(p + KEY_FONTS, saveFontSizes(config.fontSizes))
            .putString(p + KEY_ROW_ORDER, saveRowOrder(config.rowOrder))
            .apply()
    }

    /** 取件码沿用旧 key；杯贴用独立前缀，互不影响。 */
    private fun keyPrefix(category: PrintCategory): String =
        if (category == PrintCategory.PICKUP) "" else "${category.id}_"

    /** 用户保存的自定义纸张尺寸（不含内置预设）。 */
    fun loadCustomPaperSizes(): List<Pair<Float, Float>> {
        val raw = prefs.getString(KEY_CUSTOM_PAPERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(obj.getDouble("w").toFloat() to obj.getDouble("h").toFloat())
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCustomPaperSizes(sizes: List<Pair<Float, Float>>) {
        val arr = JSONArray()
        sizes.distinct().forEach { (w, h) ->
            arr.put(JSONObject().put("w", w.toDouble()).put("h", h.toDouble()))
        }
        prefs.edit().putString(KEY_CUSTOM_PAPERS, arr.toString()).apply()
    }

    fun addCustomPaperSize(widthMm: Float, heightMm: Float): List<Pair<Float, Float>> {
        val w = widthMm.coerceIn(20f, 80f)
        val h = heightMm.coerceIn(15f, 100f)
        val next = (loadCustomPaperSizes() + (w to h)).distinct().takeLast(12)
        saveCustomPaperSizes(next)
        return next
    }

    private fun loadAligns(raw: String?): Map<String, TextHAlign> {
        if (!raw.isNullOrBlank()) {
            return try {
                val obj = JSONObject(raw)
                buildMap {
                    obj.keys().forEach { key ->
                        put(key, TextHAlign.fromId(obj.getString(key)))
                    }
                }
            } catch (_: Exception) {
                emptyMap()
            }
        }
        val legacy = prefs.getString(KEY_ALIGN_LEGACY, null) ?: return emptyMap()
        val align = TextHAlign.fromId(legacy)
        return mapOf(
            PrintLayoutEngine.ElementBox.Kind.TITLE.name to align,
            PrintLayoutEngine.ElementBox.Kind.CODE.name to align,
            PrintLayoutEngine.ElementBox.Kind.REMARK.name to align
        )
    }

    private fun saveAligns(map: Map<String, TextHAlign>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v.id) }
        return obj.toString()
    }

    private fun loadFontSizes(raw: String?): Map<String, Float> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { key ->
                    put(key, obj.getDouble(key).toFloat())
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveFontSizes(map: Map<String, Float>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v.toDouble()) }
        return obj.toString()
    }

    private fun loadRowOrder(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.getString(i))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveRowOrder(order: List<String>): String {
        val arr = JSONArray()
        order.forEach { arr.put(it) }
        return arr.toString()
    }

    companion object {
        fun defaultLayout(category: PrintCategory): PrintLayoutConfig = when (category) {
            PrintCategory.PICKUP -> PrintLayoutConfig()
            PrintCategory.TEA_CUP -> PrintLayoutConfig(
                paperWidthMm = 40f,
                paperHeightMm = 30f,
                preset = LayoutPreset.CENTER_CODE,
                showTitle = true,
                showCode = true,
                showRemark = true,
                showBarcode = false,
                titleText = "取茶号",
                codeFontMm = 12f,
            )
        }

        private const val PREFS = "print_prefs"
        private const val KEY_DEFAULT_PRINTER = "default_printer_key"
        private const val KEY_DEFAULT_NAME = "default_printer_name"
        private const val KEY_W = "paper_w"
        private const val KEY_H = "paper_h"
        private const val KEY_ORIENTATION = "print_orientation"
        private const val KEY_PRESET = "preset"
        private const val KEY_ALIGN_LEGACY = "text_align"
        private const val KEY_POSITIONS_LEGACY = "custom_positions"
        private const val KEY_ALIGNS = "text_aligns"
        private const val KEY_SHOW_TITLE = "show_title"
        private const val KEY_SHOW_CODE = "show_code"
        private const val KEY_SHOW_REMARK = "show_remark"
        private const val KEY_SHOW_BARCODE = "show_barcode"
        private const val KEY_TITLE = "title_text"
        private const val KEY_CODE_FONT = "code_font"
        private const val KEY_FONTS = "font_sizes"
        private const val KEY_ROW_ORDER = "row_order"
        private const val KEY_CUSTOM_PAPERS = "custom_paper_sizes"
    }
}
