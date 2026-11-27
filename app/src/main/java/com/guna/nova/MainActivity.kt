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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private val NovaBackground = Color(0xFF0B1020)
private val NovaPanel = Color(0xFF171F35)
private val NovaPurple = Color(0xFFA99BFF)
private val NovaMint = Color(0xFF65E5D5)
private val NovaMuted = Color(0xFFAAB4CC)

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
            var liveTranscript by remember { mutableStateOf("") }
            var finalTranscript by remember { mutableStateOf("") }
            var asrEngine by remember { mutableStateOf("Parakeet Unified EN 0.6B · local") }
            val localAi by LocalAiManager.state.collectAsState()
            val asrState by ParakeetAsrManager.state.collectAsState()

            LaunchedEffect(Unit) {
                LocalAiManager.loadIfPresent(this@MainActivity)
                ParakeetAsrManager.loadIfInstalled(this@MainActivity)
                while (true) {
                    val prefs = getSharedPreferences("nova_diagnostics", MODE_PRIVATE)
                    status = prefs.getString("status", "Ready when you are") ?: "Ready when you are"
                    running = prefs.getBoolean("running", false)
                    liveTranscript = prefs.getString("liveTranscript", "") ?: ""
                    finalTranscript = prefs.getString("finalTranscript", "") ?: ""
                    asrEngine = prefs.getString("asrEngine", "Parakeet Unified EN 0.6B · local") ?: "Parakeet Unified EN 0.6B · local"
                    delay(500)
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = NovaPurple,
                    secondary = NovaMint,
                    background = NovaBackground,
                    surface = NovaPanel,
                    onSurface = Color.White
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = NovaBackground) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF171D39), NovaBackground, Color(0xFF090D18))
                                )
                            )
                            .statusBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 18.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "NOVA",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text("Tamil • English • Tanglish voice assistant", color = NovaMuted)
                            }
                            Surface(color = Color(0xFF202A46), shape = CircleShape) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(8.dp).background(
                                            if (running) NovaMint else Color(0xFF8791A8),
                                            CircleShape
                                        )
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    Text(
                                        if (running) "ACTIVE" else "IDLE",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = NovaPanel),
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(112.dp)
                                        .background(
                                            Brush.radialGradient(
                                                listOf(Color(0xFF6457C8), Color(0xFF29345C), NovaPanel)
                                            ),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (running) Icons.Default.GraphicEq else Icons.Default.Mic,
                                        contentDescription = "Microphone",
                                        tint = NovaMint,
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    if (running) "Nova is listening" else "Ready to help",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    friendlyStatus(status),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NovaMuted
                                )
                                Spacer(Modifier.height(18.dp))
                                OutlinedButton(
                                    onClick = {
                                        if (!running) {
                                            try {
                                                ContextCompat.startForegroundService(
                                                    this@MainActivity,
                                                    Intent(this@MainActivity, WakeWordService::class.java).apply {
                                                        action = WakeWordService.ACTION_ENROLL
                                                    }
                                                )
                                            } catch (e: Exception) {
                                                getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
                                                    .putBoolean("running", false)
                                                    .putString("status", "Voice setup failed: ${e.message}").apply()
                                            }
                                        }
                                    },
                                    enabled = !running,
                                    modifier = Modifier.fillMaxWidth().height(50.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NovaMint)
                                ) {
                                    Text("Set up my voice", fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Speaker profile setup: speak naturally for 8 seconds. This profile is stored separately from speech recognition.",
                                    color = NovaMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        if (!running) {
                                            try {
                                                ContextCompat.startForegroundService(
                                                    this@MainActivity,
                                                    Intent(this@MainActivity, WakeWordService::class.java)
                                                )
                                            } catch (e: Exception) {
                                                getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
                                                    .putBoolean("running", false)
                                                    .putString(
                                                        "status",
                                                        "Service start failed: ${e.javaClass.simpleName}: ${e.message}"
                                                    ).apply()
                                            }
                                        } else {
                                            getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
                                                .putBoolean("running", false).putString("status", "Listening stopped.").apply()
                                            stopService(Intent(this@MainActivity, WakeWordService::class.java).apply {
                                                action = WakeWordService.ACTION_STOP
                                            })
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(54.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (running) Color(0xFF3B4663) else NovaPurple,
                                        contentColor = if (running) Color.White else Color(0xFF17132D)
                                    )
                                ) {
                                    Icon(if (running) Icons.Default.GraphicEq else Icons.Default.Mic, null)
                                    Spacer(Modifier.width(9.dp))
                                    Text(
                                        if (running) "Stop listening" else "Start listening",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Speech recognition supports Tamil and English language switching where your phone provider has the models. Tanglish support can vary.",
                                    color = NovaMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = NovaPanel.copy(alpha = 0.98f)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                Text("LOCAL VOICE RECOGNITION", color = NovaMint, fontWeight = FontWeight.Bold)
                                Text("Parakeet Unified EN 0.6B · INT8", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(
                                    asrState.status,
                                    color = NovaMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (asrState.downloading || asrState.progress > 0f) {
                                    LinearProgressIndicator(
                                        progress = asrState.progress,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = NovaMint
                                    )
                                }
                                Button(
                                    onClick = { ParakeetAsrManager.download(this@MainActivity) },
                                    enabled = !asrState.downloading && !asrState.ready,
                                    modifier = Modifier.fillMaxWidth().height(46.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text(
                                        when {
                                            asrState.ready -> "Parakeet voice model ready"
                                            asrState.downloading -> "Downloading voice model…"
                                            ParakeetAsrManager.isInstalled(this@MainActivity) -> "Load Parakeet model"
                                            else -> "Download Parakeet model (about 630 MB)"
                                        },
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text("Recognition engine: $asrEngine", color = NovaMuted, style = MaterialTheme.typography.labelSmall)
                                Text("LIVE TRANSCRIPT", color = NovaMint, fontWeight = FontWeight.Bold)
                                Text(
                                    liveTranscript.ifBlank { if (running) "Speak now — Nova will show what it hears here." else "Start listening to test your voice." },
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Divider(color = Color(0xFF34405C))
                                Text("LAST FINAL TRANSCRIPT", color = NovaMuted, fontWeight = FontWeight.SemiBold)
                                Text(
                                    finalTranscript.ifBlank { "Your completed sentence will appear here." },
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "This Parakeet export uses simulated streaming: interim text is refreshed by decoding audio snapshots, then finalized after a pause.",
                                    color = NovaMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                        Text(
                            "WHAT CAN NOVA DO?",
                            color = NovaMuted,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        FeatureCard(
                            icon = { Icon(Icons.Default.Smartphone, null, tint = NovaMint) },
                            title = "Control your phone",
                            description = "Try: “Open Settings”, “Go home”, “Go back”, “Scroll down”."
                        )
                        FeatureCard(
                            icon = { Icon(Icons.Default.Search, null, tint = NovaMint) },
                            title = "Search the internet",
                            description = "Try: “Search online for Java developer jobs”."
                        )
                        FeatureCard(
                            icon = { Icon(Icons.Default.EditNote, null, tint = NovaMint) },
                            title = "Write or draft content",
                            description = "Try: “Write a birthday message” or “Draft an email”."
                        )

                        Text(
                            "LOCAL AI BRAIN",
                            color = NovaMuted,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Card(
                            colors = CardDefaults.cardColors(containerColor = NovaPanel.copy(alpha = 0.96f)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                Text("Qwen3.5 4B · Q4_K_M", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "About 3.03 GB download. Runs on this phone using its CPU; no AI API key or cloud inference.",
                                    color = NovaMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(localAi.status, color = NovaMint, style = MaterialTheme.typography.bodySmall)
                                if (localAi.downloading || localAi.progress > 0f) {
                                    LinearProgressIndicator(
                                        progress = localAi.progress,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = NovaMint
                                    )
                                }
                                Button(
                                    onClick = { LocalAiManager.downloadAndLoad(this@MainActivity) },
                                    enabled = !localAi.downloading && !localAi.ready,
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(15.dp)
                                ) {
                                    Text(
                                        when {
                                            localAi.ready -> "Local AI ready"
                                            localAi.downloading -> "Downloading / loading…"
                                            LocalAiManager.isDownloaded(this@MainActivity) -> "Load local AI"
                                            else -> "Download & activate local AI"
                                        },
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    "Keep at least 4 GB of free storage. On 8 GB RAM, close other heavy apps before loading the model. First setup needs Wi-Fi; later inference is offline.",
                                    color = NovaMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        AIProviderSettingsCard()

                        Text(
                            "SETUP & HELP",
                            color = NovaMuted,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedButton(
                            onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NovaPurple)
                        ) {
                            Icon(Icons.Default.AccessibilityNew, null)
                            Spacer(Modifier.width(9.dp))
                            Text("Accessibility settings")
                        }
                        OutlinedButton(
                            onClick = {
                                if (!Settings.canDrawOverlays(this@MainActivity)) {
                                    startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:$packageName")
                                        )
                                    )
                                } else {
                                    NovaOverlayService.show(this@MainActivity)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NovaMint)
                        ) {
                            Icon(Icons.Default.GraphicEq, null)
                            Spacer(Modifier.width(9.dp))
                            Text(if (Settings.canDrawOverlays(this@MainActivity)) "Show floating Nova" else "Enable floating Nova")
                        }
                        Text(
                            "Message approvals appear inside Nova's floating card. Nova never uses a separate approval notification.",
                            color = NovaMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.parse("package:$packageName")
                            }) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NovaMuted)
                        ) {
                            Icon(Icons.Default.Settings, null)
                            Spacer(Modifier.width(9.dp))
                            Text("Nova app settings")
                        }
                        Text(
                            "Nova listens continuously after you start it. Long-press Volume Up to start listening from anywhere.",
                            color = NovaMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NovaPanel.copy(alpha = 0.94f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(color = Color(0xFF263451), shape = RoundedCornerShape(13.dp)) {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(Modifier.width(13.dp))
            Column {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(description, color = NovaMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun friendlyStatus(raw: String): String {
    val value = raw.lowercase()
    return when {
        value.startsWith("copy failed:") || value.startsWith("copied") || value.startsWith("pasted") || value.startsWith("i couldn't paste") -> raw
        value.contains("model load failed") -> "Voice model could not load. Check the model setup."
        value.contains("microphone") && value.contains("error") -> "Microphone problem: $raw"
        value.startsWith("heard:") -> raw
        value.contains("listening for tamil") -> "Nova is listening for your next command."
        value.contains("model loaded") -> "Voice model loaded. Starting microphone…"
        value.contains("service started") -> "Starting the offline voice engine…"
        value.contains("stopped unexpectedly") -> "The listening service stopped. Tap Start listening to retry."
        value.contains("stopped") -> "Listening is off."
        value.contains("failed") -> raw
        else -> "Tap Start listening and speak your command directly."
    }
}
