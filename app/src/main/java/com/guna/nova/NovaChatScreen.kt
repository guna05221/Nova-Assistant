package com.guna.nova

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val ChatBg = Color(0xFF212121)
private val ChatSurface = Color(0xFF2F2F2F)
private val ChatRaised = Color(0xFF3A3A3A)
private val ChatText = Color(0xFFECECEC)
private val ChatMuted = Color(0xFFB4B4B4)
private val ChatAccent = Color(0xFFB8A7FF)

private data class NovaChatMessage(val role: String, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovaChatScreen(
    status: String,
    listening: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var messages by remember { mutableStateOf(loadChat(context)) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun sendMessage(raw: String) {
        val prompt = raw.trim()
        if (prompt.isBlank() || busy) return
        val next = messages + NovaChatMessage("user", prompt)
        messages = next
        saveChat(context, next)
        input = ""
        keyboard?.hide()
        busy = true
        scope.launch {
            val history = JSONArray().apply {
                next.takeLast(18).forEach { item ->
                    put(JSONObject().put("role", item.role).put("content", item.text))
                }
            }
            val cloudConfigured = AIProviderManager.hasGemini(context) || AIProviderManager.hasGroq(context)
            val answer = withContext(Dispatchers.IO) {
                if (cloudConfigured) {
                    val cloud = AIProviderManager.chat(context, history)
                    if (cloud.ok && cloud.message.isNotBlank()) cloud.message
                    else if (LocalAiManager.isDownloaded(context)) {
                        LocalAiManager.chat(context, historyAsText(next))
                            ?: "I couldn't complete that response. Please try again."
                    } else {
                        when {
                            cloud.message.contains("HTTP", true) -> "I'm having trouble connecting right now. Please check your internet connection or try again."
                            cloud.message.contains("key is configured", true) -> "Connect an AI provider in Settings to start chatting."
                            else -> "I couldn't reach the AI service just now. Please try again in a moment."
                        }
                    }
                } else if (LocalAiManager.isDownloaded(context)) {
                    LocalAiManager.chat(context, historyAsText(next))
                        ?: "Your local AI model couldn't respond. You can check it in Settings."
                } else {
                    "I'm ready to chat. Connect Gemini or Groq in Settings, or download the local AI model for offline conversations."
                }
            }
            val updated = messages + NovaChatMessage("assistant", answer)
            messages = updated
            saveChat(context, updated)
            busy = false
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) sendMessage(spoken)
    }

    ModalNavigationDrawer(
        drawerState = rememberDrawerState(if (drawerOpen) DrawerValue.Open else DrawerValue.Closed),
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF171717),
                modifier = Modifier.width(300.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(color = ChatAccent.copy(alpha = .18f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.SmartToy, null, tint = ChatAccent, modifier = Modifier.padding(9.dp).size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Nova", color = ChatText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                NavigationDrawerItem(
                    label = { Text("New chat") },
                    selected = false,
                    onClick = {
                        messages = emptyList()
                        saveChat(context, emptyList())
                        drawerOpen = false
                    },
                    icon = { Icon(Icons.Default.Add, null) },
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                Spacer(Modifier.height(18.dp))
                Text("RECENT", color = ChatMuted, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                if (messages.isNotEmpty()) {
                    NavigationDrawerItem(
                        label = { Text(messages.firstOrNull { it.role == "user" }?.text?.take(34) ?: "Current conversation", maxLines = 1) },
                        selected = true,
                        onClick = { drawerOpen = false },
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                } else {
                    Text("Your conversations will appear here.", color = ChatMuted,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
                Spacer(Modifier.weight(1f))
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = false,
                    onClick = { drawerOpen = false; settingsOpen = true },
                    icon = { Icon(Icons.Default.Settings, null) },
                    modifier = Modifier.padding(10.dp)
                )
                Text("Nova · Hybrid AI assistant", color = ChatMuted, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 24.dp, bottom = 22.dp))
            }
        }
    ) {
        Scaffold(
            containerColor = ChatBg,
            topBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().height(58.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { drawerOpen = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "Open menu", tint = ChatText)
                    }
                    Text("Nova", color = ChatText, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    Surface(color = ChatSurface, shape = RoundedCornerShape(20.dp)) {
                        Text("AI", color = ChatMuted, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = ChatMuted)
                    }
                }
            },
            bottomBar = {
                Column(
                    Modifier.fillMaxWidth().background(ChatBg).navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Surface(
                        color = ChatSurface,
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .05f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            IconButton(onClick = { notice = "File and image attachments will be enabled in a future update." }) {
                                Icon(Icons.Default.Add, contentDescription = "Add attachment", tint = ChatMuted)
                            }
                            BasicTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 12.dp),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ChatText),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(ChatAccent),
                                decorationBox = { inner ->
                                    Box {
                                        if (input.isBlank()) Text("Message Nova", color = ChatMuted)
                                        inner()
                                    }
                                },
                                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { sendMessage(input) })
                            )
                            IconButton(onClick = {
                                try {
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Nova")
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                                    }
                                    speechLauncher.launch(intent)
                                } catch (_: ActivityNotFoundException) {
                                    notice = "Speech recognition isn't available on this device."
                                }
                            }) {
                                Icon(Icons.Default.Mic, contentDescription = "Voice input", tint = ChatMuted)
                            }
                            Surface(
                                color = if (input.isNotBlank() && !busy) ChatText else ChatRaised,
                                shape = CircleShape,
                                modifier = Modifier.size(38.dp).clickable(enabled = input.isNotBlank() && !busy) { sendMessage(input) }
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Send",
                                    tint = if (input.isNotBlank() && !busy) ChatBg else ChatMuted,
                                    modifier = Modifier.padding(9.dp))
                            }
                        }
                    }
                    Text("Nova can make mistakes. Check important information.",
                        color = ChatMuted, style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp))
                }
            }
        ) { padding ->
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(color = ChatAccent.copy(alpha = .13f), shape = RoundedCornerShape(22.dp)) {
                        Icon(Icons.Default.SmartToy, null, tint = ChatAccent, modifier = Modifier.padding(17.dp).size(34.dp))
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("What can I help with?", color = ChatText,
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(26.dp))
                    listOf(
                        "Explain a concept" to "Explain Java interfaces with an example",
                        "Help me write" to "Help me write a professional email",
                        "Plan something" to "Help me plan my day"
                    ).forEach { (title, prompt) ->
                        Surface(
                            color = ChatSurface,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                                .clickable { sendMessage(prompt) }
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                                Text(title, color = ChatText, fontWeight = FontWeight.Medium)
                                Text(prompt, color = ChatMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(if (listening) "Voice assistant is active" else "Hybrid AI · Tamil, English and Tanglish",
                        color = if (listening) ChatAccent else ChatMuted, style = MaterialTheme.typography.labelSmall)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    itemsIndexed(messages) { _, message ->
                        if (message.role == "user") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Surface(color = ChatRaised, shape = RoundedCornerShape(22.dp),
                                    modifier = Modifier.widthIn(max = 320.dp)) {
                                    Text(message.text, color = ChatText, style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                                }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Surface(color = ChatAccent.copy(alpha = .16f), shape = CircleShape) {
                                    Icon(Icons.Default.SmartToy, null, tint = ChatAccent,
                                        modifier = Modifier.padding(7.dp).size(18.dp))
                                }
                                Spacer(Modifier.width(11.dp))
                                Text(message.text, color = ChatText, style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f).padding(top = 4.dp))
                            }
                        }
                    }
                    if (busy) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(17.dp), color = ChatAccent, strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Thinking…", color = ChatMuted, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        ModalBottomSheet(
            onDismissRequest = { settingsOpen = false },
            containerColor = Color(0xFF191919),
            contentColor = ChatText
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Nova settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("AI providers", color = ChatMuted, style = MaterialTheme.typography.labelLarge)
                AIProviderSettingsCard()
                Text("On-device AI", color = ChatMuted, style = MaterialTheme.typography.labelLarge)
                Text(LocalAiManager.state.collectAsState().value.status, color = ChatMuted,
                    style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { LocalAiManager.downloadAndLoad(context) },
                    enabled = !LocalAiManager.state.collectAsState().value.downloading && !LocalAiManager.state.collectAsState().value.ready,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(when {
                        LocalAiManager.state.collectAsState().value.ready -> "Local AI ready"
                        LocalAiManager.state.collectAsState().value.downloading -> "Downloading / loading…"
                        LocalAiManager.isDownloaded(context) -> "Load local AI"
                        else -> "Download & activate local AI"
                    })
                }
                Text("Voice assistant", color = ChatMuted, style = MaterialTheme.typography.labelLarge)
                Text(status, color = ChatMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { if (listening) onStopListening() else onStartListening() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(if (listening) Icons.Default.Close else Icons.Default.GraphicEq, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (listening) "Stop listening" else "Start listening")
                }
            }
        }
    }

    notice?.let { message ->
        AlertDialog(
            onDismissRequest = { notice = null },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } },
            title = { Text("Nova") },
            text = { Text(message) },
            containerColor = ChatSurface,
            titleContentColor = ChatText,
            textContentColor = ChatMuted
        )
    }
}

private fun historyAsText(messages: List<NovaChatMessage>): String =
    messages.takeLast(18).joinToString("\n") { "${it.role}: ${it.text}" } + "\nassistant:"

private fun loadChat(context: Context): List<NovaChatMessage> {
    val raw = context.getSharedPreferences("nova_chat_ui", Context.MODE_PRIVATE)
        .getString("current_messages", null) ?: return emptyList()
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val role = item.optString("role")
            val text = item.optString("text")
            if ((role == "user" || role == "assistant") && text.isNotBlank()) NovaChatMessage(role, text) else null
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveChat(context: Context, messages: List<NovaChatMessage>) {
    val array = JSONArray()
    messages.takeLast(60).forEach { item ->
        array.put(JSONObject().put("role", item.role).put("text", item.text))
    }
    context.getSharedPreferences("nova_chat_ui", Context.MODE_PRIVATE).edit()
        .putString("current_messages", array.toString()).apply()
}
