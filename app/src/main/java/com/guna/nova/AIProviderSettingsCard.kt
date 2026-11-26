package com.guna.nova

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AIProviderSettingsCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var gemini by remember { mutableStateOf("") }
    var groq by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Your keys stay encrypted on this phone.") }
    var busy by remember { mutableStateOf(false) }
    var geminiSaved by remember { mutableStateOf(AIProviderManager.hasGemini(context)) }
    var groqSaved by remember { mutableStateOf(AIProviderManager.hasGroq(context)) }
    var cloudEnabled by remember { mutableStateOf(AIProviderManager.isCloudEnabled(context)) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("AI CONNECTIONS", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Text("Connect Nova to cloud AI", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text("Cloud providers are optional. Local Qwen remains available offline.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Allow cloud AI", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(if (cloudEnabled) "Enabled - request text and conversation history may be sent to providers."
                        else "Disabled - no cloud requests will be sent. Saved keys do not grant permission.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = cloudEnabled, onCheckedChange = { enabled ->
                    AIProviderManager.setCloudEnabled(context, enabled)
                    cloudEnabled = enabled
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { showDialog = true },
                    label = { Text(if (geminiSaved) "Gemini connected" else "Gemini not set") })
                AssistChip(onClick = { showDialog = true },
                    label = { Text(if (groqSaved) "Groq connected" else "Groq not set") })
            }
            Button(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)) {
                Text("Manage AI connections")
            }
        }
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = { if (!busy) showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 460.dp)
                    .heightIn(max = 700.dp),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .imePadding().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("AI connections", style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold)
                            Text("Set up your cloud providers", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(enabled = !busy, onClick = { showDialog = false }) { Text("Close") }
                    }

                    ProviderKeyField(
                        title = "Gemini",
                        subtitle = "Primary AI provider",
                        saved = geminiSaved,
                        value = gemini,
                        onValueChange = { gemini = it }
                    )
                    ProviderKeyField(
                        title = "Groq",
                        subtitle = "Backup provider",
                        saved = groqSaved,
                        value = groq,
                        onValueChange = { groq = it }
                    )

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !busy && cloudEnabled && (gemini.isNotBlank() || geminiSaved),
                            onClick = {
                                if (gemini.isNotBlank()) AIProviderManager.saveGemini(context, gemini)
                                busy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { AIProviderManager.testGemini(context) }
                                    status = result.message
                                    geminiSaved = AIProviderManager.hasGemini(context)
                                    busy = false
                                }
                            }
                        ) { Text("Test Gemini") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !busy && cloudEnabled && (groq.isNotBlank() || groqSaved),
                            onClick = {
                                if (groq.isNotBlank()) AIProviderManager.saveGroq(context, groq)
                                busy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { AIProviderManager.testGroq(context) }
                                    status = result.message
                                    groqSaved = AIProviderManager.hasGroq(context)
                                    busy = false
                                }
                            }
                        ) { Text("Test Groq") }
                    }

                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(status, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Button(
                        onClick = {
                            try {
                                if (gemini.isNotBlank()) AIProviderManager.saveGemini(context, gemini)
                                if (groq.isNotBlank()) AIProviderManager.saveGroq(context, groq)
                                geminiSaved = AIProviderManager.hasGemini(context)
                                groqSaved = AIProviderManager.hasGroq(context)
                                gemini = ""; groq = ""
                                status = "Connections saved securely on this phone."
                            } catch (e: Exception) {
                                status = "Could not save keys: ${e.message ?: "storage error"}"
                            }
                        },
                        enabled = !busy && (gemini.isNotBlank() || groq.isNotBlank()),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Save connections") }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy && geminiSaved, modifier = Modifier.weight(1f), onClick = {
                            AIProviderManager.clearGemini(context); geminiSaved = false
                            status = "Gemini key removed."
                        }) { Text("Remove Gemini") }
                        TextButton(enabled = !busy && groqSaved, modifier = Modifier.weight(1f), onClick = {
                            AIProviderManager.clearGroq(context); groqSaved = false
                            status = "Groq key removed."
                        }) { Text("Remove Groq") }
                    }
                    Text(
                        "Cloud permission is separate from saving keys. When enabled, request text and conversation history may be processed by the provider. Screen, clipboard and contact data are not included in these chat calls. Keys remain encrypted with Android Keystore and are not uploaded to GitHub.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderKeyField(
    title: String,
    subtitle: String,
    saved: Boolean,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AssistChip(onClick = {}, label = { Text(if (saved) "Saved" else "Not connected") })
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(if (saved) "Saved key · enter to replace" else "Paste API key") },
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(14.dp)
        )
    }
}
