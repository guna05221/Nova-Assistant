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

    fun execute(context: Context, raw: String) {
        val appContext = context.applicationContext
        val original = raw.trim()
        val command = normalize(original)
        if (command.isBlank()) return

        val requestId = requestSequence.incrementAndGet()

        // Transaction safety is enforced before any model sees the request.
        if (isFinancialAction(command)) {
            say(appContext, "I can explain payment options, but Nova never makes payments, transfers, purchases, subscriptions, recharges or paid orders.")
            return
        }

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val directIntent = understand(command, prefs.getString("last_app", null))

        // These actions require explicit user wording and are never inferred by an AI model.
        if (directIntent.kind == "TYPE_TEXT" || directIntent.kind == "SEND") {
            executeIntent(appContext, directIntent)
            return
        }
        if (isCapabilityQuestion(command)) {
            say(appContext, capabilityReply(appContext))
            return
        }

        // Deterministic commands take precedence over AI interpretation.
        if (directIntent.kind != "UNKNOWN" && directIntent.kind != "CONVERSATION") {
            executeIntent(appContext, directIntent)
            return
        }
        if (directIntent.kind == "CONVERSATION") {
            say(appContext, directIntent.value.orEmpty())
            return
        }

        // Keep the microphone responsive while AI thinks, but discard stale model results
        // if a newer user utterance arrives before the previous request completes.
        statusOnly(appContext, "Thinking…")
        scope.launch {
            processUnknownUtterance(appContext, original, requestId)
        }
    }

    private suspend fun processUnknownUtterance(context: Context, utterance: String, requestId: Long) {
        val history = conversationHistory(context, utterance)
        val cloudAvailable = AIProviderManager.hasGemini(context) || AIProviderManager.hasGroq(context)
        val localAvailable = LocalAiManager.isDownloaded(context)

        val plannerContext = buildPlannerContext(history, utterance)
        val plannerPrompt = """
            You are Nova, a safe Android voice assistant. Understand English, Tamil and Tanglish.
            The user input includes recent conversation context and the current request. Use prior turns
            to resolve follow-ups such as "why", "that one", "tell me more" and "do it again".
            Infer the current user's intent and return exactly one JSON object:
            {"action":"ACTION","target":"","reply":""}
            Allowed actions:
            OPEN_APP, SEARCH, WEB_SEARCH, HOME, BACK, SETTINGS, SCROLL_UP, SCROLL_DOWN,
            FOOD_SEARCH, SEARCH_SWIGGY, SEARCH_ZOMATO, CONVERSATION, UNSUPPORTED.
            Use CONVERSATION for ordinary questions, explanations, calculations, jokes and casual chat.
            For CONVERSATION, return an empty reply string. The conversation engine generates
            the spoken answer separately using the full conversation history.
            Use OPEN_APP only when the user clearly asks to open an installed app.
            Use SEARCH or WEB_SEARCH only when the user clearly asks to search.
            Use FOOD_SEARCH for requests to find food or nearby restaurants.
            Use SEARCH_SWIGGY or SEARCH_ZOMATO only when the user names that service.
            Never propose SEND, TYPE_TEXT, payments, transfers, purchases, subscriptions, recharges,
            orders, deletion, publishing, security changes, calls or unsupported actions.
            Never claim that an action has already happened. Do not invent live search results.
            Preserve the user's language in the reply where practical. Ask one short question if essential
            information is missing. Return JSON only, without markdown.
        """.trimIndent()

        var plannedIntent: BrainIntent? = null
        if (cloudAvailable) {
            val planned = AIProviderManager.plan(context, plannerPrompt, plannerContext)
            if (planned.ok) plannedIntent = parseAiIntent(planned.message)
        }
        if (plannedIntent == null && localAvailable) {
            val localPlan = LocalAiManager.interpret(context, plannerContext)
            if (!localPlan.isNullOrBlank()) plannedIntent = parseAiIntent(localPlan)
        }

        if (requestSequence.get() != requestId) return

        val safeAction = plannedIntent?.takeIf { isSafeModelAction(it.kind) }
        if (safeAction != null && safeAction.kind != "CONVERSATION") {
            withContext(Dispatchers.Main) {
                if (requestSequence.get() == requestId) executeIntent(context, safeAction)
            }
            return
        }

        val answer = when {
            plannedIntent?.kind == "CONVERSATION" && !plannedIntent.value.isNullOrBlank() ->
                plannedIntent.value
            cloudAvailable -> {
                val result = AIProviderManager.chat(context, history)
                if (result.ok) result.message
                else if (localAvailable) LocalAiManager.chat(context, history.toString())
                else null
            }
            localAvailable -> LocalAiManager.chat(context, history.toString())
            else -> null
        }

        if (requestSequence.get() != requestId) return

        val response = answer?.trim()?.takeIf { it.isNotBlank() } ?: when {
            cloudAvailable -> "I couldn't reach the AI service just now. Please try again."
            localAvailable -> "I couldn't generate a response with the local model. Please try again."
            else -> "Natural conversation isn't configured yet. Connect Gemini or Groq, or install the local Qwen model."
        }

        saveConversation(context, utterance, response)
        withContext(Dispatchers.Main) {
            if (requestSequence.get() == requestId) say(context, response)
        }
    }

    private fun buildPlannerContext(history: org.json.JSONArray, latest: String): String {
        val previous = StringBuilder()
        val end = (history.length() - 1).coerceAtLeast(0)
        val start = (end - 4).coerceAtLeast(0)
        for (index in start until end) {
            val item = history.optJSONObject(index) ?: continue
            val role = item.optString("role")
            val content = item.optString("content").trim()
            if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                previous.append(role).append(": ").append(content.take(150)).append("\n")
            }
        }
        return buildString {
            if (previous.isNotEmpty()) {
                append("Recent conversation:\n")
                append(previous)
            }
            append("Current user request: ")
            append(latest.take(500))
        }.takeLast(1200)
    }

    private fun isSafeModelAction(kind: String): Boolean = kind in setOf(
        "OPEN_APP", "SEARCH", "WEB_SEARCH", "HOME", "BACK", "SETTINGS",
        "SCROLL_UP", "SCROLL_DOWN", "FOOD_SEARCH", "SEARCH_SWIGGY", "SEARCH_ZOMATO"
    )

    private fun isFinancialAction(text: String): Boolean = containsAny(
        text,
        "make a payment", "pay my bill", "pay the bill", "pay this bill", "pay now",
        "send money", "transfer money", "money transfer", "upi payment", "make payment",
        "buy this", "purchase this", "place an order", "order this", "subscribe now",
        "pay using", "pay through upi", "recharge my"
    )

    private fun conversationHistory(context: Context, latest: String): org.json.JSONArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("conversation_history", null)
        val history = try { if (stored.isNullOrBlank()) org.json.JSONArray() else org.json.JSONArray(stored) }
            catch (_: Exception) { org.json.JSONArray() }
        history.put(org.json.JSONObject().put("role", "user").put("content", latest.take(1600)))
        while (history.length() > 12) history.remove(0)
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

    private fun isCapabilityQuestion(text: String): Boolean = containsAny(
        text,
        "what can you do", "what all can you do", "what are your features", "your capabilities",
        "what can nova do", "tell me your features", "how can you help me", "what do you support",
        "நீ என்ன செய்ய முடியும்", "உன்னால என்ன செய்ய முடியும்", "என்னென்ன செய்ய முடியும்",
        "நீ என்ன பண்ணுவ", "enna ellam panna mudiyum", "nee enna panna mudiyum",
        "un features enna", "nova enna pannum"
    )

    private fun capabilityReply(context: Context): String {
        val cloudReady = AIProviderManager.hasGemini(context) || AIProviderManager.hasGroq(context)
        val localReady = LocalAiManager.isDownloaded(context)
        val aiMode = when {
            cloudReady && localReady -> "AI reasoning: Gemini/Groq with your downloaded Qwen model available as a local option."
            cloudReady -> "AI reasoning: your configured Gemini/Groq connection."
            localReady -> "AI reasoning: your downloaded Qwen model, which can work offline."
            else -> "AI reasoning: not configured yet. Add a Gemini/Groq key or download the local Qwen model."
        }
        return """
            I'm Nova. Here's what I can do right now:
            • Understand requests in English, Tamil and Tanglish.
            • Open installed apps and Android Settings; go Home or Back; scroll the screen.
            • Search Google and open nearby food results in Maps, Swiggy or Zomato.
            • Copy visible text and paste clipboard text when Nova Accessibility is enabled.
            • Have AI-assisted conversations and plan supported actions. $aiMode

            Important limits: I can type into a focused text field and tap a clearly labelled Send button only when you explicitly tell me to send. I don't make phone calls, read private chats or notifications, place food orders, book services, make purchases or payments, transfer money, delete files, or change security settings. Search handoffs open another app; I don't yet read and compare its live results automatically.
            """.trimIndent()
    }

    private fun parseAiIntent(raw: String): BrainIntent? {
        return try {
            val first = raw.indexOf('{')
            val last = raw.lastIndexOf('}')
            if (first < 0 || last <= first) return null
            val json = org.json.JSONObject(raw.substring(first, last + 1))
            val action = json.optString("action", "UNSUPPORTED").uppercase(Locale.ROOT)
            val target = json.optString("target").takeIf { it.isNotBlank() }
            val reply = json.optString("reply").takeIf { it.isNotBlank() }
            when (action) {
                "OPEN_APP", "SEARCH", "HOME", "BACK", "SETTINGS", "SCROLL_UP",
                "SCROLL_DOWN", "COPY_LAST_MESSAGE", "PASTE", "TYPE_TEXT", "SEND",
                "FOOD_SEARCH", "SEARCH_SWIGGY", "SEARCH_ZOMATO", "WEB_SEARCH" -> BrainIntent(action, target)
                "CONVERSATION" -> BrainIntent("CONVERSATION", reply)
                "UNSUPPORTED", "SEND_MESSAGE", "PAYMENT", "DELETE" ->
                    BrainIntent("CONVERSATION", reply ?: "I can't perform that action yet.")
                else -> null
            }
        } catch (_: Exception) {
            null
        }
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
                val sent = NovaAccessibilityService.instance?.clickSendButton() == true
                say(context, if (sent) "I tapped the visible Send button. Check that the app shows your message as sent." else "I couldn't find an enabled, clearly labelled Send button. I haven't sent anything.")
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
                "pasikkuthu", "pasikuthu", "saapidanum", "சாப்பிடணும்", "பசிக்குது", "உணவு தேடு"))
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

    private fun statusOnly(context: Context, message: String) {
        context.getSharedPreferences("nova_diagnostics", Context.MODE_PRIVATE).edit()
            .putString("status", message).putBoolean("running", true).apply()
    }

    private fun say(context: Context, message: String) {
        context.getSharedPreferences("nova_diagnostics", Context.MODE_PRIVATE).edit()
            .putString("status", message).putBoolean("running", true).apply()
        NovaSpeech.speak(context, message)
        // The main screen also displays status; speech output is handled by NovaSpeech.
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

    private fun search(context: Context, query: String) {
        if (query.isBlank()) return
        context.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
