package com.guna.nova

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class WakeWordService : Service(), RecognitionListener {
    private var speech: SpeechService? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private val channelId = "nova_listener"
    private val notificationId = 41

    private fun report(message: String, active: Boolean = true) {
        Log.i("NovaWakeWord", message)
        getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
            .putString("status", message).putBoolean("running", active).apply()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification(message))
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
            startForeground(notificationId, notification("Starting microphone service…"))
            report("Service started; preparing offline voice model…")
            StorageService.unpack(this, "model", "model",
                { loaded ->
                    model = loaded
                    try {
                        report("Offline model loaded; opening microphone…")
                        recognizer = Recognizer(loaded, 16000.0f, """["hey nova", "dai"]""")
                        speech = SpeechService(recognizer, 16000.0f)
                        val started = speech?.startListening(this) ?: false
                        if (started) report("Microphone active; say Hey Nova or Dai")
                        else report("Vosk could not start microphone capture", false)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onResult(hypothesis: String?) {
        Log.d("NovaWakeWord", "Final: $hypothesis")
        if (hypothesis?.let { it.contains("hey nova", ignoreCase = true) || it.contains("dai", ignoreCase = true) } == true) {
            report("Wake phrase detected; opening command listener…")
            speech?.stop()
            NovaCommand.capture(this)
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        Log.d("NovaWakeWord", "Partial: $hypothesis")
        if (hypothesis?.contains("hey nova", ignoreCase = true) == true) {
            report("Wake phrase detected; opening command listener…")
            speech?.stop()
            NovaCommand.capture(this)
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        Log.d("NovaWakeWord", "Final result: $hypothesis")
    }

    override fun onError(exception: Exception?) {
        report("Microphone recognition error: ${exception?.javaClass?.simpleName}: ${exception?.message}", false)
    }

    override fun onTimeout() {
        try {
            val started = speech?.startListening(this) ?: false
            if (started) report("Microphone active; say Hey Nova")
            else report("Microphone listener timed out and could not restart", false)
        } catch (e: Exception) {
            report("Microphone restart failed: ${e.message}", false)
        }
    }

    override fun onDestroy() {
        speech?.stop()
        recognizer?.close()
        model?.close()
        val prefs = getSharedPreferences("nova_diagnostics", MODE_PRIVATE)
        val oldStatus = prefs.getString("status", "") ?: ""
        // Preserve the real startup/recognition error instead of hiding it.
        if (oldStatus == "Microphone active; say Hey Nova" ||
            oldStatus.startsWith("Service started") ||
            oldStatus.startsWith("Offline model loaded")) {
            prefs.edit().putBoolean("running", false)
                .putString("status", "Service stopped unexpectedly. Check the latest Nova notification.")
                .apply()
        } else {
            prefs.edit().putBoolean("running", false).apply()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(notificationId)
        Log.i("NovaWakeWord", "Service destroyed; previous status=$oldStatus")
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
