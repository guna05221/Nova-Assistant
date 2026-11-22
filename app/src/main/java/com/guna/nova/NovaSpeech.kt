package com.guna.nova

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Single speech output controller. Recognition is paused before TTS and resumed
 * only after the utterance has completed, preventing Nova from hearing its own voice.
 */
object NovaSpeech {
    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var pending: String? = null
    @Volatile private var initialized = false

    @Synchronized
    fun speak(context: Context, message: String) {
        val text = message.trim()
        if (text.isBlank()) return
        WakeWordService.pauseForSpeech()
        val current = engine
        if (current == null) {
            pending = text
            engine = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    val tts = engine ?: return@synchronized
                    if (status == TextToSpeech.SUCCESS) {
                        val language = tts.setLanguage(Locale.getDefault())
                        ready = language != TextToSpeech.LANG_MISSING_DATA &&
                            language != TextToSpeech.LANG_NOT_SUPPORTED
                        if (!ready) ready = tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) { WakeWordService.resumeAfterSpeech() }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) { WakeWordService.resumeAfterSpeech() }
                            override fun onError(utteranceId: String?, errorCode: Int) { WakeWordService.resumeAfterSpeech() }
                        })
                        initialized = true
                        val queued = pending
                        pending = null
                        if (ready && !queued.isNullOrBlank()) tts.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "nova-response")
                        else WakeWordService.resumeAfterSpeech()
                    } else {
                        initialized = true
                        pending = null
                        WakeWordService.resumeAfterSpeech()
                    }
                }
            }
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
        WakeWordService.resumeAfterSpeech()
    }

    @Synchronized
    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
        initialized = false
        pending = null
        WakeWordService.resumeAfterSpeech()
    }
}
