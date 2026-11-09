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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: this service never inspects other apps' UI events or content.
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        stopEngine()
        if (instance === this) instance = null
        StatusBus.setServiceConnected(false)
        super.onDestroy()
    }

    fun startEngine(strategy: com.tacticai.bot.act.Strategy = com.tacticai.bot.act.NoopStrategy()) {
        if (engine?.isRunning == true) return
        engine = BotEngine(this, strategy) { info -> StatusBus.postTick(info) }.also { it.start() }
        StatusBus.setRunning(true)
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
