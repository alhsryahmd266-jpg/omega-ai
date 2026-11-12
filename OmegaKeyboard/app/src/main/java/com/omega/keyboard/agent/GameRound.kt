package com.omega.keyboard.agent

import android.content.Context
import android.os.Build
import com.omega.keyboard.AppLog
import com.omega.keyboard.Prefs
import com.omega.keyboard.ai.Llm
import com.omega.keyboard.core.ColorCodes
import com.omega.keyboard.core.GameChat
import com.omega.keyboard.core.GameProfile
import com.omega.keyboard.core.Screen
import com.omega.keyboard.ime.OmegaImeService
import kotlin.random.Random

/** What the autonomous mode's loop offers to a game round. */
interface GameHost {
    fun say(text: String, warn: Boolean = false)
    fun stopped(): Boolean
    fun paused(): Boolean

    /** sleeps for [ms]; true = the user asked to stop meanwhile */
    fun nap(ms: Long): Boolean

    /** a reply went out */
    fun replied(text: String)
}

/** What the autonomous mode keeps in mind about the game it is playing (one per game and screen orientation). */
class GameState(val pkg: String, val landscape: Boolean) {
    var profile = GameProfile()
    var profileLoaded = false
    var offeredTeaching = false
    var lastGrid: IntArray? = null       // the chat picture at the last read
    var prevGrid: IntArray? = null       // the chat picture at the last look
    var changedSince = 0L
    var nextReadAt = 0L
    var idleReads = 0
    var failures = 0
    var colourHinted = false
    val handled = ArrayList<String>()    // messages of the others that were answered (newest last)
    val sent = ArrayList<String>()       // what I typed in this run (newest last)
}

/**
 * One round of the autonomous mode inside a game. Games draw their own screens (nothing for the accessibility service to read), so the
 * chat is read from a picture of the screen by the local model, and the answer is typed through the keyboard.
 *
 * The chat part of the screen is looked at all the time (cheap): only when it has changed and then held still is the model asked to
 * copy the messages from the picture (slow). A line that was answered, or that is mine, is never answered again.
 */
object GameRound {
    /** of 1024 grid cells: fewer than this is "nothing new" */
    private const val CHANGE_CELLS = 10
    /** a chat that keeps moving is read anyway after this long */
    private const val SETTLE_MS = 7_000L

    /** the model could not read pictures on this phone: games are left alone until the next start */
    @Volatile var visionBroken = false

    fun reset() { visionBroken = false }

    fun allowed(prefs: Prefs): Boolean = prefs.apVision != "off" && !visionBroken && Build.VERSION.SDK_INT >= 30

    /** The screen shows no readable text at all: a game draws its own interface. */
    fun opaque(screen: Screen): Boolean =
        screen.nodes.none { it.editable } && screen.nodes.count { it.visible && (it.text.isNotBlank() || it.desc.isNotBlank()) } <= 2

    private fun idleGap(n: Int): Long = minOf(30_000L, 3_000L + 3_000L * n)

    private fun trim(list: ArrayList<String>) { while (list.size > 12) list.removeAt(0) }

    /** Asks the user to show where the chat, the typing box and the send button are; the answer is saved for next time. */
    fun teach(svc: ScreenAgentService, prefs: Prefs, g: GameState, host: GameHost) {
        host.say("حدّد مكان الشات على الشاشة (اتبع الخطوات اللي ظهرت)")
        val p = Calibrator.run(svc, g.profile.takeIf { it.taught })
        g.profileLoaded = true
        g.offeredTeaching = true
        if (p != null && p.taught) {
            g.profile = p
            prefs.setGameProfile(GameProfile.key(g.pkg, g.landscape), p)
            g.lastGrid = null
            g.nextReadAt = 0L
            host.say("تمام، اتعلّمت مكان الشات")
        } else {
            host.say(if (g.profile.taught) "سيبت الضبط زي ما كان" else "لغيت الضبط. هقرا الشاشة كلها (أبطأ وأقل دقة)", warn = !g.profile.taught)
        }
    }

    fun step(ctx: Context, svc: ScreenAgentService, prefs: Prefs, g: GameState, host: GameHost) {
        val ime = OmegaImeService.instance
        if (ime == null) { host.say("كيبورد Omega مش شغال دلوقتي. اختاره كيبورد وجرّب تاني", true); return }
        if (!g.profileLoaded) {
            g.profile = prefs.gameProfile(GameProfile.key(g.pkg, g.landscape))
            g.profileLoaded = true
        }
        if (!g.profile.taught && !g.offeredTeaching) {
            teach(svc, prefs, g, host)
            if (host.stopped()) return
        }

        // ---- a look at the chat part of the screen
        val look = GameEyes.look(ctx, svc, g.profile.chat)
        if (look == null) { host.say("مقدرتش آخد صورة للشاشة. ممكن اللعبة تكون محمية من التصوير", true); return }
        val now = System.currentTimeMillis()
        val last = g.lastGrid
        val prev = g.prevGrid
        g.prevGrid = look.grid
        if (last != null) {
            if (GameChat.changedCells(last, look.grid) < CHANGE_CELLS) {
                g.changedSince = 0L
                host.say("براقب شات اللعبة...")
                return
            }
            if (g.changedSince == 0L) g.changedSince = now
            val moving = prev != null && GameChat.changedCells(prev, look.grid) >= CHANGE_CELLS
            if (moving && now - g.changedSince < SETTLE_MS) { host.say("الشات بيتحرّك، هستنى يثبت..."); return }
        }
        if (now < g.nextReadAt) { host.say("براقب شات اللعبة..."); return }
        g.changedSince = 0L

        // ---- the model copies the messages from the picture
        host.say("بقرا شات اللعبة...")
        if (Llm.loading) host.say("النموذج بيتحمّل، ثواني...")
        val raw = try {
            Llm.generate(
                ctx, GameChat.visionSystem(prefs.apGameName), GameChat.VISION_USER, maxTokens = 420, temperature = 0.1f, topK = 1, topP = 1f,
                timeoutMs = 300_000, imagePath = look.file.absolutePath, visualTokens = 560
            )
        } catch (e: Exception) {
            if (host.stopped()) return
            AppLog.e("game", "reading the chat failed", e)
            if (Llm.visionOk == false) {
                visionBroken = true
                host.say("النموذج ده مش بيقرا صور على الجهاز ده، فمش هقدر أقرا اللعبة. جرّب Gemma 4 أو اتأكد إن النموذج بيدعم الصور", true)
            } else {
                g.failures++
                g.nextReadAt = System.currentTimeMillis() + minOf(60_000L, 6_000L * g.failures)
                host.say("قراية الشات فشلت (${Llm.lastError.take(50)}). هحاول تاني...", true)
            }
            return
        }
        if (host.stopped()) return
        g.failures = 0
        g.lastGrid = look.grid
        val messages = GameChat.parseTranscript(raw, prefs.apGameName, g.sent)
        AppLog.d("game", "read ${messages.size} lines: " + raw.replace('\n', '⏎').take(300))
        if (messages.isEmpty()) {
            g.idleReads++
            g.nextReadAt = System.currentTimeMillis() + idleGap(g.idleReads)
            host.say("مش شايف رسايل شات في الصورة. لو المربع مش على الشات دوس «ضبط»", warn = g.idleReads >= 3)
            return
        }
        val decision = GameChat.decide(messages, g.handled)
        if (!decision.reply) {
            g.idleReads++
            g.nextReadAt = System.currentTimeMillis() + idleGap(g.idleReads)
            host.say(if (decision.reason == "last_is_mine") "مستني رد من اللاعبين..." else "مستني رسالة جديدة...")
            return
        }
        g.idleReads = 0
        val theirs = messages.last().text

        // ---- the reply, written as me
        val lo = prefs.apDelayMinSec.coerceIn(0, 3)
        val hi = prefs.apDelayMaxSec.coerceIn(lo, 5)
        if (host.nap(lo * 1000L + Random.nextLong((hi - lo) * 1000L + 1))) return
        if (host.paused() || host.stopped()) return
        host.say("فيه رسالة جديدة في الشات، بكتب الرد...")
        // planned, written and checked like any chat reply; a game's chat is handed over as one text (several players)
        val written = Replier.write(ctx, prefs, messages, game = true, stop = { host.stopped() }, say = { host.say(it) })
        if (host.stopped()) return
        val text = written.text
        if (text == null) {
            g.failures++
            g.lastGrid = null
            g.nextReadAt = System.currentTimeMillis() + minOf(40_000L, 8_000L * g.failures)
            host.say("مقدرتش أكتب رد مناسب. هحاول تاني...", true)
            return
        }
        if (host.paused()) { host.say("وقفت الردود ومبعتش الرد. دوس «كمّل»"); return }

        // ---- typed into the game, in colour codes when a colour and a game format are chosen
        val coloured = ime.colourForGame(text)
        val colourOn = prefs.apColour && (prefs.textColor != 0 || prefs.textRainbow)
        if (colourOn && coloured == text && !g.colourHinted && ColorCodes.Format.of(prefs.colorCodes) == ColorCodes.Format.NONE) {
            g.colourHinted = true
            Notice.toast(ctx, "اللعبة مبتقبلش صور، فالرد هيتكتب عادي. اختار «ألوان الألعاب» من الإعدادات عشان يتلوّن")
        }
        host.say("بكتب الرد في اللعبة...")
        // from now on a chat line with this text is mine, whatever it looks like in the picture
        g.sent.add(text)
        trim(g.sent)
        val res = GameTyper.send(svc, ime, g.pkg, g.profile, coloured) { host.stopped() }
        if (res.ok) {
            g.failures = 0
            g.handled.add(theirs)
            trim(g.handled)
            g.nextReadAt = System.currentTimeMillis() + 3_000L
            host.replied(text)
            host.say("ردّيت في اللعبة: ${text.take(48)}")
        } else {
            if (host.stopped()) return
            g.sent.remove(text)
            g.failures++
            g.lastGrid = null
            g.nextReadAt = System.currentTimeMillis() + minOf(40_000L, 8_000L * g.failures)
            host.say("مقدرتش أكتب في اللعبة: ${res.note}", true)
        }
    }
}
