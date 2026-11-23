package com.guna.nova

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private val ChatWebBg = Color(0xFF212121)
private val ChatWebCard = Color(0xFF303030)
private val ChatWebMuted = Color(0xFFB4B4B4)

/**
 * Manual, first-party ChatGPT website workspace.
 *
 * This deliberately has no JavaScript bridge, DOM scraping, cookie export,
 * hidden API calls, or message interception. Nova cannot read website replies
 * or turn them into background agent instructions.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChatGptVirtualScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf("Sign in to your ChatGPT account") }

    Column(modifier.fillMaxSize().background(ChatWebBg).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = ChatWebCard),
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("CHATGPT · PRIVATE WORKSPACE", color = Color.White,
                    style = MaterialTheme.typography.titleSmall)
                Text(pageTitle, color = ChatWebMuted, style = MaterialTheme.typography.bodySmall)
                Text("Sign in with your own account. This is the official website displayed for manual use; Nova does not inspect its messages or use your website session as an API.",
                    color = ChatWebMuted, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { webView?.reload() }, enabled = webView != null) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(5.dp))
                        Text("Reload")
                    }
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://chatgpt.com/")))
                        }
                    }) {
                        Icon(Icons.Default.OpenInBrowser, null)
                        Spacer(Modifier.width(5.dp))
                        Text("Open browser")
                    }
                    TextButton(onClick = {
                        webView?.loadUrl("https://chatgpt.com/")
                    }) { Text("Home") }
                }
            }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        private fun allowed(url: String): Boolean {
                            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
                            val host = uri.host?.lowercase() ?: return false
                            return uri.scheme == "https" && (
                                host == "chatgpt.com" || host.endsWith(".chatgpt.com") ||
                                host == "openai.com" || host.endsWith(".openai.com")
                            )
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            return !allowed(request.url.toString())
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            pageTitle = view.title?.take(100)?.ifBlank { "ChatGPT website ready" }
                                ?: "ChatGPT website ready"
                        }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        settings.setSafeBrowsingEnabled(true)
                    }
                    webView = this
                    loadUrl("https://chatgpt.com/")
                }
            },
            update = { if (webView !== it) webView = it }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }
}
