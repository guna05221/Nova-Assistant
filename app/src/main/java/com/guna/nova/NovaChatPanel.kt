package com.guna.nova

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val ChatPanelBg = Color(0xFF171F35)
private val ChatPanelPurple = Color(0xFFA99BFF)
private val ChatPanelMint = Color(0xFF65E5D5)
private val ChatPanelMuted = Color(0xFFAAB4CC)

data class NovaChatLine(val speaker: String, val text: String, val id: Long = System.nanoTime())

@Composable
fun NovaChatPanel(
    messages: List<NovaChatLine>,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ChatPanelBg),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CHAT WITH NOVA", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Type or speak naturally in Tamil, English or Tanglish", color = ChatPanelMuted, style = MaterialTheme.typography.bodySmall)
                }
                Surface(color = Color(0xFF263451), shape = RoundedCornerShape(50)) {
                    Text("VOICE + TEXT", color = ChatPanelMint, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                }
            }

            if (messages.isEmpty()) {
                Text(
                    "Hi! I'm here. You can type a message or tap the microphone to talk.",
                    color = ChatPanelMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 250.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(messages, key = { _, item -> item.id }) { _, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (item.speaker == "YOU") Arrangement.End else Arrangement.Start
                        ) {
                            Surface(
                                color = if (item.speaker == "YOU") Color(0xFF34436A) else Color(0xFF252D49),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.widthIn(max = 300.dp)
                            ) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                    Text(item.speaker, color = if (item.speaker == "YOU") ChatPanelMint else ChatPanelPurple, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(3.dp))
                                    Text(item.text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onVoice,
                    modifier = Modifier.size(50.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ChatPanelMint)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Speak to Nova")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Nova…", color = ChatPanelMuted) },
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = ChatPanelPurple,
                        unfocusedBorderColor = Color(0xFF46516D),
                        cursorColor = ChatPanelMint
                    )
                )
                Button(
                    onClick = onSend,
                    enabled = input.isNotBlank(),
                    modifier = Modifier.size(50.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ChatPanelPurple, contentColor = Color(0xFF17132D))
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send message")
                }
            }
        }
    }
}
