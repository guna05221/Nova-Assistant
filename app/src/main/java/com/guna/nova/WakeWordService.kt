package com.guna.nova

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.ByteArrayOutputStream

class WakeWordService : Service() {
    companion object {
        const val ACTION_ENROLL = "com.guna.nova.ENROLL_VOICE"
        private const val SAMPLE_RATE = 16000
        private const val TAG = "NovaVoice"
    }

    @Volatile private var active = false
    @Volatile private var enrollMode = false
    private var recorder: AudioRecord? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var identity: VoiceIdentity? = null
    private val channelId = "nova_listener"
    private val notificationId = 41

    private fun report(message: String, running: Boolean = true) {
        Log.i(TAG, message)
        getSharedPreferences("nova_diagnostics", MODE_PRIVATE).edit()
            .putString("status", message).putBoolean("running", running).apply()
        getSystemService(NotificationManager::class.java).notify(notificationId, notification(message))
    }

    private fun notification(message: String) =
        NotificationCompat.Builder(this, channelId)
            .setContentTitle("Nova voice assistant")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).setOnlyAlertOnce(true).build()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(notificationId, notification("Preparing offline voice models…"))
        StorageService.unpack(this, "model", "model",
            { loaded ->
                model = loaded
                try {
                    recognizer = Recognizer(loaded, SAMPLE_RATE.toFloat())
                    identity = VoiceIdentity(this)
                    if (enrollMode) startCapture() else startCapture()
                } catch (e: Exception) {
                    report("Voice setup failed: ${e.message}", false)
                    stopSelf()
                }
            },
            { error ->
                report("Offline speech model failed: ${error?.message ?: "unknown error"}", false)
                stopSelf()
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ENROLL) {
            enrollMode = true
            if (model != null && !active) startCapture()
            else if (active) report("Stop listening first, then tap Set up my voice.")
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (active) return
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) {
            report("Microphone is unavailable", false)
            stopSelf()
            return
        }
        val audio = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2
        )
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            report("Could not open microphone", false)
            stopSelf()
            return
        }
        recorder = audio
        active = true
        Thread({ captureLoop(audio) }, "NovaAudioCapture").start()
    }

    private fun captureLoop(audio: AudioRecord) {
        val buffer = ShortArray(1600) // 100 ms
        val enrollSamples = ArrayList<Float>()
        val utterance = ArrayList<Float>()
        var voicedChunks = 0
        try {
            audio.startRecording()
            if (enrollMode) {
                report("Voice setup: speak naturally for 8 seconds.")
                val deadline = System.currentTimeMillis() + 8000
                while (active && System.currentTimeMillis() < deadline) {
                    val n = audio.read(buffer, 0, buffer.size)
                    if (n > 0) for (i in 0 until n) enrollSamples.add(buffer[i] / 32768f)
                }
                if (active && enrollSamples.size >= SAMPLE_RATE * 4) {
                    identity?.saveProfile(listOf(enrollSamples.toFloatArray()))
                    enrollMode = false
                    report("Your voice profile is saved on this phone. Start listening to use voice matching.")
                } else if (active) {
                    report("Voice setup was too short. Please try again.", false)
                }
                active = false
                stopSelf()
                return
            }

            if (identity?.isEnrolled() != true) {
                report("Set up your voice before enabling voice-only commands.", false)
                active = false
                stopSelf()
                return
            }
            report("Listening. Only your enrolled voice can trigger commands.")
            val rec = recognizer ?: throw IllegalStateException("Speech recognizer unavailable")
            while (active) {
                val n = audio.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    bytes[i * 2] = (buffer[i].toInt() and 0xff).toByte()
                    bytes[i * 2 + 1] = (buffer[i].toInt() shr 8).toByte()
                    utterance.add(buffer[i] / 32768f)
                    if (kotlin.math.abs(buffer[i].toInt()) > 450) voicedChunks++
                }
                val endpoint = rec.acceptWaveForm(bytes, bytes.size)
                if (endpoint) {
                    val result = rec.result
                    val spoken = try { JSONObject(result).optString("text", "") } catch (_: Exception) { "" }
                    val samples = utterance.toFloatArray()
                    if (spoken.isNotBlank() && samples.size >= SAMPLE_RATE * 2 && voicedChunks >= 8) {
                        report("Checking speaker…")
                        val mine = try { identity?.matches(samples) == true } catch (e: Exception) {
                            Log.e(TAG, "Speaker check failed", e); false
                        }
                        if (mine) {
                            report("Your voice recognised: $spoken")
                            NovaCommand.execute(this, spoken)
                        } else {
                            report("Another voice detected. Ignored.")
                        }
                    }
                    utterance.clear()
                    voicedChunks = 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture error", e)
            if (active) report("Audio error: ${e.message}", false)
        } finally {
            try { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() } catch (_: Exception) {}
            audio.release()
            if (recorder === audio) recorder = null
            active = false
        }
    }

    override fun onDestroy() {
        active = false
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        recognizer?.close()
        model?.close()
        identity?.close()
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
