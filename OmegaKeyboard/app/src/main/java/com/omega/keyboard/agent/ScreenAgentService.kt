package com.omega.keyboard.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.omega.keyboard.AppLog

/**
 * The accessibility service: the only way an app can read other apps' screens and tap buttons in them.
 * It does nothing on its own; translation and autonomous mode use it when the user starts them.
 */
class ScreenAgentService : AccessibilityService() {

    companion object {
        @Volatile var instance: ScreenAgentService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.d("agent", "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.packageName == packageName) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                ScreenTranslateController.onScreenEvent(e.eventType)
                AutoPilot.onScreenEvent(e.eventType)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        AppLog.d("agent", "accessibility service unbound")
        stopEverything()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopEverything()
        instance = null
        super.onDestroy()
    }

    private fun stopEverything() {
        try { AutoPilot.stop("الخدمة اتقفلت", silent = true) } catch (_: Throwable) {}
        try { ScreenTranslateController.stop() } catch (_: Throwable) {}
        Overlays.hideAll()
    }
}
