package com.guna.nova

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Nova's on-screen floating control and message-approval surface.
 * Message approvals are rendered here only; there is deliberately no notification fallback.
 */
class NovaOverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "com.guna.nova.overlay.SHOW"
        const val ACTION_APPROVAL_REQUEST = "com.guna.nova.overlay.APPROVAL_REQUEST"
        const val ACTION_APPROVAL_CLEAR = "com.guna.nova.overlay.APPROVAL_CLEAR"
        const val ACTION_STATUS = "com.guna.nova.overlay.STATUS"

        private const val EXTRA_ID = "approval_id"
        private const val EXTRA_PACKAGE = "target_package"
        private const val EXTRA_RECIPIENT = "recipient"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_EXPIRES = "expires_at"
        private const val EXTRA_STATUS = "status"

        fun show(context: Context) = start(context, ACTION_SHOW)

        fun requestApproval(
            context: Context,
            approvalId: String,
            packageName: String,
            recipient: String,
            message: String,
            expiresAtElapsed: Long
        ): Boolean {
            val intent = Intent(context, NovaOverlayService::class.java).apply {
                action = ACTION_APPROVAL_REQUEST
                putExtra(EXTRA_ID, approvalId)
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_RECIPIENT, recipient)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_EXPIRES, expiresAtElapsed)
            }
            return try { context.startService(intent) != null } catch (_: Exception) { false }
        }

        fun clearApproval(context: Context, approvalId: String) {
            val intent = Intent(context, NovaOverlayService::class.java).apply {
                action = ACTION_APPROVAL_CLEAR
                putExtra(EXTRA_ID, approvalId)
            }
            try { context.startService(intent) } catch (_: Exception) { }
        }

        fun status(context: Context, value: String) {
            val intent = Intent(context, NovaOverlayService::class.java).apply {
                action = ACTION_STATUS
                putExtra(EXTRA_STATUS, value)
            }
            try { context.startService(intent) } catch (_: Exception) { }
        }

        private fun start(context: Context, action: String) {
            try { context.startService(Intent(context, NovaOverlayService::class.java).setAction(action)) }
            catch (_: Exception) { }
        }
    }

    private data class ApprovalCard(
        val id: String,
        val packageName: String,
        val recipient: String,
        val message: String,
        val expiresAtElapsed: Long
    )

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var panel: View? = null
    private var expanded = false
    private var approval: ApprovalCard? = null
    private var statusText = "Ready"
    private var expiryView: TextView? = null
    private val waveFrames = arrayOf("◖", "◖)", "◖))", "◖)))", "◖))", "◖)")
    private var waveIndex = 0

    private val waveAnimator = object : Runnable {
        override fun run() {
            if (panel != null && approval == null) {
                waveIndex = (waveIndex + 1) % waveFrames.size
                panel?.findViewWithTag<TextView>("nova_wave")?.text = waveFrames[waveIndex] + "  NOVA"
                handler.postDelayed(this, 320L)
            }
        }
    }

    private val expiryTicker = object : Runnable {
        override fun run() {
            val current = approval ?: return
            val remaining = current.expiresAtElapsed - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                NovaCommand.expirePendingSend(this@NovaOverlayService, current.id)
            } else {
                expiryView?.text = "Expires in ${(remaining + 999L) / 1000L}s"
                handler.postDelayed(this, 400L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                if (Settings.canDrawOverlays(this)) {
                    expanded = false
                    render()
                }
            }
            ACTION_APPROVAL_REQUEST -> {
                if (Settings.canDrawOverlays(this)) {
                    approval = ApprovalCard(
                        id = intent.getStringExtra(EXTRA_ID).orEmpty(),
                        packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty(),
                        recipient = intent.getStringExtra(EXTRA_RECIPIENT).orEmpty(),
                        message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty(),
                        expiresAtElapsed = intent.getLongExtra(EXTRA_EXPIRES, 0L)
                    )
                    expanded = true
                    render()
                    handler.removeCallbacks(expiryTicker)
                    handler.post(expiryTicker)
                }
            }
            ACTION_APPROVAL_CLEAR -> {
                val id = intent.getStringExtra(EXTRA_ID).orEmpty()
                if (approval?.id == id) {
                    approval = null
                    expiryView = null
                    handler.removeCallbacks(expiryTicker)
                    expanded = false
                    render()
                }
            }
            ACTION_STATUS -> {
                statusText = intent.getStringExtra(EXTRA_STATUS).orEmpty().take(40)
                if (approval == null && panel != null) render()
            }
        }
        return START_NOT_STICKY
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(20).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun render() {
        if (!Settings.canDrawOverlays(this)) {
            removePanel()
            return
        }
        removePanel()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(20, 26, 43), Color.rgb(105, 96, 170))
            elevation = dp(14).toFloat()
        }

        if (!expanded && approval == null) {
            val bubble = TextView(this).apply {
                tag = "nova_wave"
                text = waveFrames[waveIndex] + "  NOVA"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(17), dp(12), dp(17), dp(12))
                background = rounded(Color.rgb(91, 77, 180), Color.rgb(169, 155, 255))
                setOnClickListener { expanded = true; render() }
            }
            card.addView(bubble)
        } else {
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(text("◖)))  NOVA", 15f, Color.WHITE, true),
                LinearLayout.LayoutParams(0, dp(32), 1f))
            if (approval == null) {
                val minimize = Button(this).apply {
                    text = "Minimise"
                    textSize = 10f
                    setOnClickListener { expanded = false; render() }
                }
                header.addView(minimize)
            }
            card.addView(header)

            val pending = approval
            if (pending != null) {
                card.addView(text("MESSAGE APPROVAL", 11f, Color.rgb(169, 155, 255), true))
                card.addView(text("Recipient shown in the app", 11f, Color.LTGRAY).apply {
                    setPadding(0, dp(8), 0, dp(2))
                })
                card.addView(text(pending.recipient, 15f, Color.WHITE, true))
                card.addView(text("Exact message", 11f, Color.LTGRAY).apply {
                    setPadding(0, dp(10), 0, dp(3))
                })
                val messageScroll = ScrollView(this).apply {
                    isFillViewport = false
                    background = rounded(Color.rgb(38, 47, 70))
                    setPadding(dp(9), dp(8), dp(9), dp(8))
                }
                messageScroll.addView(text(pending.message, 14f, Color.WHITE))
                card.addView(messageScroll, LinearLayout.LayoutParams(-1, dp(112)))
                expiryView = text("", 11f, Color.rgb(255, 205, 120)).apply {
                    setPadding(0, dp(6), 0, dp(4))
                }
                card.addView(expiryView)

                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }
                val deny = Button(this).apply {
                    text = "Deny"
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        NovaCommand.resolvePendingSend(this@NovaOverlayService, pending.id, false)
                    }
                }
                val allow = Button(this).apply {
                    text = "Allow"
                    setTextColor(Color.BLACK)
                    setBackgroundColor(Color.rgb(101, 229, 213))
                    setOnClickListener {
                        NovaCommand.resolvePendingSend(this@NovaOverlayService, pending.id, true)
                    }
                }
                actions.addView(deny)
                actions.addView(allow)
                card.addView(actions)
            } else {
                card.addView(text(statusText, 12f, Color.rgb(101, 229, 213)))
                card.addView(text("Tap the speaker button to return to the compact floating control.", 11f, Color.LTGRAY))
            }
        }

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val width = if (expanded || approval != null) dp(300) else WindowManager.LayoutParams.WRAP_CONTENT
        val layout = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(14)
            y = dp(110)
        }
        try {
            windowManager.addView(card, layout)
            panel = card
            if (approval == null && expanded.not()) {
                handler.removeCallbacks(waveAnimator)
                handler.postDelayed(waveAnimator, 320L)
            } else handler.removeCallbacks(waveAnimator)
            if (approval != null) handler.post(expiryTicker)
        } catch (_: Exception) {
            panel = null
            approval?.let { NovaCommand.onApprovalSurfaceUnavailable(this, it.id) }
        }
    }

    private fun removePanel() {
        handler.removeCallbacks(waveAnimator)
        panel?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        panel = null
        expiryView = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removePanel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
