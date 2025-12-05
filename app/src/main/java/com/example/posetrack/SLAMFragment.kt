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

    private val slamModule = SLAMModule()
    private var bitmapBuffer: Bitmap? = null

    // reusable temp arrays to reduce allocations
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
        // Start camera only if we have camera permission
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            // rely on MainActivity permission flow; disable start until permission granted
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

            // Request RGBA_8888 output so we don't need YUV->RGB conversion.
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // intentionally not calling setTargetResolution to avoid CameraX version mismatch
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

    /**
     * Convert an RGBA_8888 ImageProxy to an ARGB_8888 Bitmap (reusing buffers).
     * Assumes ImageProxy.format == RGBA_8888 and single plane with pixelStride >= 4.
     */
    private fun imageProxyToBitmapRGBA(image: ImageProxy, outBitmap: Bitmap): Bitmap {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride // typically 4 for RGBA

        // ensure row buffers
        if (rgbaRowBuffer == null || rgbaRowBuffer!!.size < rowStride) {
            rgbaRowBuffer = ByteArray(rowStride)
        }
        if (pixelRow == null || pixelRow!!.size < width) {
            pixelRow = IntArray(width)
        }

        val rowBytes = rgbaRowBuffer!!
        val pixels = pixelRow!!

        // iterate rows
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

            // ensure buffer position is at start
            imageProxy.planes[0].buffer.rewind()
            // convert RGBA plane -> ARGB bitmap (on cameraExecutor thread)
            imageProxyToBitmapRGBA(imageProxy, bmp)

            // run SLAM processing on cameraExecutor (we're already on it)
            cameraExecutor.execute {
                try {
                    slamModule.processFrame(bmp)

                    // update UI on main thread
                    val stats = slamModule.getStats()
                    requireActivity().runOnUiThread {
                        positionText.text = String.format(
                            getString(R.string.position_format),
                            slamModule.getCurrentPosition().x,
                            slamModule.getCurrentPosition().y,
                            0.0 // heading not computed by this SLAM prototype
                        )
                        statsText.text = String.format(
                            getString(R.string.stats_format),
                            stats.frameCount,
                            stats.distance,
                            stats.pathPoints
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
