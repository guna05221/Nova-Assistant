package com.guna.nova

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.KeyEvent
import android.os.SystemClock
import androidx.core.content.ContextCompat

internal data class VerifiedSendContext(
    val packageName: String,
    val recipient: String,
    val message: String
)

class NovaAccessibilityService : AccessibilityService() {
    companion object { var instance: NovaAccessibilityService? = null }
    private var volumeUpDownAt = 0L
    private var volumeUpActivated = false

    override fun onServiceConnected() { instance = this }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val changedPackage = event?.packageName?.toString() ?: return
        if (changedPackage == packageName) return // Ignore Nova's own floating approval controls.
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                NovaCommand.invalidatePendingSendForPackage(this, changedPackage)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val current = currentSendContext()
                NovaCommand.invalidatePendingSendIfChanged(this, current)
            }
        }
    }

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
            // WhatsApp exposes message times as separate TextViews (for example "7:52 am").
            // Exclude both 24-hour and 12-hour clock formats before selecting the last text node.
            val isTimestamp = Regex("(?i)^\\d{1,2}:\\d{2}(:\\d{2})?\\s*(am|pm)?$").matches(value)
            val isDateOrDayLabel = value.equals("today", ignoreCase = true) ||
                value.equals("yesterday", ignoreCase = true) ||
                value.equals("monday", ignoreCase = true) ||
                value.equals("tuesday", ignoreCase = true) ||
                value.equals("wednesday", ignoreCase = true) ||
                value.equals("thursday", ignoreCase = true) ||
                value.equals("friday", ignoreCase = true) ||
                value.equals("saturday", ignoreCase = true) ||
                value.equals("sunday", ignoreCase = true)
            if (value.isNotBlank() && isTextView && !node.isEditable && !isControlHint &&
                !isTimestamp && !isDateOrDayLabel && value.length > 1 && value.length < 2000) {
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


    /** Types dictated text into the focused field, or safely finds and focuses a visible editable field. */
    fun typeIntoFocusedField(value: String): Boolean {
        if (value.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        val target = if (focused?.isEditable == true) focused else {
            var found: android.view.accessibility.AccessibilityNodeInfo? = null
            fun findEditable(node: android.view.accessibility.AccessibilityNodeInfo?) {
                if (node == null || found != null || !node.isVisibleToUser || !node.isEnabled) return
                if (node.isEditable) { found = node; return }
                for (i in 0 until node.childCount) findEditable(node.getChild(i))
            }
            findEditable(root)
            found
        } ?: return false
        if (!target.isFocused) target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
        val args = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Reads the foreground package, the app's visible conversation/header title, and
     * the exact editable message. If the recipient cannot be identified unambiguously,
     * the send workflow fails closed.
     */
    fun currentSendContext(): VerifiedSendContext? {
        val root = rootInActiveWindow ?: return null
        val targetPackage = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        val editable = if (focused?.isEditable == true) focused else {
            var found: android.view.accessibility.AccessibilityNodeInfo? = null
            fun find(node: android.view.accessibility.AccessibilityNodeInfo?) {
                if (node == null || found != null || !node.isVisibleToUser || !node.isEnabled) return
                if (node.isEditable) { found = node; return }
                for (i in 0 until node.childCount) find(node.getChild(i))
            }
            find(root)
            found
        } ?: return null
        val message = editable.text?.toString().orEmpty()
        if (message.isBlank()) return null
        val recipient = findVisibleRecipientTitle(root) ?: return null
        return VerifiedSendContext(targetPackage, recipient, message)
    }

    private fun findVisibleRecipientTitle(
        root: android.view.accessibility.AccessibilityNodeInfo
    ): String? {
        val screenHeight = resources.displayMetrics.heightPixels
        val headerBottom = (screenHeight * 0.14f).toInt()
        val titleHints = listOf("conversation_contact", "contact_name", "chat_title", "toolbar_title", "conversation_title")
        val ignored = setOf("online", "typing", "last seen", "last seen today", "tap here", "search")
        val hinted = linkedSetOf<String>()
        val topCandidates = linkedSetOf<String>()

        fun collect(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val value = node.text?.toString()?.trim().orEmpty()
            if (value.length in 2..80 && value.lowercase() !in ignored && !node.isEditable) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.top >= (screenHeight * 0.015f).toInt() && bounds.bottom <= headerBottom) {
                    topCandidates.add(value)
                    val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                    if (titleHints.any { viewId.contains(it) }) hinted.add(value)
                }
            }
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        collect(root)
        return when {
            hinted.size == 1 -> hinted.first()
            hinted.size > 1 -> null
            topCandidates.size == 1 -> topCandidates.first()
            else -> null
        }
    }

    /**
     * Re-reads and compares the exact package, visible recipient title and message
     * immediately before clicking a clearly labelled Send control.
     */
    fun clickSendButton(
        expectedPackage: String,
        expectedRecipient: String,
        expectedMessage: String,
        expectedDigest: String
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != expectedPackage) return false
        val current = currentSendContext() ?: return false
        if (current.packageName != expectedPackage ||
            current.recipient != expectedRecipient ||
            current.message != expectedMessage ||
            NovaCommand.sendSnapshotDigest(current) != expectedDigest
        ) return false

        val candidates = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        fun collect(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val label = listOf(node.text?.toString(), node.contentDescription?.toString())
                .filterNotNull().joinToString(" ").trim().lowercase()
            val isSend = label == "send" || label == "send message" ||
                label == "submit" || label == "send prompt" || label == "send a message"
            if (isSend && node.isEnabled && node.isClickable) candidates.add(node)
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        collect(root)
        val target = candidates.firstOrNull() ?: return false
        return target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
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
