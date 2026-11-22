package com.guna.nova

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Shared speech output for Nova. Uses the phone's installed TTS engine and
 * queues the latest response while the engine is initializing.
 */
object NovaSpeech {
    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var pending: String? = null

    @Synchronized
    fun speak(context: Context, message: String) {
        val text = message.trim()
        if (text.isBlank()) return
        val current = engine
        if (current == null) {
            engine = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    val tts = engine ?: return@synchronized
                    if (status == TextToSpeech.SUCCESS) {
                        val language = tts.setLanguage(Locale.getDefault())
                        ready = language != TextToSpeech.LANG_MISSING_DATA &&
                            language != TextToSpeech.LANG_NOT_SUPPORTED
                        if (!ready) ready = tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE
                        val queued = pending
                        pending = null
                        if (ready && !queued.isNullOrBlank()) {
                            tts.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "nova-response")
                        }
                    }
                }
            }
            pending = text
            return
        }
        if (!ready) {
            pending = text
            return
        }
        current.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova-response")
    }

    @Synchronized
    fun stop() {
        engine?.stop()
        pending = null
    }

    @Synchronized
    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
        pending = null
    }
}
