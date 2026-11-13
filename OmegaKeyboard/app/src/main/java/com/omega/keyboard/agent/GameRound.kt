package com.omega.keyboard.agent

import android.content.Context
import android.os.Build
import com.omega.keyboard.AppLog
import com.omega.keyboard.Prefs
import com.omega.keyboard.ai.Llm
import com.omega.keyboard.core.ChatMessage
import com.omega.keyboard.core.ColorCodes
import com.omega.keyboard.core.FPoint
import com.omega.keyboard.core.GameChat
import com.omega.keyboard.core.GameProfile
import com.omega.keyboard.core.Screen
import com.omega.keyboard.ime.OmegaImeService
import java.io.File
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
    var chatOpen = false                 // the game's chat was open the last time it was used
    var scoutFails = 0                   // looks for the chat and its buttons that gave no way to type
    var nextScoutAt = 0L
    var rescouts = 0                     // times a place found by itself was given up because it showed no chat
    var rescoutAt = 0L
    var emptyReads = 0                   // reads in a row that showed no chat line at all
    var blankLooks = 0                   // pictures in a row that were all black
    var sendLooks = 0                    // looks for the game's send button that found none
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
 * Where the chat and its buttons are is found by the model too, once per game ([GameScout]); the user can still show them by hand.
 * The chat part of the screen is looked at all the time (cheap): only when it has changed and then held still is the model asked to
 * copy the messages from the picture (slow). A line that was answered, or that is mine, is never answered again.
 */
object GameRound {
    /** of 1024 grid cells: fewer than this is "nothing new" */
    private const val CHANGE_CELLS = 10
    /** a chat that keeps moving is read anyway after this long */
    private const val SETTLE_MS = 7_000L
    /** looks for the chat that may fail before the user is asked to show it */
    private const val MAX_SCOUTS = 3

    /** the model's picture reader would not open: games are left alone for a while, then it is tried again (memory may have been short) */
    @Volatile private var visionBrokenAt = 0L
    private const val VISION_COOL_MS = 3 * 60_000L

    fun visionBroken(): Boolean = visionBrokenAt != 0L && System.currentTimeMillis() - visionBrokenAt < VISION_COOL_MS

    fun markVisionBroken() { visionBrokenAt = System.currentTimeMillis() }

    /** what the person is told when the picture reader will not open (the library's own reason is added: it is what has to be fixed) */
    fun visionFailedText(): String =
        "جزء الصور في Gemma مفتحش على جهازك" + Llm.visionWhy.takeIf { it.isNotBlank() }?.let { " (${it.take(120)})" }.orEmpty() +
            ". هسيب الألعاب وهجرّبه تاني بعد ٣ دقايق"

    fun reset() { visionBrokenAt = 0L }

    fun allowed(prefs: Prefs): Boolean = prefs.apVision != "off" && !visionBroken() && Build.VERSION.SDK_INT >= 30

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
            g.chatOpen = false
            prefs.setGameProfile(GameProfile.key(g.pkg, g.landscape), p)
            g.lastGrid = null
            g.nextReadAt = 0L
            g.emptyReads = 0
            host.say("تمام، اتعلّمت مكان الشات")
        } else {
            host.say(if (g.profile.taught) "سيبت الضبط زي ما كان" else "لغيت الضبط. هقرا الشاشة كلها (أبطأ وأقل دقة)", warn = !g.profile.taught)
        }
    }

    /** The model copies the chat lines from the picture [file]. Null = it failed (the user was told, and the next try is set). */
    private fun readLines(ctx: Context, prefs: Prefs, g: GameState, host: GameHost, file: File): List<ChatMessage>? {
        if (!Llm.visionReady()) host.say("بحمّل جزء الصور في النموذج (أول مرة بس، ممكن ياخد دقيقة)...")
        val raw = try {
            Llm.generate(
                ctx, GameChat.visionSystem(prefs.apGameName), GameChat.VISION_USER, maxTokens = 420, temperature = 0.1f, topK = 1, topP = 1f,
                timeoutMs = 300_000, imagePath = file.absolutePath, visualTokens = 560
            )
        } catch (e: Exception) {
            if (host.stopped()) return null
            AppLog.e("game", "reading the chat failed", e)
            if (Llm.visionOk == false) {
                markVisionBroken()
                host.say(visionFailedText(), true)
            } else {
                g.failures++
                g.nextReadAt = System.currentTimeMillis() + minOf(60_000L, 6_000L * g.failures)
                host.say("قراية الشات فشلت (${Llm.lastError.take(50)}). هحاول تاني...", true)
            }
            return null
        }
        if (host.stopped()) return null
        val messages = GameChat.parseTranscript(raw, prefs.apGameName, g.sent)
        AppLog.d("game", "read ${messages.size} lines (${file.name}): " + raw.replace('\n', '⏎').take(300))
        return messages
    }

    fun step(ctx: Context, svc: ScreenAgentService, prefs: Prefs, g: GameState, host: GameHost) {
        val ime = OmegaImeService.instance
        if (ime == null) { host.say("كيبورد Omega مش شغال دلوقتي. اختاره كيبورد وجرّب تاني", true); return }
        if (!g.profileLoaded) {
            g.profile = prefs.gameProfile(GameProfile.key(g.pkg, g.landscape))
            g.profileLoaded = true
        }

        // ---- no way to type into this game is known yet: the model looks for its chat and buttons on the screen
        if (!g.profile.canType) {
            if (g.scoutFails < MAX_SCOUTS) {
                if (System.currentTimeMillis() >= g.nextScoutAt) {
                    val ok = GameScout.learn(ctx, svc, prefs, g, host)
                    if (host.stopped()) return
                    if (ok && g.profile.canType) {
                        g.scoutFails = 0
                    } else {
                        g.scoutFails++
                        g.nextScoutAt = System.currentTimeMillis() + 15_000L * g.scoutFails
                    }
                } else if (!g.profile.taught) {
                    host.say("هدوّر على الشات تاني كمان ثواني...")
                    return
                }
            } else if (!g.offeredTeaching) {
                host.say("معرفتش أتعرّف على خانة الكتابة لوحدي", true)
                teach(svc, prefs, g, host)
                if (host.stopped()) return
            }
            if (!g.profile.taught && g.scoutFails < MAX_SCOUTS) return      // nothing is known yet: the next round tries again
        }

        // ---- a look at the chat part of the screen
        val look = GameEyes.look(ctx, svc, g.profile.chat)
        if (look == null) { host.say("مقدرتش آخد صورة للشاشة. ممكن اللعبة تكون محمية من التصوير", true); return }
        if (look.blank) {
            g.blankLooks++
            host.say(
                if (g.blankLooks >= 3) "الصورة لسه سودا. لو اللعبة بتمنع تصوير الشاشة مش هقدر أقراها" else "منطقة الشات سودا دلوقتي، هستنى...",
                warn = g.blankLooks >= 3
            )
            return
        }
        g.blankLooks = 0
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
        var messages = readLines(ctx, prefs, g, host, look.file) ?: return
        g.failures = 0
        g.lastGrid = look.grid
        if (messages.isEmpty()) {
            // coloured letters on a busy background: the same picture with more contrast and stronger colours, once
            val sharp = GameEyes.enhanced(ctx, look)
            if (sharp != null) {
                host.say("مش شايف رسايل، هجرّب صورة أوضح...")
                val again = readLines(ctx, prefs, g, host, sharp.file) ?: return
                if (again.isNotEmpty()) {
                    messages = again
                    AppLog.d("game", "the sharper picture showed ${again.size} lines that the plain one did not")
                }
            }
        }
        if (messages.isEmpty()) {
            g.idleReads++
            g.emptyReads++
            g.nextReadAt = System.currentTimeMillis() + idleGap(g.idleReads)
            host.say("مش شايف رسايل شات في الصورة. لو المربع مش على الشات دوس «ضبط»", warn = g.idleReads >= 3)
            // a place that was found by itself and shows no chat line again and again may be the wrong place: it is looked for once more
            if (g.profile.auto && g.emptyReads >= 3 && g.rescouts < 2 && System.currentTimeMillis() >= g.rescoutAt) {
                g.rescouts++
                g.rescoutAt = System.currentTimeMillis() + 180_000L
                g.emptyReads = 0
                g.scoutFails = 0
                g.nextScoutAt = 0L
                g.profile = GameProfile()
                g.lastGrid = null
                host.say("المكان اللي لقيته مش بيطلّع رسايل. هدوّر على الشات من الأول...")
            }
            return
        }
        g.emptyReads = 0
        val decision = GameChat.decide(messages, g.handled)
        if (!decision.reply) {
            g.idleReads++
            g.nextReadAt = System.currentTimeMillis() + idleGap(g.idleReads)
            host.say(if (decision.reason == "last_is_mine") "مستني رد من اللاعبين..." else "مستني رسالة جديدة...")
            return
        }
        g.idleReads = 0
        val theirs = messages.last().text

        // a reply that cannot be typed is not worth the time of writing it
        if (!g.profile.canType && ime.liveFieldPackage() != g.pkg) {
            g.nextReadAt = System.currentTimeMillis() + 15_000L
            host.say("فيه رسالة جديدة بس مش عارف مكان خانة الكتابة. دوس «ضبط» وعلّمني مكانها", true)
            return
        }

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
        // the game's send button is looked for on the screen (slowly) only when none is known and the box's own action key sends nothing
        var findSend: (() -> FPoint?)? = null
        if (g.profile.send == null && g.sendLooks < 2) {
            findSend = { GameScout.findSend(ctx, svc, host).also { if (it == null) g.sendLooks++ } }
        }
        val res = GameTyper.send(svc, ime, g.pkg, g.profile, coloured, g.chatOpen, { host.stopped() }, findSend)
        if (res.ok) {
            g.failures = 0
            g.chatOpen = true
            val learned = res.send
            if (learned != null) {
                // the send button that worked is kept for next time
                g.profile = g.profile.copy(send = learned)
                prefs.setGameProfile(GameProfile.key(g.pkg, g.landscape), g.profile)
            }
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
