package com.guna.nova

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
    var status by remember { mutableStateOf("Keys are encrypted and stored on this phone.") }
    var busy by remember { mutableStateOf(false) }
    var geminiSaved by remember { mutableStateOf(AIProviderManager.hasGemini(context)) }
    var groqSaved by remember { mutableStateOf(AIProviderManager.hasGroq(context)) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CLOUD AI PROVIDERS", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Text(
                "Gemini ${if (geminiSaved) "• key saved" else "• not configured"}   |   Groq ${if (groqSaved) "• key saved" else "• not configured"}",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall
            )
            Text("Use Gemini first, Groq as fallback. Local Qwen remains available offline.", color = NovaMuted, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Configure AI keys")
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showDialog = false },
            title = { Text("Nova AI providers") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Gemini API key (primary)", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = gemini, onValueChange = { gemini = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text(if (geminiSaved) "Key already saved — enter to replace" else "Paste Gemini key") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Text("Groq API key (fallback)", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = groq, onValueChange = { groq = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text(if (groqSaved) "Key already saved — enter to replace" else "Paste Groq key") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = {
                            if (gemini.isNotBlank()) AIProviderManager.saveGemini(context, gemini)
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { AIProviderManager.testGemini(context) }
                                status = result.message
                                geminiSaved = AIProviderManager.hasGemini(context)
                                busy = false
                            }
                        }) { Text("Test Gemini") }
                        OutlinedButton(enabled = !busy, onClick = {
                            if (groq.isNotBlank()) AIProviderManager.saveGroq(context, groq)
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { AIProviderManager.testGroq(context) }
                                status = result.message
                                groqSaved = AIProviderManager.hasGroq(context)
                                busy = false
                            }
                        }) { Text("Test Groq") }
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = NovaMuted)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Keys are encrypted with Android Keystore. They are not uploaded to GitHub.", style = MaterialTheme.typography.bodySmall, color = NovaMuted)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    try {
                        if (gemini.isNotBlank()) AIProviderManager.saveGemini(context, gemini)
                        if (groq.isNotBlank()) AIProviderManager.saveGroq(context, groq)
                        geminiSaved = AIProviderManager.hasGemini(context)
                        groqSaved = AIProviderManager.hasGroq(context)
                        gemini = ""; groq = ""
                        status = "Saved securely on this phone."
                    } catch (e: Exception) {
                        status = "Could not save keys: ${e.message ?: "storage error"}"
                    }
                }) { Text("Save keys") }
            },
            dismissButton = {
                Row {
                    TextButton(enabled = !busy && geminiSaved, onClick = {
                        AIProviderManager.clearGemini(context); geminiSaved = false
                        status = "Gemini key removed."
                    }) { Text("Remove Gemini") }
                    TextButton(enabled = !busy && groqSaved, onClick = {
                        AIProviderManager.clearGroq(context); groqSaved = false
                        status = "Groq key removed."
                    }) { Text("Remove Groq") }
                    TextButton(enabled = !busy, onClick = { showDialog = false }) { Text("Close") }
                }
            }
        )
        // Provider tests run on IO and never display or log the key.
        // Test controls are kept outside the dialog buttons to avoid accidental API calls on save.
    }
}
