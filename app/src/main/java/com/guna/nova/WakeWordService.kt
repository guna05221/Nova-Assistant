package com.guna.nova

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
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

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(41, NotificationCompat.Builder(this, channelId)
            .setContentTitle("Nova is listening")
            .setContentText("Listening locally for Hey Nova")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build())
        StorageService.unpack(this, "model", "model",
            { loaded ->
                model = loaded
                try {
                    recognizer = Recognizer(loaded, 16000.0f, "[\"hey nova\"]")
                    speech = SpeechService(recognizer, 16000.0f)
                    speech?.startListening(this)
                } catch (_: IOException) { stopSelf() }
            },
            { stopSelf() })
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis?.contains("hey nova", ignoreCase = true) == true) {
            speech?.stop()
            NovaCommand.capture(this)
        }
    }
    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis?.contains("hey nova", ignoreCase = true) == true) {
            speech?.stop()
            NovaCommand.capture(this)
        }
    }
    override fun onFinalResult(hypothesis: String?) { }
    override fun onError(exception: Exception?) { }
    override fun onTimeout() {
        try { speech?.startListening(this) } catch (_: Exception) { }
    }
    override fun onDestroy() {
        speech?.stop()
        recognizer?.close()
        model?.close()
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
