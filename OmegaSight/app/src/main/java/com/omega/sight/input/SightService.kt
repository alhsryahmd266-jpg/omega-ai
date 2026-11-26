package com.omega.sight.input

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.omega.sight.engine.Hub
import com.omega.sight.overlay.Pill

/**
 * The accessibility service. It does three small things: it learns which app is in front (window-change events only; it never reads what
 * is on screen), it sends the aiming finger as gestures, and it hosts the little status capsule.
 */
class SightService : AccessibilityService() {
    private var main: Handler? = null
    private var stroker: Stroker? = null
    private var pill: Pill? = null
    private var imePkgs: Set<String> = emptySet()
    private var imeAt = 0L

    val strokeEpoch: Int get() = stroker?.epoch ?: 0

    fun userTouching(): Boolean = stroker?.userTouching() ?: false

    /** the finger moves by (dx, dy) screen pixels inside the aim zone around (zoneCx, zoneCy) */
    fun aim(dx: Float, dy: Float, restart: Boolean, zoneCx: Float, zoneCy: Float) {
        main?.post { stroker?.aim(dx, dy, restart, zoneCx, zoneCy) }
    }

    fun lift() {
        main?.post { stroker?.lift() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val h = Handler(Looper.getMainLooper())
        main = h
        stroker = Stroker(this, h)
        pill = Pill(this, h).also { it.start() }
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = event.packageName?.toString() ?: return
        if (p == packageName) {
            // our own screen counts when it is the app's main screen, not our little capsule
            if (event.className?.toString() == "com.omega.sight.ui.MainActivity") foreground = p
            return
        }
        if (p == "android" || p == "com.android.systemui" || isIme(p)) return
        foreground = p
    }

    override fun onInterrupt() {
        main?.post { stroker?.lift() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        pill?.stop()
        pill = null
        stroker?.lift()
        stroker = null
        if (instance === this) instance = null
        foreground = ""
        Hub.testAim = false
        return super.onUnbind(intent)
    }

    private fun isIme(p: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - imeAt > 60_000L) {
            imeAt = now
            imePkgs = try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.enabledInputMethodList.map { it.packageName }.toSet()
            } catch (_: Throwable) {
                emptySet()
            }
        }
        return p in imePkgs
    }

    companion object {
        @Volatile
        var instance: SightService? = null

        /** the package of the app in front, "" = not known yet */
        @Volatile
        var foreground: String = ""
    }
}
