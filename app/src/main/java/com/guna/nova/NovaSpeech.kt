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
    @Volatile private var currentSpeechText: String = ""
    private val utteranceSequence = AtomicLong(0L)

    @Synchronized
    fun speak(context: Context, message: String) {
        val text = message.trim()
        if (text.isBlank()) return

        val current = engine
        if (current == null) {
            pending = text
            engine = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    val tts = engine ?: return@synchronized
                    if (status == TextToSpeech.SUCCESS) {
                        ready = true
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

    private fun selectVoice(tts: TextToSpeech, text: String): Boolean {
        val preferred = if (Regex("[\\u0B80-\\u0BFF]").containsMatchIn(text)) {
            Locale("ta", "IN")
        } else {
            Locale("en", "IN")
        }
        fun available(status: Int) = status != TextToSpeech.LANG_MISSING_DATA &&
            status != TextToSpeech.LANG_NOT_SUPPORTED
        if (available(tts.setLanguage(preferred))) return true
        if (available(tts.setLanguage(Locale.getDefault()))) return true
        return available(tts.setLanguage(Locale.US))
    }

    @Synchronized
    private fun speakReady(tts: TextToSpeech, text: String) {
        if (!selectVoice(tts, text)) {
            activeUtteranceId = null
            currentSpeechText = ""
            WakeWordService.resumeAfterSpeech()
            return
        }
        val id = "nova-response-${utteranceSequence.incrementAndGet()}"
        activeUtteranceId = id
        currentSpeechText = text
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) {
            activeUtteranceId = null
            currentSpeechText = ""
            WakeWordService.resumeAfterSpeech()
        }
    }

    fun isSpeaking(): Boolean = activeUtteranceId != null

    fun isLikelyEcho(recognized: String): Boolean {
        val heard = normalize(recognized)
        val spoken = normalize(currentSpeechText)
        if (heard.isBlank() || spoken.isBlank()) return false
        if (heard == spoken || spoken.contains(heard)) return true
        val heardWords = heard.split(" ").filter { it.length > 2 }.toSet()
        val spokenWords = spoken.split(" ").filter { it.length > 2 }.toSet()
        if (heardWords.size < 2) return false
        val overlap = heardWords.intersect(spokenWords).size.toFloat() / heardWords.size
        return overlap >= 0.75f
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    @Synchronized
    private fun finishUtterance(utteranceId: String?) {
        if (utteranceId != null && activeUtteranceId == utteranceId) {
            activeUtteranceId = null
            currentSpeechText = ""
            WakeWordService.resumeAfterSpeech()
        }
    }

    @Synchronized
    fun stop() {
        activeUtteranceId = null
        currentSpeechText = ""
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
