package com.example.mgauss

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    // UI State
    private var statusMessage by mutableStateOf("Ready")
    private var magValue by mutableFloatStateOf(0f)
    private var predictionLabel by mutableStateOf("Neutral")
    private var isScanning by mutableStateOf(false)

    // Graph Data
    private val graphPoints = mutableStateListOf<Float>()

    // Broadcast Receiver to get updates from SensorManager/Service
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            var mag: Float
            intent?.let {
                if (it.hasExtra("mag")) {
                    mag = it.getFloatExtra("mag", 0f)
                    magValue = mag
                    graphPoints.add(mag)
                    if (graphPoints.size > 100) graphPoints.removeAt(0)
                }
                if (it.hasExtra("prediction")) {
                    predictionLabel = it.getStringExtra("prediction") ?: "Neutral"
                }
                if (it.hasExtra("status")) {
                    statusMessage = it.getStringExtra("status") ?: ""
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Permission check for Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start passive monitoring immediately
        startPassiveService()

        setContent {
            MaterialTheme {
                // Direct call to DetectionView, no Navigation needed
                DetectionView()
            }
        }
    }

    override fun onResume() {
        val filter = IntentFilter("com.example.mgauss.UPDATE_UI")
        super.onResume()
        ContextCompat.registerReceiver(this, updateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(updateReceiver)
    }

    private fun startPassiveService() {
        val intent = Intent(this, ScanningService::class.java)
        intent.action = "START_PASSIVE"
        startForegroundService(intent)
    }

    private fun startServiceCommand() {
        val intent = Intent(this, ScanningService::class.java)
        intent.action = "START_SCANNING"
        startForegroundService(intent)
        isScanning = true
    }

    private fun stopServiceCommand() {
        val intent = Intent(this, ScanningService::class.java)
        intent.action = "STOP_SCANNING"
        startService(intent)
        isScanning = false
        predictionLabel = "Neutral"
        magValue = 0f
    }

    // REMOVED: triggerCollectionCommand

    @Composable
    fun DetectionView() {
        val isDetected = predictionLabel == "Device"
        val bgColor = if (isDetected) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)
        val txtColor = if (isDetected) Color.Red else Color(0xFF4CAF50)

        Scaffold(
            modifier = Modifier.fillMaxSize()
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Mgauss Security", fontSize = 28.sp, fontWeight = FontWeight.Bold)

                Spacer(modifier = Modifier.height(30.dp))

                // Status Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .border(2.dp, txtColor, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = bgColor)
                ) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isDetected) "DEVICE DETECTED" else "NEUTRAL",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = txtColor
                        )
                        if (isDetected) {
                            Text("Magnetic Signature Match", fontSize = 14.sp, color = Color.Red)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))

                // Live Graph
                Text("Live Sensor Feed", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.align(Alignment.Start))
                LiveGraph(graphPoints)

                // Value Display
                Text(
                    text = "Mag: %.2f µT".format(magValue),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )

                Text(statusMessage, fontSize = 14.sp, color = Color.Gray)

                Spacer(modifier = Modifier.weight(1f))

                // Control Button
                Button(
                    onClick = { if (isScanning) stopServiceCommand() else startServiceCommand() },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) Color.Red else Color(0xFF4CAF50)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isScanning) "STOP DETECTING" else "START DETECTING",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    @Composable
    fun LiveGraph(points: List<Float>) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp))
                .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
        ) {
            val width = size.width
            val height = size.height
            val path = Path()

            // Auto-scale the graph
            val min = points.minOrNull() ?: 0f
            val max = points.maxOrNull() ?: 100f
            val range = (max - min).coerceAtLeast(1f)

            if (points.isEmpty()) return@Canvas

            points.forEachIndexed { i, v ->
                val x = (i.toFloat() / (points.size - 1).coerceAtLeast(1)) * width
                val y = height - ((v - min) / range) * height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color.Blue, style = Stroke(3.dp.toPx()))
        }
    }
}