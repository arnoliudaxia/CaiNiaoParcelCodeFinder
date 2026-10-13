package com.paddle.ocr.demo.ui.component

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.nativeCanvas
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.demo.CameraTargetHit
import kotlin.math.max
import kotlin.math.min

private val numericPattern = Regex("[0-9０-９+＋\\-－−./／:：%％#＃×*＝=()（）\\[\\]【】,，]+")
private val fourDigitsPattern = Regex("[0-9]{4}")
private val digitHyphenDigitPattern = Regex("^[0-9]-[0-9]")

private fun isNumeric(text: String): Boolean =
    text.trim().isNotEmpty() && numericPattern.matches(text.trim())

@Composable
fun ImagePreview(
    bitmap: Bitmap,
    results: List<OCRResult>,
    modifier: Modifier = Modifier,
    targetHit: CameraTargetHit? = null,
) {
    val imageBitmap: ImageBitmap = bitmap.asImageBitmap()
    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = 30f
        }
    }
    val linePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val occupied = remember { mutableListOf<android.graphics.RectF>() }

    Box(modifier = modifier) {
        Image(
            bitmap = imageBitmap,
            contentDescription = "OCR Input",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
        )
        Canvas(modifier = Modifier.matchParentSize()) {
            val scaleX = size.width / bitmap.width
            val scaleY = size.height / bitmap.height
            occupied.clear()

            results.forEachIndexed { resultIndex, result ->
                val isTarget = targetHit?.indices?.contains(resultIndex) == true
                val text = result.text.trim()
                val label = if (isTarget) "命中 ${targetHit?.target}" else text
                val numeric = isNumeric(text)
                val fourDigits = fourDigitsPattern.matches(text)
                val digitHyphenDigit = digitHyphenDigitPattern.containsMatchIn(text)
                val points = result.box.points
                val path = Path().apply {
                    points.forEachIndexed { index, point ->
                        val x = point.x * scaleX
                        val y = point.y * scaleY
                        if (index == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }

                val color = when {
                    isTarget -> Color.Red
                    !numeric -> Color(0xFFB4B4B4)
                    digitHyphenDigit -> Color(0xFF00B450)
                    fourDigits -> Color(0xFF006EFF)
                    else -> Color(0xFFFF3030)
                }
                if (numeric && (digitHyphenDigit || fourDigits)) {
                    drawPath(path, color.copy(alpha = 0.29f))
                }
                if (isTarget) drawPath(path, Color.White, style = Stroke(width = 8.dp.toPx()))
                drawPath(path, color = color, style = Stroke(width = if (isTarget) 5.dp.toPx() else if (numeric) 5f else 3f))

                // Match the Python script: non-numeric text keeps only a grey box.
                if (!numeric) return@forEachIndexed

                labelPaint.textSize = 30f
                val metrics = labelPaint.fontMetrics
                val textWidth = labelPaint.measureText(label)
                val labelWidth = textWidth + 14f
                val labelHeight = metrics.bottom - metrics.top + 14f
                val x1 = points.minOf { it.x } * scaleX
                val y1 = points.minOf { it.y } * scaleY
                val x2 = points.maxOf { it.x } * scaleX
                val y2 = points.maxOf { it.y } * scaleY
                val candidates = listOf(
                    floatArrayOf(x1, y1 - labelHeight - 6f),
                    floatArrayOf(x1, y2 + 6f),
                    floatArrayOf(x2 + 7f, y1),
                    floatArrayOf(x1 - labelWidth - 7f, y1),
                ).map { candidate ->
                    android.graphics.PointF(
                        candidate[0].coerceIn(0f, max(0f, size.width - labelWidth)),
                        candidate[1].coerceIn(0f, max(0f, size.height - labelHeight)),
                    )
                }
                val position = candidates.minByOrNull { candidate ->
                    val rect = android.graphics.RectF(
                        candidate.x, candidate.y,
                        candidate.x + labelWidth, candidate.y + labelHeight,
                    )
                    occupied.count { android.graphics.RectF.intersects(it, rect) }
                } ?: candidates.first()
                val rect = android.graphics.RectF(
                    position.x, position.y,
                    position.x + labelWidth, position.y + labelHeight,
                )
                occupied += rect

                val background = when {
                    isTarget -> android.graphics.Color.RED
                    digitHyphenDigit -> android.graphics.Color.argb(180, 0, 140, 65)
                    fourDigits -> android.graphics.Color.argb(180, 0, 90, 220)
                    else -> android.graphics.Color.BLACK
                }
                val canvas = drawContext.canvas.nativeCanvas
                canvas.drawRect(rect, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = background
                    style = Paint.Style.FILL
                })
                labelPaint.color = if (isTarget || result.confidence >= 0.5f) {
                    android.graphics.Color.WHITE
                } else {
                    android.graphics.Color.rgb(255, 208, 96)
                }
                canvas.drawText(
                    label,
                    position.x + 7f,
                    position.y + 7f - metrics.top,
                    labelPaint,
                )
            }
        }
    }
}
