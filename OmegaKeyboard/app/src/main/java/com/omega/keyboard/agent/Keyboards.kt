package com.omega.keyboard.agent

import android.accessibilityservice.AccessibilityService
import com.omega.keyboard.ime.OmegaImeService

/** Keeps keyboards off the screen while we read it, so the whole app is visible and nothing is covered. */
object Keyboards {
    @Volatile private var held = false

    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    /** Asks the system to hold every keyboard back ([hidden]) or lets them show again. */
    fun setMode(svc: AccessibilityService, hidden: Boolean) {
        try {
            svc.softKeyboardController.setShowMode(
                if (hidden) AccessibilityService.SHOW_MODE_HIDDEN else AccessibilityService.SHOW_MODE_AUTO
            )
            held = hidden
        } catch (_: Throwable) {
        }
    }

    /**
     * Hides the keyboard (ours first, then whatever keyboard it is) and waits until the app has taken the room back.
     * Returns true when no keyboard is on screen any more. Blocking.
     */
    fun ensureHidden(svc: ScreenAgentService): Boolean {
        if (!ScreenReader.keyboardVisible(svc)) return true
        OmegaImeService.instance?.hideKeyboard()
        if (ScreenReader.waitKeyboardGone(svc, 800)) { nap(320); return true }
        // somebody else's keyboard: the system can hold all of them back
        setMode(svc, true)
        val gone = ScreenReader.waitKeyboardGone(svc, 1800)
        if (gone) nap(320)
        return gone
    }

    /** After a one-off read: let keyboards appear again (autonomous mode keeps them away for its whole run). */
    fun release(svc: AccessibilityService) {
        if (held && !AutoPilot.running) setMode(svc, false)
    }
}
