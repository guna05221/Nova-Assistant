package com.guna.nova

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Local-first intent router. Unknown speech is never silently treated as a web search.
 * A short conversation memory is stored locally to resolve simple follow-ups.
 */
object NovaCommand {
    private const val PREFS = "nova_brain"

    fun execute(context: Context, raw: String) {
        val appContext = context.applicationContext
        val original = raw.trim()
        val command = normalize(original)
        if (command.isBlank()) return

        // Handle explicit typing/sending locally before AI intent planning. This prevents
        // a model from misclassifying "type what is Java" as a normal conversation.
        val directIntent = understand(command, null)
        if (directIntent.kind == "TYPE_TEXT" || directIntent.kind == "SEND") {
            executeIntent(appContext, directIntent)
            return
        }

        // Answer capability questions from the real app state, not an unconstrained model guess.
        if (isCapabilityQuestion(command)) {
            say(appContext, capabilityReply(appContext))
            return
        }

        if (AIProviderManager.hasGemini(appContext) || AIProviderManager.hasGroq(appContext)) {
            statusOnly(appContext, "Thinking…")
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val prompt = listOf(
                    "You are Nova, an Android assistant. Understand English, Tamil and Tanglish.",
                    "Infer the user's goal. Return exactly JSON: {\"action\":\"ACTION\",\"target\":\"\",\"reply\":\"\"}.",
                    "IMPORTANT: Answer ordinary questions and requests for explanations, advice, definitions, calculations, jokes, writing, and casual conversation with CONVERSATION and a helpful reply. Do NOT open Chrome or search for these. Use SEARCH/WEB_SEARCH only when the user explicitly says search, Google, look up online, or asks to open a website.",
                    "If the user explicitly asks to open an app and ask/type a question there, use OPEN_APP for the app only for now; do not pretend to type or send until the app automation confirms it.",
                    "Allowed: FOOD_SEARCH, SEARCH_SWIGGY, SEARCH_ZOMATO, WEB_SEARCH, OPEN_APP, SEARCH, HOME, BACK, SETTINGS, SCROLL_UP, SCROLL_DOWN, COPY_LAST_MESSAGE, PASTE, TYPE_TEXT, SEND, CONVERSATION, UNSUPPORTED.",
                    "Current supported actions: open installed apps and Android Settings, Home, Back, scroll, copy visible text, paste clipboard text, type dictated text into the currently focused editable field, and click a clearly labelled Send/Submit button only after explicit user instruction; Google search and nearby food discovery handoffs are also supported.",
                    "Conversation and intent planning use configured Gemini/Groq keys, or the downloaded Qwen local model. Do not claim web pages are read, results compared, or tasks completed; searches are handed off to another app.",
                    "Never claim unsupported abilities such as making calls, reading notifications or private chats, arbitrary app control, booking, ordering food, buying, transferring money, making payments, deleting files, or changing security settings. Accessibility typing/sending works only when the foreground app exposes a focused editable field and a clearly labelled enabled Send/Submit button.",
                    "Financial transactions and purchases are never allowed, even if the user confirms. Do not place food orders. For unsupported actions, use CONVERSATION and explain the limitation.",
                    "Never claim completion before the Android action runs. If details are missing, ask one short question using CONVERSATION."
                ).joinToString("\n")
                val cloud = AIProviderManager.plan(appContext, prompt, original)
                val selected = if (cloud.ok) parseAiIntent(cloud.message) else null
                if (selected != null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { executeIntent(appContext, selected) }
                } else if (LocalAiManager.isDownloaded(appContext)) {
                    LocalAiManager.interpretAsync(appContext, original) { localPlan ->
                        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        executeIntent(appContext, localPlan?.let { parseAiIntent(it) } ?: understand(command, prefs.getString("last_app", null)))
                    }
                } else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        executeIntent(appContext, understand(command, prefs.getString("last_app", null)))
                    }
                }
            }
            return
        }

        if (LocalAiManager.isDownloaded(appContext)) {
            statusOnly(appContext, "Thinking…")
            LocalAiManager.interpretAsync(appContext, original) { plan ->
                val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                executeIntent(appContext, plan?.let { parseAiIntent(it) } ?: understand(command, prefs.getString("last_app", null)))
            }
            return
        }

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        executeIntent(appContext, understand(command, prefs.getString("last_app", null)))
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
                "CONVERSATION" -> BrainIntent("CONVERSATION", reply ?: "Tell me a little more about what you want me to do.")
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
        if (containsAny(t, "do that again", "again", "repeat that", "மீண்டும் செய்", "thirumba pannu")) {
            return when (lastApp) {
                null -> BrainIntent("CONVERSATION", "I don't have a previous action to repeat yet.")
                else -> BrainIntent("OPEN_APP", lastApp)
            }
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
