package com.guna.nova

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class NovaAccessibilityService : AccessibilityService() {
    companion object { var instance: NovaAccessibilityService? = null }
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

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
