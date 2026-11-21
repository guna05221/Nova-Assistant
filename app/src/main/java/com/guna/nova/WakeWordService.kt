package com.guna.nova

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class WakeWordService : Service(), RecognitionListener {
    private var speech: SpeechService? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val channelId = "nova_listener"
    private val notificationId = 41
    @Volatile private var active = false

    private fun report(message: String, running: Boolean = true) {
        Log.i("NovaVoice", message)
        getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
            .putString("status", message).putBoolean("running", running).apply()
        getSystemService(NotificationManager::class.java).notify(notificationId, notification(message))
    }

    private fun notification(message: String) =
        NotificationCompat.Builder(this, channelId)
            .setContentTitle("Nova voice assistant")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            startForeground(notificationId, notification("Starting continuous voice listening…"))
            report("Loading offline voice model…")
            StorageService.unpack(this, "model", "model",
                { loaded ->
                    model = loaded
                    try {
                        recognizer = Recognizer(loaded, 16000.0f)
                        active = true
                        startRecognition()
                    } catch (e: Exception) {
                        report("Voice startup error: ${e.javaClass.simpleName}: ${e.message}", false)
                        stopSelf()
                    }
                },
                { error ->
                    report("Offline model load failed: ${error?.message ?: "unknown error"}", false)
                    stopSelf()
                })
        } catch (e: Exception) {
            report("Foreground service failed: ${e.javaClass.simpleName}: ${e.message}", false)
            stopSelf()
        }
    }

    private fun startRecognition() {
        if (!active || recognizer == null) return
        try {
            speech?.stop()
            speech = SpeechService(recognizer, 16000.0f)
            val started = speech?.startListening(this) ?: false
            if (started) report("Listening continuously. Speak a command anytime.")
            else report("Could not start microphone capture", false)
        } catch (e: Exception) {
            report("Microphone restart failed: ${e.message}", false)
            handler.postDelayed({ startRecognition() }, 700)
        }
    }

    private fun restartRecognition() {
        if (!active) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ startRecognition() }, 350)
    }

    private fun processRecognizedText(hypothesis: String?) {
        if (!active || hypothesis.isNullOrBlank()) return
        val text = try { JSONObject(hypothesis).optString("text", "") } catch (_: Exception) { "" }
        if (text.isNotBlank()) {
            report("Heard: $text")
            NovaCommand.execute(this, text)
        }
        restartRecognition()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onResult(hypothesis: String?) {
        Log.d("NovaVoice", "Recognized: $hypothesis")
        processRecognizedText(hypothesis)
    }

    override fun onPartialResult(hypothesis: String?) {}
    override fun onFinalResult(hypothesis: String?) {
        Log.d("NovaVoice", "Final: $hypothesis")
    }

    override fun onError(exception: Exception?) {
        report("Recognition issue: ${exception?.message ?: "retrying"}")
        restartRecognition()
    }

    override fun onTimeout() {
        restartRecognition()
    }

    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        speech?.stop()
        recognizer?.close()
        model?.close()
        getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
            .putBoolean("running", false).putString("status", "Listening stopped.").apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(notificationId)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Nova voice listener", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
