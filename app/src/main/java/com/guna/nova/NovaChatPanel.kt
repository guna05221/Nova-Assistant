package com.guna.nova

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().background(ChatPanelBg)
    ) {
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(color = ChatPanelAccent.copy(alpha = 0.15f), shape = RoundedCornerShape(22.dp)) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = ChatPanelAccent,
                            modifier = Modifier.padding(17.dp).size(34.dp)
                        )
                    }
                    Spacer(Modifier.height(22.dp))
                    Text(
                        "What can I help with?",
                        color = ChatPanelText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Ask Nova anything, or tell it what you'd like to get done.",
                        color = ChatPanelMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    listOf(
                        "Explain something" to "Explain Java interfaces with a simple example",
                        "Write something" to "Help me write a professional email",
                        "Plan something" to "Help me plan my day"
                    ).forEach { (title, prompt) ->
                        Surface(
                            color = ChatPanelSurface,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.035f)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                                .clickable { onSuggestion(prompt) }
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
                modifier = Modifier.weight(1f).fillMaxWidth(),
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
                                Text(
                                    item.text,
                                    color = ChatPanelText,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Surface(color = ChatPanelAccent.copy(alpha = 0.15f), shape = CircleShape) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = "Nova",
                                    tint = ChatPanelAccent,
                                    modifier = Modifier.padding(7.dp).size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(11.dp))
                            Text(
                                item.text,
                                color = ChatPanelText,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f).padding(top = 3.dp)
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().background(ChatPanelBg)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Surface(
                color = ChatPanelSurface,
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.045f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    IconButton(onClick = onVoice, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Default.Mic, contentDescription = "Voice input", tint = ChatPanelMuted)
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message Nova", color = ChatPanelMuted) },
                        maxLines = 5,
                        shape = RoundedCornerShape(22.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ChatPanelText,
                            unfocusedTextColor = ChatPanelText,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            cursorColor = ChatPanelAccent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        )
                    )
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        color = if (input.isNotBlank()) ChatPanelText else ChatPanelRaised,
                        shape = CircleShape,
                        modifier = Modifier.padding(bottom = 4.dp).size(40.dp)
                            .clickable(enabled = input.isNotBlank()) { onSend() }
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send message",
                            tint = if (input.isNotBlank()) ChatPanelBg else ChatPanelMuted,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
            Text(
                "Nova can make mistakes. Check important information.",
                color = ChatPanelMuted,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)
            )
        }
    }
}
