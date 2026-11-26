package com.guna.nova

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun GroqSettingsCard() {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf(GroqKeyStore.get(context)) }
    var message by remember { mutableStateOf("") }
    val (requests, tokens) = GroqApiClient.usage(context)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2B2B)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("GROQ AI", color = Color(0xFF65E5D5), style = MaterialTheme.typography.labelLarge)
            Text("One cloud model: GPT-OSS 20B. Phone commands stay local and do not use AI quota.",
                color = Color(0xFFAAB4CC), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Groq API key") },
                placeholder = { Text("Paste your key here") },
                visualTransformation = PasswordVisualTransformation()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (apiKey.trim().startsWith("gsk_") && apiKey.trim().length > 20) {
                            GroqKeyStore.save(context, apiKey)
                            apiKey = GroqKeyStore.get(context)
                            message = "API key saved encrypted on this device."
                        } else {
                            message = "Enter a valid-looking Groq API key. The key is not sent anywhere until you ask Nova an AI question."
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save key") }
                OutlinedButton(
                    onClick = {
                        GroqKeyStore.clear(context)
                        apiKey = ""
                        message = "Saved Groq key cleared from this device."
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Clear key") }
            }
            Text("Nova's local usage guard: $requests / 900 requests and $tokens / 180,000 tokens today.",
                color = Color(0xFFAAB4CC), style = MaterialTheme.typography.bodySmall)
            Text("This is a local safety estimate, not Groq's live quota meter. Groq may enforce lower account limits. Nova never enables paid billing or a paid fallback.",
                color = Color(0xFFAAB4CC), style = MaterialTheme.typography.bodySmall)
            if (message.isNotBlank()) Text(message, color = Color(0xFF65E5D5), style = MaterialTheme.typography.bodySmall)
        }
    }
}
