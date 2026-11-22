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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * User-controlled, on-screen live conversation panel. Speech content is kept in memory only.
 * The microphone itself remains owned by WakeWordService and Android's privacy indicator.
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
        @Volatile private var service: NovaOverlayService? = null

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
    private var panel: LinearLayout? = null
    private var conversation: LinearLayout? = null
    private var statusView: TextView? = null
    private var scroll: ScrollView? = null
    private var params: WindowManager.LayoutParams? = null
    private var partialView: TextView? = null
    private val history = ArrayDeque<Pair<String, String>>()

    override fun onCreate() {
        super.onCreate()
        service = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> { removePanel(); stopSelf() }
            ACTION_SHOW -> showPanel()
            ACTION_USER_PARTIAL -> {
                showPanel()
                updatePartial(intent.getStringExtra(EXTRA_TEXT).orEmpty())
            }
            ACTION_USER_FINAL -> {
                showPanel()
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isNotBlank()) addTurn("YOU", text)
                removePartial()
                setStatus("Processing")
            }
            ACTION_ASSISTANT -> {
                showPanel()
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isNotBlank()) addTurn("NOVA", text)
                removePartial()
                setStatus("Speaking")
            }
            ACTION_STATUS -> {
                showPanel()
                setStatus(intent.getStringExtra(EXTRA_TEXT).orEmpty())
            }
        }
        return START_NOT_STICKY
    }

    private fun showPanel() {
        if (!Settings.canDrawOverlays(this)) return
        if (panel != null) return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(20, 26, 43))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(105, 96, 170))
            }
            elevation = dp(12).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "◉  NOVA"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        statusView = TextView(this).apply {
            text = "Listening"
            setTextColor(Color.rgb(101, 229, 213))
            textSize = 12f
            gravity = Gravity.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(28), 1f))
        header.addView(statusView, LinearLayout.LayoutParams(dp(105), dp(28)))
        card.addView(header)

        scroll = ScrollView(this).apply { isFillViewport = false }
        conversation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        scroll!!.addView(conversation)
        card.addView(scroll, LinearLayout.LayoutParams(-1, dp(190)))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val minimize = Button(this).apply {
            text = "Minimise"
            textSize = 11f
            setOnClickListener { removePanel() }
        }
        val close = Button(this).apply {
            text = "Close"
            textSize = 11f
            setOnClickListener { hide(this@NovaOverlayService) }
        }
        controls.addView(minimize)
        controls.addView(close)
        card.addView(controls)

        val windowType = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val windowParams = WindowManager.LayoutParams(
            max(resources.displayMetrics.widthPixels - dp(24), dp(260)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(42)
        }
        var startX = 0f
        var startY = 0f
        var initialX = 0
        var initialY = 0
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    initialX = windowParams.x; initialY = windowParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    windowParams.x = initialX + (event.rawX - startX).toInt()
                    windowParams.y = initialY + (event.rawY - startY).toInt()
                    try { windowManager.updateViewLayout(card, windowParams) } catch (_: Exception) { }
                    true
                }
                else -> true
            }
        }
        try {
            windowManager.addView(card, windowParams)
            panel = card
            params = windowParams
            history.forEach { (speaker, message) -> appendBubble(speaker, message) }
        } catch (_: Exception) {
            panel = null
        }
    }

    private fun addTurn(speaker: String, message: String) {
        history.addLast(speaker to message.take(700))
        while (history.size > 12) history.removeFirst()
        if (panel == null) showPanel()
        appendBubble(speaker, message.take(700))
    }

    private fun appendBubble(speaker: String, message: String) {
        val target = conversation ?: return
        val density = resources.displayMetrics.density
        val bubble = TextView(this).apply {
            text = "$speaker\n$message"
            textSize = 13f
            setTextColor(if (speaker == "YOU") Color.rgb(220, 225, 242) else Color.WHITE)
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                setColor(if (speaker == "YOU") Color.rgb(42, 51, 75) else Color.rgb(52, 48, 83))
                cornerRadius = 14 * density
            }
        }
        target.addView(bubble, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = (7 * density).toInt()
        })
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updatePartial(text: String) {
        if (text.isBlank()) return
        removePartial()
        val target = conversation ?: return
        partialView = TextView(this).apply {
            this.text = "YOU · $text"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(8, 8, 8, 8)
        }
        target.addView(partialView)
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
        setStatus("Listening")
    }

    private fun removePartial() {
        partialView?.let { conversation?.removeView(it) }
        partialView = null
    }

    private fun setStatus(value: String) {
        statusView?.text = value.take(24)
    }

    private fun removePanel() {
        val current = panel ?: return
        try { windowManager.removeView(current) } catch (_: Exception) { }
        panel = null
        conversation = null
        statusView = null
        scroll = null
        partialView = null
    }

    override fun onDestroy() {
        removePanel()
        if (service === this) service = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
