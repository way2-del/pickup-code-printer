package com.pickup.print

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.pickup.print.databinding.ActivityPrintLayoutBinding
import kotlin.math.roundToInt

class PrintLayoutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrintLayoutBinding
    private lateinit var prefs: PrintPrefs
    private var updating = false
    private var rowOrder: MutableList<String> = mutableListOf()
    private var textAligns: MutableMap<String, TextHAlign> = linkedMapOf()
    private var fontSizes: MutableMap<String, Float> = linkedMapOf()
    private var selectedKind: PrintLayoutEngine.ElementBox.Kind? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrintLayoutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        prefs = PrintPrefs(this)
        val config = prefs.loadLayout()
        rowOrder = config.rowOrder.toMutableList()
        textAligns = config.textAligns.toMutableMap()
        fontSizes = config.fontSizes.toMutableMap()
        if (!fontSizes.containsKey(PrintLayoutEngine.ElementBox.Kind.CODE.name)) {
            fontSizes[PrintLayoutEngine.ElementBox.Kind.CODE.name] = config.codeFontMm
        }

        binding.etWidth.setText(formatMm(config.paperWidthMm))
        binding.etHeight.setText(formatMm(config.paperHeightMm))
        binding.etTitle.setText(config.titleText)
        binding.swTitle.isChecked = config.showTitle
        binding.swCode.isChecked = config.showCode
        binding.swRemark.isChecked = config.showRemark
        binding.swBarcode.isChecked = config.showBarcode
        when (config.orientation) {
            PrintOrientation.PORTRAIT -> binding.chipOrientPortrait.isChecked = true
            PrintOrientation.LANDSCAPE -> binding.chipOrientLandscape.isChecked = true
        }

        val labels = LayoutPreset.entries.map { it.label }
        binding.spinnerPreset.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        binding.spinnerPreset.setSelection(LayoutPreset.entries.indexOf(config.preset).coerceAtLeast(0))

        binding.labelPreview.setEditable(true, object : LabelPreviewView.Listener {
            override fun onOrderChanged(order: List<String>) {
                rowOrder = order.toMutableList()
                refreshPreview()
            }

            override fun onSelectionChanged(kind: PrintLayoutEngine.ElementBox.Kind?) {
                selectedKind = kind
                syncSelectionControls()
                binding.tvSelectedHint.text = when (kind) {
                    null -> "点选元素调字号/对齐；拖到另一行可交换"
                    PrintLayoutEngine.ElementBox.Kind.TITLE -> "已选中：标题 — 可调字号/对齐，拖到其他行交换"
                    PrintLayoutEngine.ElementBox.Kind.CODE -> "已选中：取件码 — 可调字号/对齐，拖到其他行交换"
                    PrintLayoutEngine.ElementBox.Kind.REMARK -> "已选中：备注 — 可调字号/对齐，拖到其他行交换"
                    PrintLayoutEngine.ElementBox.Kind.BARCODE -> "已选中：一维码 — 拖到其他行交换"
                }
            }
        })

        binding.btnBack.setOnClickListener {
            prefs.saveLayout(currentConfig())
            finish()
        }
        binding.btnSave.setOnClickListener {
            prefs.saveLayout(currentConfig())
            Toast.makeText(this, "打印布局已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
        binding.btnResetPos.setOnClickListener {
            rowOrder.clear()
            binding.labelPreview.resetOrder()
            refreshPreview()
            syncSelectionControls()
            Toast.makeText(this, "已恢复预设行顺序", Toast.LENGTH_SHORT).show()
        }

        binding.chip48x40.setOnClickListener { setSize(48f, 40f) }
        binding.chip40x30.setOnClickListener { setSize(40f, 30f) }
        binding.chip50x30.setOnClickListener { setSize(50f, 30f) }
        binding.chip50x40.setOnClickListener { setSize(50f, 40f) }
        binding.chipOrientation.setOnCheckedStateChangeListener { _, _ -> refreshPreview() }

        bindStepper(
            minus = binding.btnFontMinus,
            plus = binding.btnFontPlus,
            field = binding.etFontSize,
            step = FONT_STEP,
            min = 1.5f,
            max = { 18f },
            onCommit = { applyFontFromField() }
        )

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshPreview()
        }
        binding.etWidth.addTextChangedListener(watcher)
        binding.etHeight.addTextChangedListener(watcher)
        binding.etTitle.addTextChangedListener(watcher)
        binding.etSampleCode.addTextChangedListener(watcher)

        binding.swTitle.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        binding.swCode.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        binding.swRemark.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        binding.swBarcode.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        binding.chipAlign.setOnCheckedStateChangeListener { _, _ ->
            if (updating) return@setOnCheckedStateChangeListener
            val kind = selectedKind ?: return@setOnCheckedStateChangeListener
            if (!isTextKind(kind)) return@setOnCheckedStateChangeListener
            textAligns[kind.name] = currentChipAlign()
            refreshPreview()
        }
        binding.spinnerPreset.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    rowOrder.clear()
                    binding.labelPreview.resetOrder()
                    refreshPreview()
                    syncSelectionControls()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }

        syncSelectionControls()
        refreshPreview()
    }

    private fun bindStepper(
        minus: MaterialButton,
        plus: MaterialButton,
        field: TextInputEditText,
        step: Float,
        min: Float,
        max: () -> Float,
        onCommit: () -> Unit
    ) {
        minus.setOnClickListener {
            if (selectedKind == null || !isTextKind(selectedKind!!)) return@setOnClickListener
            val cur = field.text?.toString()?.toFloatOrNull() ?: return@setOnClickListener
            updating = true
            field.setText(formatMm((cur - step).coerceIn(min, max().coerceAtLeast(min))))
            updating = false
            onCommit()
        }
        plus.setOnClickListener {
            if (selectedKind == null || !isTextKind(selectedKind!!)) return@setOnClickListener
            val cur = field.text?.toString()?.toFloatOrNull() ?: return@setOnClickListener
            updating = true
            field.setText(formatMm((cur + step).coerceIn(min, max().coerceAtLeast(min))))
            updating = false
            onCommit()
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updating || selectedKind == null) return
                onCommit()
            }
        })
    }

    private fun isTextKind(kind: PrintLayoutEngine.ElementBox.Kind): Boolean =
        kind != PrintLayoutEngine.ElementBox.Kind.BARCODE

    private fun currentChipAlign(): TextHAlign = when {
        binding.chipAlignLeft.isChecked -> TextHAlign.LEFT
        binding.chipAlignRight.isChecked -> TextHAlign.RIGHT
        else -> TextHAlign.CENTER
    }

    private fun syncSelectionControls() {
        syncAlignChipsFromSelection()
        syncFontFieldFromSelection()
        val textOk = selectedKind != null && isTextKind(selectedKind!!)
        binding.btnFontMinus.isEnabled = textOk
        binding.btnFontPlus.isEnabled = textOk
        binding.etFontSize.isEnabled = textOk
    }

    private fun syncAlignChipsFromSelection() {
        updating = true
        val kind = selectedKind
        val enabled = kind != null && isTextKind(kind)
        binding.chipAlign.isEnabled = enabled
        binding.chipAlignLeft.isEnabled = enabled
        binding.chipAlignCenter.isEnabled = enabled
        binding.chipAlignRight.isEnabled = enabled
        val align = if (kind != null && enabled) {
            textAligns[kind.name] ?: TextHAlign.CENTER
        } else {
            TextHAlign.CENTER
        }
        when (align) {
            TextHAlign.LEFT -> binding.chipAlignLeft.isChecked = true
            TextHAlign.CENTER -> binding.chipAlignCenter.isChecked = true
            TextHAlign.RIGHT -> binding.chipAlignRight.isChecked = true
        }
        updating = false
    }

    private fun syncFontFieldFromSelection() {
        val kind = selectedKind
        updating = true
        if (kind == null || !isTextKind(kind)) {
            binding.etFontSize.setText("")
        } else {
            val el = binding.labelPreview.elementOf(kind)
            val size = fontSizes[kind.name] ?: el?.fontMm?.toFloat() ?: 3f
            binding.etFontSize.setText(formatMm(size))
        }
        updating = false
    }

    private fun applyFontFromField() {
        val kind = selectedKind ?: return
        if (!isTextKind(kind)) return
        val size = binding.etFontSize.text?.toString()?.toFloatOrNull() ?: return
        fontSizes[kind.name] = size.coerceIn(1.5f, 18f)
        refreshPreview()
    }

    private fun setSize(w: Float, h: Float) {
        updating = true
        binding.etWidth.setText(formatMm(w))
        binding.etHeight.setText(formatMm(h))
        updating = false
        refreshPreview()
    }

    private fun currentConfig(): PrintLayoutConfig {
        val w = binding.etWidth.text?.toString()?.toFloatOrNull() ?: 48f
        val h = binding.etHeight.text?.toString()?.toFloatOrNull() ?: 40f
        val preset = LayoutPreset.entries.getOrElse(binding.spinnerPreset.selectedItemPosition) {
            LayoutPreset.TITLE_TOP
        }
        val orientation = if (binding.chipOrientLandscape.isChecked) {
            PrintOrientation.LANDSCAPE
        } else {
            PrintOrientation.PORTRAIT
        }
        val order = binding.labelPreview.currentOrder().ifEmpty { rowOrder }
        val codeFont = fontSizes[PrintLayoutEngine.ElementBox.Kind.CODE.name] ?: 10f
        return PrintLayoutConfig(
            paperWidthMm = w.coerceIn(20f, 80f),
            paperHeightMm = h.coerceIn(15f, 100f),
            orientation = orientation,
            preset = preset,
            textAligns = textAligns.toMap(),
            showTitle = binding.swTitle.isChecked,
            showCode = binding.swCode.isChecked,
            showRemark = binding.swRemark.isChecked,
            showBarcode = binding.swBarcode.isChecked,
            titleText = binding.etTitle.text?.toString()?.ifBlank { "上门取件码" } ?: "上门取件码",
            codeFontMm = codeFont.coerceIn(4f, 18f),
            fontSizes = fontSizes.toMap(),
            rowOrder = order
        )
    }

    private fun refreshPreview() {
        if (updating) return
        val code = binding.etSampleCode.text?.toString()?.ifBlank { "8842" } ?: "8842"
        binding.labelPreview.setPreview(currentConfig(), code, "备注示例")
    }

    private fun formatMm(v: Float): String {
        val rounded = (v * 10f).roundToInt() / 10f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
    }

    companion object {
        private const val FONT_STEP = 0.5f
    }
}
