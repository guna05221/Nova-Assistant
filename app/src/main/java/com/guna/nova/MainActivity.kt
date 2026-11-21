package com.guna.nova

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val asks = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) asks += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(asks.toTypedArray())

        setContent {
            var running by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf("Ready when you are") }
            LaunchedEffect(Unit) {
                while (true) {
                    val prefs = getSharedPreferences("nova_diagnostics", MODE_PRIVATE)
                    status = prefs.getString("status", "Ready when you are") ?: "Ready when you are"
                    running = prefs.getBoolean("running", false)
                    delay(500)
                }
            }
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF9D8CFF), secondary = Color(0xFF62E8E0),
                background = Color(0xFF080A14), surface = Color(0xFF15182A)
            )) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF080A14)) {
                    Column(
                        Modifier.fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color(0xFF171A35), Color(0xFF080A14))))
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("N O V A", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text("Your voice. Your phone.", color = Color(0xFF9EA6C8))
                        Spacer(Modifier.height(42.dp))
                        Box(Modifier.size(190.dp).background(
                            Brush.radialGradient(listOf(Color(0xFF6655D9), Color(0xFF20264D), Color.Transparent)),
                            CircleShape
                        ), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Mic, null, tint = Color(0xFF75F0E8), modifier = Modifier.size(76.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(status, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(26.dp))
                        Button(onClick = {
                            if (!running) {
                                status = "Requesting microphone service…"
                                try {
                                    ContextCompat.startForegroundService(this@MainActivity,
                                        Intent(this@MainActivity, WakeWordService::class.java))
                                } catch (e: Exception) {
                                    getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
                                        .putBoolean("running", false)
                                        .putString("status", "Service start failed: ${e.javaClass.simpleName}: ${e.message}")
                                        .apply()
                                }
                            } else {
                                stopService(Intent(this@MainActivity, WakeWordService::class.java))
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (running) "Stop listening" else "Start listening")
                        }
                        OutlinedButton(onClick = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Settings, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Enable accessibility control")
                        }
                        Text("Nova reports microphone and model startup status here.",
                            color = Color(0xFF9299B7), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
