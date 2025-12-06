package com.example.posetrack.slam

import android.content.Context
import android.graphics.*
import androidx.core.graphics.toColorInt
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
/**
 * Fixed SLAMView - Now properly displays features as blue dots
 * Compatible with ImprovedSLAMModule
 */
class SLAMView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val pathPaint = Paint().apply {
        color = "#FF6B35".toColorInt()
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    // Blue dots for features/landmarks
    private val featurePaint = Paint().apply {
        color = "#00D4FF".toColorInt() // Bright cyan blue
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Outer glow for features
    private val featureGlowPaint = Paint().apply {
        color = "#4000D4FF".toColorInt() // Transparent cyan
        style = Paint.Style.FILL
        isAntiAlias = true
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
        color = "#30FFFFFF".toColorInt() // Semi-transparent white for dark background
        strokeWidth = 1f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = "#FFFFFF".toColorInt()
        textSize = 32f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    // Data
    private var pathPoints = listOf<SLAMModule.Position>()
    private var features = listOf<SLAMModule.FeaturePoint>()

    // Visual params
    private var scale = 100f         // pixels per meter
    private var centerX = 0f
    private var centerY = 0f

    // Reusable objects
    private val drawPath = Path()
    private val textBounds = Rect()
    private val infoBuilder = StringBuilder(64)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
    }

    /**
     * Main update method - called from SLAMFragment
     */
    fun updatePath(positions: List<SLAMModule.Position>, featurePoints: List<SLAMModule.FeaturePoint>) {
        pathPoints = positions
        features = featurePoints
        if (positions.size > 10) {
            calculateScale()
        }
        invalidate()
    }

    fun reset() {
        pathPoints = emptyList()
        features = emptyList()
        scale = 100f
        invalidate()
    }

    private fun calculateScale() {
        if (pathPoints.isEmpty()) return

        val minX = pathPoints.minOfOrNull { it.x } ?: 0.0
        val maxX = pathPoints.maxOfOrNull { it.x } ?: 0.0
        val minY = pathPoints.minOfOrNull { it.y } ?: 0.0
        val maxY = pathPoints.maxOfOrNull { it.y } ?: 0.0

        val rangeX = (maxX - minX).coerceAtLeast(0.5)
        val rangeY = (maxY - minY).coerceAtLeast(0.5)

        val scaleX = (width * 0.7) / rangeX
        val scaleY = (height * 0.7) / rangeY

        scale = min(scaleX, min(scaleY, 200.0)).coerceAtLeast(80.0).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Dark semi-transparent background for overlay on camera
        canvas.drawColor("#80000000".toColorInt())

        drawGrid(canvas)

        if (pathPoints.isEmpty()) {
            val msg = "Start SLAM tracking"
            textPaint.getTextBounds(msg, 0, msg.length, textBounds)
            canvas.drawText(msg, (width - textBounds.width()) / 2f, height / 2f, textPaint)
            return
        }

        // Draw map features (BLUE DOTS) - THIS IS THE KEY FIX
        // Features are in GLOBAL coordinates (meters), need to transform to screen
        for (feat in features) {
            // Convert from global meters to screen pixels
            val fx = feat.x * scale + centerX
            val fy = -feat.y * scale + centerY

            // Only draw if visible on screen
            if (fx >= 0 && fx <= width && fy >= 0 && fy <= height) {
                // Draw glow effect
                canvas.drawCircle(fx, fy, 8f, featureGlowPaint)
                // Draw bright blue dot
                canvas.drawCircle(fx, fy, 4f, featurePaint)
            }
        }

        // Draw SLAM path
        if (pathPoints.size >= 2) {
            drawPath.reset()
            val first = pathPoints[0]
            drawPath.moveTo(
                first.x.toFloat() * scale + centerX,
                -first.y.toFloat() * scale + centerY
            )

            for (i in 1 until pathPoints.size) {
                val pt = pathPoints[i]
                drawPath.lineTo(
                    pt.x.toFloat() * scale + centerX,
                    -pt.y.toFloat() * scale + centerY
                )
            }
            canvas.drawPath(drawPath, pathPaint)
        }

        // Draw start marker
        val start = pathPoints.first()
        canvas.drawCircle(
            start.x.toFloat() * scale + centerX,
            -start.y.toFloat() * scale + centerY,
            18f,
            startPaint
        )

        // Draw current position marker
        val current = pathPoints.last()
        canvas.drawCircle(
            current.x.toFloat() * scale + centerX,
            -current.y.toFloat() * scale + centerY,
            18f,
            currentPaint
        )

        // Info text with better visibility
        infoBuilder.setLength(0)
        infoBuilder.append("Points: ").append(pathPoints.size)
            .append(" | Landmarks: ").append(features.size)
        canvas.drawText(infoBuilder.toString(), 30f, height - 30f, textPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        val gridSize = scale.coerceAtLeast(40f)
        var x = centerX % gridSize
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += gridSize
        }
        var y = centerY % gridSize
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += gridSize
        }
    }
}