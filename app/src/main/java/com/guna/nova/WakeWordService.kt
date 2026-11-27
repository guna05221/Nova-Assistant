package com.guna.nova

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.speech.*
import android.util.Log
import androidx.core.app.NotificationCompat

class WakeWordService : Service() {
    companion object {
        const val ACTION_ENROLL = "com.guna.nova.ENROLL_VOICE"
        const val ACTION_STOP = "com.guna.nova.STOP_LISTENING"
        private const val RATE = 16000
        private const val TAG = "NovaVoice"
        @Volatile private var lastHandledPhrase: String = ""
        @Volatile private var lastHandledAt: Long = 0L
        @Volatile private var runningService: WakeWordService? = null
        fun pauseForSpeech() { runningService?.pauseRecognitionForSpeech() }
        fun resumeAfterSpeech() { runningService?.resumeRecognitionAfterSpeech() }
    }
    @Volatile private var active = false
    @Volatile private var speechPaused = false
    @Volatile private var recognitionActive = false
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var recorder: AudioRecord? = null
    private var identity: VoiceIdentity? = null
    private val channel = "nova_listener"
    private val notificationId = 41

    private fun report(message: String, running: Boolean = true) {
        Log.i(TAG, message)
        getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
            .putString("status", message).putBoolean("running", running).apply()
        getSystemService(NotificationManager::class.java).notify(notificationId, notification(message))
    }
    private fun notification(message: String) = NotificationCompat.Builder(this, channel)
        .setContentTitle("Nova voice assistant").setContentText(message)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true)
        .setOnlyAlertOnce(true).build()

    override fun onCreate() {
        super.onCreate()
        runningService = this
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "Nova voice listener", NotificationManager.IMPORTANCE_LOW))
        }
        val note = notification("Preparing speech recognition…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(notificationId, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(notificationId, note)
        identity = VoiceIdentity(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            active = false
            speechPaused = false
            recognitionActive = false
            handler.removeCallbacksAndMessages(null)
            try { recognizer?.cancel() } catch (_: Exception) {}
            try { recorder?.stop() } catch (_: Exception) {}
            getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
                .putBoolean("running", false).putString("status", "Listening stopped.").apply()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        active = true
        getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
            .putBoolean("running", true).putString("status", "Starting microphone…").apply()

        val voiceIdentity = identity
        if (intent?.action == ACTION_ENROLL) {
            if (voiceIdentity?.isAvailable != true) {
                report("Voice enrollment unavailable: the local speaker-verification model is missing.", false)
                active = false
                stopSelf(startId)
                return START_NOT_STICKY
            }
            Thread({ enrollVoice() }, "NovaEnrollment").start()
        } else if (VoiceIdentity.requiresEnrollment(
                modelAvailable = voiceIdentity?.isAvailable == true,
                enrolled = voiceIdentity?.isEnrolled() == true
            )) {
            report("Set up your voice profile first. Speaker verification is separate from speech recognition.", false)
            active = false
            stopSelf()
        } else {
            startSpeechRecognition()
        }
        return START_NOT_STICKY
    }

    // Captures a speaker profile separately; it is not used as a per-command gate yet.
    private fun enrollVoice() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { report("Microphone unavailable for voice setup.", false); active = false; stopSelf(); return }
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, min * 2)
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release(); report("Could not open microphone for voice setup.", false); active = false; stopSelf(); return
        }
        recorder = audio
        val samples = ArrayList<Float>()
        val buffer = ShortArray(1600)
        try {
            audio.startRecording()
            report("Voice setup: speak naturally for 8 seconds.")
            val until = System.currentTimeMillis() + 8000
            while (active && System.currentTimeMillis() < until) {
                val n = audio.read(buffer, 0, buffer.size)
                if (n > 0) for (i in 0 until n) samples.add(buffer[i] / 32768f)
            }
            if (active && samples.size >= RATE * 4) {
                identity?.saveProfile(listOf(samples.toFloatArray()))
                report("Voice profile saved on this phone. Speaker verification is kept separate.")
            } else if (active) report("Voice setup was too short. Please try again.", false)
        } catch (e: Exception) {
            Log.e(TAG, "Enrollment failed", e); report("Voice setup failed: ${e.message}", false)
        } finally {
            try { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() } catch (_: Exception) {}
            audio.release(); recorder = null; active = false; stopSelf()
        }
    }

    private fun startSpeechRecognition() {
        Thread({
            try {
                val loaded = kotlinx.coroutines.runBlocking {
                    ParakeetAsrManager.loadIfInstalled(this@WakeWordService)
                }
                if (!loaded) {
                    report("Parakeet Unified EN 0.6B is not installed. Open Nova and download the local voice model first.", false)
                    active = false
                    stopSelf()
                    return@Thread
                }
                captureWithParakeet()
            } catch (e: Exception) {
                Log.e(TAG, "Parakeet startup failed", e)
                report("Parakeet startup failed: ${e.message ?: e.javaClass.simpleName}", false)
                active = false
                stopSelf()
            }
        }, "NovaParakeetStartup").start()
    }

    private fun updateTranscript(text: String, final: Boolean = false) {
        getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
            .putString(if (final) "finalTranscript" else "liveTranscript", text)
            .putString("asrEngine", "Parakeet Unified EN 0.6B INT8 · local")
            .apply()
    }

    /**
     * Parakeet Unified's published Sherpa ONNX export is offline-only.
     * We emulate live transcription by decoding snapshots of the current utterance,
     * then decode once more when VAD detects the end of speech.
     */
    private fun captureWithParakeet() {
        val minBuffer = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) error("AudioRecord returned invalid buffer size")
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 3, RATE * 2)
        )
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            error("Could not initialize microphone")
        }
        recorder = audio
        val frame = ShortArray(1600)
        val utterance = ArrayList<Float>(RATE * 12)
        var speaking = false
        var silenceMs = 0L
        var speechMs = 0L
        var lastPartialAt = 0L
        val silenceThreshold = 0.012
        val endSilenceMs = 700L
        val partialIntervalMs = 2500L
        val maxUtteranceMs = 25000L

        try {
            audio.startRecording()
            recognitionActive = true
            report("Listening with Parakeet Unified EN 0.6B — offline English")
            updateTranscript("")
            while (active) {
                if (speechPaused) {
                    audio.read(frame, 0, frame.size)
                    continue
                }
                val count = audio.read(frame, 0, frame.size)
                if (count <= 0) {
                    if (count < 0) Log.w(TAG, "AudioRecord read error: $count")
                    continue
                }
                var energy = 0.0
                for (i in 0 until count) {
                    val sample = frame[i].toFloat() / 32768f
                    energy += sample * sample
                }
                val rms = kotlin.math.sqrt(energy / count)
                val frameMs = count * 1000L / RATE

                if (rms >= silenceThreshold) {
                    speaking = true
                    silenceMs = 0L
                    speechMs += frameMs
                    for (i in 0 until count) utterance.add(frame[i] / 32768f)
                } else if (speaking) {
                    silenceMs += frameMs
                    for (i in 0 until count) utterance.add(frame[i] / 32768f)
                }

                val now = SystemClock.elapsedRealtime()
                if (speaking && speechMs >= 700L && now - lastPartialAt >= partialIntervalMs) {
                    lastPartialAt = now
                    val snapshot = utterance.toFloatArray()
                    try {
                        val partial = kotlinx.coroutines.runBlocking {
                            ParakeetAsrManager.transcribe(snapshot)
                        }
                        if (partial.isNotBlank()) {
                            updateTranscript(partial)
                            report("Live transcript updated")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Interim transcription failed", e)
                    }
                }

                if (speaking && ((silenceMs >= endSilenceMs && speechMs >= 300L) || speechMs >= maxUtteranceMs)) {
                    val samples = utterance.toFloatArray()
                    try {
                        val phrase = kotlinx.coroutines.runBlocking {
                            ParakeetAsrManager.transcribe(samples)
                        }
                        if (phrase.isNotBlank()) {
                            val understood = SpeechUnderstanding.process(this, phrase, null)
                            val command = understood.transcript
                            updateTranscript(phrase, final = true)
                            report("Heard: $phrase")
                            val nowElapsed = SystemClock.elapsedRealtime()
                            val normalized = command.lowercase(java.util.Locale.ROOT)
                                .replace(Regex("\\s+"), " ").trim()
                            val repeated = normalized == lastHandledPhrase && nowElapsed - lastHandledAt < 1200L
                            if (!repeated) {
                                lastHandledPhrase = normalized
                                lastHandledAt = nowElapsed
                                NovaCommand.execute(this, command)
                            }
                        } else {
                            updateTranscript("(No speech recognized)", final = true)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Final transcription failed", e)
                        report("Transcription failed: ${e.message}", false)
                    }
                    utterance.clear()
                    speaking = false
                    silenceMs = 0L
                    speechMs = 0L
                    lastPartialAt = 0L
                    updateTranscript("")
                }
            }
        } finally {
            recognitionActive = false
            try { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() } catch (_: Exception) {}
            audio.release()
            recorder = null
        }
    }

    private fun pauseRecognitionForSpeech() {
        handler.post {
            speechPaused = true
            recognitionActive = false
            try { recognizer?.cancel() } catch (_: Exception) {}
        }
    }

    private fun resumeRecognitionAfterSpeech() {
        handler.postDelayed({
            if (active) {
                speechPaused = false
                recognitionActive = true
                report("Listening with Parakeet Unified EN 0.6B — offline English")
            }
        }, 500L)
    }

    override fun onDestroy() {
        if (runningService === this) runningService = null
        active = false; speechPaused = false; recognitionActive = false; handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        identity?.close(); identity = null
        ParakeetAsrManager.release()
        getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
            .putBoolean("running", false).putString("status", "Listening stopped.").apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(notificationId)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
