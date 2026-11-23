package com.guna.nova

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val BrainPurple = Color(0xFFB7A8FF)
private val BrainMuted = Color(0xFFAAB4CC)
private val BrainPanel = Color(0xFF2B2B2B)

@Composable
fun NovaBrainScreen(
    chatGptConnected: Boolean,
    geminiConnected: Boolean,
    groqConnected: Boolean,
    localReady: Boolean,
    connectionStatus: String,
    onConnectChatGpt: () -> Unit,
    onDisconnectChatGpt: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var liveConnected by remember { mutableStateOf(chatGptConnected) }
    var liveStatus by remember { mutableStateOf(connectionStatus) }
    var liveEmail by remember { mutableStateOf(ChatGptAccountStore.email(context)) }
    var showBillingSafetyDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            liveConnected = ChatGptAccountStore.isConnected(context)
            liveStatus = ChatGptAccountStore.status(context)
            liveEmail = ChatGptAccountStore.email(context)
            delay(700)
        }
    }
    val pulse = rememberInfiniteTransition(label = "brain-pulse")
    val scale = pulse.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "brain-scale"
    )

    Column(
        modifier.fillMaxSize().background(Color(0xFF212121))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("NOVA BRAIN", color = BrainPurple, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold)
            Text("The intelligence behind your assistant", color = Color.White,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("One reasoning engine, connected to Nova's memory, tools and safety layer.",
                color = BrainMuted, style = MaterialTheme.typography.bodyMedium)
        }

        Box(
            Modifier.padding(top = 6.dp).size(164.dp).scale(scale.value)
                .background(
                    Brush.radialGradient(listOf(Color(0x665E4ACB), Color(0x222D284B), Color.Transparent)),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.size(116.dp),
                shape = CircleShape,
                color = Color(0xFF302B49),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrainPurple.copy(alpha = 0.55f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("🧠", style = MaterialTheme.typography.displayLarge)
                }
            }
        }

        BrainProviderCard(
            title = "ChatGPT",
            subtitle = "Primary brain · OpenAI",
            status = "Website workspace",
            primary = true
        )
        Button(
            onClick = onConnectChatGpt,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Open ChatGPT workspace")
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF252A32)),
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("MANUAL CHATGPT ACCESS", color = BrainPurple,
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text("Nova opens the official ChatGPT website in its own workspace. Sign in there with your account. Website sign-in is separate from Nova's background AI provider and does not let Nova read website messages or use hidden APIs.",
                    color = Color.White, style = MaterialTheme.typography.bodySmall)
                Text("No automatic credit purchases, recharges or reloads are performed by Nova.",
                    color = BrainMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("ChatGPT website session is separate from Nova's AI provider connection.",
            color = BrainMuted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth())

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("BACKUP INTELLIGENCE", color = BrainMuted,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            BrainProviderCard("Gemini", "Cloud fallback", if (geminiConnected) "Configured" else "Not configured")
            BrainProviderCard("Groq", "Secondary cloud fallback", if (groqConnected) "Configured" else "Not configured")
            BrainProviderCard("Local AI", "On-device · offline capable", if (localReady) "Model ready" else "Model not loaded")
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF252A32)),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("NOVA EXECUTION LAYER", color = BrainPurple,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("Memory  ·  Phone tools  ·  Research  ·  Safety checks",
                    color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold)
                Text("Nova remains responsible for device actions and approvals. Financial actions stay permanently blocked.",
                    color = BrainMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BrainProviderCard(
    title: String,
    subtitle: String,
    status: String,
    primary: Boolean = false
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (primary) Color(0xFF302B49) else BrainPanel
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (primary) BrainPurple.copy(alpha = 0.16f) else Color(0xFF3B414D)
            ) {
                Text(
                    if (primary) "✳" else "•",
                    color = if (primary) BrainPurple else BrainMuted,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = BrainMuted, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(status, color = if (status == "Connected" || status == "Configured" || status == "Model ready")
                    Color(0xFF65E5D5) else BrainMuted,
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
