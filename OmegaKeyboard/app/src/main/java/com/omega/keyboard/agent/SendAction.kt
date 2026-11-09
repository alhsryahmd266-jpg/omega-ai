package com.omega.keyboard.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.omega.keyboard.AppLog
import com.omega.keyboard.core.ChatParser
import com.omega.keyboard.core.SendFinder
import com.omega.keyboard.core.SendState
import com.omega.keyboard.core.SendVerifier
import com.omega.keyboard.core.UiNode
import com.omega.keyboard.ime.OmegaImeService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Types a message into the open chat and presses send, then checks that it really went out.
 * Every step has a fallback, and every step is verified by reading the screen again:
 *   text:  set-text action  ->  paste from clipboard  ->  our own keyboard
 *   send:  click the send button (best 2 candidates)  ->  real touch on it  ->  Enter action
 * Blocking; run on a background thread.
 */
object SendAction {
    class Result(val ok: Boolean, val how: String, val detail: String)

    private fun fail(why: String) = Result(false, "", why)
    private fun log(s: String) = AppLog.d("send", s)
    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    fun sendText(svc: ScreenAgentService, text: String, allowBlind: Boolean = true): Result {
        // 1) the composer (and a picture of the screen before there is any text in it: the send button is the one that changes)
        var snap = ScreenReader.read(svc) ?: return fail("مش قادر أقرا الشاشة")
        val before = snap.screen
        val input0 = ChatParser.findInput(before)
        if (input0 == null) { snap.release(); return fail("مش لاقي خانة الكتابة") }
        val node = snap.node(input0.id)
        if (node == null) { snap.release(); return fail("خانة الكتابة اختفت") }

        // 2) the text
        val placed = putText(svc, node, text)
        snap.release()
        if (placed == null) return fail("مقدرتش أكتب الرد في الخانة")
        log("text placed via $placed")
        nap(420)  // the app swaps the microphone for the send button

        // 3) send, by every way there is
        var lastDetail = "مفيش زر إرسال ظاهر"
        for (attempt in 1..3) {
            snap = ScreenReader.read(svc) ?: return fail("الشاشة اختفت قبل الإرسال")
            val screen = snap.screen
            val input = ChatParser.findInput(screen)
            if (input == null) { snap.release(); return fail("خانة الكتابة اختفت") }
            if (attempt > 1 && SendVerifier.isSent(screen, text)) { snap.release(); return Result(true, "already-sent", "") }
            if (SendVerifier.fieldState(screen, text) == SendState.CLEARED && attempt > 1) { snap.release(); return Result(true, "cleared", "") }

            // (a) the buttons that look like "send", best first
            val cands = SendFinder.candidates(screen, input, before)
            log("attempt $attempt candidates=" + cands.joinToString { "${it.node.shortId.ifEmpty { it.node.label }}:${it.score}:${it.why}" })
            for (c in cands.take(3)) {
                val how = tap(svc, snap, c.node)
                log("tap ${c.why} -> $how")
                if (how != null && waitSent(svc, text)) { snap.release(); return Result(true, "$how(${c.why})", "") }
                lastDetail = "ضغطت على زر الإرسال والرسالة ما اتبعتتش"
            }
            if (cands.isEmpty()) lastDetail = "مش لاقي زر الإرسال في التطبيق ده"

            // (b) the "send" key of the box (accessibility Enter, then our own keyboard's send action)
            val inputNode = snap.node(input.id)
            if (Build.VERSION.SDK_INT >= 30 && inputNode != null) {
                val enter = inputNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                log("ime-enter -> $enter")
                if (enter && waitSent(svc, text)) { snap.release(); return Result(true, "ime-enter", "") }
            }
            val kb = try { OmegaImeService.instance?.pressSend() == true } catch (t: Throwable) { false }
            log("keyboard send -> $kb")
            if (kb && waitSent(svc, text)) { snap.release(); return Result(true, "keyboard-send", "") }

            // (c) a touch where the button usually is: the end of the box's row, then the end of the row under it
            if (allowBlind) {
                val spots = ArrayList<Pair<Int, Int>>()
                spots.add((if (screen.rtl) input.left - screen.dp(24) else input.right + screen.dp(24)) to input.centerY)
                spots.add((if (screen.rtl) screen.dp(36) else screen.width - screen.dp(36)) to (input.bottom + screen.dp(36)))
                for ((x, y) in spots) {
                    if (x !in 1 until screen.width || y !in 1 until screen.height) continue
                    val g = gesture(svc, x, y)
                    log("blind tap at $x,$y -> $g")
                    if (g && waitSent(svc, text)) { snap.release(); return Result(true, "blind-tap", "") }
                }
            }
            snap.release()
            nap(500)
        }

        // couldn't send: do not leave a half-written reply in the user's chat box
        clearField(svc)
        return fail(lastDetail)
    }

    /** Writes the reply into the chat box but does not press send (the "draft only" setting). */
    fun typeOnly(svc: ScreenAgentService, text: String): Boolean {
        val snap = ScreenReader.read(svc) ?: return false
        val input = ChatParser.findInput(snap.screen)
        val node = input?.let { snap.node(it.id) }
        val placed = if (node != null) putText(svc, node, text) else null
        snap.release()
        return placed != null
    }

    // ------------------------------------------------------------------ typing

    private fun fieldHas(svc: ScreenAgentService, text: String): Boolean {
        nap(220)
        val s = ScreenReader.read(svc) ?: return false
        val st = SendVerifier.fieldState(s.screen, text)
        s.release()
        return st == SendState.STILL_IN_FIELD
    }

    private fun putText(svc: ScreenAgentService, node: AccessibilityNodeInfo, text: String): String? {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        nap(120)

        // (a) set-text action
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) && fieldHas(svc, text)) return "set-text"

        // (b) paste (replaces whatever was in the box)
        try {
            val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("omega", text))
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, (node.text?.length ?: 0))
            }
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE) && fieldHas(svc, text)) return "paste"
        } catch (t: Throwable) {
            AppLog.e("send", "paste failed", t)
        }

        // (c) our own keyboard's input connection
        try {
            if (OmegaImeService.instance?.replaceFieldText(text) == true && fieldHas(svc, text)) return "keyboard"
        } catch (t: Throwable) {
            AppLog.e("send", "ime commit failed", t)
        }
        return null
    }

    private fun clearField(svc: ScreenAgentService) {
        try {
            val s = ScreenReader.read(svc) ?: return
            val input = ChatParser.findInput(s.screen)
            val node = input?.let { s.node(it.id) }
            if (node != null) {
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            s.release()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ pressing

    private fun hasClick(n: AccessibilityNodeInfo): Boolean = try {
        n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
    } catch (_: Throwable) {
        false
    }

    /** Clicks the node (or the closest clickable parent), else touches its centre. Returns how, or null. */
    internal fun tap(svc: ScreenAgentService, snap: Snapshot, ui: UiNode): String? {
        var cur: AccessibilityNodeInfo? = snap.node(ui.id)
        var hops = 0
        while (cur != null && hops < 4) {
            if (cur.isEnabled && (cur.isClickable || hasClick(cur))) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "click"
            }
            cur = try { cur.parent } catch (_: Throwable) { null }
            hops++
        }
        return if (gesture(svc, ui.centerX, ui.centerY)) "touch" else null
    }

    fun gesture(svc: AccessibilityService, x: Int, y: Int): Boolean {
        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build()
            val latch = CountDownLatch(1)
            var ok = false
            val dispatched = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            }, null)
            if (!dispatched) false else { latch.await(1500, TimeUnit.MILLISECONDS); ok }
        } catch (t: Throwable) {
            AppLog.e("send", "gesture failed", t)
            false
        }
    }

    /** Waits up to ~2 s for the field to empty / the message bubble to appear. */
    private fun waitSent(svc: ScreenAgentService, text: String): Boolean {
        val end = System.currentTimeMillis() + 2000
        nap(250)
        while (true) {
            val s = ScreenReader.read(svc)
            if (s != null) {
                val sent = SendVerifier.isSent(s.screen, text)
                s.release()
                if (sent) return true
            }
            if (System.currentTimeMillis() >= end) return false
            nap(250)
        }
    }
}
