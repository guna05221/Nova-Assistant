package com.guna.nova

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val ChatWebBg = Color(0xFF212121)
private val ChatWebCard = Color(0xFF303030)
private val ChatWebMuted = Color(0xFFB4B4B4)

/**
 * ChatGPT account access through the user's normal Android browser.
 * OpenAI sign-in may reject embedded WebViews, so authentication and
 * ChatGPT interaction are intentionally delegated to the official site
 * in the browser. Nova does not read browser cookies or page contents.
 */
@Composable
fun ChatGptVirtualScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    fun openChatGpt() {
        val url = Uri.parse("https://chatgpt.com/")
        // Explicit browser packages prevent verified links opening the ChatGPT app.
        val browserPackages = listOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev",
            "com.sec.android.app.sbrowser", "org.mozilla.firefox",
            "com.microsoft.emmx", "com.brave.browser", "com.opera.browser"
        )
        for (browser in browserPackages) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, url).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    setPackage(browser)
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) { /* try next installed browser */ }
        }
        android.widget.Toast.makeText(context,
            "No supported browser found. Install or enable Chrome, then try again.",
            android.widget.Toast.LENGTH_LONG).show()
    }

    Column(
        modifier.fillMaxSize().background(ChatWebBg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = ChatWebCard),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "CHATGPT · ACCOUNT WORKSPACE",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Sign in securely using your normal Android browser.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "The ChatGPT sign-in page may block embedded app browsers. Nova therefore opens the official ChatGPT website in your browser, where you can log in with your own Go account.",
                    color = ChatWebMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = { openChatGpt() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign in to ChatGPT")
                }
                OutlinedButton(
                    onClick = { openChatGpt() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open ChatGPT again")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = ChatWebCard),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("IMPORTANT", color = Color(0xFFB69CFF),
                    style = MaterialTheme.typography.labelLarge)
                Text(
                    "Browser sign-in lets you use ChatGPT manually. It does not connect Nova's background AI to your ChatGPT account. Nova cannot read browser messages, copy session cookies, or bypass OpenAI usage limits.",
                    color = ChatWebMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Nova will not purchase credits, enable automatic reloads, or perform financial actions.",
                    color = ChatWebMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
