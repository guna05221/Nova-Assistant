package com.guna.nova

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Single speech output controller.
 *
 * Recognition is paused for the complete lifetime of the active utterance.
 * Utterance IDs prevent a cancelled QUEUE_FLUSH response from resuming the
 * microphone while a newer response is still speaking.
 */
object NovaSpeech {
    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var pending: String? = null
    @Volatile private var activeUtteranceId: String? = null
    private val utteranceSequence = AtomicLong(0L)

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
                        val languageStatus = tts.setLanguage(Locale.getDefault())
                        ready = languageStatus != TextToSpeech.LANG_MISSING_DATA &&
                            languageStatus != TextToSpeech.LANG_NOT_SUPPORTED
                        if (!ready) {
                            val fallbackStatus = tts.setLanguage(Locale.US)
                            ready = fallbackStatus != TextToSpeech.LANG_MISSING_DATA &&
                                fallbackStatus != TextToSpeech.LANG_NOT_SUPPORTED
                        }
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit

                            override fun onDone(utteranceId: String?) {
                                finishUtterance(utteranceId)
                            }

                            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                                finishUtterance(utteranceId)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                finishUtterance(utteranceId)
                            }

                            override fun onError(utteranceId: String?, errorCode: Int) {
                                finishUtterance(utteranceId)
                            }
                        })

                        val queued = pending
                        pending = null
                        if (ready && !queued.isNullOrBlank()) speakReady(tts, queued)
                        else {
                            activeUtteranceId = null
                            WakeWordService.resumeAfterSpeech()
                        }
                    } else {
                        ready = false
                        pending = null
                        activeUtteranceId = null
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
        speakReady(current, text)
    }

    @Synchronized
    private fun speakReady(tts: TextToSpeech, text: String) {
        val id = "nova-response-${utteranceSequence.incrementAndGet()}"
        activeUtteranceId = id
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) {
            activeUtteranceId = null
            WakeWordService.resumeAfterSpeech()
        }
    }

    @Synchronized
    private fun finishUtterance(utteranceId: String?) {
        if (utteranceId != null && activeUtteranceId == utteranceId) {
            activeUtteranceId = null
            WakeWordService.resumeAfterSpeech()
        }
    }

    @Synchronized
    fun stop() {
        activeUtteranceId = null
        pending = null
        engine?.stop()
        WakeWordService.resumeAfterSpeech()
    }

    @Synchronized
    fun shutdown() {
        activeUtteranceId = null
        pending = null
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
        WakeWordService.resumeAfterSpeech()
    }
}
