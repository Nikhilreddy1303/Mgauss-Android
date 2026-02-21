package com.example.mgauss

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ScanningService : Service(), SensorManager.SensorListener {

    private var sensorManager: SensorManager? = null
    private val CHANNEL_ID = "mgauss_service_channel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sensorManager = SensorManager(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            "START_PASSIVE" -> {
                startForegroundService("Monitoring for alerts...")
                sensorManager?.stopDetection()
            }
            "START_SCANNING" -> {
                startForegroundService("Scanning Active")
                sensorManager?.startDetection()
            }
            "STOP_SCANNING" -> {
                sensorManager?.stopDetection()
                startForegroundService("Monitoring for alerts...")
            }
            "KILL_SERVICE" -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sensorManager?.stopDetection()
        super.onDestroy()
    }

    override fun onStatusChanged(message: String) {
        val intent = Intent("com.example.mgauss.UPDATE_UI")
        intent.putExtra("status", message)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    override fun onMagUpdate(magnitude: Float) {
        val intent = Intent("com.example.mgauss.UPDATE_UI")
        intent.putExtra("mag", magnitude)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    override fun onGraphUpdate(timeOffset: Float, magnitude: Float) { }

    override fun onPredictionResult(label: String) {
        val intent = Intent("com.example.mgauss.UPDATE_UI")
        intent.putExtra("prediction", label)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun startForegroundService(statusText: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mgauss Security")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Mgauss Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}