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
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
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

private enum class NovaTab(val title: String) {
    VOICE("Voice"), AI("AI Brain"), SETTINGS("Settings")
}

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
            var selectedTab by remember { mutableStateOf(NovaTab.VOICE) }
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
                    primary = NovaPurple, secondary = NovaMint,
                    background = NovaBackground, surface = NovaPanel, onSurface = Color.White
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = NovaBackground) {
                    Column(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color(0xFF171D39), NovaBackground, Color(0xFF090D18)))
                        ).statusBarsPadding()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("NOVA", style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Simple testing interface", color = NovaMuted)
                            }
                            Surface(color = Color(0xFF202A46), shape = CircleShape) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).background(
                                        if (running) NovaMint else Color(0xFF8791A8), CircleShape
                                    ))
                                    Spacer(Modifier.width(7.dp))
                                    Text(if (running) "ACTIVE" else "IDLE", color = Color.White,
                                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (selectedTab) {
                                NovaTab.VOICE -> VoiceTestingPage(
                                    running = running,
                                    status = status,
                                    asrStatus = asrState.status,
                                    asrProgress = asrState.progress,
                                    asrDownloading = asrState.downloading,
                                    asrReady = asrState.ready,
                                    asrInstalled = ParakeetAsrManager.isInstalled(this@MainActivity),
                                    asrEngine = asrEngine,
                                    liveTranscript = liveTranscript,
                                    finalTranscript = finalTranscript,
                                    onVoiceSetup = { startVoiceSetup() },
                                    onToggleListening = { toggleListening(running) },
                                    onDownloadAsr = { ParakeetAsrManager.download(this@MainActivity) }
                                )
                                NovaTab.AI -> AiTestingPage(localAi = localAi)
                                NovaTab.SETTINGS -> SettingsTestingPage(
                                    onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                    onOverlay = {
                                        if (!Settings.canDrawOverlays(this@MainActivity)) {
                                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                android.net.Uri.parse("package:$packageName")))
                                        } else NovaOverlayService.show(this@MainActivity)
                                    },
                                    onAppSettings = {
                                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = android.net.Uri.parse("package:$packageName")
                                        })
                                    }
                                )
                            }
                        }

                        NavigationBar(containerColor = NovaPanel, tonalElevation = 0.dp) {
                            NovaTab.values().forEach { tab ->
                                NavigationBarItem(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    icon = {
                                        Icon(when (tab) {
                                            NovaTab.VOICE -> Icons.Default.Mic
                                            NovaTab.AI -> Icons.Default.Psychology
                                            NovaTab.SETTINGS -> Icons.Default.Settings
                                        }, contentDescription = tab.title)
                                    },
                                    label = { Text(tab.title) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = NovaMint,
                                        selectedTextColor = NovaMint,
                                        indicatorColor = Color(0xFF30365A),
                                        unselectedIconColor = NovaMuted,
                                        unselectedTextColor = NovaMuted
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startVoiceSetup() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_ENROLL
            })
        } catch (e: Exception) {
            getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
                .putBoolean("running", false)
                .putString("status", "Voice setup failed: ${e.message}").apply()
        }
    }

    private fun toggleListening(running: Boolean) {
        if (!running) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
            } catch (e: Exception) {
                getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
                    .putBoolean("running", false)
                    .putString("status", "Service start failed: ${e.javaClass.simpleName}: ${e.message}").apply()
            }
        } else {
            getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
                .putBoolean("running", false).putString("status", "Listening stopped.").apply()
            stopService(Intent(this, WakeWordService::class.java).apply { action = WakeWordService.ACTION_STOP })
        }
    }
}

@Composable
private fun VoiceTestingPage(
    running: Boolean, status: String, asrStatus: String, asrProgress: Float,
    asrDownloading: Boolean, asrReady: Boolean, asrInstalled: Boolean,
    asrEngine: String, liveTranscript: String, finalTranscript: String,
    onVoiceSetup: () -> Unit, onToggleListening: () -> Unit, onDownloadAsr: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = NovaPanel),
            shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (running) Icons.Default.GraphicEq else Icons.Default.Mic, null,
                    tint = NovaMint, modifier = Modifier.size(52.dp))
                Text(if (running) "Nova is listening" else "Ready to help",
                    style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                Text(friendlyStatus(status), color = NovaMuted)
                OutlinedButton(onClick = onVoiceSetup, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                    Text("Set up my voice")
                }
                Button(onClick = onToggleListening, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(if (running) Icons.Default.GraphicEq else Icons.Default.Mic, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) "Stop listening" else "Start listening")
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = NovaPanel),
            shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("LOCAL VOICE RECOGNITION", color = NovaMint, fontWeight = FontWeight.Bold)
                Text("Parakeet Unified EN 0.6B · INT8", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(asrStatus, color = NovaMuted, style = MaterialTheme.typography.bodySmall)
                if (asrDownloading || asrProgress > 0f) {
                    LinearProgressIndicator(progress = asrProgress, modifier = Modifier.fillMaxWidth())
                }
                Button(onClick = onDownloadAsr, enabled = !asrDownloading && !asrReady,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(when {
                        asrReady -> "Parakeet ready"
                        asrDownloading -> "Downloading model…"
                        asrInstalled -> "Load Parakeet model"
                        else -> "Download Parakeet model"
                    })
                }
                Text("Engine: $asrEngine", color = NovaMuted, style = MaterialTheme.typography.labelSmall)
                Text("LIVE TRANSCRIPT", color = NovaMint, fontWeight = FontWeight.Bold)
                Text(liveTranscript.ifBlank { "Your live speech will appear here." }, color = Color.White)
                Divider(color = Color(0xFF34405C))
                Text("FINAL TRANSCRIPT", color = NovaMint, fontWeight = FontWeight.Bold)
                Text(finalTranscript.ifBlank { "Your completed sentence will appear here." }, color = Color.White)
            }
        }
        Text("Testing focus: English speech recognition, continuous listening and response behavior.",
            color = NovaMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AiTestingPage(localAi: LocalAiManager.State) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = NovaPanel),
            shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("LOCAL AI BRAIN", color = NovaMint, fontWeight = FontWeight.Bold)
                Text("Qwen3.5 4B · Q4_K_M", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("About 3.03 GB download. Runs locally on this phone.", color = NovaMuted)
                Text(localAi.status, color = NovaMint, style = MaterialTheme.typography.bodySmall)
                if (localAi.downloading || localAi.progress > 0f) {
                    LinearProgressIndicator(progress = localAi.progress, modifier = Modifier.fillMaxWidth())
                }
                Button(onClick = { LocalAiManager.downloadAndLoad(androidx.compose.ui.platform.LocalContext.current) },
                    enabled = !localAi.downloading && !localAi.ready, modifier = Modifier.fillMaxWidth()) {
                    Text(when {
                        localAi.ready -> "Local AI ready"
                        localAi.downloading -> "Downloading / loading…"
                        LocalAiManager.isDownloaded(androidx.compose.ui.platform.LocalContext.current) -> "Load local AI"
                        else -> "Download & activate local AI"
                    })
                }
                Text("Keep at least 4 GB free. On 8 GB RAM, close other heavy apps before loading.",
                    color = NovaMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        AIProviderSettingsCard()
    }
}

@Composable
private fun SettingsTestingPage(
    onAccessibility: () -> Unit, onOverlay: () -> Unit, onAppSettings: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SETTINGS", color = NovaMint, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = onAccessibility, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.AccessibilityNew, null)
            Spacer(Modifier.width(8.dp))
            Text("Accessibility settings")
        }
        OutlinedButton(onClick = onOverlay, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.GraphicEq, null)
            Spacer(Modifier.width(8.dp))
            Text("Floating Nova permission / show")
        }
        OutlinedButton(onClick = onAppSettings, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Settings, null)
            Spacer(Modifier.width(8.dp))
            Text("Nova app settings")
        }
        Text("Temporary testing layout. The full visual redesign will come after voice and AI testing.",
            color = NovaMuted, style = MaterialTheme.typography.bodySmall)
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
