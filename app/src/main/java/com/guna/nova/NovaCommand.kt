package com.guna.nova

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.util.Locale

object NovaCommand {
    fun execute(context: Context, raw: String) {
        val command = raw.lowercase(Locale.ROOT).trim()
        when {
            command == "open settings" || command == "settings" ->
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            command == "go back" -> NovaAccessibilityService.instance?.back()
            command == "go home" -> NovaAccessibilityService.instance?.home()
            command == "scroll down" -> NovaAccessibilityService.instance?.scroll(false)
            command == "scroll up" -> NovaAccessibilityService.instance?.scroll(true)
            command.startsWith("search online for ") -> search(context, command.removePrefix("search online for "))
            command.startsWith("look up ") -> search(context, command.removePrefix("look up "))
            command.startsWith("find online ") -> search(context, command.removePrefix("find online "))
            command.startsWith("search for ") -> search(context, command.removePrefix("search for "))
            command.startsWith("search ") -> search(context, command.removePrefix("search "))
            command.startsWith("write ") || command.startsWith("draft ") ||
                command.startsWith("create content ") -> shareWritingPrompt(context, raw)
            command.startsWith("open ") -> openApp(context, command.removePrefix("open ").trim())
            command.isNotBlank() -> search(context, command)
        }
    }

    private fun openApp(context: Context, label: String) {
        val apps = context.packageManager.getInstalledApplications(0)
        val match = apps.firstOrNull {
            context.packageManager.getApplicationLabel(it).toString().lowercase(Locale.ROOT).contains(label)
        }
        val launch = match?.let { context.packageManager.getLaunchIntentForPackage(it.packageName) }
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        } else search(context, "open $label")
    }

    private fun search(context: Context, query: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun shareWritingPrompt(context: Context, command: String) {
        val task = command.replaceFirst(Regex("(?i)^(write|draft|create content)\\s*"), "").trim()
        val prompt = "Write polished, useful content for this request. Ask for missing details if needed.\\n\\nRequest: $task"
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, prompt)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(share, "Continue writing with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
