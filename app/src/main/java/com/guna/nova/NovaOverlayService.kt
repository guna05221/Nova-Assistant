package com.guna.nova

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.sin

/**
 * Voice-reactive floating Nova panel. The microphone remains owned by WakeWordService;
 * this service only renders transient voice state and never records audio itself.
 */
class NovaOverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "com.guna.nova.overlay.SHOW"
        const val ACTION_HIDE = "com.guna.nova.overlay.HIDE"
        const val ACTION_USER_PARTIAL = "com.guna.nova.overlay.USER_PARTIAL"
        const val ACTION_USER_FINAL = "com.guna.nova.overlay.USER_FINAL"
        const val ACTION_ASSISTANT = "com.guna.nova.overlay.ASSISTANT"
        const val ACTION_STATUS = "com.guna.nova.overlay.STATUS"
        const val ACTION_AUDIO_LEVEL = "com.guna.nova.overlay.AUDIO_LEVEL"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LEVEL = "level"
        @Volatile var chatScreenVisible: Boolean = false
        @Volatile private var activeService: NovaOverlayService? = null

        fun show(context: android.content.Context) = send(context, ACTION_SHOW)
        fun hide(context: android.content.Context) = send(context, ACTION_HIDE)
        fun userPartial(context: android.content.Context, text: String) = send(context, ACTION_USER_PARTIAL, text)
        fun userFinal(context: android.content.Context, text: String) = send(context, ACTION_USER_FINAL, text)
        fun assistant(context: android.content.Context, text: String) = send(context, ACTION_ASSISTANT, text)
        fun status(context: android.content.Context, text: String) = send(context, ACTION_STATUS, text)
        fun audioLevel(context: android.content.Context, level: Float) {
            // RMS callbacks arrive frequently; update the existing view directly.
            activeService?.updateAudioLevel(level)
        }

        private fun send(context: android.content.Context, action: String, text: String? = null) {
            val intent = Intent(context, NovaOverlayService::class.java).setAction(action)
            if (text != null) intent.putExtra(EXTRA_TEXT, text)
            try { context.startService(intent) } catch (_: Exception) { }
        }
    }

    private lateinit var windowManager: WindowManager
    private var panel: LinearLayout? = null
    private var statusView: TextView? = null
    private var transcriptView: TextView? = null
    private var orbView: VoiceOrbView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var currentStatus = "Tap to chat with Nova"
    private var currentTranscript = ""

    override fun onCreate() {
        super.onCreate()
        activeService = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private fun updateAudioLevel(level: Float) {
        if (panel == null && !chatScreenVisible) showPanel()
        orbView?.setAudioLevel(level)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> { removePanel(); stopSelf() }
            ACTION_SHOW -> showPanel()
            ACTION_AUDIO_LEVEL -> {
                showPanel()
                orbView?.setAudioLevel(intent.getFloatExtra(EXTRA_LEVEL, 0f))
            }
            ACTION_USER_PARTIAL -> {
                showPanel()
                currentTranscript = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                transcriptView?.text = currentTranscript
                setStatus("Listening")
                orbView?.setMode(VoiceOrbView.MODE_LISTENING)
            }
            ACTION_USER_FINAL -> {
                showPanel()
                currentTranscript = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                transcriptView?.text = currentTranscript
                setStatus("Thinking")
                orbView?.setMode(VoiceOrbView.MODE_THINKING)
            }
            ACTION_ASSISTANT -> {
                showPanel()
                currentTranscript = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                transcriptView?.text = currentTranscript
                setStatus("Nova is speaking")
                orbView?.setMode(VoiceOrbView.MODE_SPEAKING)
            }
            ACTION_STATUS -> {
                showPanel()
                val raw = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                setStatus(friendlyStatus(raw))
                val lower = raw.lowercase()
                orbView?.setMode(when {
                    lower.contains("speak") -> VoiceOrbView.MODE_SPEAKING
                    lower.contains("think") || lower.contains("process") || lower.contains("heard:") -> VoiceOrbView.MODE_THINKING
                    lower.contains("listen") || lower.contains("ready for speech") -> VoiceOrbView.MODE_LISTENING
                    else -> VoiceOrbView.MODE_IDLE
                })
            }
        }
        return START_NOT_STICKY
    }

    private fun showPanel() {
        if (chatScreenVisible) { removePanel(); return }
        if (!Settings.canDrawOverlays(this) || panel != null) return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 25, 38))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.rgb(91, 83, 150))
            }
            elevation = dp(14).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "✦  NOVA"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        statusView = TextView(this).apply {
            text = currentStatus
            textSize = 11f
            setTextColor(Color.rgb(164, 151, 255))
            gravity = Gravity.END
            maxLines = 1
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(28), 1f))
        header.addView(statusView, LinearLayout.LayoutParams(dp(130), dp(28)))
        card.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(3))
        }
        orbView = VoiceOrbView(this).apply {
            contentDescription = "Nova animated voice activity"
            setOnClickListener { openFullChat() }
        }
        content.addView(orbView, LinearLayout.LayoutParams(dp(82), dp(82)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val hint = TextView(this).apply {
            text = "Speak naturally"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        transcriptView = TextView(this).apply {
            text = currentTranscript.ifBlank { "I'm listening to you…" }
            textSize = 12f
            setTextColor(Color.rgb(190, 190, 205))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        labels.addView(hint)
        labels.addView(transcriptView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        content.addView(labels, LinearLayout.LayoutParams(0, -1, 1f))
        content.setOnClickListener { openFullChat() }
        card.addView(content, LinearLayout.LayoutParams(-1, dp(88)))

        val footer = TextView(this).apply {
            text = "Tap to open chat  ·  Drag header to move"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(130, 132, 154))
            setPadding(0, dp(3), 0, 0)
            setOnClickListener { openFullChat() }
        }
        card.addView(footer)

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            dp(290), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(90)
        }

        var downX = 0f
        var downY = 0f
        var initialX = 0
        var initialY = 0
        var dragged = false
        header.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    initialX = params.x; initialY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) dragged = true
                    if (dragged) {
                        params.x = max(0, initialX - dx)
                        params.y = max(0, initialY + dy)
                        try { windowManager.updateViewLayout(card, params) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> true
            }
        }

        try {
            windowManager.addView(card, params)
            panel = card
            windowParams = params
            orbView?.setMode(if (currentStatus.contains("listen", true)) VoiceOrbView.MODE_LISTENING else VoiceOrbView.MODE_IDLE)
        } catch (_: Exception) {
            panel = null; statusView = null; transcriptView = null; orbView = null
        }
    }

    private fun openFullChat() {
        removePanel()
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try { startActivity(launch) } catch (_: Exception) { }
    }

    private fun friendlyStatus(raw: String): String {
        val value = raw.lowercase()
        return when {
            value.contains("429") || value.contains("rate limit") -> "AI service busy"
            value.contains("http") || value.contains("failed") || value.contains("error") -> "Needs attention"
            value.contains("listen") -> "Listening"
            value.contains("speak") -> "Nova is speaking"
            value.contains("think") || value.contains("process") || value.contains("heard:") -> "Thinking"
            else -> "Tap to chat with Nova"
        }
    }

    private fun setStatus(value: String) {
        currentStatus = value.take(48)
        statusView?.text = currentStatus
    }

    private fun removePanel() {
        val current = panel ?: return
        try { windowManager.removeView(current) } catch (_: Exception) { }
        panel = null; statusView = null; transcriptView = null; orbView = null; windowParams = null
    }

    override fun onDestroy() {
        removePanel()
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Lightweight Canvas orb: microphone RMS drives its pulse; no extra model/API calls. */
private class VoiceOrbView(context: android.content.Context) : View(context) {
    companion object {
        const val MODE_IDLE = 0
        const val MODE_LISTENING = 1
        const val MODE_THINKING = 2
        const val MODE_SPEAKING = 3
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var audioLevel = 0f
    private var mode = MODE_IDLE
    private var frame = 0
    private val density = resources.displayMetrics.density

    fun setAudioLevel(value: Float) {
        // SpeechRecognizer RMS values are typically small dB values; map gently to 0..1.
        audioLevel = (((value + 2f) / 12f).coerceIn(0f, 1f))
        invalidate()
    }

    fun setMode(value: Int) {
        mode = value
        if (value != MODE_LISTENING) audioLevel = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        frame++
        val cx = width / 2f
        val cy = height / 2f
        val base = 19f * density
        val wave = if (mode == MODE_LISTENING) audioLevel else if (mode == MODE_SPEAKING) .35f else .08f
        val time = frame * .12f
        val pulse = (sin(time.toDouble()).toFloat() + 1f) / 2f
        val radius = base * (1f + wave * .42f + pulse * if (mode == MODE_IDLE) .035f else .10f)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(28, 153, 133, 255)
        canvas.drawCircle(cx, cy, base * 2.05f + pulse * 3f * density, paint)
        paint.color = Color.argb(48, 153, 133, 255)
        canvas.drawCircle(cx, cy, base * 1.62f + wave * 8f * density, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = if (mode == MODE_THINKING) Color.rgb(101, 229, 213) else Color.rgb(165, 148, 255)
        canvas.drawCircle(cx, cy, base * (1.32f + pulse * .10f), paint)

        paint.style = Paint.Style.FILL
        paint.color = when (mode) {
            MODE_THINKING -> Color.rgb(101, 229, 213)
            MODE_SPEAKING -> Color.rgb(192, 174, 255)
            else -> Color.rgb(151, 133, 255)
        }
        canvas.drawCircle(cx, cy, radius, paint)

        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 18f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("✦", cx, cy + 6f * density, paint)

        // A small moving sound-wave halo makes activity visible even at low input volume.
        paint.color = Color.argb(210, 207, 197, 255)
        for (i in 0 until 9) {
            val angle = (i * Math.PI / 4.5) + time
            val bar = (3f + wave * 9f + (sin(time + i).toFloat() + 1f) * 2f) * density
            val x = cx + kotlin.math.cos(angle).toFloat() * (base * 1.78f)
            val y = cy + kotlin.math.sin(angle).toFloat() * (base * 1.78f)
            canvas.drawCircle(x, y, bar * .28f, paint)
        }
        if (isAttachedToWindow) postInvalidateDelayed(32L)
    }
}
