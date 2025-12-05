package com.example.posetrack.dr

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.util.Log
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.posetrack.MainActivity
import com.example.posetrack.R

class DeadReckoningService : Service() {

    private lateinit var drModule: DeadReckoningModule
    private val binder = LocalBinder()

    companion object {
        const val CHANNEL_ID = "PoseTrackChannel"
        const val NOTIFICATION_ID = 1
    }

    inner class LocalBinder : Binder() {
        fun getService(): DeadReckoningService = this@DeadReckoningService
    }

    override fun onCreate() {
        super.onCreate()
        drModule = DeadReckoningModule(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = try {
            createNotification()
        } catch (e: Exception) {
            // building notification failed — log and stop service rather than crash app
            Log.e("DeadReckoningService", "Failed to build notification", e)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // Try to start foreground — SecurityException can be thrown here if permissions are missing.
            startForeground(NOTIFICATION_ID, notification)
        } catch (se: SecurityException) {
            Log.e("DeadReckoningService", "SecurityException starting foreground service: ${se.message}", se)
            // Stop the service gracefully if we don't have required permission.
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            Log.e("DeadReckoningService", "Unexpected exception when starting foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // Now start module — also protect it with try/catch (sensor init can throw)
        try {
            drModule.start()
        } catch (e: Exception) {
            Log.e("DeadReckoningService", "Failed to start DR module", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }


    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        drModule.stop()
    }

    fun getDRModule() = drModule

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PoseTrack Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracking position"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PoseTrack Active")
            .setContentText("Tracking position...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }
}