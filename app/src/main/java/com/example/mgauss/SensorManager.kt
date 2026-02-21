package com.example.mgauss

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.sqrt

data class SensorSnapshot(
    val timestamp: Long,
    val mx: Double, val my: Double, val mz: Double,
    val qx: Double, val qy: Double, val qz: Double, val qw: Double
)

class SensorManager(private val context: Context, private val listener: SensorListener) : SensorEventListener {

    interface SensorListener {
        fun onStatusChanged(message: String)
        fun onMagUpdate(magnitude: Float)
        fun onGraphUpdate(timeOffset: Float, magnitude: Float)
        fun onPredictionResult(label: String)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val inferenceEngine = InferenceEngine(context)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mgauss::SensorLock")
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Mgauss::WifiLock")
    } else {
        @Suppress("DEPRECATION")
        wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Mgauss::WifiLock")
    }
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val CHANNEL_ID = "mgauss_alerts"
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val udpManager = UDPManager(context) { senderUUID ->
        Handler(Looper.getMainLooper()).post {
            var msg: String
            msg = "⚠️ ALERT FROM ${senderUUID.take(4)}"
            listener.onStatusChanged(msg)
            triggerLocalFeedback(showNotification = true, customMessage = msg)
        }
    }

    // Inference variables
    var isInferring = false
    private val inferenceBuffer = Collections.synchronizedList(ArrayList<SensorSnapshot>())
    private var lastInferenceTimeNs: Long = 0
    private val INFERENCE_INTERVAL_NS = 100_000_000L

    // Sensor values
    private var qx = 0f; private var qy = 0f; private var qz = 0f; private var qw = 1f
    private val tempQuaternion = FloatArray(4)
    private var ax = 0f; private var ay = 0f; private var az = 0f

    // Alert logic
    private var isDeviceActive = false
    private val alertHandler = Handler(Looper.getMainLooper())

    // --- LOCAL ALERT LOOP (Vibration/Sound Only) ---
    private val alertRunnable = object : Runnable {
        override fun run() {
            if (isDeviceActive) {
                // Only trigger local feedback here. NO NETWORK SPAM.
                triggerLocalFeedback(showNotification = false)
                alertHandler.postDelayed(this, 1000)
            }
        }
    }

    init {
        createNotificationChannel()
        udpManager.startListening()
        wifiLock.setReferenceCounted(false)
    }

    fun startDetection() {
        isInferring = true
        synchronized(inferenceBuffer) { inferenceBuffer.clear() }
        lastInferenceTimeNs = System.nanoTime()
        isDeviceActive = false
        if (!wakeLock.isHeld) wakeLock.acquire(10*60*1000L)
        if (!wifiLock.isHeld) wifiLock.acquire()
        listener.onStatusChanged("Detecting...")
        startSensors()
    }

    fun stopDetection() {
        stopSensors()
        isInferring = false
        isDeviceActive = false
        alertHandler.removeCallbacks(alertRunnable)
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
        listener.onStatusChanged("Detection Stopped")
        listener.onPredictionResult("Neutral")
    }

    private fun startSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)?.let { sensorManager.registerListener(this, it, 10000) }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensorManager.registerListener(this, it, 10000) }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensorManager.registerListener(this, it, 10000) }
    }

    private fun stopSensors() { sensorManager.unregisterListener(this) }

    override fun onSensorChanged(event: SensorEvent?) {
        var nowNs: Long
        var mx: Float
        var my: Float
        var mz: Float
        var magTotal: Float
        var snapshot: SensorSnapshot
        var bufferCopy: ArrayList<SensorSnapshot>

        if (event == null) return
        nowNs = event.timestamp

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            ax = event.values[0]; ay = event.values[1]; az = event.values[2]
            return
        }
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getQuaternionFromVector(tempQuaternion, event.values)
            qw = tempQuaternion[0]; qx = tempQuaternion[1]; qy = tempQuaternion[2]; qz = tempQuaternion[3]
            return
        }

        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) {
            mx = event.values[0]; my = event.values[1]; mz = event.values[2]
            magTotal = sqrt(mx * mx + my * my + mz * mz)
            listener.onMagUpdate(magTotal)

            if (isInferring) {
                snapshot = SensorSnapshot(nowNs, mx.toDouble(), my.toDouble(), mz.toDouble(), qx.toDouble(), qy.toDouble(), qz.toDouble(), qw.toDouble())

                synchronized(inferenceBuffer) {
                    inferenceBuffer.add(snapshot)
                    while (inferenceBuffer.isNotEmpty() && inferenceBuffer[0].timestamp < nowNs - 1_500_000_000L) {
                        inferenceBuffer.removeAt(0)
                    }
                }

                if (nowNs - lastInferenceTimeNs >= INFERENCE_INTERVAL_NS) {
                    bufferCopy = synchronized(inferenceBuffer) { ArrayList(inferenceBuffer) }

                    if (bufferCopy.isNotEmpty() && (bufferCopy.last().timestamp - bufferCopy.first().timestamp >= 1_000_000_000L)) {
                        lastInferenceTimeNs = nowNs
                        CoroutineScope(Dispatchers.Default).launch {
                            try { processInference(bufferCopy, nowNs) } catch (e: Exception) { e.printStackTrace() }
                        }
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun processInference(buffer: List<SensorSnapshot>, captureTime: Long) {
        var deviceProb: Float
        var finalLabel: String

        val result = inferenceEngine.predict(buffer, captureTime)
        deviceProb = if (result.label == "Device") result.confidence else (1.0f - result.confidence)

        if (!isDeviceActive) {
            if (deviceProb > 0.75f) {
                isDeviceActive = true

                // --- BURST MODE NETWORK ALERT ---
                // Fires 5 times over 1 second to ensure delivery, then stops.
                CoroutineScope(Dispatchers.IO).launch {
                    repeat(5) {
                        udpManager.sendAlert()
                        delay(200) // 200ms delay to allow radio wake-up
                    }
                }

                Handler(Looper.getMainLooper()).post {
                    sendNotification("Magnetic Anomaly Detected!")
                }

                // Start local feedback loop (Vibrate/Sound)
                alertHandler.post(alertRunnable)
            }
        } else {
            if (deviceProb < 0.30f) {
                isDeviceActive = false
                alertHandler.removeCallbacks(alertRunnable)
            }
        }

        finalLabel = if (isDeviceActive) "Device" else "Neutral"
        CoroutineScope(Dispatchers.Main).launch { listener.onPredictionResult(finalLabel) }
    }

    private fun triggerLocalFeedback(showNotification: Boolean, customMessage: String = "Magnetic Anomaly Detected!") {
        var timings: LongArray
        var amplitudes: IntArray

        toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            timings = longArrayOf(0, 500)
            amplitudes = intArrayOf(0, 255)
            try { vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1)) } catch (e: Exception) { }
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }

        if (showNotification) {
            sendNotification(customMessage)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Mgauss Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Security alerts"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendNotification(message: String) {
        var builder: NotificationCompat.Builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Mgauss Alert")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), builder.build())
    }
}