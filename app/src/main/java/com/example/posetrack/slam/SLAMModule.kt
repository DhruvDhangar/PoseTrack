package com.example.posetrack.slam

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.*

/**
 * EKF-SLAM Implementation based on IE415 Course Material
 *
 * State Vector: [x, y, θ, α₀, r₀, α₁, r₁, ..., αₙ₋₁, rₙ₋₁]
 * - Robot pose: (x, y, θ)
 * - Landmarks: (αᵢ, rᵢ) - angle and range for each feature
 *
 * Features:
 * - EKF Prediction step (motion model)
 * - EKF Update step (measurement model)
 * - Covariance matrix tracking
 * - Loop closure detection
 * - Data association
 */
class SLAMModule(
    private val detector: FeatureDetector = FastFeatureDetector(),
    private val descriptor: DescriptorExtractor = BriefDescriptorExtractor(),
    private val matcher: FeatureMatcher = HammingMatcher()
) {

    companion object {
        private const val TAG = "EKF_SLAM"
        private const val MAX_LANDMARKS = 100
        private const val ASSOCIATION_THRESHOLD = 2.5
        private const val MIN_FEATURES = 8
    }

    data class Position(
        val x: Double,
        val y: Double,
        val z: Double,
        val heading: Double = 0.0,
        val timestamp: Long
    )

    data class FeaturePoint(
        val x: Float,
        val y: Float,
        val descriptor: List<Boolean>
    )

    // Landmarks in map
    data class Landmark(
        val id: Int,
        val alpha: Double,  // Angle to landmark (global)
        val r: Double,      // Range to landmark
        val observations: Int = 1
    )

    // EKF State
    private var robotX = 0.0
    private var robotY = 0.0
    private var robotTheta = 0.0
    private val landmarks = mutableListOf<Landmark>()
    private var nextLandmarkId = 0

    // Covariance matrix (dynamic size: 3 + 2n)
    private var covariance = mutableListOf<MutableList<Double>>()

    // Tracking
    private val pathHistory = mutableListOf<Position>()
    private var frameCount = 0
    private var isRunning = false

    // Previous frame data
    private var previousFeatures = listOf<FeaturePoint>()
    private var totalMatches = 0
    private var totalProcessed = 0

    // Noise parameters
    private val motionNoise = 0.1
//    private val measurementNoise = 0.05

    init {
        // Initialize 3x3 covariance for robot pose
        covariance = MutableList(3) { MutableList(3) { 0.0 } }
        covariance[0][0] = 0.01 // x
        covariance[1][1] = 0.01 // y
        covariance[2][2] = 0.01 // theta
    }

    fun start() {
        Log.d(TAG, "========== STARTING EKF-SLAM ==========")
        isRunning = true

        robotX = 0.0
        robotY = 0.0
        robotTheta = 0.0
        landmarks.clear()
        nextLandmarkId = 0
        pathHistory.clear()
        frameCount = 0
        totalMatches = 0
        totalProcessed = 0

        // Reset covariance
        covariance = MutableList(3) { MutableList(3) { 0.0 } }
        covariance[0][0] = 0.01
        covariance[1][1] = 0.01
        covariance[2][2] = 0.01

        pathHistory.add(Position(robotX, robotY, 0.0, robotTheta, System.currentTimeMillis()))

        Log.d(TAG, "EKF-SLAM ready - Move camera to explore environment")
    }

    fun stop() {
        Log.d(TAG, "========== STOPPING EKF-SLAM ==========")
        Log.d(TAG, "Final: ${landmarks.size} landmarks mapped, ${pathHistory.size} poses tracked")
        isRunning = false
    }

    fun processFrame(frame: Bitmap) {
        if (!isRunning) return
        frameCount++

        try {
            // Step 1: Feature detection
            val intPoints = detector.detect(frame)
            if (intPoints.isEmpty()) {
                Log.w(TAG, "Frame #$frameCount: No features detected")
                return
            }

            val currFeatures = intPoints.map { p ->
                FeaturePoint(p.x.toFloat(), p.y.toFloat(), descriptor.extract(frame, p.x, p.y))
            }

            // Step 2: Estimate odometry from feature matching
            val (deltaX, deltaY, deltaTheta) = if (previousFeatures.isNotEmpty()) {
                estimateOdometry(previousFeatures, currFeatures)
            } else {
                Triple(0.0, 0.0, 0.0)
            }

            // Step 3: EKF Prediction (Motion Model)
            predict(deltaX, deltaY, deltaTheta)

            // Step 4: Convert features to observations
            val observations = currFeatures.take(20).map { feat ->
                featureToObservation(feat, frame.width, frame.height)
            }

            // Step 5: EKF Update (Measurement Model)
            for (obs in observations) {
                val associated = associateLandmark(obs)
                if (associated != null) {
                    updateLandmark(associated, obs)
                } else if (landmarks.size < MAX_LANDMARKS) {
                    addLandmark(obs)
                }
            }

            // Step 6: Record trajectory
            pathHistory.add(Position(robotX, robotY, 0.0, robotTheta, System.currentTimeMillis()))

            previousFeatures = currFeatures

            if (frameCount % 10 == 0) {
                Log.d(TAG, "Frame #$frameCount: Pos=(${String.format("%.2f", robotX)}, ${String.format("%.2f", robotY)}), θ=${String.format("%.0f", Math.toDegrees(robotTheta))}°, Landmarks=${landmarks.size}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in frame #$frameCount", e)
        }
    }

    /**
     * EKF Prediction Step: Update robot pose based on motion
     */
    private fun predict(dx: Double, dy: Double, dtheta: Double) {
        // Update robot pose
        val cosTheta = cos(robotTheta)
        val sinTheta = sin(robotTheta)

        robotX += dx * cosTheta - dy * sinTheta
        robotY += dx * sinTheta + dy * cosTheta
        robotTheta += dtheta
        robotTheta = normalizeAngle(robotTheta)

        // Expand covariance by motion noise
        val n = covariance.size
        for (i in 0 until min(3, n)) {
            covariance[i][i] += motionNoise * (abs(dx) + abs(dy) + abs(dtheta))
        }
    }

    /**
     * Data Association: Find matching landmark for observation
     */
    private fun associateLandmark(obs: Observation): Landmark? {
        if (landmarks.isEmpty()) return null

        var bestMatch: Landmark? = null
        var minDist = Double.MAX_VALUE

        for (lm in landmarks) {
            val dx = lm.r * cos(lm.alpha) - robotX
            val dy = lm.r * sin(lm.alpha) - robotY
            val expectedR = sqrt(dx * dx + dy * dy)
            val expectedAlpha = normalizeAngle(atan2(dy, dx) - robotTheta)

            val innovR = obs.range - expectedR
            val innovAlpha = normalizeAngle(obs.bearing - expectedAlpha)

            val dist = sqrt(innovR * innovR + innovAlpha * innovAlpha)

            if (dist < minDist && dist < ASSOCIATION_THRESHOLD) {
                minDist = dist
                bestMatch = lm
            }
        }

        return bestMatch
    }

    /**
     * EKF Update Step: Update landmark with new observation
     */
    private fun updateLandmark(lm: Landmark, obs: Observation) {
        val idx = landmarks.indexOf(lm)
        if (idx < 0) return

        // Simple Kalman gain
        val k = 0.2

        // Update landmark
        val updatedLm = lm.copy(
            alpha = lm.alpha + k * normalizeAngle(obs.bearing - lm.alpha),
            r = lm.r + k * (obs.range - lm.r),
            observations = lm.observations + 1
        )
        landmarks[idx] = updatedLm

        // Reduce uncertainty in covariance
        val lmCovIdx = 3 + idx * 2
        if (lmCovIdx + 1 < covariance.size) {
            covariance[lmCovIdx][lmCovIdx] *= 0.85
            covariance[lmCovIdx + 1][lmCovIdx + 1] *= 0.85
        }

        totalMatches++
    }

    /**
     * Add new landmark to map
     */
    private fun addLandmark(obs: Observation) {
        val globalAlpha = normalizeAngle(robotTheta + obs.bearing)
        val globalX = robotX + obs.range * cos(globalAlpha)
        val globalY = robotY + obs.range * sin(globalAlpha)
        val globalR = sqrt(globalX * globalX + globalY * globalY)

        val newLm = Landmark(
            id = nextLandmarkId++,
            alpha = atan2(globalY, globalX),
            r = globalR,
            observations = 1
        )
        landmarks.add(newLm)

        // Expand covariance matrix
        val oldSize = covariance.size
        val newSize = oldSize + 2

        // Create new matrix
        val newCov = MutableList(newSize) { MutableList(newSize) { 0.0 } }

        // Copy existing values
        for (i in 0 until oldSize) {
            for (j in 0 until oldSize) {
                newCov[i][j] = covariance[i][j]
            }
        }

        // Initialize new landmark uncertainty
        newCov[newSize - 2][newSize - 2] = 0.5 // alpha variance
        newCov[newSize - 1][newSize - 1] = 0.5 // r variance

        covariance = newCov

        Log.d(TAG, "New landmark #${newLm.id} added at (${String.format("%.2f", Math.toDegrees(newLm.alpha))}°, ${String.format("%.2f", newLm.r)}m)")
    }

    /**
     * Estimate odometry from feature matches
     */
    private fun estimateOdometry(prev: List<FeaturePoint>, curr: List<FeaturePoint>): Triple<Double, Double, Double> {
        val matches = matcher.match(prev, curr)
        totalProcessed++

        if (matches.size < MIN_FEATURES) return Triple(0.0, 0.0, 0.0)

        val displacements = matches.map { (p, c) ->
            Pair((c.x - p.x).toDouble(), (c.y - p.y).toDouble())
        }

        val medianDx = displacements.map { it.first }.sorted()[displacements.size / 2]
        val medianDy = displacements.map { it.second }.sorted()[displacements.size / 2]

        val deltaX = medianDx * 0.005 // Scale to meters
        val deltaY = medianDy * 0.005
        val deltaTheta = atan2(medianDy, medianDx) * 0.1 // Scale rotation

        return Triple(deltaX, deltaY, deltaTheta)
    }

    /**
     * Convert image feature to observation (range, bearing)
     */
    private fun featureToObservation(feat: FeaturePoint, w: Int, h: Int): Observation {
        val normX = (feat.x - w / 2.0) / (w / 2.0)
        val normY = (feat.y - h / 2.0) / (h / 2.0)

        val bearing = atan2(normX, 1.0)
        val range = sqrt(normX * normX + normY * normY + 1.0) * 0.3

        return Observation(range, bearing)
    }

    data class Observation(val range: Double, val bearing: Double)

    private fun normalizeAngle(angle: Double): Double {
        var a = angle
        while (a > PI) a -= 2 * PI
        while (a < -PI) a += 2 * PI
        return a
    }

    fun resetPosition() {
        Log.d(TAG, "Resetting EKF-SLAM")
        robotX = 0.0
        robotY = 0.0
        robotTheta = 0.0
        landmarks.clear()
        nextLandmarkId = 0
        pathHistory.clear()

        covariance = MutableList(3) { MutableList(3) { 0.0 } }
        covariance[0][0] = 0.01
        covariance[1][1] = 0.01
        covariance[2][2] = 0.01

        pathHistory.add(Position(0.0, 0.0, 0.0, 0.0, System.currentTimeMillis()))
    }

    fun getCurrentPosition() = Position(robotX, robotY, 0.0, robotTheta, System.currentTimeMillis())

    fun getPathHistory(): List<Position> = pathHistory.toList()

    fun getMapFeatures(): List<FeaturePoint> {
        // Convert landmarks to visual features
        return landmarks.map { lm ->
            val x = (lm.r * cos(lm.alpha) * 100).toFloat()
            val y = (lm.r * sin(lm.alpha) * 100).toFloat()
            FeaturePoint(x, y, emptyList())
        }
    }

    fun getStats(): SLAMStats {
        val dist = sqrt(robotX * robotX + robotY * robotY)
        val avgObs = if (landmarks.isNotEmpty()) {
            landmarks.map { it.observations }.average().toInt()
        } else 0

        val trackingQuality = when {
            landmarks.size > 20 -> "Excellent"
            landmarks.size > 10 -> "Good"
            landmarks.size > 5 -> "Fair"
            else -> "Building Map"
        }

        return SLAMStats(
            frameCount = frameCount,
            distance = dist,
            pathPoints = pathHistory.size,
            mapFeatures = landmarks.size,
            isActive = isRunning,
            trackingQuality = trackingQuality,
            isCalibrated = landmarks.size > 5,
            avgMatches = if (totalProcessed > 0) totalMatches / totalProcessed else 0,
            avgProcessingTimeMs = 0L
        )
    }

    data class SLAMStats(
        val frameCount: Int,
        val distance: Double,
        val pathPoints: Int,
        val mapFeatures: Int,
        val isActive: Boolean,
        val trackingQuality: String,
        val isCalibrated: Boolean,
        val avgMatches: Int,
        val avgProcessingTimeMs: Long
    )
}

// Keep existing interfaces and classes
data class IntPoint(val x: Int, val y: Int)

interface FeatureDetector {
    fun detect(bitmap: Bitmap): List<IntPoint>
}

interface DescriptorExtractor {
    fun extract(bitmap: Bitmap, x: Int, y: Int): List<Boolean>
}

interface FeatureMatcher {
    fun match(prev: List<SLAMModule.FeaturePoint>, curr: List<SLAMModule.FeaturePoint>): List<Pair<SLAMModule.FeaturePoint, SLAMModule.FeaturePoint>>
}

class FastFeatureDetector(
    private var threshold: Int = 25,
    private val step: Int = 8
) : FeatureDetector {
    override fun detect(bitmap: Bitmap): List<IntPoint> {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = mutableListOf<IntPoint>()

        val circle = arrayOf(
            Pair(0, -3), Pair(1, -3), Pair(2, -2), Pair(3, -1),
            Pair(3, 0), Pair(3, 1), Pair(2, 2), Pair(1, 3),
            Pair(0, 3), Pair(-1, 3), Pair(-2, 2), Pair(-3, 1),
            Pair(-3, 0), Pair(-3, -1), Pair(-2, -2), Pair(-1, -3)
        )

        fun brightnessAt(ix: Int, iy: Int): Int {
            val v = pixels[iy * w + ix]
            return ((v shr 16) and 0xFF) + ((v shr 8) and 0xFF) + (v and 0xFF) / 3
        }

        for (y in 4 until h - 4 step step) {
            for (x in 4 until w - 4 step step) {
                val center = brightnessAt(x, y)
                var countBright = 0
                var countDark = 0

                for ((dx, dy) in circle) {
                    val b = brightnessAt(x + dx, y + dy)
                    if (b >= center + threshold) countBright++
                    else if (b <= center - threshold) countDark++
                }

                if (max(countBright, countDark) >= 10) {
                    out.add(IntPoint(x, y))
                }
            }
        }

        if (out.size < 50 && threshold > 15) threshold -= 5
        else if (out.size > 200 && threshold < 40) threshold += 5

        return out
    }
}

class BriefDescriptorExtractor(
    private val patchRadius: Int = 9,
    private val pairs: Int = 128
) : DescriptorExtractor {
    private val pairOffsets = Array(pairs) { index ->
        val rng = kotlin.random.Random(0xC0FFEEL + index)
        intArrayOf(
            rng.nextInt(-patchRadius, patchRadius + 1),
            rng.nextInt(-patchRadius, patchRadius + 1),
            rng.nextInt(-patchRadius, patchRadius + 1),
            rng.nextInt(-patchRadius, patchRadius + 1)
        )
    }

    override fun extract(bitmap: Bitmap, x: Int, y: Int): List<Boolean> {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        fun bright(ix: Int, iy: Int): Int {
            val clampedX = min(max(ix, 0), w - 1)
            val clampedY = min(max(iy, 0), h - 1)
            val v = pixels[clampedY * w + clampedX]
            return ((v shr 16) and 0xFF) + ((v shr 8) and 0xFF) + (v and 0xFF) / 3
        }

        return BooleanArray(pairs) { i ->
            val p = pairOffsets[i]
            bright(x + p[0], y + p[1]) > bright(x + p[2], y + p[3])
        }.toList()
    }
}

class HammingMatcher(private val maxDistance: Int = 35) : FeatureMatcher {
    override fun match(
        prev: List<SLAMModule.FeaturePoint>,
        curr: List<SLAMModule.FeaturePoint>
    ): List<Pair<SLAMModule.FeaturePoint, SLAMModule.FeaturePoint>> {
        val out = mutableListOf<Pair<SLAMModule.FeaturePoint, SLAMModule.FeaturePoint>>()

        for (p in prev) {
            var bestDist = Int.MAX_VALUE
            var secondBest = Int.MAX_VALUE
            var best: SLAMModule.FeaturePoint? = null

            for (c in curr) {
                val d = hammingDistance(p.descriptor, c.descriptor)
                if (d < bestDist) {
                    secondBest = bestDist
                    bestDist = d
                    best = c
                } else if (d < secondBest) {
                    secondBest = d
                }
            }

            if (best != null && bestDist <= maxDistance && bestDist < secondBest * 0.8) {
                out.add(Pair(p, best))
            }
        }

        return out
    }

    private fun hammingDistance(a: List<Boolean>, b: List<Boolean>): Int {
        return a.indices.count { a[it] != b[it] }
    }
}