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

/** 按毫米纸张比例模拟标签预览；可点选元素并拖动调整位置。 */
class LabelPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onPositionsChanged(positions: Map<String, ElementPos>)
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
    private var dragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var workingPositions: MutableMap<String, ElementPos> = linkedMapOf()

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

    fun setEditable(enabled: Boolean, listener: Listener? = null) {
        this.editable = enabled
        this.listener = listener
        isClickable = enabled
    }

    fun setPreview(config: PrintLayoutConfig, code: String, remark: String?) {
        this.config = config
        this.sampleCode = code.ifBlank { "8842" }
        this.sampleRemark = remark?.ifBlank { "备注" } ?: "备注"
        workingPositions = config.customPositions.toMutableMap()
        invalidate()
    }

    fun currentPositions(): Map<String, ElementPos> = workingPositions.toMap()

    fun clearCustomPositions() {
        workingPositions.clear()
        selectedKind = null
        listener?.onPositionsChanged(emptyMap())
        listener?.onSelectionChanged(null)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 20f
        val availW = width - pad * 2
        val availH = height - pad * 2 - 28f
        if (availW <= 0 || availH <= 0) return

        val paperW = config.paperWidthMm.coerceAtLeast(10f)
        val paperH = config.paperHeightMm.coerceAtLeast(10f)
        scale = min(availW / paperW, availH / paperH)
        val drawW = paperW * scale
        val drawH = paperH * scale
        paperLeft = (width - drawW) / 2f
        paperTop = (height - drawH) / 2f + 8f
        val rect = RectF(paperLeft, paperTop, paperLeft + drawW, paperTop + drawH)

        canvas.drawRoundRect(rect, 8f, 8f, paperPaint)
        canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

        canvas.drawText(
            "${paperW.toInt()}×${paperH.toInt()} mm · 点选拖动调整",
            width / 2f,
            paperTop - 10f,
            hintPaint
        )

        val cfg = config.copy(customPositions = workingPositions)
        liveElements = PrintLayoutEngine.buildElements(cfg, sampleCode, sampleRemark)
        for (el in liveElements) {
            val x = paperLeft + (el.x * scale).toFloat()
            val y = paperTop + (el.y * scale).toFloat()
            val w = (el.w * scale).toFloat()
            val h = (el.h * scale).toFloat()
            val box = RectF(x, y, x + w, y + h)
            if (el.kind == selectedKind) {
                canvas.drawRect(box, selectFill)
                canvas.drawRect(box, selectPaint)
            }
            when (el.kind) {
                PrintLayoutEngine.ElementBox.Kind.BARCODE -> drawFakeBarcode(canvas, x, y, w, h)
                else -> {
                    textPaint.textSize = (el.fontMm * scale).toFloat().coerceAtLeast(10f)
                    textPaint.isFakeBoldText = el.kind == PrintLayoutEngine.ElementBox.Kind.CODE
                    val cx = x + w / 2f
                    val cy = y + h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                    canvas.drawText(el.text, cx, cy, textPaint)
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
                listener?.onSelectionChanged(hit)
                dragging = hit != null
                lastTouchX = event.x
                lastTouchY = event.y
                parent?.requestDisallowInterceptTouchEvent(hit != null)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val kind = selectedKind ?: return true
                if (!dragging) return true
                val dxPx = event.x - lastTouchX
                val dyPx = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y
                val el = liveElements.find { it.kind == kind } ?: return true
                val cur = workingPositions[kind.name]
                    ?: ElementPos(el.x.toFloat(), el.y.toFloat())
                val nx = (cur.xMm + dxPx / scale).coerceIn(0f, (config.paperWidthMm - 2f).coerceAtLeast(0f))
                val ny = (cur.yMm + dyPx / scale).coerceIn(0f, (config.paperHeightMm - 2f).coerceAtLeast(0f))
                workingPositions[kind.name] = ElementPos(nx, ny)
                listener?.onPositionsChanged(workingPositions.toMap())
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): PrintLayoutEngine.ElementBox.Kind? {
        // 从上到下逆序，优先点到上层
        for (el in liveElements.asReversed()) {
            val l = paperLeft + (el.x * scale).toFloat()
            val t = paperTop + (el.y * scale).toFloat()
            val r = l + (el.w * scale).toFloat()
            val b = t + (el.h * scale).toFloat()
            // 扩大一点点击区域
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
        textPaint.textSize = h * 0.22f
        textPaint.isFakeBoldText = false
        canvas.drawText(sampleCode, x + w / 2f, y + h, textPaint)
    }
}
