package com.pickup.print

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pickup.print.databinding.ActivityPrintLayoutBinding

class PrintLayoutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrintLayoutBinding
    private lateinit var prefs: PrintPrefs
    private var updating = false
    private var customPositions: Map<String, ElementPos> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrintLayoutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.apply(this, binding.root)

        prefs = PrintPrefs(this)
        val config = prefs.loadLayout()
        customPositions = config.customPositions

        binding.etWidth.setText(config.paperWidthMm.toString())
        binding.etHeight.setText(config.paperHeightMm.toString())
        binding.etTitle.setText(config.titleText)
        binding.etCodeFont.setText(config.codeFontMm.toString())
        binding.swTitle.isChecked = config.showTitle
        binding.swCode.isChecked = config.showCode
        binding.swRemark.isChecked = config.showRemark
        binding.swBarcode.isChecked = config.showBarcode

        val labels = LayoutPreset.entries.map { it.label }
        binding.spinnerPreset.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        binding.spinnerPreset.setSelection(LayoutPreset.entries.indexOf(config.preset).coerceAtLeast(0))

        binding.labelPreview.setEditable(true, object : LabelPreviewView.Listener {
            override fun onPositionsChanged(positions: Map<String, ElementPos>) {
                customPositions = positions
            }

            override fun onSelectionChanged(kind: PrintLayoutEngine.ElementBox.Kind?) {
                binding.tvSelectedHint.text = when (kind) {
                    null -> "点选预览中的文字/条码，拖动调整位置"
                    PrintLayoutEngine.ElementBox.Kind.TITLE -> "已选中：标题 — 拖动移动"
                    PrintLayoutEngine.ElementBox.Kind.CODE -> "已选中：取件码 — 拖动移动"
                    PrintLayoutEngine.ElementBox.Kind.REMARK -> "已选中：备注 — 拖动移动"
                    PrintLayoutEngine.ElementBox.Kind.BARCODE -> "已选中：一维码 — 拖动移动"
                }
            }
        })

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener {
            prefs.saveLayout(currentConfig())
            Toast.makeText(this, "打印布局已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
        binding.btnResetPos.setOnClickListener {
            customPositions = emptyMap()
            binding.labelPreview.clearCustomPositions()
            refreshPreview()
            Toast.makeText(this, "已恢复预设位置", Toast.LENGTH_SHORT).show()
        }

        binding.chip48x40.setOnClickListener { setSize(48f, 40f) }
        binding.chip40x30.setOnClickListener { setSize(40f, 30f) }
        binding.chip50x30.setOnClickListener { setSize(50f, 30f) }
        binding.chip50x40.setOnClickListener { setSize(50f, 40f) }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshPreview()
        }
        binding.etWidth.addTextChangedListener(watcher)
        binding.etHeight.addTextChangedListener(watcher)
        binding.etTitle.addTextChangedListener(watcher)
        binding.etCodeFont.addTextChangedListener(watcher)
        binding.etSampleCode.addTextChangedListener(watcher)

        binding.swTitle.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        binding.swCode.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        binding.swRemark.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        binding.swBarcode.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        binding.spinnerPreset.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    // 换预设时清自定义位置，避免错位
                    customPositions = emptyMap()
                    binding.labelPreview.clearCustomPositions()
                    refreshPreview()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }

        refreshPreview()
    }

    private fun setSize(w: Float, h: Float) {
        updating = true
        binding.etWidth.setText(w.toString())
        binding.etHeight.setText(h.toString())
        updating = false
        refreshPreview()
    }

    private fun currentConfig(): PrintLayoutConfig {
        val w = binding.etWidth.text?.toString()?.toFloatOrNull() ?: 48f
        val h = binding.etHeight.text?.toString()?.toFloatOrNull() ?: 40f
        val font = binding.etCodeFont.text?.toString()?.toFloatOrNull() ?: 10f
        val preset = LayoutPreset.entries.getOrElse(binding.spinnerPreset.selectedItemPosition) {
            LayoutPreset.TITLE_TOP
        }
        // 以预览控件里的最新拖动结果为准
        val positions = binding.labelPreview.currentPositions().ifEmpty { customPositions }
        return PrintLayoutConfig(
            paperWidthMm = w.coerceIn(20f, 80f),
            paperHeightMm = h.coerceIn(15f, 100f),
            preset = preset,
            showTitle = binding.swTitle.isChecked,
            showCode = binding.swCode.isChecked,
            showRemark = binding.swRemark.isChecked,
            showBarcode = binding.swBarcode.isChecked,
            titleText = binding.etTitle.text?.toString()?.ifBlank { "上门取件码" } ?: "上门取件码",
            codeFontMm = font.coerceIn(4f, 18f),
            customPositions = positions
        )
    }

    private fun refreshPreview() {
        if (updating) return
        val code = binding.etSampleCode.text?.toString()?.ifBlank { "8842" } ?: "8842"
        binding.labelPreview.setPreview(currentConfig(), code, "备注示例")
    }
}
