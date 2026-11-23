package com.guna.nova

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Add
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val NovaBackground = Color(0xFF212121)
private val NovaPanel = Color(0xFF2B2B2B)
private val NovaPurple = Color(0xFFA99BFF)
private val NovaMint = Color(0xFF65E5D5)
private val NovaMuted = Color(0xFFAAB4CC)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onResume() {
        super.onResume()
        NovaOverlayService.chatScreenVisible = true
        NovaOverlayService.hide(this)
    }

    override fun onPause() {
        NovaOverlayService.chatScreenVisible = false
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Remove credentials and downloaded AI model data left by earlier Nova builds.
        getSharedPreferences("nova_ai_secure", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("nova_chatgpt", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("nova_brain", MODE_PRIVATE).edit().clear().apply()
        java.io.File(filesDir, "models").deleteRecursively()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
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
            var chatInput by remember { mutableStateOf("") }
            var selectedTab by remember { mutableIntStateOf(0) }
            val chatMessages = remember { mutableStateListOf<NovaChatLine>() }
            var lastAssistantAt by remember { mutableLongStateOf(0L) }
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()

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
                while (true) {
                    val prefs = getSharedPreferences("nova_diagnostics", MODE_PRIVATE)
                    status = prefs.getString("status", "Ready when you are") ?: "Ready when you are"
                    running = prefs.getBoolean("running", false)
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
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = true,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerContainerColor = Color(0xFF111318),
                            modifier = Modifier.width(300.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(color = NovaPurple.copy(alpha = 0.18f), shape = RoundedCornerShape(13.dp)) {
                                    Text("N", color = NovaPurple, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Nova", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("Phone controls", color = NovaMuted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            NavigationDrawerItem(
                                label = { Text("New chat") },
                                selected = false,
                                icon = { Icon(Icons.Default.Add, null) },
                                onClick = {
                                    chatMessages.clear()
                                    chatInput = ""
                                    selectedTab = 0
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(horizontal = 10.dp)
                            )
                            Spacer(Modifier.height(18.dp))
                            Text("WORKSPACE", color = NovaMuted, style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
                            NavigationDrawerItem(
                                label = { Text("Chat") }, selected = selectedTab == 0,
                                icon = { Icon(Icons.Default.Home, null) },
                                onClick = { selectedTab = 0; scope.launch { drawerState.close() } },
                                modifier = Modifier.padding(horizontal = 10.dp)
                            )
                            NavigationDrawerItem(
                                label = { Text("Memory & plans") }, selected = selectedTab == 2,
                                icon = { Icon(Icons.Default.EventNote, null) },
                                onClick = { selectedTab = 2; scope.launch { drawerState.close() } },
                                modifier = Modifier.padding(horizontal = 10.dp)
                            )
                            NavigationDrawerItem(
                                label = { Text("Activity") }, selected = selectedTab == 3,
                                icon = { Icon(Icons.Default.History, null) },
                                onClick = { selectedTab = 3; scope.launch { drawerState.close() } },
                                modifier = Modifier.padding(horizontal = 10.dp)
                            )
                            Spacer(Modifier.weight(1f))
                            NavigationDrawerItem(
                                label = { Text("Settings") }, selected = selectedTab == 5,
                                icon = { Icon(Icons.Default.Settings, null) },
                                onClick = { selectedTab = 5; scope.launch { drawerState.close() } },
                                modifier = Modifier.padding(horizontal = 10.dp)
                            )
                            Text("Nova · Phone utilities", color = NovaMuted, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 22.dp))
                        }
                    }
                ) {
                    Scaffold(
                        containerColor = NovaBackground,
                        topBar = {
                            Row(
                                modifier = Modifier.fillMaxWidth().statusBarsPadding()
                                    .height(58.dp).padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Open navigation", tint = Color.White)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Nova", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        when (selectedTab) {
                                            2 -> "Memory & plans"
                                            3 -> "Recent activity"
                                            5 -> "Preferences and controls"
                                            else -> "Phone controls"
                                        },
                                        color = NovaMuted, style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (selectedTab == 0) {
                                    IconButton(onClick = {
                                        if (running) {
                                            startService(Intent(this@MainActivity, WakeWordService::class.java).apply {
                                                action = WakeWordService.ACTION_STOP
                                            })
                                        } else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                        } else {
                                            try {
                                                NovaOverlayService.chatScreenVisible = false
                                                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, WakeWordService::class.java))
                                            } catch (e: Exception) {
                                                getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
                                                    .putBoolean("running", false)
                                                    .putString("status", "Could not start voice listening: ${e.message}")
                                                    .apply()
                                            }
                                        }
                                    }) {
                                        Icon(
                                            if (running) Icons.Default.Mic else Icons.Default.GraphicEq,
                                            contentDescription = if (running) "Stop voice listening" else "Start voice listening",
                                            tint = NovaMint
                                        )
                                    }
                                    IconButton(onClick = { chatMessages.clear(); chatInput = "" }) {
                                        Icon(Icons.Default.Add, contentDescription = "New chat", tint = Color.White)
                                    }
                                } else {
                                    IconButton(onClick = { selectedTab = 0 }) {
                                        Icon(Icons.Default.Home, contentDescription = "Back to chat", tint = Color.White)
                                    }
                                }
                            }
                        }
                    ) { padding ->
                        Box(
                            Modifier.fillMaxSize().padding(padding)
                                .background(Color(0xFF212121))
                        ) {
                            when (selectedTab) {
                                0 -> NovaChatPanel(
                                    messages = chatMessages,
                                    input = chatInput,
                                    onInputChange = { chatInput = it },
                                    onSend = { sendChatMessage() },
                                    onVoice = { startVoiceChat() },
                                    onSuggestion = { prompt ->
                                        chatMessages.add(NovaChatLine("YOU", prompt))
                                        NovaCommand.execute(this@MainActivity, prompt)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                                2 -> Column(
                                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    MemoryLibraryScreen(
                                        NovaWorkspaceStore.getReminders(this@MainActivity),
                                        NovaWorkspaceStore.getInstructions(this@MainActivity),
                                        NovaWorkspaceStore.getPaymentNotices(this@MainActivity)
                                    )
                                }
                                3 -> Column(
                                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    ActivityLibraryScreen(NovaWorkspaceStore.getInstructions(this@MainActivity), status)
                                }
                                else -> Column(
                                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text("Settings", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text("Manage Nova's phone permissions and controls.", color = NovaMuted)
                                    GroqSettingsCard()
                                    Text("VOICE ASSISTANT", color = NovaMint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                    Card(colors = CardDefaults.cardColors(containerColor = NovaPanel), shape = RoundedCornerShape(18.dp)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                            Text(if (running) "Voice listening is active" else "Voice listening is off", color = Color.White, fontWeight = FontWeight.SemiBold)
                                            Text(friendlyStatus(status), color = NovaMuted, style = MaterialTheme.typography.bodySmall)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(
                                                    onClick = {
                                                        if (!running) {
                                                            try {
                                                                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, WakeWordService::class.java).apply {
                                                                    action = WakeWordService.ACTION_ENROLL
                                                                })
                                                            } catch (_: Exception) {
                                                                getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
                                                                    .putBoolean("running", false)
                                                                    .putString("status", "Voice setup couldn't start. Check microphone permission.")
                                                                    .apply()
                                                            }
                                                        }
                                                    },
                                                    enabled = !running,
                                                    modifier = Modifier.weight(1f)
                                                ) { Text("Set up voice") }
                                                Button(
                                                    onClick = {
                                                        if (!running) {
                                                            try {
                                                                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, WakeWordService::class.java))
                                                            } catch (_: Exception) {
                                                                getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
                                                                    .putBoolean("running", false)
                                                                    .putString("status", "Voice service couldn't start. Check microphone permission.")
                                                                    .apply()
                                                            }
                                                        } else {
                                                            getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
                                                                .putBoolean("running", false).putString("status", "Listening stopped.").apply()
                                                            stopService(Intent(this@MainActivity, WakeWordService::class.java).apply {
                                                                action = WakeWordService.ACTION_STOP
                                                            })
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1f)
                                                ) { Text(if (running) "Stop listening" else "Start listening") }
                                            }
                                        }
                                    }
                                    Text("DEVICE PERMISSIONS", color = NovaMint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                    OutlinedButton(
                                        onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.AccessibilityNew, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Accessibility settings")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.parse("package:$packageName")
                                            })
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Settings, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Nova app settings")
                                    }
                                    Text("Financial actions such as payments, transfers, purchases, subscriptions and paid orders remain permanently blocked.", color = NovaMuted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
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
        value.contains("http 429") || value.contains("http 503") || value.contains("too many requests") ->
            "Online research is temporarily busy. Please try again shortly."
        Regex("http\\s+\\d{3}").containsMatchIn(value) ->
            "Nova could not complete that online request. Please try again in a moment."
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
            Card(colors = CardDefaults.cardColors(containerColor = NovaPanel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Activity", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = NovaPanel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("CURRENT STATUS", color = NovaMint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text(friendlyStatus(status), color = Color.White)
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
    Card(colors = CardDefaults.cardColors(containerColor = NovaPanel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
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
