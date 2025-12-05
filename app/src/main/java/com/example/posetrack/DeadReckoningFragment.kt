package com.example.posetrack

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.posetrack.dr.DeadReckoningService

class DeadReckoningFragment : Fragment() {

    private var drService: DeadReckoningService? = null
    private var bound = false

    private lateinit var pathView: PathView
    private lateinit var positionText: TextView
    private lateinit var statsText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var resetButton: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as DeadReckoningService.LocalBinder
            drService = binder.getService()
            bound = true
            setupListener()
            Toast.makeText(requireContext(), getString(R.string.toast_tracking_started), Toast.LENGTH_LONG).show()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_dead_reckoning, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pathView = view.findViewById(R.id.pathView)
        positionText = view.findViewById(R.id.positionText)
        statsText = view.findViewById(R.id.statsText)
        startButton = view.findViewById(R.id.startButton)
        stopButton = view.findViewById(R.id.stopButton)
        resetButton = view.findViewById(R.id.resetButton)

        stopButton.isEnabled = false
        resetButton.isEnabled = false

        startButton.setOnClickListener { startTracking() }
        stopButton.setOnClickListener { stopTracking() }
        resetButton.setOnClickListener { reset() }
    }

    private fun startTracking() {
        val intent = Intent(requireContext(), DeadReckoningService::class.java)
        ContextCompat.startForegroundService(requireContext(), intent)
        requireContext().bindService(intent, connection, android.content.Context.BIND_AUTO_CREATE)

        startButton.isEnabled = false
        stopButton.isEnabled = true
        resetButton.isEnabled = true
    }

    private fun stopTracking() {
        if (bound) {
            requireContext().unbindService(connection)
            bound = false
        }
        requireContext().stopService(Intent(requireContext(), DeadReckoningService::class.java))

        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    private fun reset() {
        drService?.getDRModule()?.resetPosition(0.0, 0.0, 0.0)
        pathView.reset()
        // use existing string resources
        positionText.text = getString(R.string.position_reset_label)
        statsText.text = getString(R.string.stats_cleared_label)
    }

    private fun setupListener() {
        drService?.getDRModule()?.addPositionListener { pos ->
            activity?.runOnUiThread {
                // Use position_format from strings.xml: X: %1$.3f m | Y: %2$.3f m | Heading: %3$.0f°
                val headingDeg = Math.toDegrees(pos.heading).toFloat()
                positionText.text = getString(
                    R.string.position_format,
                    pos.x,
                    pos.y,
                    headingDeg
                )

                val stats = drService?.getDRModule()?.getStats()
                stats?.let {
                    // stats_format: Steps: %1$d | Distance: %2$.2f m | Points: %3$d
                    statsText.text = getString(
                        R.string.stats_format,
                        it.stepCount,
                        it.distance,
                        it.pathPoints
                    )
                }

                pathView.updatePath(drService?.getDRModule()?.getPathHistory() ?: emptyList())
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (bound) {
            try {
                requireContext().unbindService(connection)
            } catch (_: Exception) { /* ignore */ }
        }
    }
}
