package com.omega.keyboard.agent

import android.content.Context
import com.omega.keyboard.AppLog
import com.omega.keyboard.Bg
import com.omega.keyboard.core.PreviewSendFinder

/**
 * A coloured picture put into a chat box usually opens the app's own preview, and the message only leaves when its send
 * button is pressed. With the accessibility service on, this waits for that preview and presses the button, so the colour
 * message really goes out with one tap on the keyboard. Without the service the user presses send himself.
 */
object PreviewSend {
    private const val FIRST_WAIT_MS = 650L
    private const val POLL_MS = 320L
    private const val GIVE_UP_MS = 6500L

    @Volatile private var running = false

    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    /** True when the service that can press buttons is on. */
    fun canPress(): Boolean = ScreenAgentService.instance != null

    /**
     * [pkg]: the app the picture went to; a send button of any other app is never pressed.
     * [onDone]: told (on a background thread) whether the button was pressed.
     */
    fun start(ctx: Context, pkg: String?, onDone: ((Boolean) -> Unit)? = null) {
        val svc = ScreenAgentService.instance
        if (svc == null || running) { onDone?.invoke(false); return }
        running = true
        Bg.io {
            var pressed = false
            try { pressed = waitAndPress(svc, pkg) } finally { running = false }
            try { onDone?.invoke(pressed) } catch (t: Throwable) { AppLog.e("picsend", "callback failed", t) }
        }
    }

    private fun waitAndPress(svc: ScreenAgentService, pkg: String?): Boolean {
        nap(FIRST_WAIT_MS)
        val end = System.currentTimeMillis() + GIVE_UP_MS
        while (System.currentTimeMillis() < end) {
            val snap = ScreenReader.read(svc)
            if (snap != null) {
                try {
                    val s = snap.screen
                    if (pkg == null || s.pkg == pkg) {
                        val btn = PreviewSendFinder.find(s)
                        if (btn != null) {
                            val how = SendAction.tap(svc, snap, btn)
                            AppLog.d("picsend", "pressed '${btn.label}' (${btn.shortId}) -> $how")
                            if (how != null) return true
                        }
                    }
                } finally {
                    snap.release()
                }
            }
            nap(POLL_MS)
        }
        AppLog.d("picsend", "no send button showed up")
        return false
    }
}
