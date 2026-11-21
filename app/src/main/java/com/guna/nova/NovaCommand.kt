package com.guna.nova

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import java.util.Locale

/**
 * Local-first intent router. Unknown speech is never silently treated as a web search.
 * A short conversation memory is stored locally to resolve simple follow-ups.
 */
object NovaCommand {
    private const val PREFS = "nova_brain"

    fun execute(context: Context, raw: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val original = raw.trim()
        val command = normalize(original)
        if (command.isBlank()) return

        val lastApp = prefs.getString("last_app", null)
        val intent = understand(command, lastApp)

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
                if (!openApp(context, app)) say(context, "I couldn't find $app on this phone. Which app did you mean?")
                else remember(context, "OPEN_APP", app)
            }
            "SEARCH" -> {
                search(context, intent.value.orEmpty())
                remember(context, "SEARCH", intent.value)
            }
            "CONVERSATION" -> say(context, intent.value ?: "I'm listening. Tell me what you'd like me to do.")
            else -> say(context, "I heard: $original. I don't understand the action yet, so I won't open a browser. You can say open an app, go home, settings, or search for something.")
        }
    }

    private data class BrainIntent(val kind: String, val value: String? = null)

    private fun understand(text: String, lastApp: String?): BrainIntent {
        val t = text.lowercase(Locale.ROOT)
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

    private fun say(context: Context, message: String) {
        context.getSharedPreferences("nova_diagnostics", Context.MODE_PRIVATE).edit()
            .putString("status", message).putBoolean("running", true).apply()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
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
