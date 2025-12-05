package com.example.posetrack.slam

import android.content.Context
import android.graphics.*
import androidx.core.graphics.toColorInt
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.max

/**
 * SLAMView - optimized to avoid allocations in onDraw.
 *
 * Use updatePath()/setPath()/setFeatures() from UI thread.
 * Coordinates expected in meters; scale converts meters -> pixels.
 */
class SLAMView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val pathPaint = Paint().apply {
        color = "#FF6B35".toColorInt()
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val featurePaint = Paint().apply {
        color = "#00D4FF".toColorInt()
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
        color = "#EEEEEE".toColorInt()
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = "#424242".toColorInt()
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    // Data
    private var pathPoints = listOf<SLAMModule.Position>()
    private var features = listOf<SLAMModule.FeaturePoint>()

    // Visual params
    private var scale = 100f         // pixels per meter
    private var centerX = 0f
    private var centerY = 0f

    // Reusable objects to avoid allocations during draw
    private val drawPath = Path()
    private val textBounds = Rect()
    private val infoBuilder = StringBuilder(64)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
    }

    /**
     * Primary update method used by the SLAMFragment in examples.
     * positions: world coordinates in meters
     * featurePoints: feature coordinates in meters (x,y)
     */
    fun updatePath(positions: List<SLAMModule.Position>, featurePoints: List<SLAMModule.FeaturePoint>) {
        pathPoints = positions
        features = featurePoints
        if (positions.size > 10) {
            calculateScale()
        }
        invalidate()
    }

    // Backwards-compatible helper methods (some earlier code called these)
    @Suppress("unused") // kept as public API for external callers (may be used later)
    fun setPath(positions: List<SLAMModule.Position>) {
        pathPoints = positions
        if (positions.size > 10) calculateScale()
        invalidate()
    }

    @Suppress("unused") // kept as public API for external callers (may be used later)
    fun setFeatures(featurePoints: List<SLAMModule.FeaturePoint>) {
        features = featurePoints
        invalidate()
    }

    fun reset() {
        pathPoints = emptyList()
        features = emptyList()
        invalidate()
    }

    private fun calculateScale() {
        // Compute bounding box of path (in meters)
        val minX = pathPoints.minOfOrNull { it.x } ?: 0.0
        val maxX = pathPoints.maxOfOrNull { it.x } ?: 0.0
        val minY = pathPoints.minOfOrNull { it.y } ?: 0.0
        val maxY = pathPoints.maxOfOrNull { it.y } ?: 0.0

        val rangeX = (maxX - minX).coerceAtLeast(0.5)
        val rangeY = (maxY - minY).coerceAtLeast(0.5)

        val scaleX = (width * 0.7) / rangeX
        val scaleY = (height * 0.7) / rangeY

        // clamp to reasonable px/m range
        scale = min(scaleX, min(scaleY, 200.0)).coerceAtLeast(80.0).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawColor(Color.WHITE)
        drawGrid(canvas)

        if (pathPoints.isEmpty()) {
            val msg = "Start SLAM tracking"
            textPaint.getTextBounds(msg, 0, msg.length, textBounds)
            canvas.drawText(msg, (width - textBounds.width()) / 2f, height / 2f, textPaint)
            return
        }

        // Draw map features - iterate index range to avoid temporary slice
        val startIndex = max(0, features.size - 100)
        for (i in startIndex until features.size) {
            val feat = features[i]
            val fx = feat.x * scale + centerX
            val fy = -feat.y * scale + centerY
            canvas.drawCircle(fx, fy, 3f, featurePaint)
        }

        // Draw SLAM path using reusable Path
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

        // Info text (reuse StringBuilder)
        infoBuilder.setLength(0)
        infoBuilder.append("Points: ").append(pathPoints.size)
            .append(" | Features: ").append(features.size)
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
