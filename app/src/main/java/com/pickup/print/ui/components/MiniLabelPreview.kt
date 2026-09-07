package com.pickup.print.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.pickup.print.PrintLayoutConfig
import com.pickup.print.PrintLayoutEngine
import com.pickup.print.TextHAlign
import kotlin.math.min

/**
 * 按打印纸张比例绘制迷你标签预览（标题 / 码 / 备注 / 假条码）。
 */
@Composable
fun MiniLabelPreview(
    config: PrintLayoutConfig,
    code: String,
    remark: String?,
    modifier: Modifier = Modifier,
) {
    val paperW = config.layoutWidthMm.coerceAtLeast(10f)
    val paperH = config.layoutHeightMm.coerceAtLeast(10f)
    val elements = remember(config, code, remark) {
        PrintLayoutEngine.buildElements(config, code.ifBlank { "——" }, remark)
    }
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(paperW / paperH)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, Color(0xFFD6D3D1), shape),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = min(size.width / paperW, size.height / paperH)
            val drawW = paperW * scale
            val drawH = paperH * scale
            val left = (size.width - drawW) / 2f
            val top = (size.height - drawH) / 2f

            val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val barcodePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                style = android.graphics.Paint.Style.FILL
            }

            for (el in elements) {
                val x = left + (el.x * scale).toFloat()
                val y = top + (el.y * scale).toFloat()
                val w = (el.w * scale).toFloat()
                val h = (el.h * scale).toFloat()
                when (el.kind) {
                    PrintLayoutEngine.ElementBox.Kind.BARCODE -> {
                        var cursor = x
                        var i = 0
                        while (cursor < x + w) {
                            val barW = if (i % 3 == 0) 3f else 1.5f
                            if (i % 2 == 0) {
                                drawContext.canvas.nativeCanvas.drawRect(
                                    cursor, y, cursor + barW, y + h * 0.75f, barcodePaint
                                )
                            }
                            cursor += barW + 1.2f
                            i++
                        }
                        textPaint.textAlign = android.graphics.Paint.Align.CENTER
                        textPaint.textSize = h * 0.22f
                        textPaint.isFakeBoldText = false
                        drawContext.canvas.nativeCanvas.drawText(
                            code.ifBlank { "——" },
                            x + w / 2f,
                            y + h,
                            textPaint,
                        )
                    }
                    else -> {
                        textPaint.textSize = (el.fontMm * scale).toFloat().coerceAtLeast(6f)
                        textPaint.isFakeBoldText =
                            el.kind == PrintLayoutEngine.ElementBox.Kind.CODE
                        val ty = y + h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                        when (config.alignFor(el.kind)) {
                            TextHAlign.LEFT -> {
                                textPaint.textAlign = android.graphics.Paint.Align.LEFT
                                drawContext.canvas.nativeCanvas.drawText(el.text, x + 2f, ty, textPaint)
                            }
                            TextHAlign.RIGHT -> {
                                textPaint.textAlign = android.graphics.Paint.Align.RIGHT
                                drawContext.canvas.nativeCanvas.drawText(el.text, x + w - 2f, ty, textPaint)
                            }
                            TextHAlign.CENTER -> {
                                textPaint.textAlign = android.graphics.Paint.Align.CENTER
                                drawContext.canvas.nativeCanvas.drawText(el.text, x + w / 2f, ty, textPaint)
                            }
                        }
                    }
                }
            }

            // 边框已由 Compose border 处理；此处可补内阴影线
            drawRect(
                color = Color(0xFFD6D3D1).copy(alpha = 0.35f),
                topLeft = Offset(left, top),
                size = Size(drawW, drawH),
                style = Stroke(width = 1f),
            )
        }
    }
}
