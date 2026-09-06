package com.pickup.print

import android.content.Context
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

/** 自定义元素左上角坐标（毫米）。 */
data class ElementPos(val xMm: Float, val yMm: Float)

data class PrintLayoutConfig(
    val paperWidthMm: Float = 48f,
    val paperHeightMm: Float = 40f,
    val preset: LayoutPreset = LayoutPreset.TITLE_TOP,
    val showTitle: Boolean = true,
    val showCode: Boolean = true,
    val showRemark: Boolean = true,
    val showBarcode: Boolean = true,
    val titleText: String = "上门取件码",
    val codeFontMm: Float = 10f,
    /** key = ElementBox.Kind.name */
    val customPositions: Map<String, ElementPos> = emptyMap()
)

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

    fun loadLayout(): PrintLayoutConfig = PrintLayoutConfig(
        paperWidthMm = prefs.getFloat(KEY_W, 48f),
        paperHeightMm = prefs.getFloat(KEY_H, 40f),
        preset = LayoutPreset.fromId(prefs.getString(KEY_PRESET, LayoutPreset.TITLE_TOP.id)),
        showTitle = prefs.getBoolean(KEY_SHOW_TITLE, true),
        showCode = prefs.getBoolean(KEY_SHOW_CODE, true),
        showRemark = prefs.getBoolean(KEY_SHOW_REMARK, true),
        showBarcode = prefs.getBoolean(KEY_SHOW_BARCODE, true),
        titleText = prefs.getString(KEY_TITLE, "上门取件码") ?: "上门取件码",
        codeFontMm = prefs.getFloat(KEY_CODE_FONT, 10f),
        customPositions = loadPositions(prefs.getString(KEY_POSITIONS, null))
    )

    fun saveLayout(config: PrintLayoutConfig) {
        prefs.edit()
            .putFloat(KEY_W, config.paperWidthMm)
            .putFloat(KEY_H, config.paperHeightMm)
            .putString(KEY_PRESET, config.preset.id)
            .putBoolean(KEY_SHOW_TITLE, config.showTitle)
            .putBoolean(KEY_SHOW_CODE, config.showCode)
            .putBoolean(KEY_SHOW_REMARK, config.showRemark)
            .putBoolean(KEY_SHOW_BARCODE, config.showBarcode)
            .putString(KEY_TITLE, config.titleText)
            .putFloat(KEY_CODE_FONT, config.codeFontMm)
            .putString(KEY_POSITIONS, savePositions(config.customPositions))
            .apply()
    }

    private fun loadPositions(raw: String?): Map<String, ElementPos> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { key ->
                    val item = obj.getJSONObject(key)
                    put(key, ElementPos(item.getDouble("x").toFloat(), item.getDouble("y").toFloat()))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun savePositions(map: Map<String, ElementPos>): String {
        val obj = JSONObject()
        map.forEach { (k, v) ->
            obj.put(k, JSONObject().put("x", v.xMm.toDouble()).put("y", v.yMm.toDouble()))
        }
        return obj.toString()
    }

    companion object {
        private const val PREFS = "print_prefs"
        private const val KEY_DEFAULT_PRINTER = "default_printer_key"
        private const val KEY_DEFAULT_NAME = "default_printer_name"
        private const val KEY_W = "paper_w"
        private const val KEY_H = "paper_h"
        private const val KEY_PRESET = "preset"
        private const val KEY_SHOW_TITLE = "show_title"
        private const val KEY_SHOW_CODE = "show_code"
        private const val KEY_SHOW_REMARK = "show_remark"
        private const val KEY_SHOW_BARCODE = "show_barcode"
        private const val KEY_TITLE = "title_text"
        private const val KEY_CODE_FONT = "code_font"
        private const val KEY_POSITIONS = "custom_positions"
    }
}
