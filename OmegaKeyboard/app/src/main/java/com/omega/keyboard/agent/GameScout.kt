package com.omega.keyboard.agent

import android.content.Context
import com.omega.keyboard.AppLog
import com.omega.keyboard.Prefs
import com.omega.keyboard.ai.Llm
import com.omega.keyboard.core.FPoint
import com.omega.keyboard.core.GameLocate
import com.omega.keyboard.core.GameProfile
import com.omega.keyboard.ime.OmegaImeService

/**
 * Finds a game's chat and the buttons that go with it by itself, so the user never has to show them.
 *
 * A picture of the whole screen goes to the model with one question: where are the chat messages, the chat button, the typing box
 * and the send button. What it answers is not trusted: the touch on the box (or the chat button) must make a box attach itself to
 * our keyboard, which is proof that the right place was found. When the typing box shows only after the chat button, the screen is
 * looked at a second time, with the chat open.
 */
object GameScout {
    private const val TOKENS = 1120

    /** why a look failed, in words for the user */
    private class Fail(val say: String) : Exception(say)

    /** the user stopped the autonomous mode meanwhile */
    private class Stopped : Exception()

    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    /** where our own keyboard starts, as a fraction of the screen's height (1 = it is not on the screen) */
    private fun keyboardTop(svc: ScreenAgentService): Float {
        val kb = OmegaImeService.instance?.keyboardHeightPx() ?: 0
        if (kb <= 0) return 1f
        val h = GameEyes.realSize(svc).second
        return (1f - kb.toFloat() / h).coerceIn(0.3f, 1f)
    }

    /** One picture of the whole screen, one question for the model. */
    private fun look(ctx: Context, svc: ScreenAgentService, host: GameHost, tag: String): GameLocate.Found {
        val kbTop = keyboardTop(svc)
        val shot = GameEyes.look(ctx, svc, null, name = "game_scout")
            ?: throw Fail("مقدرتش آخد صورة للشاشة. ممكن اللعبة تكون محمية من التصوير")
        if (shot.blank) throw Fail("صورة الشاشة طلعت سودا: اللعبة بتمنع التصوير، فمش هقدر أقراها")
        if (!Llm.visionReady()) host.say("بحمّل جزء الصور في النموذج (أول مرة بس، ممكن ياخد دقيقة)...")
        val raw = try {
            Llm.generate(
                ctx, "", GameLocate.SCOUT_USER, maxTokens = 420, temperature = 0.1f, topK = 1, topP = 1f,
                timeoutMs = 300_000, imagePath = shot.file.absolutePath, visualTokens = TOKENS
            )
        } catch (e: Exception) {
            if (host.stopped()) throw Stopped()
            AppLog.e("scout", "$tag: the model could not look", e)
            if (Llm.visionOk == false) {
                GameRound.markVisionBroken()
                throw Fail(GameRound.visionFailedText())
            }
            throw Fail("النموذج مقدرش يبص على الشاشة (${Llm.lastError.take(50)})")
        }
        if (host.stopped()) throw Stopped()
        val dets = GameLocate.parse(raw)
        val found = GameLocate.classify(dets, maxCenterY = kbTop)
        AppLog.d(
            "scout",
            "$tag: ${dets.size} boxes [${dets.joinToString { it.label }}] -> chat=${found.chat != null} button=${found.button != null} " +
                "input=${found.input != null} send=${found.send != null} · " + raw.replace('\n', '⏎').take(260)
        )
        return found
    }

    /**
     * Learns the game in front: where its chat is and how to type into it. The result is kept (per game and orientation) and shown
     * for a few seconds. True when something usable was found. Blocking: call it from a background thread.
     */
    fun learn(ctx: Context, svc: ScreenAgentService, prefs: Prefs, g: GameState, host: GameHost): Boolean {
        val ime = OmegaImeService.instance ?: return false
        host.say("بدوّر على الشات وزراير اللعبة بنفسي (أول مرة بس، ممكن ياخد دقيقة)...")
        val (w, h) = GameEyes.realSize(svc)
        val stop = { host.stopped() }
        fun tap(p: FPoint) = SendAction.gesture(svc, p.pxX(w).toInt(), p.pxY(h).toInt())

        /** touches [p]; true when a box of the game attached itself to our keyboard because of it */
        fun touch(p: FPoint, again: Boolean): Boolean {
            val before = ime.startCount()
            tap(p)
            if (GameTyper.waitFresh(ime, g.pkg, before, 2800, stop)) return true
            if (!again || stop()) return false
            // some games open their typing box only when a keyboard is allowed to show: let it, and touch once more
            Keyboards.setMode(svc, false)
            val before2 = ime.startCount()
            tap(p)
            return GameTyper.waitFresh(ime, g.pkg, before2, 3000, stop)
        }

        try {
            val a = look(ctx, svc, host, "screen")
            if (!a.any) {
                host.say("مش لاقي شات ولا زراير على الشاشة دي. افتح الشات في اللعبة وهحاول تاني", true)
                return false
            }
            var chat = a.chat
            var send = a.send
            var open: FPoint? = null
            var spot: FPoint? = null
            val live = ime.liveFieldPackage() == g.pkg

            // 1. the typing box is on the screen: touching it must bring up our keyboard
            a.input?.let { box ->
                val p = GameLocate.center(box)
                host.say("لقيت خانة الكتابة، بجرّبها...")
                if (touch(p, again = true) || (live && !stop())) {
                    spot = p
                    open = a.button?.let { GameLocate.center(it) }
                }
            }
            if (stop()) throw Stopped()

            // 2. only the chat button: one touch may open a box that is ready, or open the chat first
            if (spot == null) a.button?.let { button ->
                val bp = GameLocate.center(button)
                host.say("لقيت زرار الشات، بجرّبه...")
                if (touch(bp, again = false)) {
                    spot = bp
                } else if (!stop()) {
                    nap(1000)
                    host.say("بدوّر على خانة الكتابة بعد ما فتحت الشات...")
                    val b = look(ctx, svc, host, "chat open")
                    if (b.chat != null) chat = b.chat
                    if (b.send != null) send = b.send
                    b.input?.let { box ->
                        val p = GameLocate.center(box)
                        if (touch(p, again = true)) { open = bp; spot = p }
                    }
                }
            }
            if (stop()) throw Stopped()

            val found = GameLocate.Found(chat, a.button, a.input, send)
            val profile = GameLocate.profileOf(found, open, spot, base = g.profile)
            if (!profile.taught) {
                host.say("مقدرتش أحدد حاجة مفيدة. دوس «ضبط» وعلّمني أماكن الشات", true)
                return false
            }
            g.profile = profile
            g.profileLoaded = true
            g.chatOpen = spot != null
            g.lastGrid = null
            g.nextReadAt = 0L
            prefs.setGameProfile(GameProfile.key(g.pkg, g.landscape), profile)
            AppLog.d("scout", "learned: " + profile.encode())
            if (spot != null) ime.hideKeyboard()
            val parts = ArrayList<String>()
            if (profile.chat != null) parts.add("الشات")
            if (profile.open != null) parts.add("زرار الشات")
            if (profile.input != null) parts.add("خانة الكتابة")
            if (profile.send != null) parts.add("زرار الإرسال")
            if (spot != null) {
                host.say("لقيت " + parts.joinToString(" و") + " ✓ (لو أي مكان غلط دوس «ضبط»)")
            } else {
                host.say("لقيت " + parts.joinToString(" و") + " بس مقدرتش أفتح خانة الكتابة. دوس «ضبط» وعلّمني مكانها", true)
            }
            // shown over the game for a few seconds, and gone again before the next picture is taken
            GameMarks.flash(svc, profile, stop = stop)
            return true
        } catch (_: Stopped) {
            return false
        } catch (f: Fail) {
            host.say(f.say, true)
            return false
        } catch (t: Throwable) {
            AppLog.e("scout", "could not learn the game", t)
            if (!host.stopped()) host.say("معرفتش أتعرّف على اللعبة لوحدي. دوس «ضبط» وعلّمني", true)
            return false
        } finally {
            if (AutoPilot.running) Keyboards.setMode(svc, true)      // keyboards stay away while the game is played
        }
    }

    /**
     * The game's own send button, looked for on the screen while the message sits typed in the box (the box's action key did not send it).
     * Null when none was found. Blocking.
     */
    fun findSend(ctx: Context, svc: ScreenAgentService, host: GameHost): FPoint? {
        host.say("بدوّر على زرار الإرسال في اللعبة...")
        return try {
            look(ctx, svc, host, "send button").send?.let { GameLocate.center(it) }
        } catch (_: Stopped) {
            null
        } catch (f: Fail) {
            AppLog.d("scout", "no send button: ${f.say}")
            null
        } catch (t: Throwable) {
            AppLog.e("scout", "could not look for the send button", t)
            null
        }
    }
}
