package com.guna.nova

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.KeyEvent
import android.os.SystemClock
import androidx.core.content.ContextCompat

class NovaAccessibilityService : AccessibilityService() {
    companion object { var instance: NovaAccessibilityService? = null }
    private var volumeUpDownAt = 0L
    private var volumeUpActivated = false

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return super.onKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    volumeUpDownAt = event.downTime
                    volumeUpActivated = false
                }
                val heldFor = SystemClock.uptimeMillis() - volumeUpDownAt
                if (!volumeUpActivated && (heldFor >= 650L || event.repeatCount >= 2)) {
                    volumeUpActivated = true
                    val launch = Intent(this, WakeWordService::class.java)
                    ContextCompat.startForegroundService(this, launch)
                }
                return volumeUpActivated
            }
            KeyEvent.ACTION_UP -> {
                val consumed = volumeUpActivated
                volumeUpActivated = false
                volumeUpDownAt = 0L
                return consumed
            }
        }
        return false
    }
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }


    /**
     * Copies the last visible, non-editable text item from the active app window.
     * Apps that hide their text from Accessibility cannot be read.
     */
    fun copyLastMessage(): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = mutableListOf<String>()
        fun collect(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            // Use actual visible text only. contentDescription often contains control
            // hints such as "Voice message, Button. Double tap and hold to record".
            val value = node.text?.toString()?.trim().orEmpty()
            val className = node.className?.toString().orEmpty()
            val isTextView = className.endsWith("TextView")
            val isControlHint = value.contains("double tap", ignoreCase = true) ||
                value.contains("slide left", ignoreCase = true) ||
                value.contains("slide up", ignoreCase = true) ||
                value.contains("release to send", ignoreCase = true) ||
                value.contains("hold to record", ignoreCase = true) ||
                value.equals("voice message", ignoreCase = true)
            if (value.isNotBlank() && isTextView && !node.isEditable && !isControlHint &&
                value.length > 1 && value.length < 2000 &&
                !value.matches(Regex("\\d{1,2}:\\d{2}(:\\d{2})?"))) {
                candidates.add(value)
            }
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        collect(root)
        val message = candidates.distinct().lastOrNull() ?: return false
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Nova copied message", message))
        return true
    }

    /** Pastes clipboard content into the currently focused editable field. */
    fun pasteClipboard(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        if (!focused.isEditable) return false
        return focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
    }

    fun back() { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun home() { performGlobalAction(GLOBAL_ACTION_HOME) }
    fun scroll(up: Boolean) {
        val x = resources.displayMetrics.widthPixels * 0.5f
        val h = resources.displayMetrics.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(x, if (up) h * 0.3f else h * 0.75f)
            lineTo(x, if (up) h * 0.75f else h * 0.3f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450)).build()
        dispatchGesture(gesture, null, null)
    }
}
