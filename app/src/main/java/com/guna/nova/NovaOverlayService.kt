package com.guna.nova

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
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

/**
 * Compact, non-focusable Nova launcher chip.
 *
 * The overlay is intentionally not a chat window: it never captures keyboard input
 * or displays a partial conversation. Tapping it opens the full-screen chat Activity.
 * WakeWordService remains responsible for microphone capture.
 */
class NovaOverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "com.guna.nova.overlay.SHOW"
        const val ACTION_HIDE = "com.guna.nova.overlay.HIDE"
        const val ACTION_USER_PARTIAL = "com.guna.nova.overlay.USER_PARTIAL"
        const val ACTION_USER_FINAL = "com.guna.nova.overlay.USER_FINAL"
        const val ACTION_ASSISTANT = "com.guna.nova.overlay.ASSISTANT"
        const val ACTION_STATUS = "com.guna.nova.overlay.STATUS"
        const val EXTRA_TEXT = "text"

        fun show(context: android.content.Context) = send(context, ACTION_SHOW)
        fun hide(context: android.content.Context) = send(context, ACTION_HIDE)
        fun userPartial(context: android.content.Context, text: String) = send(context, ACTION_USER_PARTIAL, text)
        fun userFinal(context: android.content.Context, text: String) = send(context, ACTION_USER_FINAL, text)
        fun assistant(context: android.content.Context, text: String) = send(context, ACTION_ASSISTANT, text)
        fun status(context: android.content.Context, text: String) = send(context, ACTION_STATUS, text)

        private fun send(context: android.content.Context, action: String, text: String? = null) {
            val intent = Intent(context, NovaOverlayService::class.java).setAction(action)
            if (text != null) intent.putExtra(EXTRA_TEXT, text)
            try { context.startService(intent) } catch (_: Exception) { }
        }
    }

    private lateinit var windowManager: WindowManager
    private var chip: LinearLayout? = null
    private var statusView: TextView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var currentStatus = "Tap to chat with Nova"

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> { removeChip(); stopSelf() }
            ACTION_SHOW -> showChip()
            ACTION_USER_PARTIAL -> {
                showChip()
                setStatus("Listening…")
            }
            ACTION_USER_FINAL -> {
                showChip()
                setStatus("Thinking…")
            }
            ACTION_ASSISTANT -> {
                showChip()
                setStatus("Nova replied · Tap to chat")
            }
            ACTION_STATUS -> {
                showChip()
                setStatus(friendlyStatus(intent.getStringExtra(EXTRA_TEXT).orEmpty()))
            }
        }
        return START_NOT_STICKY
    }

    private fun showChip() {
        if (!Settings.canDrawOverlays(this) || chip != null) return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(10), dp(13), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(35, 35, 35))
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), Color.rgb(91, 83, 130))
            }
            elevation = dp(10).toFloat()
            contentDescription = "Open Nova full-screen chat"
        }

        val mark = TextView(this).apply {
            text = "✦"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(184, 167, 255))
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        val title = TextView(this).apply {
            text = "Nova"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        statusView = TextView(this).apply {
            text = currentStatus
            textSize = 11f
            setTextColor(Color.rgb(180, 180, 180))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        labels.addView(title)
        labels.addView(statusView)
        container.addView(mark, LinearLayout.LayoutParams(dp(22), -2))
        container.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))

        val close = TextView(this).apply {
            text = "×"
            textSize = 22f
            setTextColor(Color.rgb(190, 190, 190))
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(2), 0)
            contentDescription = "Close Nova floating button"
            setOnClickListener { hide(this@NovaOverlayService) }
        }
        container.addView(close, LinearLayout.LayoutParams(dp(30), dp(34)))

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            dp(220),
            dp(58),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(14)
            y = dp(105)
        }

        var downX = 0f
        var downY = 0f
        var initialX = 0
        var initialY = 0
        var dragged = false
        container.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) dragged = true
                    if (dragged) {
                        params.x = max(0, initialX - dx)
                        params.y = max(0, initialY - dy)
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) openFullChat()
                    true
                }
                else -> true
            }
        }

        try {
            windowManager.addView(container, params)
            chip = container
            windowParams = params
        } catch (_: Exception) {
            chip = null
            statusView = null
        }
    }

    private fun openFullChat() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        try { startActivity(launch) } catch (_: Exception) { }
    }

    private fun friendlyStatus(raw: String): String {
        val value = raw.lowercase()
        return when {
            value.contains("429") || value.contains("rate limit") -> "Service busy · Tap to retry in chat"
            value.contains("http") || value.contains("failed") || value.contains("error") -> "Something needs attention · Tap to chat"
            value.contains("listen") -> "Listening…"
            value.contains("speak") -> "Nova is speaking"
            value.contains("think") || value.contains("process") -> "Thinking…"
            else -> "Tap to chat with Nova"
        }
    }

    private fun setStatus(value: String) {
        currentStatus = value.take(48)
        statusView?.text = currentStatus
    }

    private fun removeChip() {
        val current = chip ?: return
        try { windowManager.removeView(current) } catch (_: Exception) { }
        chip = null
        statusView = null
        windowParams = null
    }

    override fun onDestroy() {
        removeChip()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
