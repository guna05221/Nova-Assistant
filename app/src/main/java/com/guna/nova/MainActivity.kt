package com.guna.nova

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private val NovaBackground = Color(0xFF212121)
private val NovaPurple = Color(0xFFB8A7FF)
private val NovaMint = Color(0xFF65E5D5)

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val asks = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) asks += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(asks.toTypedArray())

        setContent {
            var running by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf("Ready when you are") }
            val localAi by LocalAiManager.state.collectAsState()

            LaunchedEffect(Unit) {
                LocalAiManager.loadIfPresent(this@MainActivity)
                while (true) {
                    val prefs = getSharedPreferences("nova_diagnostics", MODE_PRIVATE)
                    status = prefs.getString("status", "Ready when you are") ?: "Ready when you are"
                    running = prefs.getBoolean("running", false)
                    delay(500)
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = NovaPurple,
                    secondary = NovaMint,
                    background = NovaBackground,
                    surface = Color(0xFF2F2F2F),
                    onSurface = Color.White
                )
            ) {
                Surface(color = NovaBackground) {
                    NovaChatScreen(
                        status = status,
                        listening = running,
                        onStartListening = {
                            try {
                                ContextCompat.startForegroundService(
                                    this@MainActivity,
                                    Intent(this@MainActivity, WakeWordService::class.java)
                                )
                            } catch (e: Exception) {
                                getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
                                    .putBoolean("running", false)
                                    .putString("status", "Voice service couldn't start. Check microphone permission and try again.")
                                    .apply()
                            }
                        },
                        onStopListening = {
                            getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
                                .putBoolean("running", false)
                                .putString("status", "Listening stopped.")
                                .apply()
                            stopService(Intent(this@MainActivity, WakeWordService::class.java).apply {
                                action = WakeWordService.ACTION_STOP
                            })
                        }
                    )
                }
            }
        }
    }
}
