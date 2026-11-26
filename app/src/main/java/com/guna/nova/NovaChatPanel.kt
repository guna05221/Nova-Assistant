package com.guna.nova

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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val ChatPanelBg = Color(0xFF212121)
private val ChatPanelSurface = Color(0xFF303030)
private val ChatPanelRaised = Color(0xFF3A3A3A)
private val ChatPanelText = Color(0xFFECECEC)
private val ChatPanelMuted = Color(0xFFB4B4B4)
private val ChatPanelAccent = Color(0xFFB8A7FF)

data class NovaChatLine(val speaker: String, val text: String, val id: Long = System.nanoTime())

@Composable
fun NovaChatPanel(
    messages: List<NovaChatLine>,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    isThinking: Boolean = false,
    onStopSpeaking: () -> Unit = {},
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    Column(modifier = modifier.fillMaxSize().background(ChatPanelBg).imePadding()) {
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(color = ChatPanelAccent.copy(alpha = .15f), shape = RoundedCornerShape(22.dp)) {
                        Icon(Icons.Default.Smartphone, null, tint = ChatPanelAccent,
                            modifier = Modifier.padding(17.dp).size(34.dp))
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("Your AI assistant", color = ChatPanelText,
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("Ask anything, continue a conversation, or tell Nova what to do on your phone.",
                        color = ChatPanelMuted, style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    listOf(
                        "Explain a concept" to "Explain Java OOP with a simple example",
                        "Continue a conversation" to "Give me a practical example",
                        "Control my phone" to "Open YouTube"
                    ).forEach { (title, prompt) ->
                        Surface(
                            color = ChatPanelSurface, shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = .04f)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onSuggestion(prompt) }
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                                Text(title, color = ChatPanelText, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(3.dp))
                                Text(prompt, color = ChatPanelMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                itemsIndexed(messages, key = { _, item -> item.id }) { _, item ->
                    if (item.speaker == "YOU") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(
                                color = ChatPanelRaised,
                                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 7.dp),
                                modifier = Modifier.widthIn(max = 320.dp)
                            ) {
                                Text(item.text, color = ChatPanelText, style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Surface(color = ChatPanelAccent.copy(alpha = .15f), shape = CircleShape) {
                                Icon(Icons.Default.Smartphone, "Phone command", tint = ChatPanelAccent,
                                    modifier = Modifier.padding(7.dp).size(18.dp))
                            }
                            Spacer(Modifier.width(11.dp))
                            Text(item.text, color = ChatPanelText, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f).padding(top = 3.dp))
                        }
                    }
                }
                if (isThinking) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 38.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = ChatPanelAccent,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Nova is thinking…", color = ChatPanelMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().background(ChatPanelBg).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Surface(
                color = ChatPanelSurface, shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .07f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 58.dp)
                        .padding(start = 7.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onVoice, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Default.Mic, "Talk to Nova", tint = ChatPanelMuted)
                    }
                    IconButton(onClick = onStopSpeaking, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Default.Stop, "Stop Nova speaking", tint = ChatPanelAccent)
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = ChatPanelText),
                        cursorBrush = SolidColor(ChatPanelAccent),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) onSend() }),
                        decorationBox = { innerTextField ->
                            Box {
                                if (input.isBlank()) Text("Message Nova…", color = ChatPanelMuted,
                                    style = MaterialTheme.typography.bodyLarge)
                                innerTextField()
                            }
                        }
                    )
                    Surface(
                        color = if (input.isNotBlank()) ChatPanelAccent else ChatPanelRaised,
                        shape = CircleShape,
                        modifier = Modifier.size(42.dp).clickable(enabled = input.isNotBlank()) { onSend() }
                    ) {
                        Icon(Icons.Default.Send, "Send message",
                            tint = if (input.isNotBlank()) Color(0xFF171421) else ChatPanelMuted,
                            modifier = Modifier.padding(11.dp))
                    }
                }
            }
            Text("AI conversation · Phone commands run locally · Tap ■ to stop speech",
                color = ChatPanelMuted, style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp))
        }
    }
}
