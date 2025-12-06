package com.example.posetrack.dr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.*

/**
 * Improved Dead Reckoning Module with:
 * - Gravity compensation using complementary filter
 * - Sensor fusion for orientation (Madgwick filter)
 * - Kalman filter for position estimation
 * - Zero-velocity update (ZUPT) for drift correction
 * - Proper step detection with dynamic threshold
 */
class DeadReckoningModule(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())

    // Sensors
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    data class Position(
        val x: Double,
        val y: Double,
        val heading: Double,
        val timestamp: Long,
        val quality: Float = 1.0f // Position quality indicator
    )

    private var currentPosition = Position(0.0, 0.0, 0.0, System.currentTimeMillis())
    private val pathHistory = mutableListOf<Position>()
    private var listeners = mutableListOf<(Position) -> Unit>()

    // Sensor data buffers
    private val accelRaw = FloatArray(3)
    private val gyroRaw = FloatArray(3)
    private val magRaw = FloatArray(3)

    // Gravity estimation (complementary filter)
    private val gravity = FloatArray(3)
    private val linearAccel = FloatArray(3)

    // Orientation quaternion (Madgwick filter)
    private val quaternion = FloatArray(4)

    // Kalman filter states
    private var velocityX = 0.0
    private var velocityY = 0.0
    private var positionX = 0.0
    private var positionY = 0.0

    // Kalman filter covariances
    private val positionCovariance = Array(4) { DoubleArray(4) }

    // ZUPT (Zero Velocity Update) detection
    private var isStationary = false
    private val accelMagnitudeWindow = mutableListOf<Double>()
    private val gyroMagnitudeWindow = mutableListOf<Double>()
    private val windowSize = 10

    // Step detection
    private var stepCount = 0
    private var lastStepTime = 0L
    private var strideLength = 0.7 // Default 70cm
//    private val stepAccelWindow = mutableListOf<Double>()

    // Timing
    private var lastUpdateTime = 0L
    private var isRunning = false

    // Calibration
    private val gyroBias = FloatArray(3)
    private val accelBias = FloatArray(3)
    private var isCalibrated = false
    private val calibrationSamples = mutableListOf<FloatArray>()
    private val calibrationDuration = 2000L // 2 seconds

    companion object {
        private const val TAG = "ImprovedDR"
        private const val SAMPLE_RATE_MS = 20 // 50 Hz
        private const val GRAVITY_MAGNITUDE = 9.81f
        private const val ALPHA = 0.98f // Complementary filter
        private const val BETA = 0.1f // Madgwick filter gain
        private const val ZUPT_ACCEL_THRESHOLD = 0.5 // m/s²
        private const val ZUPT_GYRO_THRESHOLD = 0.1 // rad/s
        private const val PROCESS_NOISE = 0.1
        private const val MEASUREMENT_NOISE = 1.0
    }

    init {
        // Initialize quaternion to identity
        quaternion[0] = 1f

        // Initialize covariance matrix
        for (i in 0..3) {
            positionCovariance[i][i] = 1.0
        }
    }

    fun start() {
        Log.d(TAG, "========== STARTING IMPROVED DR ==========")
        isRunning = true
        pathHistory.clear()
        pathHistory.add(currentPosition)
        stepCount = 0
        lastUpdateTime = System.currentTimeMillis()

        // Start calibration
        startCalibration()

        // Register sensors at high rate
        val sensorDelay = SensorManager.SENSOR_DELAY_GAME
        accelerometer?.let {
            sensorManager.registerListener(this, it, sensorDelay)
            Log.d(TAG, "✓ Accelerometer registered")
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, sensorDelay)
            Log.d(TAG, "✓ Gyroscope registered")
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, sensorDelay)
            Log.d(TAG, "✓ Magnetometer registered")
        }
        stepDetector?.let {
            sensorManager.registerListener(this, it, sensorDelay)
            Log.d(TAG, "✓ Step detector registered")
        }

        startUpdateLoop()
    }

    private fun startCalibration() {
        Log.d(TAG, "Starting calibration - keep device stationary...")
        calibrationSamples.clear()
        isCalibrated = false

        handler.postDelayed({
            if (calibrationSamples.size > 10) {
                // Calculate biases
                val accelSum = FloatArray(3)
                val gyroSum = FloatArray(3)

                calibrationSamples.forEach { sample ->
                    accelSum[0] += sample[0]
                    accelSum[1] += sample[1]
                    accelSum[2] += sample[2]
                    gyroSum[0] += sample[3]
                    gyroSum[1] += sample[4]
                    gyroSum[2] += sample[5]
                }

                val n = calibrationSamples.size
                gyroBias[0] = gyroSum[0] / n
                gyroBias[1] = gyroSum[1] / n
                gyroBias[2] = gyroSum[2] / n

                // Accelerometer bias (subtract gravity on Z)
                accelBias[0] = accelSum[0] / n
                accelBias[1] = accelSum[1] / n
                accelBias[2] = accelSum[2] / n - GRAVITY_MAGNITUDE

                isCalibrated = true
                Log.d(TAG, "✓ Calibration complete: gyro bias=[${gyroBias.joinToString()}]")
                Log.d(TAG, "  accel bias=[${accelBias.joinToString()}]")
            } else {
                Log.w(TAG, "✗ Calibration failed - not enough samples")
            }
        }, calibrationDuration)
    }

    private fun startUpdateLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isRunning && isCalibrated) {
                    updatePosition()
                    handler.postDelayed(this, SAMPLE_RATE_MS.toLong())
                } else if (isRunning) {
                    handler.postDelayed(this, SAMPLE_RATE_MS.toLong())
                }
            }
        }, SAMPLE_RATE_MS.toLong())
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRunning) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelRaw[0] = event.values[0]
                accelRaw[1] = event.values[1]
                accelRaw[2] = event.values[2]

                // Store for calibration
                if (!isCalibrated && calibrationSamples.size < 100) {
                    calibrationSamples.add(floatArrayOf(
                        accelRaw[0], accelRaw[1], accelRaw[2],
                        gyroRaw[0], gyroRaw[1], gyroRaw[2]
                    ))
                }

                if (isCalibrated) {
                    processAccelerometer()
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                gyroRaw[0] = event.values[0]
                gyroRaw[1] = event.values[1]
                gyroRaw[2] = event.values[2]

                if (isCalibrated) {
                    processGyroscope()
                }
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                magRaw[0] = event.values[0]
                magRaw[1] = event.values[1]
                magRaw[2] = event.values[2]
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                if (isCalibrated) {
                    onStepDetected()
                }
            }
        }
    }

    private fun processAccelerometer() {
        // Remove bias
        val accel = FloatArray(3) {
            accelRaw[it] - accelBias[it]
        }

        // Complementary filter for gravity estimation
        val alpha = ALPHA
        gravity[0] = alpha * gravity[0] + (1 - alpha) * accel[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * accel[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * accel[2]

        // Extract linear acceleration
        linearAccel[0] = accel[0] - gravity[0]
        linearAccel[1] = accel[1] - gravity[1]
        linearAccel[2] = accel[2] - gravity[2]

        // ZUPT detection - check if stationary
        val accelMag = sqrt(
            linearAccel[0] * linearAccel[0] +
                    linearAccel[1] * linearAccel[1] +
                    linearAccel[2] * linearAccel[2]
        ).toDouble()

        accelMagnitudeWindow.add(accelMag)
        if (accelMagnitudeWindow.size > windowSize) {
            accelMagnitudeWindow.removeAt(0)
        }

        // Update orientation using Madgwick filter
        updateOrientation(accel, gyroRaw)
    }

    private fun processGyroscope() {
        // Remove bias
        val gyro = FloatArray(3) {
            gyroRaw[it] - gyroBias[it]
        }

        // ZUPT detection
        val gyroMag = sqrt(
            gyro[0] * gyro[0] +
                    gyro[1] * gyro[1] +
                    gyro[2] * gyro[2]
        ).toDouble()

        gyroMagnitudeWindow.add(gyroMag)
        if (gyroMagnitudeWindow.size > windowSize) {
            gyroMagnitudeWindow.removeAt(0)
        }

        // Check if stationary
        if (accelMagnitudeWindow.size >= windowSize && gyroMagnitudeWindow.size >= windowSize) {
            val accelStd = calculateStd(accelMagnitudeWindow)
            val gyroStd = calculateStd(gyroMagnitudeWindow)

            isStationary = accelStd < ZUPT_ACCEL_THRESHOLD && gyroStd < ZUPT_GYRO_THRESHOLD
        }
    }

    private fun updateOrientation(accel: FloatArray, gyro: FloatArray) {
        // Simplified Madgwick AHRS algorithm
        val q0 = quaternion[0]
        val q1 = quaternion[1]
        val q2 = quaternion[2]
        val q3 = quaternion[3]

        // Normalize accelerometer measurement
        val norm = sqrt(accel[0] * accel[0] + accel[1] * accel[1] + accel[2] * accel[2])
        if (norm < 0.01f) return

        val ax = accel[0] / norm
        val ay = accel[1] / norm
        val az = accel[2] / norm

        // Gradient descent algorithm corrective step
        val s0 = -2f * (q2 * (2f * q1 * q3 - 2f * q0 * q2 - ax) + q1 * (2f * q0 * q1 + 2f * q2 * q3 - ay) - 4f * q0 * (1 - 2f * q1 * q1 - 2f * q2 * q2 - az))
        val s1 = 2f * (q3 * (2f * q1 * q3 - 2f * q0 * q2 - ax) + q0 * (2f * q0 * q1 + 2f * q2 * q3 - ay) - 4f * q1 * (1 - 2f * q1 * q1 - 2f * q2 * q2 - az))
        val s2 = 2f * (-q0 * (2f * q1 * q3 - 2f * q0 * q2 - ax) + q3 * (2f * q0 * q1 + 2f * q2 * q3 - ay) - 4f * q2 * (1 - 2f * q1 * q1 - 2f * q2 * q2 - az))
        val s3 = 2f * (q1 * (2f * q1 * q3 - 2f * q0 * q2 - ax) + q2 * (2f * q0 * q1 + 2f * q2 * q3 - ay))

        // Normalize step magnitude
        val sNorm = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
        val gx = gyro[0] - gyroBias[0]
        val gy = gyro[1] - gyroBias[1]
        val gz = gyro[2] - gyroBias[2]

        // Compute rate of change of quaternion
        val qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz) - BETA * s0 / sNorm
        val qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy) - BETA * s1 / sNorm
        val qDot2 = 0.5f * (q0 * gy - q1 * gz + q3 * gx) - BETA * s2 / sNorm
        val qDot3 = 0.5f * (q0 * gz + q1 * gy - q2 * gx) - BETA * s3 / sNorm

        // Integrate to yield quaternion
        val dt = SAMPLE_RATE_MS / 1000f
        quaternion[0] += qDot0 * dt
        quaternion[1] += qDot1 * dt
        quaternion[2] += qDot2 * dt
        quaternion[3] += qDot3 * dt

        // Normalize quaternion
        val qNorm = sqrt(quaternion[0] * quaternion[0] + quaternion[1] * quaternion[1] +
                quaternion[2] * quaternion[2] + quaternion[3] * quaternion[3])
        quaternion[0] /= qNorm
        quaternion[1] /= qNorm
        quaternion[2] /= qNorm
        quaternion[3] /= qNorm
    }

    private fun updatePosition() {
        val now = System.currentTimeMillis()
        val dt = (now - lastUpdateTime) / 1000.0
        if (dt <= 0) return
        lastUpdateTime = now

        // ZUPT: If stationary, set velocity to zero
        if (isStationary) {
            velocityX = 0.0
            velocityY = 0.0
            // Reduce uncertainty
            for (i in 0..3) {
                positionCovariance[i][i] *= 0.9
            }
        }

        // Transform linear acceleration to world frame
        val worldAccel = transformToWorldFrame(linearAccel)

        // Kalman filter prediction
        // State: [x, y, vx, vy]
        // Prediction: x = x + vx*dt, y = y + vy*dt, vx = vx + ax*dt, vy = vy + ay*dt

        val ax = worldAccel[0].toDouble()
        val ay = worldAccel[1].toDouble()

        // Update velocity and position
        velocityX += ax * dt
        velocityY += ay * dt
        positionX += velocityX * dt
        positionY += velocityY * dt

        // Update covariance (simplified)
        val q = PROCESS_NOISE * dt * dt
        positionCovariance[0][0] += q
        positionCovariance[1][1] += q
        positionCovariance[2][2] += q * 0.1
        positionCovariance[3][3] += q * 0.1

        // Get heading from quaternion
        val heading = atan2(
            2.0 * (quaternion[0] * quaternion[3] + quaternion[1] * quaternion[2]),
            1.0 - 2.0 * (quaternion[2] * quaternion[2] + quaternion[3] * quaternion[3])
        )

        // Calculate quality indicator
        val quality = 1.0f / (1.0f + positionCovariance[0][0].toFloat() + positionCovariance[1][1].toFloat())

        currentPosition = Position(positionX, positionY, heading, now, quality)

        // Add to history periodically
        if (pathHistory.isEmpty() ||
            (positionX - pathHistory.last().x).pow(2) + (positionY - pathHistory.last().y).pow(2) > 0.01) {
            pathHistory.add(currentPosition)
        }

        notifyListeners()
    }

    private fun transformToWorldFrame(accel: FloatArray): FloatArray {
        // Rotate acceleration vector using quaternion
        val q0 = quaternion[0]
        val q1 = quaternion[1]
        val q2 = quaternion[2]
        val q3 = quaternion[3]

        val ax = accel[0]
        val ay = accel[1]
        val az = accel[2]

        // Quaternion rotation: v' = q * v * q^(-1)
        val wx = 2f * (q0 * ax + q1 * ay + q2 * az)
        val wy = 2f * (q0 * ay - q1 * ax + q3 * az)
        val wz = 2f * (q0 * az - q2 * ax - q3 * ay)

        return floatArrayOf(
            ax + q0 * wx + q2 * wy - q1 * wz,
            ay - q3 * wx + q0 * wy + q1 * wz,
            az + q1 * wx - q2 * wy + q0 * wz
        )
    }

    private fun onStepDetected() {
        stepCount++
        val now = System.currentTimeMillis()

        // Estimate stride length from step frequency
        if (lastStepTime > 0) {
            val stepPeriod = (now - lastStepTime) / 1000.0
            if (stepPeriod < 2.0) { // Valid step
                // Faster steps = longer stride (up to a point)
                strideLength = 0.4 + min(0.6, 1.0 / stepPeriod) * 0.4
            }
        }
        lastStepTime = now

        // Use step as measurement update for Kalman filter
        val heading = atan2(
            2.0 * (quaternion[0] * quaternion[3] + quaternion[1] * quaternion[2]),
            1.0 - 2.0 * (quaternion[2] * quaternion[2] + quaternion[3] * quaternion[3])
        )

        val stepX = strideLength * sin(heading)
        val stepY = strideLength * cos(heading)

        // Measurement update (simplified)
        val measurementX = positionX + stepX
        val measurementY = positionY + stepY

        val k = positionCovariance[0][0] / (positionCovariance[0][0] + MEASUREMENT_NOISE)
        positionX = positionX + k * (measurementX - positionX)
        positionY = positionY + k * (measurementY - positionY)

        // Update covariance
        positionCovariance[0][0] *= (1 - k)
        positionCovariance[1][1] *= (1 - k)

        Log.d(TAG, "Step #$stepCount at (${String.format("%.2f", positionX)}, ${String.format("%.2f", positionY)})")
    }

    private fun calculateStd(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }

    fun stop() {
        Log.d(TAG, "========== STOPPING IMPROVED DR ==========")
        isRunning = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }

    fun resetPosition(x: Double, y: Double, heading: Double? = null) {
        positionX = x
        positionY = y
        velocityX = 0.0
        velocityY = 0.0

        if (heading != null) {
            // Convert heading to quaternion
            quaternion[0] = cos(heading / 2).toFloat()
            quaternion[1] = 0f
            quaternion[2] = 0f
            quaternion[3] = sin(heading / 2).toFloat()
        }

        pathHistory.clear()
        currentPosition = Position(x, y, heading ?: 0.0, System.currentTimeMillis())
        pathHistory.add(currentPosition)
        stepCount = 0

        // Reset covariance
        for (i in 0..3) {
            for (j in 0..3) {
                positionCovariance[i][j] = if (i == j) 1.0 else 0.0
            }
        }

        notifyListeners()
        Log.d(TAG, "Position reset to ($x, $y)")
    }

    fun addPositionListener(listener: (Position) -> Unit) {
        listeners.add(listener)
        listener(currentPosition)
    }

    private fun notifyListeners() {
        listeners.forEach { it(currentPosition) }
    }

//    fun getCurrentPosition() = currentPosition
    fun getPathHistory(): List<Position> = pathHistory.toList()

    fun getStats(): TrackingStats {
        val distance = sqrt(positionX.pow(2) + positionY.pow(2))
        val heading = atan2(
            2.0 * (quaternion[0] * quaternion[3] + quaternion[1] * quaternion[2]),
            1.0 - 2.0 * (quaternion[2] * quaternion[2] + quaternion[3] * quaternion[3])
        )

        return TrackingStats(
            stepCount = stepCount,
            distance = distance,
            headingDegrees = Math.toDegrees(heading),
            pathPoints = pathHistory.size,
            strideLength = strideLength,
            isActive = isRunning,
            quality = currentPosition.quality,
            isCalibrated = isCalibrated,
            isStationary = isStationary
        )
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        Log.d(TAG, "${sensor.name} accuracy changed: $accuracy")
    }

    data class TrackingStats(
        val stepCount: Int,
        val distance: Double,
        val headingDegrees: Double,
        val pathPoints: Int,
        val strideLength: Double,
        val isActive: Boolean,
        val quality: Float,
        val isCalibrated: Boolean,
        val isStationary: Boolean
    )
}