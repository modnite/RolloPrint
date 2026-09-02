package com.example.rolloprint

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class PrintServerService : Service() {

    private val binder = LocalBinder()
    var printServer: NetworkPrintServer? = null
        private set

    companion object {
        const val CHANNEL_ID = "rollo_print_server_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.rolloprint.START_SERVER"
        const val ACTION_STOP = "com.example.rolloprint.STOP_SERVER"
        var isServerRunning = false
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): PrintServerService = this@PrintServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopSelf()
            }
            ACTION_START, null -> {
                startForegroundServer()
            }
        }
        return START_STICKY
    }

    private fun startForegroundServer() {
        val notification = createNotification("Initializing Rollo Print Server...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isServerRunning = true
    }

    fun initializeServer(usbPrintManager: UsbPrintManager, logger: (String) -> Unit, onStatusChanged: (Boolean, String?) -> Unit) {
        if (printServer == null) {
            printServer = NetworkPrintServer(this, usbPrintManager, { logMsg ->
                logger(logMsg)
            }, { running, ip ->
                isServerRunning = running
                updateNotification(if (running) "Active on $ip:9100" else "Server Stopped")
                onStatusChanged(running, ip)
            })
        }
        printServer?.start()
    }

    fun stopServer() {
        printServer?.stop()
        isServerRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun updateNotification(statusText: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.notify(NOTIFICATION_ID, createNotification(statusText))
        } catch (_: Exception) {}
    }

    private fun createNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, PrintServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rollo Network Print Server")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Server", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rollo Print Server Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground notification for local network print server"
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
