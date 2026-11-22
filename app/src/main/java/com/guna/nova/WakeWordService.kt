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
        NovaOverlayService.status(this, message)
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
        NovaOverlayService.show(this)
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
        if (intent?.action == ACTION_ENROLL) Thread({ enrollVoice() }, "NovaEnrollment").start()
        else if (identity?.isEnrolled() != true) {
            report("Set up your voice profile first. Speaker verification is separate from speech recognition.", false)
            active = false
            stopSelf()
        } else startSpeechRecognition()
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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            report("No speech recognition provider found. Enable Google's speech services.", false)
            active = false; stopSelf(); return
        }
        try {
            recognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            else SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { recognitionActive = true; report("Listening for Tamil, English and Tanglish. Speak naturally.") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (partial.isNotBlank()) NovaOverlayService.userPartial(this@WakeWordService, partial)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onError(error: Int) {
                    recognitionActive = false
                    if (active && !speechPaused) handler.postDelayed({ listenAgain() },
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 500L)
                }
                override fun onResults(results: Bundle?) {
                    recognitionActive = false
                    if (!active) return
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (phrase.isNotBlank()) {
                        val now = SystemClock.elapsedRealtime()
                        val normalized = phrase.lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()
                        val isRecentEcho = normalized == lastHandledPhrase && now - lastHandledAt < 1200L
                        if (isRecentEcho) {
                            Log.i(TAG, "Ignored repeated recognition within echo window: $phrase")
                        } else {
                            lastHandledPhrase = normalized
                            lastHandledAt = now
                            NovaOverlayService.userFinal(this@WakeWordService, phrase)
                            report("Heard: $phrase")
                            try { NovaCommand.execute(this@WakeWordService, phrase) }
                            catch (e: Exception) { Log.e(TAG, "Command failed", e); report("Command failed: ${e.message}") }
                        }
                    }
                    if (!speechPaused) handler.postDelayed({ listenAgain() }, 350L)
                }
            })
            listenAgain()
        } catch (e: Exception) {
            Log.e(TAG, "Recognizer start failed", e); report("Speech recognition failed: ${e.message}", false)
            active = false; stopSelf()
        }
    }

    private fun listenAgain() {
        if (!active || speechPaused || recognitionActive) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            if (Build.VERSION.SDK_INT >= 34) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, arrayListOf("ta-IN", "en-IN"))
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("ta-IN", "en-IN"))
            }
        }
        try {
            recognitionActive = true
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            recognitionActive = false
            Log.w(TAG, "startListening retry", e)
            handler.postDelayed({ listenAgain() }, 1000L)
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
                recognitionActive = false
                listenAgain()
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
        getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
            .putBoolean("running", false).putString("status", "Listening stopped.").apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(notificationId)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}