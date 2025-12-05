package com.example.posetrack

import android.content.Context
import android.graphics.*
import androidx.core.graphics.toColorInt
import android.util.AttributeSet
import android.view.View
import com.example.posetrack.dr.DeadReckoningModule
import kotlin.math.*

class PathView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Paints (preallocated)
    private val pathPaint = Paint().apply {
        color = "#2196F3".toColorInt()
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val startPaint = Paint().apply {
        color = "#4CAF50".toColorInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val currentPaint = Paint().apply {
        color = "#F44336".toColorInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = "#EEEEEE".toColorInt()
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = "#424242".toColorInt()
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    // Reusable objects to avoid allocations during onDraw
    private val drawPath = Path()
    private val textBounds = Rect()
    private val arrowPaint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }

    // Data
    private var pathPoints = listOf<DeadReckoningModule.Position>()
    private var scale = 80f
    private var offsetX = 0f
    private var offsetY = 0f

    fun updatePath(positions: List<DeadReckoningModule.Position>) {
        pathPoints = positions
        if (positions.size > 1) {
            calculateScale()
        }
        invalidate()
    }

    private fun calculateScale() {
        // guard for empty list
        if (pathPoints.isEmpty()) return

        val minX = pathPoints.minOf { it.x }
        val maxX = pathPoints.maxOf { it.x }
        val minY = pathPoints.minOf { it.y }
        val maxY = pathPoints.maxOf { it.y }

        val rangeX = (maxX - minX).coerceAtLeast(1.0)
        val rangeY = (maxY - minY).coerceAtLeast(1.0)

        val scaleX = (width * 0.7).takeIf { rangeX > 0 }?.div(rangeX) ?: 50.0
        val scaleY = (height * 0.7).takeIf { rangeY > 0 }?.div(rangeY) ?: 50.0

        // clamp scale to a reasonable range
        scale = minOf(scaleX, scaleY, 150.0).coerceAtLeast(50.0).toFloat()

        val centerX = (minX + maxX) / 2
        val centerY = (minY + maxY) / 2

        offsetX = width / 2f - centerX.toFloat() * scale
        offsetY = height / 2f + centerY.toFloat() * scale
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawColor(Color.WHITE)

        // Draw grid (re-uses gridPaint)
        val gridSize = scale
        // Start x/y from the first line inside view. Using modulo handles offset.
        var x = offsetX % gridSize
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += gridSize
        }
        var y = offsetY % gridSize
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += gridSize
        }

        if (pathPoints.isEmpty()) {
            // Use string resource from context for text
            val msg = context.getString(R.string.waiting_to_start)
            textPaint.getTextBounds(msg, 0, msg.length, textBounds)
            canvas.drawText(msg, (width - textBounds.width()) / 2f, height / 2f, textPaint)
            return
        }

        // Draw path (reuse drawPath)
        if (pathPoints.size >= 2) {
            drawPath.reset()
            val first = pathPoints[0]
            drawPath.moveTo(
                first.x.toFloat() * scale + offsetX,
                -first.y.toFloat() * scale + offsetY
            )

            for (i in 1 until pathPoints.size) {
                val pt = pathPoints[i]
                drawPath.lineTo(
                    pt.x.toFloat() * scale + offsetX,
                    -pt.y.toFloat() * scale + offsetY
                )
            }

            canvas.drawPath(drawPath, pathPaint)
        }

        // Draw start point
        val start = pathPoints.first()
        canvas.drawCircle(
            start.x.toFloat() * scale + offsetX,
            -start.y.toFloat() * scale + offsetY,
            20f,
            startPaint
        )

        // Draw current point with heading arrow
        val current = pathPoints.last()
        val cx = current.x.toFloat() * scale + offsetX
        val cy = -current.y.toFloat() * scale + offsetY

        canvas.drawCircle(cx, cy, 20f, currentPaint)

        // Heading arrow: reuse arrowPaint, but ensure its color matches currentPaint
        arrowPaint.color = currentPaint.color
        arrowPaint.strokeWidth = 8f

        val arrowLen = 50f
        // rotation: current.heading is in radians (same as earlier code)
        val endX = cx + cos(current.heading - PI / 2).toFloat() * arrowLen
        val endY = cy + sin(current.heading - PI / 2).toFloat() * arrowLen
        canvas.drawLine(cx, cy, endX, endY, arrowPaint)

        // Info text: reuse textPaint and avoid allocating a new String object? (string interpolation is fine)
        val info = context.getString(R.string.path_info, pathPoints.size, scale)
        // drawText uses textPaint directly
        canvas.drawText(info, 30f, height - 30f, textPaint)
    }

    fun reset() {
        pathPoints = emptyList()
        invalidate()
    }
}
