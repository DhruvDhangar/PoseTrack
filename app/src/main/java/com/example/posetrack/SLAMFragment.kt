@file:Suppress("DEPRECATION", "UsePropertyAccessSyntax")
package com.example.posetrack

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.fragment.app.Fragment
import com.example.posetrack.slam.SLAMModule
import com.example.posetrack.slam.SLAMView
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class SLAMFragment : Fragment() {

    private val tag = "SLAMFragment"

    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var slamView: SLAMView
    private lateinit var positionText: TextView
    private lateinit var statsText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var resetButton: Button

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Use improved SLAM module
    private val slamModule = SLAMModule()
    private var bitmapBuffer: Bitmap? = null

    // Reusable temp arrays
    private var pixelRow: IntArray? = null
    private var rgbaRowBuffer: ByteArray? = null

    @Volatile
    private var isProcessingFrame = false

    @Volatile
    private var isTracking = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_slam, container, false)
        previewView = root.findViewById(R.id.previewView)
        slamView = root.findViewById(R.id.slamView)
        positionText = root.findViewById(R.id.positionText)
        statsText = root.findViewById(R.id.statsText)
        startButton = root.findViewById(R.id.startButton)
        stopButton = root.findViewById(R.id.stopButton)
        resetButton = root.findViewById(R.id.resetButton)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            startButton.isEnabled = false
            stopButton.isEnabled = false
        }
    }

    private fun setupButtons() {
        startButton.setOnClickListener {
            isTracking = true
            slamModule.start()
            startButton.isEnabled = false
            stopButton.isEnabled = true
            resetButton.isEnabled = true
        }

        stopButton.setOnClickListener {
            isTracking = false
            slamModule.stop()
            startButton.isEnabled = true
            stopButton.isEnabled = false
        }

        resetButton.setOnClickListener {
            slamModule.resetPosition()
            slamView.reset()
            positionText.text = getString(R.string.waiting_to_start)
            statsText.text = getString(R.string.stats_not_available)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
                analyzeImageRGBA(imageProxy)
            }

            try {
                cameraProvider?.unbindAll()
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e(tag, "Failed to bind camera use cases", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun imageProxyToBitmapRGBA(image: ImageProxy, outBitmap: Bitmap): Bitmap {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        if (rgbaRowBuffer == null || rgbaRowBuffer!!.size < rowStride) {
            rgbaRowBuffer = ByteArray(rowStride)
        }
        if (pixelRow == null || pixelRow!!.size < width) {
            pixelRow = IntArray(width)
        }

        val rowBytes = rgbaRowBuffer!!
        val pixels = pixelRow!!

        for (y in 0 until height) {
            val offset = y * rowStride
            buffer.position(offset)
            buffer.get(rowBytes, 0, rowStride)

            var dst = 0
            var src = 0
            while (dst < width) {
                val r = rowBytes[src].toInt() and 0xFF
                val g = rowBytes[src + 1].toInt() and 0xFF
                val b = rowBytes[src + 2].toInt() and 0xFF
                val a = rowBytes[src + 3].toInt() and 0xFF
                pixels[dst] = (a shl 24) or (r shl 16) or (g shl 8) or b
                dst++
                src += pixelStride
            }

            outBitmap.setPixels(pixels, 0, width, 0, y, width, 1)
        }

        return outBitmap
    }

    private fun analyzeImageRGBA(imageProxy: ImageProxy) {
        if (!isTracking) {
            imageProxy.close()
            return
        }

        if (isProcessingFrame) {
            imageProxy.close()
            return
        }

        isProcessingFrame = true
        try {
            if (bitmapBuffer == null || bitmapBuffer?.width != imageProxy.width || bitmapBuffer?.height != imageProxy.height) {
                bitmapBuffer = createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            }
            val bmp = bitmapBuffer!!

            imageProxy.planes[0].buffer.rewind()
            imageProxyToBitmapRGBA(imageProxy, bmp)

            // Process on camera executor thread
            cameraExecutor.execute {
                try {
                    val frameStart = System.currentTimeMillis()
                    slamModule.processFrame(bmp)
                    val processingTime = System.currentTimeMillis() - frameStart

                    val stats = slamModule.getStats()
                    requireActivity().runOnUiThread {
                        // Show position with uncertainty indicator
                        val uncertaintyIcon = when {
                            stats.uncertainty < 0.5 -> "🟢"
                            stats.uncertainty < 1.0 -> "🟡"
                            else -> "🔴"
                        }

                        positionText.text = String.format(
                            getString(R.string.position_format),
                            slamModule.getCurrentPosition().x,
                            slamModule.getCurrentPosition().y,
                            Math.toDegrees(slamModule.getCurrentPosition().heading)
                        ) + " $uncertaintyIcon"

                        // Show detailed stats
                        statsText.text = String.format(
                            "Frames: %d | Dist: %.2f m | Landmarks: %d\nQuality: %s | Processing: %dms",
                            stats.frameCount,
                            stats.distance,
                            stats.mapFeatures,
                            stats.trackingQuality,
                            processingTime
                        )

                        slamView.updatePath(slamModule.getPathHistory(), slamModule.getMapFeatures())
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error processing frame", e)
                } finally {
                    isProcessingFrame = false
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Conversion or processing error", e)
            isProcessingFrame = false
        } finally {
            imageProxy.close()
        }
    }

    override fun onStop() {
        super.onStop()
        isTracking = false
        slamModule.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) { }
        cameraExecutor.shutdown()
    }
}