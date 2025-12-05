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

class DeadReckoningModule(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())

    // Sensors
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    data class Position(val x: Double, val y: Double, val heading: Double, val timestamp: Long)

    private var currentPosition = Position(0.0, 0.0, 0.0, System.currentTimeMillis())
    private var listeners = mutableListOf<(Position) -> Unit>()
    private val pathHistory = mutableListOf<Position>()

    // Sensor data
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)

    // Orientation
    private var currentHeading = 0.0
    private var headingEstablished = false

    // Step detection
    private var stepCount = 0
    private var strideLength = 0.7 // 70cm default stride

    // Filtering
    private val alpha = 0.8f

    private var isRunning = false
    private var lastUpdateTime = 0L

    companion object {
        private const val TAG = "DR_Module"
        private const val UPDATE_INTERVAL = 100L // Update UI every 100ms
    }

    fun start() {
        Log.d(TAG, "========== STARTING DR MODULE ==========")
        isRunning = true

        pathHistory.clear()
        pathHistory.add(currentPosition)
        stepCount = 0

        // Register sensors
        var registered = 0

        accelerometer?.also { sensor ->
            val success = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            Log.d(TAG, "Accelerometer: ${if (success) "✓ Registered" else "✗ Failed"}")
            if (success) registered++
        } ?: Log.e(TAG, "✗ No Accelerometer")

        magnetometer?.also { sensor ->
            val success = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            Log.d(TAG, "Magnetometer: ${if (success) "✓ Registered" else "✗ Failed"}")
            if (success) registered++
        } ?: Log.e(TAG, "✗ No Magnetometer")

        stepDetector?.also { sensor ->
            val success = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            Log.d(TAG, "Step Detector: ${if (success) "✓ Registered" else "✗ Failed"}")
            if (success) registered++
        } ?: Log.e(TAG, "✗ No Step Detector")

        Log.d(TAG, "Sensors registered: $registered/3")

        // Start update loop
        startUpdateLoop()
    }

    private fun startUpdateLoop() {
        handler.post(object : Runnable {
            override fun run() {
                if (isRunning) {
                    // Periodic updates even without steps (for heading changes)
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime > UPDATE_INTERVAL) {
                        notifyListeners()
                        lastUpdateTime = now
                    }
                    handler.postDelayed(this, UPDATE_INTERVAL)
                }
            }
        })
    }

    fun stop() {
        Log.d(TAG, "========== STOPPING DR MODULE ==========")
        isRunning = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }

    fun resetPosition(x: Double, y: Double, heading: Double? = null) {
        currentPosition = Position(x, y, heading ?: currentHeading, System.currentTimeMillis())
        if (heading != null) {
            currentHeading = heading
        }
        pathHistory.clear()
        pathHistory.add(currentPosition)
        stepCount = 0
        notifyListeners()
        Log.d(TAG, "Position reset to ($x, $y)")
    }

    fun addPositionListener(listener: (Position) -> Unit) {
        listeners.add(listener)
        // Immediately notify with current position
        listener(currentPosition)
    }

    // Public accessor used by Activity / Service to read current position synchronously
    fun getCurrentPosition() = currentPosition

    fun getPathHistory(): List<Position> = pathHistory.toList()

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRunning) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Update gravity
                gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

                // Update heading if we have magnetometer data
                if (geomagnetic[0] != 0f || geomagnetic[1] != 0f) {
                    updateHeading()
                }
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                geomagnetic[0] = event.values[0]
                geomagnetic[1] = event.values[1]
                geomagnetic[2] = event.values[2]
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                onStepDetected()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        Log.d(TAG, "${sensor.name} accuracy: $accuracy")
    }

    private fun updateHeading() {
        val rotationMatrix = FloatArray(9)
        val inclinationMatrix = FloatArray(9)

        if (SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, gravity, geomagnetic)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)

            currentHeading = orientation[0].toDouble()

            if (!headingEstablished) {
                headingEstablished = true
                Log.d(TAG, "Initial heading: ${Math.toDegrees(currentHeading).toInt()}°")
            }
        }
    }

    private fun onStepDetected() {
        stepCount++

        // Calculate displacement based on current heading and stride
        val dx = strideLength * sin(currentHeading)
        val dy = strideLength * cos(currentHeading)

        // Update position
        currentPosition = Position(
            currentPosition.x + dx,
            currentPosition.y + dy,
            currentHeading,
            System.currentTimeMillis()
        )

        pathHistory.add(currentPosition)

        Log.d(
            TAG, "STEP #$stepCount → X:${"%.2f".format(currentPosition.x)}m, Y:${"%.2f".format(currentPosition.y)}m, " +
                    "H:${Math.toDegrees(currentHeading).toInt()}°"
        )

        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { listener ->
            try {
                listener(currentPosition)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }

    fun getStats(): TrackingStats {
        val distance = sqrt(currentPosition.x.pow(2) + currentPosition.y.pow(2))
        return TrackingStats(
            stepCount = stepCount,
            distance = distance,
            headingDegrees = Math.toDegrees(currentHeading),
            pathPoints = pathHistory.size,
            strideLength = strideLength,
            isActive = isRunning
        )
    }

    data class TrackingStats(
        val stepCount: Int,
        val distance: Double,
        val headingDegrees: Double,
        val pathPoints: Int,
        val strideLength: Double,
        val isActive: Boolean
    )
}
