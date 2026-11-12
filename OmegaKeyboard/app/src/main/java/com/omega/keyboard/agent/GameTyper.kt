package com.omega.keyboard.agent

import com.omega.keyboard.AppLog
import com.omega.keyboard.core.FPoint
import com.omega.keyboard.core.GameChat
import com.omega.keyboard.core.GameProfile
import com.omega.keyboard.ime.OmegaImeService

/**
 * Types a message into a game's chat box and sends it. A game's box is not an accessibility node, so the way in is the keyboard:
 * touch the spot where the game opens its typing box until the box attaches itself to our keyboard, type through it, press its
 * action key, touch the game's send button when the text is still there, and put the keyboard away again.
 */
object GameTyper {
    class Result(val ok: Boolean, val note: String)

    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun waitLive(ime: OmegaImeService, pkg: String, ms: Long, stop: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (stop()) return false
            if (ime.liveFieldPackage() == pkg) return true
            nap(120)
        }
        return ime.liveFieldPackage() == pkg
    }

    /** the start of the message is still in the box: it was not sent */
    private fun stillThere(ime: OmegaImeService, text: String): Boolean {
        val now = ime.liveFieldText() ?: return false
        val head = GameChat.key(text).take(6)
        return head.isNotEmpty() && GameChat.key(now).contains(head)
    }

    /** Blocking. [stop] is asked between the steps. */
    fun send(svc: ScreenAgentService, ime: OmegaImeService, pkg: String, profile: GameProfile, text: String, stop: () -> Boolean): Result {
        val (w, h) = GameEyes.realSize(svc)
        fun tap(p: FPoint?): Boolean = p != null && SendAction.gesture(svc, p.pxX(w).toInt(), p.pxY(h).toInt())
        var typed = false
        try {
            // 1. a box must be open and attached to our keyboard
            if (ime.liveFieldPackage() != pkg) {
                if (profile.input == null) return Result(false, "مفيش خانة كتابة مفتوحة، ومكانها لسه متعلّمش. اضغط «ضبط» وحدّد مكانها")
                tap(profile.input)
                if (!waitLive(ime, pkg, 2600, stop)) {
                    if (stop()) return Result(false, "stopped")
                    // some games open their box only when a keyboard is allowed to show: let it, and touch once more
                    Keyboards.setMode(svc, false)
                    tap(profile.input)
                    if (!waitLive(ime, pkg, 3000, stop)) return Result(false, "الكيبورد ماتوصّلش بخانة الكتابة اللي في اللعبة")
                }
            }
            if (stop()) return Result(false, "stopped")
            nap(250)

            // 2. type
            if (!ime.replaceFieldText(text)) return Result(false, "مقدرتش أكتب في الخانة")
            typed = true
            nap(300)
            if (!stillThere(ime, text)) return Result(false, "الخانة مقبلتش الكلام (اللعبة بتمسحه أو بتغيّره)")

            // 3. send: the box's own action key first, the taught send button after it
            ime.pressEnter()
            nap(1000)
            if (stillThere(ime, text) && profile.send != null) {
                tap(profile.send)
                nap(1000)
            }
            if (stillThere(ime, text)) return Result(false, "الرسالة فضلت في الخانة ومتبعتتش")
            typed = false
            return Result(true, "")
        } finally {
            if (typed) try { ime.replaceFieldText("") } catch (t: Throwable) { AppLog.e("gametyper", "could not clear the box", t) }
            if (AutoPilot.running) {         // stopped meanwhile: the keyboards were already given back
                Keyboards.setMode(svc, true)
                ime.hideKeyboard()
            }
        }
    }
}
