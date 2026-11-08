package com.drivelink.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.drivelink.MainActivity
import com.drivelink.driving.JourneyManager

/** Foreground service that keeps location + Nearby communication alive during a journey. */
class JourneyService : Service() {
    companion object {
        const val ACTION_STOP = "com.drivelink.STOP"
        private const val CHANNEL = "journey"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, JourneyService::class.java))
        }
        fun stop(context: Context) { context.stopService(Intent(context, JourneyService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            JourneyManager.stop(); stopSelf(); return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Journey", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("DriveLink journey active")
            .setContentText("Sharing position with nearby DriveLink vehicles")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true).setContentIntent(open).build()
        ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        JourneyManager.start(applicationContext)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        JourneyManager.stop()
        super.onDestroy()
    }
}
