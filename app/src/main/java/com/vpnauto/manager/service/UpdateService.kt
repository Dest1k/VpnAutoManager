package com.vpnauto.manager.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vpnauto.manager.R

/**
 * Опциональный foreground-сервис для обеспечения работы в фоне.
 * WorkManager сам по себе достаточен, но этот сервис помогает
 * на агрессивных прошивках (MIUI, One UI и т.д.)
 */
class UpdateService : Service() {

    companion object {
        const val CHANNEL_ID = "vpn_service_channel"
        const val NOTIF_ID = 2001
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle("VPN Guard")
            .setContentText("Автообновление активно")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "VPN Guard сервис",
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}
