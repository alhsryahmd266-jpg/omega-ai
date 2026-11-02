package com.omega.keyboard.agent

import android.view.accessibility.AccessibilityNodeInfo
import com.omega.keyboard.AppLog
import com.omega.keyboard.core.ScrollLogic
import com.omega.keyboard.core.UiNode

/**
 * A chat app can sit above its newest messages (it opens at the first unread one, or the user scrolled up). The newest
 * message is what we have to answer, so before reading the conversation we move its list to the end.
 * Blocking: call from a background thread.
 */
object ChatScroll {
    /** Scroll events until this time come from us, not from the user. */
    @Volatile var ownScrollUntil = 0L
        private set
    @Volatile private var stuckUntil = 0L

    class Result(val steps: Int, val atEnd: Boolean)

    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
    private fun log(s: String) = AppLog.d("scroll", s)

    /** False for a while after a list refused to move, so a stubborn app is not poked every round. */
    fun canTry(): Boolean = System.currentTimeMillis() >= stuckUntil

    private fun pressDown(node: AccessibilityNodeInfo): Boolean {
        val ids = try { node.actionList.map { it.id } } catch (_: Throwable) { emptyList() }
        val down = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        if (down in ids && node.performAction(down)) return true
        return AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun fling(svc: ScreenAgentService, list: UiNode): Boolean {
        val p = ScrollLogic.swipeUpPath(list)
        return Gestures.swipeVertical(svc, p[0], p[1], p[2], 200)
    }

    /** Moves the open conversation to its newest messages. Stops early when the user asks to stop or nothing moves. */
    fun toLatest(svc: ScreenAgentService, maxSteps: Int = 12, cancelled: () -> Boolean = { false }): Result {
        if (!canTry()) return Result(0, false)
        var steps = 0
        var same = 0
        var lastSig = 0
        while (steps < maxSteps && !cancelled()) {
            val snap = ScreenReader.read(svc) ?: return Result(steps, false)
            val list = ScrollLogic.listWithMoreBelow(snap.screen)
            if (list == null) {
                snap.release()
                ownScrollUntil = System.currentTimeMillis() + 900
                return Result(steps, true)
            }
            val sig = ScrollLogic.signature(snap.screen, list)
            if (steps > 0 && sig == lastSig) same++ else same = 0
            lastSig = sig
            if (same >= 2) {
                snap.release()
                stuckUntil = System.currentTimeMillis() + 30_000
                log("list does not move any more, giving up for now")
                return Result(steps, false)
            }
            ownScrollUntil = System.currentTimeMillis() + 1800
            val node = snap.node(list.id)
            // page by page first; if the end is still far away, fling
            var ok = steps < 5 && node != null && pressDown(node)
            if (!ok) ok = fling(svc, list)
            snap.release()
            steps++
            log("step $steps ok=$ok")
            if (!ok) {
                stuckUntil = System.currentTimeMillis() + 30_000
                return Result(steps, false)
            }
            nap(380)
        }
        ownScrollUntil = System.currentTimeMillis() + 900
        return Result(steps, false)
    }
}
