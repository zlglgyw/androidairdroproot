package com.getgua.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service yang menjalankan server HTTP kendali HP.
 *
 * Alasan utama aplikasi ini ada: Termux kadang di-kill Android
 * (terbukti 12 Nov 2026 saat layar mati), sementara foreground
 * service dengan notifikasi persisten jauh lebih tahan dibunuh.
 * START_STICKY membuat service di-restart sistem bila sempat mati,
 * dan BootReceiver menyalakannya lagi sesudah reboot.
 *
 * Tidak ada idle timeout di sini (berbeda dengan phone_server yang
 * berhenti sendiri sesudah 30 menit idle) — service ini memang
 * dirancang untuk selalu hidup.
 */
class ClientService : Service() {

    companion object {
        const val PORT = 8766
        const val ACTION_STOP = "com.getgua.client.STOP"

        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "getgua_client"

        @Volatile var running = false
            private set
    }

    private var server: HttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        Automation.init(this)
        TokenStore.reload()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        acquireWakeLock()
        if (server == null) {
            try {
                server = HttpServer(PORT, Router(applicationContext)).also { it.start() }
                running = true
                AppLog.log("service", "started, http on port $PORT")
            } catch (e: Exception) {
                AppLog.log("service", "http start failed: ${(e.message ?: "").take(100)}")
                running = false
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        running = false
        releaseWakeLock()
        AppLog.log("service", "destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "getgua-client:http").apply {
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }
}
