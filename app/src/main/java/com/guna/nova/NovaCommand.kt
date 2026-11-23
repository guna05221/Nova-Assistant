package com.guna.nova

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale

/**
 * Local-first intent router. Unknown speech is never silently treated as a web search.
 * A short conversation memory is stored locally to resolve simple follow-ups.
 */
object NovaCommand {
    private const val PREFS = "nova_brain"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestSequence = AtomicLong(0L)

    // One in-memory, one-time approval. Never persisted and never reusable.
    private data class PendingSend(
        val packageName: String,
        val expectedText: String,
        val expiresAt: Long
    )
    @Volatile private var pendingSend: PendingSend? = null
    private val pendingSendLock = Any()
    private const val SEND_CONFIRMATION_WINDOW_MS = 20_000L

    fun execute(context: Context, raw: String) {
        val appContext = context.applicationContext
        val command = normalize(raw.trim())
        if (command.isBlank()) return

        if (isFinancialAction(command)) {
            synchronized(pendingSendLock) { pendingSend = null }
            say(appContext, "Nova does not perform payments, transfers, purchases, subscriptions, recharges or paid orders.")
            return
        }

        val pending = synchronized(pendingSendLock) { pendingSend }
        if (pending != null) {
            when {
                isConfirmation(command) -> { resolvePendingSend(appContext, true); return }
                isCancellation(command) -> { resolvePendingSend(appContext, false); return }
                else -> {
                    synchronized(pendingSendLock) { pendingSend = null }
                    ApprovalNotification.dismiss(appContext)
                }
            }
        }

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val intent = understand(command, prefs.getString("last_app", null))
        if (intent.kind == "OBSERVE_SCREEN") {
            val snapshot = NovaAccessibilityService.instance?.observeActiveScreen()
            val response = if (snapshot == null) {
                "Enable Nova Accessibility and keep the app you want to inspect open."
            } else {
                val nodes = snapshot.optJSONArray("nodes")
                val visible = mutableListOf<String>()
                if (nodes != null) for (i in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(i) ?: continue
                    val label = node.optString("text").ifBlank { node.optString("description") }.trim()
                    if (label.isNotBlank() && label !in visible) visible.add(label)
                    if (visible.size >= 12) break
                }
                if (visible.isEmpty()) "The current app does not expose readable screen text."
                else visible.joinToString(". ").take(900)
            }
            say(appContext, response)
            return
        }
        if (intent.kind == "SEND") {
            val snapshot = NovaAccessibilityService.instance?.currentSendContext()
            if (snapshot == null || snapshot.second.isBlank()) {
                say(appContext, "I couldn't verify a message in the current text field. Nothing was sent.")
                return
            }
            synchronized(pendingSendLock) {
                pendingSend = PendingSend(snapshot.first, snapshot.second, System.currentTimeMillis() + SEND_CONFIRMATION_WINDOW_MS)
            }
            ApprovalNotification.show(appContext, "Send this message?", snapshot.second, SEND_CONFIRMATION_WINDOW_MS)
            say(appContext, "Approve the exact message using the notification, or deny it.")
            return
        }
        if (intent.kind == "UNKNOWN") {
            scope.launch {
                val history = conversationHistory(appContext, "")
                val result = GroqApiClient.ask(appContext, command, history)
                if (result.answer != null) {
                    saveConversation(appContext, command, result.answer)
                    say(appContext, result.answer)
                } else {
                    say(appContext, result.error ?: "Nova couldn't answer that right now.")
                }
            }
            return
        }
        executeIntent(appContext, intent)
    }

    fun notifyApprovalUnavailable(context: Context) {
        say(context, "I couldn't display the Allow or Deny prompt. Please enable Nova notifications and try again. Nothing was sent.")
    }

    fun resolvePendingSend(context: Context, allow: Boolean) {
        val approved = synchronized(pendingSendLock) {
            val current = pendingSend
            pendingSend = null // consume before any side effect; never reusable
            current?.takeIf { it.expiresAt >= System.currentTimeMillis() }
        }
        ApprovalNotification.dismiss(context)
        if (!allow) {
            say(context, "Denied. Nothing was sent.")
            return
        }
        if (approved == null) {
            say(context, "That approval expired. Nothing was sent.")
            return
        }
        val sent = NovaAccessibilityService.instance
            ?.clickSendButton(approved.packageName, approved.expectedText) == true
        say(context, if (sent) "I tapped Send for that message. Please check the app to verify it was sent."
            else "I didn't send it because the app or message changed. Please review it and ask again.")
    }

    private fun isConfirmation(text: String): Boolean = text in setOf(
        "yes", "yes send", "confirm", "confirm send", "send now", "ஆம்", "ஆமாம்", "சரி", "அனுப்பு"
    )

    private fun isCancellation(text: String): Boolean = text in setOf(
        "no", "cancel", "cancel it", "stop", "don't send", "do not send",
        "வேண்டாம்", "நிறுத்து", "அனுப்ப வேண்டாம்"
    )

    private fun isFinancialAction(text: String): Boolean = containsAny(
        text,
        "make a payment", "make payment", "pay my bill", "pay the bill", "pay this bill",
        "pay now", "pay bill", "bill payment", "send money", "transfer money", "money transfer",
        "upi payment", "pay using", "pay through upi", "buy this", "buy it", "purchase this",
        "purchase it", "checkout", "place an order", "order this", "order it", "subscribe now",
        "start subscription", "recharge my", "recharge phone", "mobile recharge",
        "பணம் அனுப்பு", "பணம் செலுத்து", "பணம் கட்டு", "ரீசார்ஜ் செய்", "வாங்கு", "ஆர்டர் செய்",
        "panam anuppu", "panam seluthu", "panam kattu", "recharge pannu", "order pannu", "vaangi kudu"
    )

    private fun conversationHistory(context: Context, latest: String): org.json.JSONArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("conversation_history", null)
        val history = try { if (stored.isNullOrBlank()) org.json.JSONArray() else org.json.JSONArray(stored) }
            catch (_: Exception) { org.json.JSONArray() }
        // The API client appends the current prompt exactly once after this saved history.
        while (history.length() > 10) history.remove(0)
        return history
    }

    private fun saveConversation(context: Context, user: String, answer: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("conversation_history", null)
        val history = try { if (stored.isNullOrBlank()) org.json.JSONArray() else org.json.JSONArray(stored) }
            catch (_: Exception) { org.json.JSONArray() }
        history.put(org.json.JSONObject().put("role", "user").put("content", user.take(1600)))
        history.put(org.json.JSONObject().put("role", "assistant").put("content", answer.take(2400)))
        while (history.length() > 12) history.remove(0)
        prefs.edit().putString("conversation_history", history.toString()).apply()
    }

    private fun executeIntent(context: Context, intent: BrainIntent) {
        when (intent.kind) {
            "COPY_LAST_MESSAGE" -> {
                val service = NovaAccessibilityService.instance
                val copied = service?.copyLastMessage() == true
                val message = when {
                    service == null -> "Copy failed: enable Nova in Android Accessibility settings, then try again."
                    copied -> "Copied the latest visible text. Tap the destination text box and say: paste it."
                    else -> "Copy failed: no readable message text was found. Keep the chat open and try again."
                }
                say(context, message)
            }
            "PASTE" -> {
                val pasted = NovaAccessibilityService.instance?.pasteClipboard() == true
                say(context, if (pasted) "Pasted." else "I couldn't paste. Tap the text box first, then say paste it.")
            }
            "TYPE_TEXT" -> {
                val typed = NovaAccessibilityService.instance?.typeIntoFocusedField(intent.value.orEmpty()) == true
                say(context, if (typed) "Text entered. Say send it when you want me to send it." else "I couldn't find a focused text box. Open the app and tap its message field first.")
            }
            "SEND" -> {
                // SEND is handled by the one-time approval gate in execute(); never execute here.
                say(context, "To send a message, ask me to send it and approve that exact message when I ask.")
            }
            "HOME" -> {
                val accessibility = NovaAccessibilityService.instance
                if (accessibility != null) accessibility.home() else {
                    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(home)
                }
                remember(context, "HOME", null)
            }
            "BACK" -> {
                NovaAccessibilityService.instance?.back()
                remember(context, "BACK", null)
            }
            "SETTINGS" -> {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                remember(context, "SETTINGS", null)
            }
            "SCROLL_DOWN" -> NovaAccessibilityService.instance?.scroll(false)
            "SCROLL_UP" -> NovaAccessibilityService.instance?.scroll(true)
            "OPEN_APP" -> {
                val app = intent.value.orEmpty()
                if (app.isBlank() || !openApp(context, app)) say(context, "I couldn't find ${app.ifBlank { "that app" }} on this phone.")
                else remember(context, "OPEN_APP", app)
            }
            "SEARCH", "WEB_SEARCH" -> {
                val query = intent.value.orEmpty()
                if (query.isBlank()) say(context, "What should I search for?")
                else { search(context, query); remember(context, intent.kind, query) }
            }
            "YOUTUBE_SEARCH" -> {
                val query = intent.value.orEmpty()
                val opened = openYouTubeSearch(context, query)
                say(context, if (opened) "Opening YouTube search results for $query." else "I couldn't open YouTube search. Please check whether YouTube or a browser is available.")
                if (opened) remember(context, "YOUTUBE_SEARCH", query)
            }
            "FOOD_SEARCH" -> {
                val preference = intent.value?.takeIf { it.isNotBlank() } ?: "restaurants and food"
                val opened = FoodDiscovery.findNearby(context, preference)
                say(context, if (opened) "Opening nearby food and restaurant results. You can also ask me to search Swiggy or Zomato." else "I couldn't open nearby food search.")
                remember(context, "FOOD_SEARCH", preference)
            }
            "SEARCH_SWIGGY", "SEARCH_ZOMATO" -> {
                val app = if (intent.kind == "SEARCH_SWIGGY") "Swiggy" else "Zomato"
                val query = intent.value?.takeIf { it.isNotBlank() } ?: "food near me"
                val opened = FoodDiscovery.searchDeliveryApp(context, app, query)
                say(context, if (opened) "Opening $app food search." else "I couldn't open $app.")
                remember(context, intent.kind, query)
            }
            "CONVERSATION" -> say(context, intent.value ?: "I'm listening. Tell me what you'd like me to do.")
            else -> say(context, "I couldn't understand that request. Please say it another way.")
        }
    }

    private data class BrainIntent(val kind: String, val value: String? = null)

    private fun understand(text: String, lastApp: String?): BrainIntent {
        val t = text.lowercase(Locale.ROOT)
        // Natural-language fallback for common goals when the local model returns invalid output.
        if (containsAny(t, "i am hungry", "i'm hungry", "im hungry", "hungry", "find food", "food nearby",
                "nearby restaurants", "restaurants near me", "want to eat", "something to eat",
                "order biryani", "want biryani", "biryani", "pasikkuthu", "pasikuthu", "saapidanum",
                "சாப்பிடணும்", "பசிக்குது", "உணவு தேடு"))
            return BrainIntent("FOOD_SEARCH")
        if (t.contains("swiggy")) return BrainIntent("SEARCH_SWIGGY", text)
        if (t.contains("zomato")) return BrainIntent("SEARCH_ZOMATO", text)

        // Explicit voice typing and sending; typing never implies sending.
        val typePrefixes = listOf("type this ", "type ", "enter text ", "write in the text box ")
        val typePrefix = typePrefixes.firstOrNull { t.startsWith(it) }
        if (typePrefix != null) {
            val dictated = text.substring(typePrefix.length).trim()
            if (dictated.isNotBlank()) return BrainIntent("TYPE_TEXT", dictated)
        }
        if (containsAny(t, "send it", "send this", "send message", "press send", "அனுப்பு", "send pannu", "anuppu"))
            return BrainIntent("SEND")

        // Clipboard commands act on the currently visible app screen.
        if (containsAny(t, "copy the last message", "copy last message", "copy that message", "last message copy", "கடைசி மெசேஜ் காப்பி", "kadaisi message copy", "last message ah copy")) return BrainIntent("COPY_LAST_MESSAGE")
        if (containsAny(t, "paste it", "paste that", "paste the message", "paste", "இத ஒட்டு", "ஒட்டு", "paste pannu", "adha paste pannu", "paste pannunga")) return BrainIntent("PASTE")

        // Explicit read-only request to describe the active screen.
        if (containsAny(t, "observe my current screen", "read my screen", "what is visible",
                "tell me what is on my screen", "describe the screen", "screen la enna irukku",
                "என் ஸ்கிரீனில் என்ன இருக்கிறது", "திரையில் என்ன தெரிகிறது"))
            return BrainIntent("OBSERVE_SCREEN")

        // English, common Tamil-script and Tanglish expressions.
        if (containsAny(t, "go home", "go to home", "home screen", "home page", "take me home",
                "return home", "open home", "show home", "முகப்பு", "வீட்டுக்கு போ", "ஹோம் ஸ்கிரீன்",
                "home ku po", "veetuku po", "home screen ku po", "main screen ku po")) return BrainIntent("HOME")
        if (containsAny(t, "go back", "come back", "back please", "பின்னால் செல்", "திரும்பி போ", "back po", "pinnaadi po"))
            return BrainIntent("BACK")
        if (containsAny(t, "open settings", "phone settings", "அமைப்புகள்", "settings open pannu", "settings ah open pannu"))
            return BrainIntent("SETTINGS")
        if (containsAny(t, "scroll down", "கீழே போ", "keela scroll", "scroll keela"))
            return BrainIntent("SCROLL_DOWN")
        if (containsAny(t, "scroll up", "மேலே போ", "mela scroll", "scroll mela"))
            return BrainIntent("SCROLL_UP")

        // Compound YouTube requests must be split into app + query, not treated as one app name.
        val youtubeSearch = Regex(
            """^(?:please )?(?:open|launch)\s+(?:the\s+)?youtube(?:\s+app)?\s+and\s+(?:search(?:\s+for)?|look\s+for)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text.trim())
        if (youtubeSearch != null) {
            val query = youtubeSearch.groupValues[1].trim()
            if (query.isNotBlank()) return BrainIntent("YOUTUBE_SEARCH", query)
        }

        val searchPrefixes = listOf("search online for ", "search the web for ", "look up ", "find online ",
            "search for ", "search ", "google ")
        val searchPrefix = searchPrefixes.firstOrNull { t.startsWith(it) }
        if (searchPrefix != null) return BrainIntent("SEARCH", text.substring(searchPrefix.length).trim())

        val openPrefixes = listOf("please open ", "can you open ", "open ", "launch ", "start ",
            "திற ", "open pannu ", "open pannunga ")
        val openPrefix = openPrefixes.firstOrNull { t.startsWith(it) }
        if (openPrefix != null) {
            val target = text.substring(openPrefix.length).trim()
            if (target.isNotBlank()) return BrainIntent("OPEN_APP", target)
        }

        if (containsAny(t, "what can you do", "who are you", "how are you", "hello", "hi nova",
                "வணக்கம்", "எப்படி இருக்க", "enna panra", "epdi iruka", "vanakkam"))
            return BrainIntent("CONVERSATION", "Hello! I'm Nova. You can speak to me naturally in Tamil, English or Tanglish. What would you like?")
        // Repeat an app action only when the user explicitly names an app-opening action.
        // Generic "again" belongs to conversation reasoning (e.g. another joke / explain again).
        val reopen = Regex("^(?:please )?(?:open|launch|start) (.+?) again$")
            .matchEntire(t)?.groupValues?.get(1)?.trim()
        if (!reopen.isNullOrBlank()) return BrainIntent("OPEN_APP", reopen)
        if (containsAny(t, "repeat the last action", "do the last action again", "repeat that action")) {
            return if (lastApp.isNullOrBlank())
                BrainIntent("CONVERSATION", "I don't have an app action to repeat yet.")
            else BrainIntent("OPEN_APP", lastApp)
        }
        if (containsAny(t, "not that", "wrong one", "வேண்டாம்", "illa adhu illa", "no not this"))
            return BrainIntent("CONVERSATION", "Okay, I won't do that. Tell me what you meant instead.")
        return BrainIntent("UNKNOWN")
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).trim()
        .replace(Regex("[.!?,;:]+$"), "").replace(Regex("\\s+"), " ")

    private fun containsAny(text: String, vararg phrases: String) = phrases.any { text == it || text.contains(it) }

    private fun remember(context: Context, action: String, value: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_action", action).putString("last_app", value).apply()
    }

    private fun friendlyResponse(raw: String): String {
        val value = raw.lowercase(Locale.ROOT)
        return when {
            value.contains("http 429") || value.contains("too many requests") ->
                "The online research service is receiving too many requests right now. Please try again in a little while. You can continue chatting with me."
            value.contains("http 503") || value.contains("http 502") || value.contains("http 504") ->
                "The online research service is temporarily unavailable. Please try again shortly."
            Regex("http\\s+\\d{3}").containsMatchIn(value) ->
                "I couldn't complete the online research just now. Please try again in a moment."
            value.contains("network") || value.contains("timeout") ->
                "I couldn't connect to the online research service. Check your internet connection and try again."
            else -> raw
        }
    }

    private fun statusOnly(context: Context, message: String) {
        val safeMessage = friendlyResponse(message)
        context.getSharedPreferences("nova_diagnostics", Context.MODE_PRIVATE).edit()
            .putString("status", safeMessage).putBoolean("running", true).apply()
        NovaOverlayService.status(context, safeMessage)
    }

    private fun say(context: Context, message: String) {
        val safeMessage = friendlyResponse(message)
        context.getSharedPreferences("nova_diagnostics", Context.MODE_PRIVATE).edit()
            .putString("status", safeMessage)
            .putString("last_assistant_message", safeMessage)
            .putLong("last_assistant_at", System.currentTimeMillis())
            .putBoolean("running", true)
            .apply()
        NovaOverlayService.assistant(context, safeMessage)
        NovaSpeech.speak(context, safeMessage)
        // The main chat screen observes the saved response and renders it as a chat bubble.
    }

    private fun openApp(context: Context, label: String): Boolean {
        val apps = context.packageManager.getInstalledApplications(0)
        val query = label.lowercase(Locale.ROOT).trim()
        val match = apps.firstOrNull {
            context.packageManager.getApplicationLabel(it).toString().lowercase(Locale.ROOT) == query
        } ?: apps.firstOrNull {
            context.packageManager.getApplicationLabel(it).toString().lowercase(Locale.ROOT).contains(query)
        }
        val launch = match?.let { context.packageManager.getLaunchIntentForPackage(it.packageName) } ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return true
    }

    private fun openYouTubeSearch(context: Context, query: String): Boolean {
        if (query.isBlank()) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) { false }
    }

    private fun openResearchFallback(context: Context, query: String): Boolean {
        if (query.isBlank()) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) { false }
    }

    private fun search(context: Context, query: String) {
        if (query.isBlank()) return
        context.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
