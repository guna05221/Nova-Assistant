package com.guna.nova

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

object NovaCommand {
    fun capture(context: android.content.Context) {
        val intent = Intent(context, CommandCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

class CommandCaptureActivity : android.app.Activity() {
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            finish()
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { finish() }
            override fun onResults(results: Bundle?) {
                val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                handle(phrase)
                finish()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer?.startListening(request)
    }

    private fun handle(raw: String) {
        val command = raw.lowercase(Locale.ROOT).trim()
        when {
            command == "open settings" || command == "settings" ->
                startActivity(Intent(Settings.ACTION_SETTINGS))
            command == "go back" -> NovaAccessibilityService.instance?.back()
            command == "go home" -> NovaAccessibilityService.instance?.home()
            command == "scroll down" -> NovaAccessibilityService.instance?.scroll(false)
            command == "scroll up" -> NovaAccessibilityService.instance?.scroll(true)
            command.startsWith("search online for ") -> search(command.removePrefix("search online for "))
            command.startsWith("look up ") -> search(command.removePrefix("look up "))
            command.startsWith("find online ") -> search(command.removePrefix("find online "))
            command.startsWith("search for ") -> search(command.removePrefix("search for "))
            command.startsWith("search ") -> search(command.removePrefix("search "))
            command.startsWith("write ") || command.startsWith("draft ") ||
                command.startsWith("create content ") -> shareWritingPrompt(raw)
            command.startsWith("open ") -> openApp(command.removePrefix("open ").trim())
            command.isNotBlank() -> search(command)
        }
    }

    private fun openApp(label: String) {
        val apps = packageManager.getInstalledApplications(0)
        val match = apps.firstOrNull {
            packageManager.getApplicationLabel(it).toString().lowercase(Locale.ROOT).contains(label)
        }
        val launch = match?.let { packageManager.getLaunchIntentForPackage(it.packageName) }
        if (launch != null) startActivity(launch) else search("open $label")
    }

    private fun search(query: String) {
        startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
    }

    private fun shareWritingPrompt(command: String) {
        val task = command.replaceFirst(Regex("(?i)^(write|draft|create content)\\s*"), "").trim()
        val prompt = "Write polished, useful content for this request. Ask for missing details if needed.\\n\\nRequest: $task"
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, prompt)
        }
        startActivity(Intent.createChooser(share, "Continue writing with"))
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }
}
