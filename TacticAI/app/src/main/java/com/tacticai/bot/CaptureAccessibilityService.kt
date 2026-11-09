package com.tacticai.bot

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * This service does exactly two things: take a screenshot on request, and send a synthetic touch on
 * request (see [accessibility_service_config.xml] — window-content reading is switched off, and no
 * package filter is set because it only ever acts on whatever is on screen when the user turns it on).
 */
class CaptureAccessibilityService : AccessibilityService() {

    private var engine: BotEngine? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        StatusBus.setServiceConnected(true)
    }

    /** Package name only (never window content) — just enough for the [Safety] blocklist check. */
    var foregroundPackage: String? = null
        private set

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only the event's own package-name field is read here — window content stays untouched
        // (canRetrieveWindowContent is false in the service config).
        event?.packageName?.let { foregroundPackage = it.toString() }
        if (Safety.isBlocked(foregroundPackage)) stopEngine()
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        stopEngine()
        if (instance === this) instance = null
        StatusBus.setServiceConnected(false)
        super.onDestroy()
    }

    /** Null on success; the reason string if refused ([Safety] blocklist or already running). */
    fun startEngine(strategy: com.tacticai.bot.act.Strategy = com.tacticai.bot.act.NoopStrategy()): String? {
        if (Safety.isBlocked(foregroundPackage)) return Safety.reason(foregroundPackage)
        if (engine?.isRunning == true) return null
        engine = BotEngine(this, strategy) { info -> StatusBus.postTick(info) }.also { it.start() }
        StatusBus.setRunning(true)
        return null
    }

    fun stopEngine() {
        engine?.stop()
        engine = null
        StatusBus.setRunning(false)
    }

    companion object {
        /** There is only ever one instance system-wide; OverlayService reaches it through here. */
        var instance: CaptureAccessibilityService? = null
            private set
    }
}
