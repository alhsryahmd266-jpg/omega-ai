package com.ahmedxg.gamebot

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Two jobs: (1) fallback touch injection (see AccessibilityInput), (2) tells the safety guard which
 * app is in the foreground. It never reads screen content.
 */
class BotAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: BotAccessibilityService? = null

        @Volatile
        var lastPackage: String = ""

        @Volatile
        var lastPackageTs: Long = 0L

        private val NEUTRAL = setOf("com.android.systemui", "android", "com.ahmedxg.gamebot")
    }

    override fun onServiceConnected() {
        instance = this
        Logger.i("accessibility service connected")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = e.packageName?.toString() ?: return
            if (pkg in NEUTRAL) return
            lastPackage = pkg
            lastPackageTs = SystemClock.uptimeMillis()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
