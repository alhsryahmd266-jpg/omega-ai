package com.omega.keyboard.agent

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.omega.keyboard.AppLog
import com.omega.keyboard.Bg
import com.omega.keyboard.Prefs
import com.omega.keyboard.ai.Llm
import com.omega.keyboard.core.Align
import com.omega.keyboard.core.ArabicText
import com.omega.keyboard.core.ChatMessage
import com.omega.keyboard.core.ChatParseResult
import com.omega.keyboard.core.ChatParser
import com.omega.keyboard.core.LlmPrompts
import com.omega.keyboard.core.Matchers
import com.omega.keyboard.core.ReplyPolicy
import com.omega.keyboard.core.ScrollLogic
import com.omega.keyboard.core.SendFinder
import com.omega.keyboard.core.Screen
import com.omega.keyboard.core.Side
import com.omega.keyboard.core.UiNode
import com.omega.keyboard.ime.OmegaImeService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Autonomous mode, one button: the keyboard hides, the local model reads the open chat through the accessibility
 * service, writes the next message as the user (as if the user were typing it himself), presses send, and keeps going with
 * every new message, all day if need be, until the user presses stop. It starts working the moment it is switched on and
 * never stops by itself: a failure (model, send button, a crash in one round) is waited out and tried again, never a reason
 * to quit. There are no spoken orders: it reads, writes, sends.
 */
object AutoPilot {
    @Volatile var running = false
        private set
    @Volatile var status = ""
        private set
    @Volatile var repliesSent = 0
        private set
    /** replies are held back until the user presses "كمّل" */
    @Volatile var paused = false
        private set

    @Volatile private var stopFlag = false
    @Volatile private var userScrollAt = 0L
    @Volatile private var lastEventWake = 0L
    /** after "كمّل": answer the newest message again even if it counts as answered */
    @Volatile private var forget = false
    private val wake = Semaphore(0)
    private var worker: Thread? = null
    private val listeners = CopyOnWriteArrayList<(Boolean, String, Boolean) -> Unit>()

    fun addListener(l: (running: Boolean, status: String, warn: Boolean) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Boolean, String, Boolean) -> Unit) { listeners.remove(l) }

    // ------------------------------------------------------------------ what the control bar shows

    private const val TITLE = "الوضع الذاتي"

    private fun emit(text: String, warn: Boolean) {
        status = text
        for (l in listeners) try { l(running, text, warn) } catch (_: Throwable) {}
    }

    private fun paint(text: String, warn: Boolean) {
        val svc = ScreenAgentService.instance ?: return
        if (running) Overlays.withBar(svc, true) { it.setStatus(text, warn) }
    }

    private fun publish(text: String, warn: Boolean = false) {
        emit(text, warn)
        paint(text, warn)
    }

    private fun barActions(): List<ControlBar.Action> = listOf(
        if (paused) ControlBar.Action("pause", "play_arrow", "كمّل", 0xFF1A73E8.toInt())
        else ControlBar.Action("pause", "pause", "استنى", 0xFFB26A00.toInt()),
        ControlBar.Action("stop", "stop", "إيقاف", 0xFFD93025.toInt()),
    )

    private fun refreshBar() {
        val svc = ScreenAgentService.instance ?: return
        if (running) Overlays.withBar(svc, true) { it.setTitle(TITLE); it.setActions(barActions()) }
    }

    // ------------------------------------------------------------------ start / stop

    fun start(ctx: Context) {
        val svc = ScreenAgentService.instance
        if (svc == null) { Notice.needAccessibility(ctx); return }
        if (running) { Notice.toast(ctx, "الوضع الذاتي شغال بالفعل"); return }
        if (!Llm.isConfigured(ctx)) { Notice.needModel(ctx); return }
        ScreenTranslateController.stop()
        val app = ctx.applicationContext

        stopFlag = false
        paused = false
        forget = false
        userScrollAt = 0L
        wake.drainPermits()
        Llm.keepLoaded = true          // answers must come fast: the model stays in memory for the whole run
        running = true
        repliesSent = 0
        status = "بقرا الشاشة..."

        Overlays.withBar(svc, keepScreenOn = true) { bar ->
            bar.setTitle(TITLE)
            bar.setStatus(status)
            bar.setActions(barActions())
            bar.onAction = { id ->
                when (id) {
                    "stop" -> stop("وقفته من الزرار")
                    "pause" -> setPaused(!paused)
                }
            }
        }
        // keep our keyboard (and every other one) out of the way for the whole run
        OmegaImeService.instance?.hideKeyboard()
        Keyboards.setMode(svc, true)
        for (l in listeners) try { l(true, status, false) } catch (_: Throwable) {}

        // the model loads in the background while the first screen is already being read
        Bg.io {
            try {
                Llm.ensureLoaded(app)
            } catch (e: Exception) {
                AppLog.e("autopilot", "model load failed", e)
            }
        }
        val t = Thread({ loop(app) }, "omega-autopilot")
        t.isDaemon = true
        worker = t
        t.start()
    }

    fun stop(reason: String = "اتوقف", silent: Boolean = false) {
        if (!running) return
        running = false
        stopFlag = true
        paused = false
        Llm.keepLoaded = false
        wake.release()
        Llm.cancel()
        val svc = ScreenAgentService.instance
        if (svc != null) Keyboards.setMode(svc, false)
        Overlays.hideBar()
        status = reason
        AppLog.d("autopilot", "stopped: $reason (replies: $repliesSent)")
        for (l in listeners) try { l(false, reason, false) } catch (_: Throwable) {}
        if (!silent && svc != null) Notice.toast(svc, "الوضع الذاتي وقف: $reason")
    }

    fun setPaused(on: Boolean) {
        if (!running || paused == on) return
        paused = on
        if (!on) forget = true
        refreshBar()
        publish(if (on) "واقف مؤقتًا. دوس «كمّل» لما تحب أكمّل." else "تمام، بتابع المحادثة")
        wake.release()
    }

    /**
     * Fed by the accessibility service with every content / scroll event of other apps: new content wakes the loop
     * (at most every 1.5 s, chat apps emit events all the time); a scroll we did not cause means the user is scrolling.
     */
    fun onScreenEvent(type: Int) {
        if (!running) return
        val now = System.currentTimeMillis()
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (now > ChatScroll.ownScrollUntil) userScrollAt = now
            return
        }
        if (paused) return
        if (now - lastEventWake >= 1500 && wake.availablePermits() < 1) {
            lastEventWake = now
            wake.release()
        }
    }

    // ------------------------------------------------------------------ the loop

    private fun nap(ms: Long): Boolean {
        // returns true when we were asked to stop
        var left = ms
        while (left > 0 && !stopFlag) {
            val step = minOf(left, 250L)
            try { Thread.sleep(step) } catch (_: InterruptedException) { return true }
            left -= step
        }
        return stopFlag
    }

    private fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg.substringAfterLast('.')
    }

    private fun norm(s: String) = ArabicText.normalize(s).replace(Regex("\\s+"), " ").trim()

    /** Anything that matches a message we sent ourselves is mine, whatever the layout heuristics said. */
    private fun fixSides(res: ChatParseResult, sent: List<String>): ChatParseResult {
        if (sent.isEmpty()) return res
        val heads = sent.map { norm(it).take(24) }.filter { it.length >= 2 }
        val fixed = res.messages.map { m ->
            val t = norm(m.text)
            // a short text ("ok", "تمام") that sits clearly on the other side is theirs, not a copy of what I sent
            val clearlyTheirs = m.side == Side.THEM && t.length < 6
            if (!clearlyTheirs && heads.any { h -> t == h || (h.length >= 6 && t.contains(h)) || (t.length >= 6 && h.contains(t.take(24))) })
                ChatMessage(m.text, Side.ME, m.top, m.bottom, m.nodeId)
            else m
        }
        return ChatParseResult(fixed, res.input, res.learnedAlign)
    }

    private val KNOWN_CHAT = listOf(
        "com.whatsapp", "com.gbwhatsapp", "com.yowhatsapp", "com.fmwhatsapp", "org.telegram", "org.thunderdog.challegram",
        "com.facebook.orca", "com.facebook.mlite", "com.instagram.android", "com.viber.voip", "org.thoughtcrime.securesms",
        "jp.naver.line", "com.snapchat.android", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.mms", "com.discord", "com.Slack", "com.microsoft.teams", "com.imo.android", "com.tencent.mm", "com.skype",
        "com.kakao.talk", "app.threema", "im.vector", "com.vk.im", "com.bbm", "com.truecaller", "com.twitter.android", "com.linkedin.android",
    )
    /** chats with an AI are conversations as well; their answers stream in word by word, so they get more patience */
    private val AI_CHAT = listOf(
        "com.anthropic.claude", "com.openai.chatgpt", "com.google.android.apps.bard", "com.microsoft.copilot", "ai.x.grok", "com.deepseek.chat",
    )

    private fun isAiChat(pkg: String): Boolean = AI_CHAT.any { pkg == it || pkg.startsWith("$it.") }
    private val MESSAGE_HINTS = listOf(
        "message", "رسالة", "رسائل", "مراسلة", "اكتب", "chat", "type a", "mensaje", "nachricht", "mesaj", "сообщение", "メッセージ", "消息", "메시지",
    )

    fun isKnownChat(pkg: String): Boolean = KNOWN_CHAT.any { pkg == it || pkg.startsWith("$it.") } || isAiChat(pkg)

    /** The autonomous mode only types into something that looks like a conversation (never a bank app's text field). */
    private fun chatLike(screen: Screen, input: UiNode?): Boolean {
        if (input == null) return false
        if (isKnownChat(screen.pkg)) return true
        val hint = (input.text + " " + input.desc + " " + input.hint).lowercase()
        if (MESSAGE_HINTS.any { hint.contains(it) }) return true
        return SendFinder.candidates(screen, input).any { it.score >= 80 }
    }

    private fun alignFor(prefs: Prefs, pkg: String): Align = when (prefs.apSide) {
        "left" -> Align.LEFT
        "right" -> Align.RIGHT
        else -> prefs.align(pkg)
    }

    /** Parses the chat; when delivery ticks prove which edge is "mine" in this app, that is remembered. */
    private fun parseChat(screen: Screen, prefs: Prefs, sent: List<String>): ChatParseResult {
        val res = fixSides(ChatParser.parse(screen, alignFor(prefs, screen.pkg)), sent)
        val learned = res.learnedAlign
        if (learned != null && prefs.apSide == "auto" && learned != prefs.align(screen.pkg)) {
            prefs.setAlign(screen.pkg, learned)
            AppLog.d("autopilot", "my bubbles are on the ${learned.name} in ${screen.pkg} (from delivery ticks)")
        }
        return res
    }

    private fun describe(reason: String, ctx: Context, pkg: String): String = when (reason) {
        "no_input" -> "مفيش محادثة مفتوحة. افتح محادثة وأنا هرد (${appLabel(ctx, pkg)})"
        "no_messages" -> "مش شايف رسايل على الشاشة"
        "last_is_mine" -> "مستني رد من الطرف التاني..."
        "already_handled" -> "مستني رسالة جديدة..."
        else -> "براقب المحادثة..."
    }

    private const val USER_SCROLL_GRACE_MS = 6000L
    /** a text in the message box that does not change for this long is not being typed any more: the reply replaces it */
    private const val DRAFT_GRACE_MS = 5000L

    /** A reply that is written but is not out yet (the send did not work): tried again as it is, not written anew. */
    private class Waiting(val key: String, val reply: String)

    private fun loop(ctx: Context) {
        val prefs = Prefs.get(ctx)
        val handled = HashMap<String, String>()
        val retryAt = HashMap<String, Long>()
        val sentTexts = HashMap<String, ArrayList<String>>()
        val waiting = HashMap<String, Waiting>()
        val noReply = HashMap<String, Int>()
        var modelFailures = 0
        var sendFailures = 0
        var crashes = 0
        var lastCrashAt = 0L
        var firstReply = true
        var firstRound = true
        var stale = 0
        var draftText: String? = null
        var draftSince = 0L
        while (!stopFlag) {
            // one round. Whatever goes wrong inside it ends the round, never the run: the loop only ends when the user stops it.
            try {
                // the first look happens at once; later ones when something changes (or every few seconds)
                if (firstRound) firstRound = false
                else {
                    wake.tryAcquire(4, TimeUnit.SECONDS)
                    wake.drainPermits()
                    if (stopFlag) break
                    if (nap(400)) break
                }
                val svc = ScreenAgentService.instance
                if (svc == null) {
                    publish("خدمة إمكانية الوصول مقفولة. فعّلها من الإعدادات وأنا هكمّل", warn = true)
                    if (nap(3000)) break
                    continue
                }
                if (forget) { handled.clear(); retryAt.clear(); waiting.clear(); noReply.clear(); forget = false }
                if (paused) { publish("واقف مؤقتًا. دوس «كمّل»"); continue }

                // nothing may cover the conversation while we read it
                if (ScreenReader.keyboardVisible(svc)) Keyboards.ensureHidden(svc)
                if (stopFlag) break

                val snap = ScreenReader.read(svc)
                if (snap == null) { publish("مش قادر أقرا الشاشة دي. افتح المحادثة"); continue }
                val pkg = snap.screen.pkg
                if (pkg.isBlank() || pkg == ctx.packageName) { snap.release(); publish("افتح المحادثة وأنا هرد"); continue }
                val sent = sentTexts[pkg] ?: ArrayList<String>().also { sentTexts[pkg] = it }
                var parsed = parseChat(snap.screen, prefs, sent)
                val looksLikeChat = chatLike(snap.screen, parsed.input)
                val moreBelow = looksLikeChat && ScrollLogic.listWithMoreBelow(snap.screen) != null
                snap.release()

                if (!looksLikeChat) {
                    publish("ده مش شكله تطبيق محادثة. افتح المحادثة وأنا هرد (${appLabel(ctx, pkg)})")
                    continue
                }

                // the newest messages may sit below the part that is on screen: go down to them before reading
                if (moreBelow && ChatScroll.canTry()) {
                    if (System.currentTimeMillis() - userScrollAt < USER_SCROLL_GRACE_MS) {
                        publish("مستنيك تخلّص سكرول وبعدين هنزل لآخر الرسائل...")
                        continue
                    }
                    publish("بنزّل لآخر الرسائل...")
                    ChatScroll.toLatest(svc) { stopFlag }
                    if (stopFlag) break
                    val s2 = ScreenReader.read(svc) ?: continue
                    parsed = parseChat(s2.screen, prefs, sent)
                    s2.release()
                }

                val decision = ReplyPolicy.decide(parsed, handled[pkg], force = false, unknownAsThem = true)
                var workKey = decision.key
                if (!decision.reply) {
                    stale = 0
                    publish(describe(decision.reason, ctx, pkg))
                    continue
                }

                // somebody is typing in the box right now: never write over it. What merely sits there (a leftover, or
                // the box's grey prompt that an app hands over as if it were text) is replaced after a few seconds.
                val field = parsed.input
                val draft = field?.takeIf { Matchers.isDraft(it.text, it.showingHint, it.desc, it.hint) }?.text?.trim()
                if (draft != null) {
                    val now = System.currentTimeMillis()
                    if (draft != draftText) { draftText = draft; draftSince = now }
                    if (now - draftSince < DRAFT_GRACE_MS) {
                        publish("فيه نص بيتكتب في الخانة، هستنى ثواني...")
                        continue
                    }
                } else {
                    draftText = null
                }
                val retry = retryAt[pkg]
                if (retry != null && System.currentTimeMillis() < retry) continue

                publish("فيه رسالة جديدة، بكتب الرد...")
                var reply: String? = waiting[pkg]?.takeIf { it.key == workKey }?.reply     // written before, not sent yet
                if (reply == null) {
                    // a person takes a moment to answer
                    val lo = prefs.apDelayMinSec.coerceAtLeast(0)
                    val hi = maxOf(prefs.apDelayMaxSec, lo)
                    val pause = if (firstReply) 700L else (lo * 1000L) + Random.nextLong((hi - lo) * 1000L + 1)
                    if (nap(maxOf(pause, 700L))) break
                    // the other side may still be typing (or an AI chat may still be streaming its answer): look again every
                    // second or so and answer once the end of the conversation has held still for two looks in a row
                    var seen = decision.key
                    var calm = 0
                    var looks = 0
                    val maxLooks = if (isAiChat(pkg)) 45 else 10
                    while (calm < 2 && looks < maxLooks && !stopFlag) {
                        if (nap(1200)) break
                        val s2 = ScreenReader.read(svc) ?: break
                        val p2 = parseChat(s2.screen, prefs, sent)
                        s2.release()
                        looks++
                        val k = ReplyPolicy.key(p2.messages)
                        if (k == seen) calm++
                        else { seen = k; calm = 0; parsed = p2; publish("الرسالة لسه بتتكتب، هستنى...") }
                    }
                    if (stopFlag) break
                    // the situation may have turned while we waited (the last message is mine now, or gone): decide again
                    val d2 = ReplyPolicy.decide(parsed, handled[pkg], force = false, unknownAsThem = true)
                    if (!d2.reply) { publish(describe(d2.reason, ctx, pkg)); continue }
                    workKey = d2.key
                    if (paused) continue

                    // ---- the message to send: written as me, and always an answer
                    if (Llm.loading) publish("النموذج بيتحمّل، ثواني...")
                    val system = LlmPrompts.replySystem(prefs.apPersona)
                    val user = LlmPrompts.replyUser(parsed.messages)
                    // what the model is given, so a reply that misses the point can be traced to what it read
                    AppLog.d("autopilot", "reading: " + user.replace('\n', '⏎').takeLast(500))
                    var tries = 0
                    var modelFailed = false
                    while (reply == null && tries < 3 && !stopFlag) {
                        tries++
                        val raw = try {
                            // a calm temperature keeps a small model on the subject; the retries loosen up only if the first try was unusable
                            Llm.generate(
                                ctx, system, user, maxTokens = 320, temperature = if (tries == 1) 0.6f else 0.85f,
                                topK = 40, topP = 0.9f, timeoutMs = 240_000
                            )
                        } catch (e: Exception) {
                            if (stopFlag) break
                            modelFailed = true
                            AppLog.e("autopilot", "generate failed", e)
                            null
                        }
                        if (raw == null) break
                        reply = LlmPrompts.cleanReply(raw)
                    }
                    if (stopFlag) break
                    if (modelFailed) {
                        // the model process died, ran out of memory or timed out: wait a little, load it afresh, try again. Never quit.
                        modelFailures++
                        retryAt[pkg] = System.currentTimeMillis() + minOf(60_000L, 5_000L * modelFailures)
                        publish("النموذج وقف أو اتأخر (${Llm.lastError.take(60)}). هحمّله من جديد وأحاول تاني...", warn = true)
                        if (modelFailures % 3 == 0) try { Llm.unload() } catch (_: Throwable) {}
                        continue
                    }
                    modelFailures = 0
                    if (reply == null) {
                        // three tries and nothing usable: look again once more later; only then leave this message alone
                        val n = (noReply[workKey] ?: 0) + 1
                        noReply[workKey] = n
                        if (n >= 2) { handled[pkg] = workKey; publish("مقدرتش أكتب رد مناسب للرسالة دي. هستنى اللي بعدها.") }
                        else { retryAt[pkg] = System.currentTimeMillis() + 8_000; publish("بحاول أكتب الرد تاني...") }
                        continue
                    }
                }
                val text: String = checkNotNull(reply)
                // "استنى" may have been pressed while the model was writing
                if (paused) { publish("وقفت الردود ومبعتش الرد. دوس «كمّل»"); continue }

                // last look before sending: a newer message means this reply is already out of date
                // (twice at most: a chat that keeps changing must not keep us from ever sending)
                val s3 = ScreenReader.read(svc)
                if (s3 != null) {
                    val p3 = parseChat(s3.screen, prefs, sent)
                    s3.release()
                    if (ReplyPolicy.key(p3.messages) != workKey && stale < 2) {
                        stale++
                        waiting.remove(pkg)
                        publish("وصلت رسالة أحدث، هرد عليها بدل القديمة")
                        continue
                    }
                }
                stale = 0

                if (!prefs.apAutoSend) {
                    val ok = SendAction.typeOnly(svc, text)
                    handled[pkg] = workKey
                    publish(if (ok) "كتبت الرد. ابعته إنت: $text" else "مقدرتش أكتب الرد", warn = !ok)
                    continue
                }

                publish("بكتب الرد وببعته...")
                // from now on a bubble with this text is mine whatever the send check says: a reply that did go out must
                // never be taken for a message from the other side (that would be an endless conversation with myself)
                if (sent.lastOrNull() != text) sent.add(text)
                while (sent.size > 12) sent.removeAt(0)
                val res = SendAction.sendText(svc, text)
                if (res.ok) {
                    sendFailures = 0
                    modelFailures = 0
                    crashes = 0
                    waiting.remove(pkg)
                    repliesSent++
                    firstReply = false
                    handled[pkg] = workKey
                    learnAlignment(svc, prefs, pkg, text)
                    AppLog.d("autopilot", "sent via ${res.how}: ${text.take(60)}")
                    publish("ردّيت $repliesSent مرة. آخر رد: ${text.take(48)}")
                } else {
                    // not a reason to stop: the same reply is tried again after a wait that grows to half a minute
                    sendFailures++
                    waiting[pkg] = Waiting(workKey, text)
                    retryAt[pkg] = System.currentTimeMillis() + minOf(30_000L, 6_000L * sendFailures)
                    publish("مقدرتش أبعت: ${res.detail}. هحاول تاني...", warn = true)
                    AppLog.d("autopilot", "send failed ($sendFailures): ${res.detail}")
                }
            } catch (t: Throwable) {
                if (stopFlag) break
                val now = System.currentTimeMillis()
                if (now - lastCrashAt > 60_000) crashes = 0
                lastCrashAt = now
                crashes++
                AppLog.e("autopilot", "round failed", t)
                publish("حصلت مشكلة صغيرة وبكمّل: " + (t.message ?: t.javaClass.simpleName).take(50), warn = true)
                if (nap(if (crashes < 5) 2_000L else 10_000L)) break
            }
        }
    }

    /** After our own message is on screen, remember which edge it sits on: that is "my side" in this app. */
    private fun learnAlignment(svc: ScreenAgentService, prefs: Prefs, pkg: String, text: String) {
        try {
            Thread.sleep(500)
            val s = ScreenReader.read(svc) ?: return
            val head = norm(text).take(24)
            val n = s.screen.nodes.firstOrNull { !it.editable && it.visible && head.length >= 3 && norm(it.label).contains(head) }
            if (n != null) {
                val left = n.left
                val right = s.screen.width - n.right
                if (prefs.apSide == "auto" && kotlin.math.abs(left - right) > s.screen.width * 0.08) {
                    prefs.setAlign(pkg, if (left < right) Align.LEFT else Align.RIGHT)
                }
            }
            s.release()
        } catch (_: Throwable) {
        }
    }
}
