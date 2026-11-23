package com.guna.nova

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.History
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            var researchReport by remember { mutableStateOf(NovaResearchStore.latest(this@MainActivity)) }
            val localAi by LocalAiManager.state.collectAsState()
            var chatInput by remember { mutableStateOf("") }
            var selectedTab by remember { mutableIntStateOf(0) }
            val chatMessages = remember { mutableStateListOf<NovaChatLine>() }
            var lastAssistantAt by remember { mutableLongStateOf(0L) }
            val voiceInput = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
                    if (spoken.isNotBlank()) {
                        chatMessages.add(NovaChatLine("YOU", spoken))
                        NovaCommand.execute(this@MainActivity, spoken)
                    }
                }
            }

            fun sendChatMessage() {
                val message = chatInput.trim()
                if (message.isNotBlank()) {
                    chatMessages.add(NovaChatLine("YOU", message))
                    chatInput = ""
                    NovaCommand.execute(this@MainActivity, message)
                }
            }

            fun startVoiceChat() {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Nova")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
                try { voiceInput.launch(intent) } catch (_: Exception) {
                    getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
                        .putString("status", "Voice input is unavailable on this device.").apply()
                }
            }

            LaunchedEffect(Unit) {
                LocalAiManager.loadIfPresent(this@MainActivity)
                while (true) {
                    val prefs = getSharedPreferences("nova_diagnostics", MODE_PRIVATE)
                    status = prefs.getString("status", "Ready when you are") ?: "Ready when you are"
                    running = prefs.getBoolean("running", false)
                    researchReport = NovaResearchStore.latest(this@MainActivity)
                    val assistantAt = prefs.getLong("last_assistant_at", 0L)
                    if (assistantAt > lastAssistantAt) {
                        val assistantText = prefs.getString("last_assistant_message", "").orEmpty()
                        if (assistantText.isNotBlank()) chatMessages.add(NovaChatLine("NOVA", assistantText))
                        lastAssistantAt = assistantAt
                    }
                    delay(700)
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
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Compact app bar stays visible while the content below scrolls.
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("NOVA", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Your personal assistant", color = NovaMuted, style = MaterialTheme.typography.labelSmall)
                            }
                            Surface(color = Color(0xFF202A46), shape = CircleShape) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(7.dp).background(if (running) NovaMint else Color(0xFF8791A8), CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (running) "ACTIVE" else "READY", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = {
                                if (!Settings.canDrawOverlays(this@MainActivity)) {
                                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                                } else {
                                    startService(Intent(this@MainActivity, NovaOverlayService::class.java).apply { action = NovaOverlayService.ACTION_SHOW })
                                }
                            }) {
                                Icon(Icons.Default.GraphicEq, contentDescription = "Open floating chat", tint = NovaMint)
                            }
                        }

                        // Only the middle content scrolls. Header and chat composer remain in place.
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (selectedTab == 0) {
                            Text("How can I help?", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text("Ask naturally. Nova can chat, research and help with tasks.", color = NovaMuted, style = MaterialTheme.typography.bodySmall)

                            researchReport?.let { report ->
                                Card(colors = CardDefaults.cardColors(containerColor = NovaPanel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("LATEST RESEARCH", color = NovaMint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                        Text(report.query, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                        Text(report.text, color = Color(0xFFE0E5F2), style = MaterialTheme.typography.bodyMedium)
                                        report.sources.forEachIndexed { index, source ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(source.title, color = NovaMuted, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                                TextButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url))) } }) { Text("Open", color = NovaMint) }
                                            }
                                        }
                                    }
                                }
                            }

                            Text("VOICE", color = NovaMuted, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            Card(colors = CardDefaults.cardColors(containerColor = NovaPanel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (running) "Voice listening is active" else "Voice listening is off", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text(friendlyStatus(status), color = NovaMuted, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                if (!running) {
                                                    try {
                                                        ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, WakeWordService::class.java).apply { action = WakeWordService.ACTION_ENROLL })
                                                    } catch (e: Exception) {
                                                        getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit().putBoolean("running", false).putString("status", "Voice setup failed: ${e.message}").apply()
                                                    }
                                                }
                                            },
                                            enabled = !running,
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(13.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NovaMint)
                                        ) { Text("Set up voice") }
                                        Button(
                                            onClick = {
                                                if (!running) {
                                                    try { ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, WakeWordService::class.java)) }
                                                    catch (e: Exception) { getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit().putBoolean("running", false).putString("status", "Service start failed: ${e.message}").apply() }
                                                } else {
                                                    getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit().putBoolean("running", false).putString("status", "Listening stopped.").apply()
                                                    stopService(Intent(this@MainActivity, WakeWordService::class.java).apply { action = WakeWordService.ACTION_STOP })
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(13.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = if (running) Color(0xFF3B4663) else NovaPurple, contentColor = if (running) Color.White else Color(0xFF17132D))
                                        ) { Text(if (running) "Stop listening" else "Start listening") }
                                    }
                                }
                            }

                            Text("EXPLORE NOVA", color = NovaMuted, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            FeatureCard(
                                icon = { Icon(Icons.Default.Smartphone, null, tint = NovaMint) },
                                title = "Control your phone",
                                description = "Open apps, navigate screens and perform supported actions."
                            )
                            FeatureCard(
                                icon = { Icon(Icons.Default.Search, null, tint = NovaMint) },
                                title = "Research online",
                                description = "Compare options, examine sources and prepare recommendations."
                            )
                            FeatureCard(
                                icon = { Icon(Icons.Default.EditNote, null, tint = NovaMint) },
                                title = "Write and plan",
                                description = "Draft messages, organise ideas and plan your next steps."
                            )

                            Text("LOCAL AI", color = NovaMuted, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            Card(colors = CardDefaults.cardColors(containerColor = NovaPanel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Qwen3.5 4B · Q4_K_M", color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Text("Runs on this phone after downloading the model.", color = NovaMuted, style = MaterialTheme.typography.bodySmall)
                                    Text(localAi.status, color = NovaMint, style = MaterialTheme.typography.bodySmall)
                                    if (localAi.downloading || localAi.progress > 0f) LinearProgressIndicator(progress = localAi.progress, modifier = Modifier.fillMaxWidth(), color = NovaMint)
                                    Button(onClick = { LocalAiManager.downloadAndLoad(this@MainActivity) }, enabled = !localAi.downloading && !localAi.ready, modifier = Modifier.fillMaxWidth()) {
                                        Text(when { localAi.ready -> "Local AI ready"; localAi.downloading -> "Downloading / loading…"; LocalAiManager.isDownloaded(this@MainActivity) -> "Load local AI"; else -> "Download & activate local AI" })
                                    }
                                }
                            }
                            AIProviderSettingsCard()
                            Text("ADVANCED SETTINGS", color = NovaMuted, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = NovaPurple)) {
                                Icon(Icons.Default.AccessibilityNew, null); Spacer(Modifier.width(8.dp)); Text("Accessibility settings")
                            }
                            OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") }) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = NovaMuted)) {
                                Icon(Icons.Default.Settings, null); Spacer(Modifier.width(8.dp)); Text("Nova app settings")
                            }
                            Spacer(Modifier.height(8.dp))
                            } else if (selectedTab == 1) {
                                ResearchLibraryScreen(researchReport) { url ->
                                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                }
                            } else if (selectedTab == 2) {
                                MemoryLibraryScreen(
                                    NovaWorkspaceStore.getReminders(this@MainActivity),
                                    NovaWorkspaceStore.getInstructions(this@MainActivity),
                                    NovaWorkspaceStore.getPaymentNotices(this@MainActivity)
                                )
                            } else {
                                ActivityLibraryScreen(NovaWorkspaceStore.getInstructions(this@MainActivity), status)
                            }
                        }

                        // The composer is pinned to the bottom; only the conversation history scrolls inside it.
                        NovaChatPanel(
                            messages = chatMessages,
                            input = chatInput,
                            onInputChange = { chatInput = it },
                            onSend = { sendChatMessage() },
                            onVoice = { startVoiceChat() }
                        )
                        NavigationBar(containerColor = Color(0xFF111A30), contentColor = Color.White) {
                            NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                            NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.Search, null) }, label = { Text("Research") })
                            NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.EventNote, null) }, label = { Text("Memory") })
                            NavigationBarItem(selected = selectedTab == 3, onClick = { selectedTab = 3 }, icon = { Icon(Icons.Default.History, null) }, label = { Text("Activity") })
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

@Composable
private fun ResearchLibraryScreen(report: NovaResearchStore.Report?, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Research", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Recommendations and evidence Nova has gathered.", color = NovaMuted)
        if (report == null) EmptyLibraryCard("No research reports yet", "Ask Nova to research a product, course, restaurant or anything you want to compare.")
        else Card(colors = CardDefaults.cardColors(containerColor = NovaPanel), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("LATEST REPORT", color = NovaMint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(report.query, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Saved " + SimpleDateFormat("dd MMM yyyy · hh:mm a", Locale.getDefault()).format(Date(report.savedAt)), color = NovaMuted, style = MaterialTheme.typography.labelSmall)
                HorizontalDivider(color = Color(0xFF34415E))
                Text(report.text, color = Color(0xFFE7EAF3), style = MaterialTheme.typography.bodyMedium)
                if (report.sources.isNotEmpty()) {
                    Text("SOURCES", color = NovaMint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    report.sources.forEach { source ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF222D49)), shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(source.title, color = Color.White, modifier = Modifier.weight(1f), maxLines = 3)
                                TextButton(onClick = { onOpen(source.url) }) { Text("Open", color = NovaMint) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryLibraryScreen(
    reminders: List<NovaWorkspaceStore.Reminder>,
    instructions: List<NovaWorkspaceStore.Instruction>,
    payments: List<NovaWorkspaceStore.PaymentNotice>
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Memory & plans", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Important events, reminders and commitments saved by Nova.", color = NovaMuted)
        Text("SAVED EVENTS", color = NovaMint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        if (reminders.isEmpty()) EmptyLibraryCard("No saved events yet", "Confirmed dates, destinations and reminders will appear here when saved.")
        reminders.forEach { event ->
            Card(colors = CardDefaults.cardColors(containerColor = NovaPanel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("SAVED EVENT", color = NovaMint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(event.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    MemoryDetailRow("Date", SimpleDateFormat("dd MMMM yyyy · hh:mm a", Locale.getDefault()).format(Date(event.eventAt)))
                    if (event.location.isNotBlank()) MemoryDetailRow("Destination", event.location)
                    MemoryDetailRow("Source", event.source)
                    MemoryDetailRow("Status", event.status.name.lowercase().replaceFirstChar { it.uppercase() })
                    if (event.details.isNotBlank()) Text(event.details, color = NovaMuted, style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider(color = Color(0xFF34415E))
                    Text("REMINDER", color = NovaMint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("Event saved. Reminder delivery depends on the reminder scheduler being enabled.", color = NovaMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("ONGOING PLANS", color = NovaMint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        val active = instructions.filter { it.status != NovaWorkspaceStore.InstructionStatus.COMPLETED }
        if (active.isEmpty()) EmptyLibraryCard("No active plans", "Tasks Nova is working on will appear here.")
        active.take(8).forEach { item ->
            Card(colors = CardDefaults.cardColors(containerColor = NovaPanel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(item.status.name, color = NovaMint, style = MaterialTheme.typography.labelSmall)
                    if (item.progress.isNotEmpty()) Text(item.progress.last(), color = NovaMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("BILLS & SUBSCRIPTIONS", color = NovaMint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        if (payments.isEmpty()) EmptyLibraryCard("Nothing tracked yet", "Saved bill and subscription notices will appear here.")
        payments.take(6).forEach { notice ->
            Card(colors = CardDefaults.cardColors(containerColor = NovaPanel), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(notice.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(notice.provider + " · " + notice.status.name, color = NovaMuted, style = MaterialTheme.typography.bodySmall)
                    if (notice.dueAt != null) Text("Due " + SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(notice.dueAt)), color = NovaMint, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ActivityLibraryScreen(instructions: List<NovaWorkspaceStore.Instruction>, status: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Activity", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = NovaPanel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("CURRENT STATUS", color = NovaMint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text(status, color = Color.White)
            }
        }
        val ordered = instructions.sortedByDescending { it.updatedAt }
        if (ordered.isEmpty()) EmptyLibraryCard("No activity recorded yet", "Task progress and results will appear here.")
        ordered.forEach { item ->
            Card(colors = CardDefaults.cardColors(containerColor = NovaPanel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(item.status.name, color = NovaMint, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(item.originalRequest, color = NovaMuted, style = MaterialTheme.typography.bodySmall)
                    item.progress.takeLast(4).forEach { Text("• " + it, color = Color(0xFFE0E5F2), style = MaterialTheme.typography.bodySmall) }
                    if (item.result.isNotBlank()) Text(item.result, color = NovaMint, style = MaterialTheme.typography.bodySmall)
                    Text(SimpleDateFormat("dd MMM yyyy · hh:mm a", Locale.getDefault()).format(Date(item.updatedAt)), color = NovaMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryCard(title: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = NovaPanel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(detail, color = NovaMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MemoryDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = NovaMuted, modifier = Modifier.weight(0.8f))
        Text(value, color = Color.White, modifier = Modifier.weight(1.2f), fontWeight = FontWeight.SemiBold)
    }
}
