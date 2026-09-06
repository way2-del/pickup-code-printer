package com.pickup.print

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** 按毫米纸张比例模拟标签预览；点选后拖到另一行可交换内容。 */
class LabelPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onOrderChanged(order: List<String>)
        fun onSelectionChanged(kind: PrintLayoutEngine.ElementBox.Kind?)
    }

    private var config: PrintLayoutConfig = PrintLayoutConfig()
    private var sampleCode: String = "8842"
    private var sampleRemark: String = "顺丰"
    private var editable: Boolean = false
    private var listener: Listener? = null

    private var paperLeft = 0f
    private var paperTop = 0f
    private var scale = 1f
    private var liveElements: List<PrintLayoutEngine.ElementBox> = emptyList()
    private var selectedKind: PrintLayoutEngine.ElementBox.Kind? = null
    private var dragKind: PrintLayoutEngine.ElementBox.Kind? = null
    private var dropTarget: PrintLayoutEngine.ElementBox.Kind? = null
    private var workingOrder: MutableList<String> = mutableListOf()

    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD6D3D1.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
    }
    private val barcodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA8A29E.toInt()
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0F766E.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val selectFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x220F766E
        style = Paint.Style.FILL
    }
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEA580C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val dropFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22EA580C
        style = Paint.Style.FILL
    }

    fun setEditable(enabled: Boolean, listener: Listener? = null) {
        this.editable = enabled
        this.listener = listener
        isClickable = enabled
    }

    fun setPreview(config: PrintLayoutConfig, code: String, remark: String?) {
        this.config = config
        this.sampleCode = code.ifBlank { "8842" }
        this.sampleRemark = remark?.ifBlank { "备注" } ?: "备注"
        workingOrder = config.rowOrder.toMutableList()
        invalidate()
    }

    fun currentOrder(): List<String> = workingOrder.toList()

    fun elementOf(kind: PrintLayoutEngine.ElementBox.Kind): PrintLayoutEngine.ElementBox? {
        val cfg = config.copy(rowOrder = workingOrder)
        return PrintLayoutEngine.buildElements(cfg, sampleCode, sampleRemark)
            .find { it.kind == kind }
    }

    fun resetOrder() {
        workingOrder.clear()
        selectedKind = null
        dragKind = null
        dropTarget = null
        listener?.onOrderChanged(emptyList())
        listener?.onSelectionChanged(null)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 20f
        val availW = width - pad * 2
        val availH = height - pad * 2 - 28f
        if (availW <= 0 || availH <= 0) return

        val paperW = config.layoutWidthMm.coerceAtLeast(10f)
        val paperH = config.layoutHeightMm.coerceAtLeast(10f)
        scale = min(availW / paperW, availH / paperH)
        val drawW = paperW * scale
        val drawH = paperH * scale
        paperLeft = (width - drawW) / 2f
        paperTop = (height - drawH) / 2f + 8f
        val rect = RectF(paperLeft, paperTop, paperLeft + drawW, paperTop + drawH)

        canvas.drawRoundRect(rect, 8f, 8f, paperPaint)
        canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

        val dir = config.orientation.label
        canvas.drawText(
            "${config.paperWidthMm.toInt()}×${config.paperHeightMm.toInt()} mm · $dir · 拖行交换",
            width / 2f,
            paperTop - 10f,
            hintPaint
        )

        val cfg = config.copy(rowOrder = workingOrder)
        liveElements = PrintLayoutEngine.buildElements(cfg, sampleCode, sampleRemark)
        for (el in liveElements) {
            val x = paperLeft + (el.x * scale).toFloat()
            val y = paperTop + (el.y * scale).toFloat()
            val w = (el.w * scale).toFloat()
            val h = (el.h * scale).toFloat()
            val box = RectF(x, y, x + w, y + h)
            when {
                el.kind == dropTarget && dropTarget != dragKind -> {
                    canvas.drawRect(box, dropFill)
                    canvas.drawRect(box, dropPaint)
                }
                el.kind == selectedKind || el.kind == dragKind -> {
                    canvas.drawRect(box, selectFill)
                    canvas.drawRect(box, selectPaint)
                }
            }
            when (el.kind) {
                PrintLayoutEngine.ElementBox.Kind.BARCODE -> drawFakeBarcode(canvas, x, y, w, h)
                else -> {
                    // 与打印一致：字画在行框顶部区域内垂直居中显示（框高=字号）
                    textPaint.textSize = (el.fontMm * scale).toFloat().coerceAtLeast(6f)
                    textPaint.isFakeBoldText = el.kind == PrintLayoutEngine.ElementBox.Kind.CODE
                    val ty = y + h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                    when (config.alignFor(el.kind)) {
                        TextHAlign.LEFT -> {
                            textPaint.textAlign = Paint.Align.LEFT
                            canvas.drawText(el.text, x + 2f, ty, textPaint)
                        }
                        TextHAlign.RIGHT -> {
                            textPaint.textAlign = Paint.Align.RIGHT
                            canvas.drawText(el.text, x + w - 2f, ty, textPaint)
                        }
                        TextHAlign.CENTER -> {
                            textPaint.textAlign = Paint.Align.CENTER
                            canvas.drawText(el.text, x + w / 2f, ty, textPaint)
                        }
                    }
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTest(event.x, event.y)
                selectedKind = hit
                dragKind = hit
                dropTarget = null
                listener?.onSelectionChanged(hit)
                parent?.requestDisallowInterceptTouchEvent(hit != null)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragKind == null) return true
                val hit = hitTest(event.x, event.y)
                val next = if (hit != null && hit != dragKind) hit else null
                if (next != dropTarget) {
                    dropTarget = next
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val from = dragKind
                val to = dropTarget
                dragKind = null
                dropTarget = null
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP && from != null && to != null && from != to) {
                    val defaults = PrintLayoutEngine.defaultRowOrder(
                        config.copy(rowOrder = emptyList()),
                        sampleCode,
                        sampleRemark
                    )
                    workingOrder = PrintLayoutEngine.swapOrder(workingOrder, from, to, defaults)
                        .toMutableList()
                    selectedKind = from
                    listener?.onOrderChanged(workingOrder.toList())
                    listener?.onSelectionChanged(from)
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): PrintLayoutEngine.ElementBox.Kind? {
        for (el in liveElements.asReversed()) {
            val l = paperLeft + (el.x * scale).toFloat()
            val t = paperTop + (el.y * scale).toFloat()
            val r = l + (el.w * scale).toFloat()
            val b = t + (el.h * scale).toFloat()
            if (x in (l - 8)..(r + 8) && y in (t - 8)..(b + 8)) return el.kind
        }
        return null
    }

    private fun drawFakeBarcode(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        var cursor = x
        var i = 0
        while (cursor < x + w) {
            val barW = if (i % 3 == 0) 3f else 1.5f
            if (i % 2 == 0) {
                canvas.drawRect(cursor, y, cursor + barW, y + h * 0.75f, barcodePaint)
            }
            cursor += barW + 1.2f
            i++
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = h * 0.22f
        textPaint.isFakeBoldText = false
        canvas.drawText(sampleCode, x + w / 2f, y + h, textPaint)
    }
}
