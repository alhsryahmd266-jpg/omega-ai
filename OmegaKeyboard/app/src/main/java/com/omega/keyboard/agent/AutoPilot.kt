package com.omega.keyboard.agent

import android.content.Context
import android.os.Build
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
import com.omega.keyboard.core.Order
import com.omega.keyboard.core.OrderParser
import com.omega.keyboard.core.ReplyPolicy
import com.omega.keyboard.core.ScrollLogic
import com.omega.keyboard.core.SendFinder
import com.omega.keyboard.core.Screen
import com.omega.keyboard.core.Side
import com.omega.keyboard.core.UiNode
import com.omega.keyboard.ime.OmegaImeService
import com.omega.keyboard.voice.VoiceSession
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Autonomous mode. The keyboard hides, the local model reads the open chat through the accessibility service,
 * writes the next message as the user, presses send, and keeps going until the user presses stop.
 *
 * It starts working the moment it is switched on. While it runs the user can give orders by voice (the microphone
 * button on the control bar switches that on and off) or with the buttons: send exactly this, answer now, follow the
 * conversation, wait. An order is something to carry out, never text to type.
 */
object AutoPilot {
    @Volatile var running = false
        private set
    @Volatile var status = ""
        private set
    @Volatile var repliesSent = 0
        private set
    /** replies are held back (the user said "استنى"); orders still run */
    @Volatile var paused = false
        private set

    /** One thing the user told us to do, outside the normal "answer new messages" routine. */
    private sealed class Job {
        val at = System.currentTimeMillis()
        class Say(val text: String) : Job()
        class Compose(val instruction: String) : Job()
        class ReplyNow : Job()
    }

    private val lock = Any()
    private val instructions = ArrayList<String>()
    private val jobs = ArrayList<Job>()
    @Volatile private var stopFlag = false
    @Volatile private var voiceWanted = false
    @Volatile private var userScrollAt = 0L
    @Volatile private var lastEventWake = 0L
    /** "تابع المحادثة": answer the newest message again even if it counts as answered */
    @Volatile private var forget = false
    @Volatile private var voiceHoldUntil = 0L
    @Volatile private var loopStatus = ""
    @Volatile private var loopWarn = false
    private val wake = Semaphore(0)
    private var worker: Thread? = null
    private val listeners = CopyOnWriteArrayList<(Boolean, String, Boolean) -> Unit>()

    fun addListener(l: (running: Boolean, status: String, warn: Boolean) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Boolean, String, Boolean) -> Unit) { listeners.remove(l) }

    // ------------------------------------------------------------------ what the control bar shows

    private fun emit(text: String, warn: Boolean) {
        status = text
        for (l in listeners) try { l(running, text, warn) } catch (_: Throwable) {}
    }

    private fun paint(text: String, warn: Boolean) {
        val svc = ScreenAgentService.instance ?: return
        if (running) Overlays.withBar(svc, true) { it.setStatus(text, warn) }
    }

    /** Status of the loop itself. A voice note that is still on the bar is not covered up by it (a warning always is). */
    private fun publish(text: String, warn: Boolean = false) {
        loopStatus = text
        loopWarn = warn
        emit(text, warn)
        if (warn || System.currentTimeMillis() >= voiceHoldUntil) paint(text, warn)
    }

    /** A short message for the user (what was heard, what was understood); the loop's own status comes back afterwards. */
    fun note(text: String, warn: Boolean = false, holdMs: Long = 4000) {
        if (!running) return
        voiceHoldUntil = System.currentTimeMillis() + holdMs
        emit(text, warn)
        paint(text, warn)
        Bg.uiDelayed(holdMs + 80) {
            if (running && System.currentTimeMillis() >= voiceHoldUntil && loopStatus.isNotEmpty()) paint(loopStatus, loopWarn)
        }
    }

    /** Speech that is still being recognised. */
    fun heard(partial: String) {
        if (partial.isNotBlank()) note("🎤 " + partial.trim().takeLast(80), holdMs = 1800)
    }

    private fun title() = "الوضع الذاتي · " + if (voiceWanted) "الصوت شغال" else "الصوت مقفول"

    private fun barActions(): List<ControlBar.Action> = listOf(
        if (voiceWanted) ControlBar.Action("voice", "mic", "الصوت شغال", 0xFF188038.toInt())
        else ControlBar.Action("voice", "mic_off", "الصوت مقفول", 0xFF5F6368.toInt()),
        if (paused) ControlBar.Action("pause", "play_arrow", "كمّل", 0xFF1A73E8.toInt())
        else ControlBar.Action("pause", "pause", "استنى", 0xFFB26A00.toInt()),
        ControlBar.Action("stop", "stop", "إيقاف", 0xFFD93025.toInt()),
    )

    private fun refreshBar() {
        val svc = ScreenAgentService.instance ?: return
        if (running) Overlays.withBar(svc, true) { it.setTitle(title()); it.setActions(barActions()) }
    }

    // ------------------------------------------------------------------ start / stop

    fun start(ctx: Context) {
        val svc = ScreenAgentService.instance
        if (svc == null) { Notice.needAccessibility(ctx); return }
        if (running) { Notice.toast(ctx, "الوضع الذاتي شغال بالفعل"); return }
        if (!Llm.isConfigured(ctx)) { Notice.needModel(ctx); return }
        ScreenTranslateController.stop()
        val app = ctx.applicationContext
        val prefs = Prefs.get(app)

        synchronized(lock) {
            instructions.clear()
            jobs.clear()
        }
        stopFlag = false
        paused = false
        forget = false
        userScrollAt = 0L
        voiceHoldUntil = 0L
        voiceWanted = prefs.apListen
        wake.drainPermits()
        running = true
        repliesSent = 0
        loopStatus = "بقرا الشاشة..."
        loopWarn = false
        status = loopStatus

        Overlays.withBar(svc, keepScreenOn = true) { bar ->
            bar.setTitle(title())
            bar.setStatus("بقرا الشاشة...")
            bar.setActions(barActions())
            bar.onAction = { id ->
                when (id) {
                    "stop" -> stop("وقفته من الزرار")
                    "voice" -> toggleVoice(app)
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
                note("النموذج مش بيشتغل: ${e.message}", warn = true, holdMs = 10_000)
            }
        }
        val t = Thread({ loop(app) }, "omega-autopilot")
        t.isDaemon = true
        worker = t
        t.start()
        if (voiceWanted) Bg.ui { VoiceSession.startAuto(app) }
    }

    fun stop(reason: String = "اتوقف", silent: Boolean = false) {
        if (!running) return
        running = false
        stopFlag = true
        paused = false
        wake.release()
        Llm.cancel()
        Bg.ui { VoiceSession.stopAuto() }
        val svc = ScreenAgentService.instance
        if (svc != null) Keyboards.setMode(svc, false)
        Overlays.hideBar()
        status = reason
        AppLog.d("autopilot", "stopped: $reason (replies: $repliesSent)")
        for (l in listeners) try { l(false, reason, false) } catch (_: Throwable) {}
        if (!silent && svc != null) Notice.toast(svc, "الوضع الذاتي وقف: $reason")
    }

    // ------------------------------------------------------------------ the buttons and the voice

    /** The microphone button on the bar: listen for orders / stop listening. */
    fun toggleVoice(ctx: Context) {
        if (!running) return
        val app = ctx.applicationContext
        val on = !voiceWanted
        voiceWanted = on
        Prefs.get(app).apListen = on          // remembered for the next time
        refreshBar()
        Bg.ui { if (on) VoiceSession.startAuto(app, explicit = true) else VoiceSession.stopAuto() }
        note(if (on) "الصوت شغال. قول: قول له… / تابع المحادثة / استنى" else "الصوت اتقفل", holdMs = 3000)
    }

    /** The voice layer gave up (no permission, no recogniser): the switch shows "off" and says why. */
    fun voiceStopped(reason: String, warn: Boolean = true) {
        if (!running) return
        voiceWanted = false
        refreshBar()
        note(reason, warn, holdMs = 7000)
    }

    fun setPaused(on: Boolean) {
        if (!running || paused == on) return
        paused = on
        if (!on) forget = true
        refreshBar()
        note(if (on) "وقفت الردود. دوس «كمّل» أو قول «تابع المحادثة»" else "تمام، بتابع المحادثة", holdMs = 3000)
        wake.release()
    }

    private fun addJob(job: Job) {
        synchronized(lock) {
            jobs.add(job)
            while (jobs.size > 5) jobs.removeAt(0)
        }
    }

    /** Carries out what the user said. Called from the voice layer; every branch tells the user what was understood. */
    fun applyOrder(order: Order) {
        if (!running) return
        when (order) {
            is Order.Say -> { addJob(Job.Say(order.text)); note("تمام، هبعت: ${order.text.take(70)}") }
            is Order.Compose -> { addJob(Job.Compose(order.instruction)); note("تمام، هكتب الرسالة دي وأبعتها...") }
            Order.Reply -> { addJob(Job.ReplyNow()); note("تمام، برد دلوقتي", holdMs = 3000) }
            Order.Follow -> {
                forget = true
                if (paused) setPaused(false) else note("تمام، بتابع المحادثة وهرد على أي رسالة جديدة", holdMs = 3500)
            }
            Order.Pause -> setPaused(true)
            is Order.Style -> {
                synchronized(lock) { instructions.add(order.rule) }
                note("حفظت التعليمات: ${order.rule.take(70)}")
                if (order.replyNow) addJob(Job.ReplyNow())
            }
            is Order.Incomplete -> note(order.hint, warn = true, holdMs = 5000)
            is Order.NotAnOrder -> {
                // other people talking near the phone: never acted on, only mentioned
                if (order.heard.trim().split(Regex("\\s+")).size >= 2) {
                    note("سمعت: «${order.heard.trim().take(50)}» — مش أمر فاتجاهلته", holdMs = 2500)
                }
            }
        }
        wake.release()
    }

    fun clearInstructions() {
        synchronized(lock) {
            instructions.clear()
            jobs.clear()
        }
        note("مسحت كل التعليمات")
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
        if (paused && synchronized(lock) { jobs.isEmpty() }) return
        if (now - lastEventWake >= 1500 && wake.availablePermits() < 1) {
            lastEventWake = now
            wake.release()
        }
    }

    private fun consume(job: Job?) {
        if (job == null) return
        synchronized(lock) { jobs.remove(job) }
    }

    private fun expireJobs() {
        val now = System.currentTimeMillis()
        val old = synchronized(lock) {
            val o = jobs.filter { now - it.at > JOB_TTL_MS }
            jobs.removeAll(o.toSet())
            o
        }
        if (old.isNotEmpty()) publish("الأمر اتلغى لأني مالقيتش محادثة أبعتله فيها", warn = true)
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
            if (heads.any { h -> t == h || (h.length >= 6 && t.contains(h)) || (t.length >= 6 && h.contains(t.take(24))) })
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
        // chats with an AI are conversations as well
        "com.anthropic.claude", "com.openai.chatgpt", "com.google.android.apps.bard", "com.microsoft.copilot", "ai.x.grok", "com.deepseek.chat",
    )
    private val MESSAGE_HINTS = listOf(
        "message", "رسالة", "رسائل", "اكتب", "chat", "type a", "mensaje", "nachricht", "mesaj", "сообщение", "message", "メッセージ", "消息", "메시지",
    )

    fun isKnownChat(pkg: String): Boolean = KNOWN_CHAT.any { pkg == it || pkg.startsWith("$it.") }

    /** The autonomous mode only types into something that looks like a conversation (never a bank app's text field). */
    private fun chatLike(screen: Screen, input: UiNode?): Boolean {
        if (input == null) return false
        if (isKnownChat(screen.pkg)) return true
        val hint = (input.text + " " + input.desc).lowercase()
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
    private const val JOB_TTL_MS = 90_000L

    private fun loop(ctx: Context) {
        val prefs = Prefs.get(ctx)
        val handled = HashMap<String, String>()
        val retryAt = HashMap<String, Long>()
        val sentTexts = HashMap<String, ArrayList<String>>()
        var failures = 0
        var firstReply = true
        var firstRound = true
        var unsettled = 0
        try {
            while (!stopFlag) {
                // the first look happens at once; later ones when something changes (or every few seconds)
                if (firstRound) firstRound = false
                else {
                    wake.tryAcquire(4, TimeUnit.SECONDS)
                    wake.drainPermits()
                    if (stopFlag) break
                    if (nap(400)) break
                }
                val svc = ScreenAgentService.instance ?: break
                expireJobs()
                val job = synchronized(lock) { jobs.firstOrNull() }
                if (forget) { handled.clear(); retryAt.clear(); forget = false }
                if (paused && job == null) { publish("واقف مؤقتًا. دوس «كمّل» أو قول «تابع المحادثة»"); continue }

                // nothing may cover the conversation while we read it
                if (ScreenReader.keyboardVisible(svc)) Keyboards.ensureHidden(svc)
                if (stopFlag) break

                val snap = ScreenReader.read(svc)
                if (snap == null) { publish("مش قادر أقرا الشاشة دي. افتح المحادثة"); continue }
                val pkg = snap.screen.pkg
                if (pkg.isBlank() || pkg == ctx.packageName) { snap.release(); publish("افتح المحادثة وأنا هرد"); continue }
                val sent = sentTexts[pkg] ?: ArrayList()
                var parsed = parseChat(snap.screen, prefs, sent)
                val looksLikeChat = chatLike(snap.screen, parsed.input)
                val moreBelow = looksLikeChat && ScrollLogic.listWithMoreBelow(snap.screen) != null
                snap.release()

                if (!looksLikeChat) {
                    publish("ده مش شكله تطبيق محادثة. افتح المحادثة وأنا هرد (${appLabel(ctx, pkg)})")
                    continue
                }

                // the newest messages may sit below the part that is on screen: go down to them before reading
                // (a message we only have to send does not need them)
                if (job !is Job.Say && moreBelow && ChatScroll.canTry()) {
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

                val decision = ReplyPolicy.decide(parsed, handled[pkg], force = job != null, unknownAsThem = true)
                var workKey = decision.key
                if (!decision.reply) {
                    publish(describe(decision.reason, ctx, pkg))
                    continue
                }
                if (job is Job.ReplyNow && parsed.messages.isEmpty()) {
                    consume(job)
                    publish("مش شايف رسايل أرد عليها", warn = true)
                    continue
                }
                // the user is typing a message themselves: never overwrite it (the hint text is only reliable from Android 8)
                val field = parsed.input
                if (job == null && Build.VERSION.SDK_INT >= 26 && field != null && !field.showingHint && !Matchers.isComposerHint(field.text, field.desc)) {
                    publish("فيه نص مكتوب في الخانة، مستني تبعته أو تمسحه...")
                    continue
                }
                val retry = retryAt[pkg]
                if (job == null && retry != null && System.currentTimeMillis() < retry) continue

                if (job == null) {
                    publish("فيه رسالة جديدة، بكتب الرد...")
                    // the other side may still be typing (or an AI chat may still be streaming its answer): look again
                    // after a moment and only answer once the end of the conversation has stopped changing
                    val lo = prefs.apDelayMinSec.coerceAtLeast(0)
                    val hi = maxOf(prefs.apDelayMaxSec, lo)
                    val pause = if (firstReply) 700L else (lo * 1000L) + Random.nextLong((hi - lo) * 1000L + 1)
                    if (nap(maxOf(pause, 700L))) break
                    val s2 = ScreenReader.read(svc) ?: continue
                    val p2 = parseChat(s2.screen, prefs, sent)
                    s2.release()
                    if (ReplyPolicy.key(p2.messages) != decision.key) {
                        // still changing: look again, but never wait forever (an animated indicator may keep changing the text)
                        if (++unsettled < 4) { publish("الرسالة لسه بتتكتب، هستنى..."); continue }
                        workKey = ReplyPolicy.key(p2.messages)
                        parsed = p2
                    }
                    unsettled = 0
                    if (paused) continue
                } else {
                    publish(if (job is Job.Say) "بكتب اللي قلته..." else "بكتب الرسالة...")
                }

                // ---- the message to send
                val reply: String? = if (job is Job.Say) job.text else {
                    if (Llm.loading) publish("النموذج بيتحمّل، ثواني...")
                    val system = LlmPrompts.replySystem(prefs.apPersona, synchronized(lock) { instructions.toList() })
                    val user = if (job is Job.Compose) LlmPrompts.composeUser(parsed.messages, job.instruction) else LlmPrompts.replyUser(parsed.messages)
                    val raw = try {
                        Llm.generate(ctx, system, user, maxTokens = 180, temperature = 0.8f, topK = 40, topP = 0.95f, timeoutMs = 150_000)
                    } catch (e: Exception) {
                        if (stopFlag) break
                        failures++
                        retryAt[pkg] = System.currentTimeMillis() + 15_000
                        publish("النموذج فشل: ${e.message}", warn = true)
                        AppLog.e("autopilot", "generate failed", e)
                        if (failures >= 3) { stop("النموذج فشل 3 مرات: ${e.message}"); break }
                        continue
                    }
                    if (stopFlag) break
                    var r = LlmPrompts.cleanReply(raw)
                    // a small model sometimes hands the order straight back; never send that, word it ourselves
                    if (job is Job.Compose && (r == null || LlmPrompts.echoes(r, job.instruction))) r = OrderParser.composeFallback(job.instruction)
                    r
                }
                if (reply == null) {
                    handled[pkg] = workKey
                    if (job != null) {
                        consume(job)
                        publish("مقدرتش أكتب الرسالة دي. جرّب تقول: قول له ... وأنا أبعتها زي ما هي", warn = true)
                    } else {
                        publish("مفيش داعي أرد على الرسالة دي")
                    }
                    continue
                }
                // "استنى" may have been said while the model was writing
                if (paused && job == null) { publish("وقفت الردود ومبعتش الرد. دوس «كمّل» أو قول «تابع المحادثة»"); continue }

                // last look before sending: a newer message means this reply is already out of date
                if (job == null) {
                    val s3 = ScreenReader.read(svc)
                    if (s3 != null) {
                        val p3 = parseChat(s3.screen, prefs, sent)
                        s3.release()
                        if (ReplyPolicy.key(p3.messages) != workKey) {
                            publish("وصلت رسالة أحدث، هرد عليها بدل القديمة")
                            continue
                        }
                    }
                }

                if (!prefs.apAutoSend) {
                    val ok = SendAction.typeOnly(svc, reply)
                    handled[pkg] = workKey
                    if (ok) consume(job)
                    publish(if (ok) "كتبت الرد. ابعته إنت: $reply" else "مقدرتش أكتب الرد", warn = !ok)
                    continue
                }

                val res = SendAction.sendText(svc, reply, allowBlind = isKnownChat(pkg))
                if (res.ok) {
                    failures = 0
                    repliesSent++
                    firstReply = false
                    handled[pkg] = workKey
                    consume(job)
                    sent.add(reply)
                    while (sent.size > 12) sent.removeAt(0)
                    sentTexts[pkg] = sent
                    learnAlignment(svc, prefs, pkg, reply)
                    AppLog.d("autopilot", "sent via ${res.how}: ${reply.take(60)}")
                    publish("ردّيت $repliesSent مرة. آخر رد: ${reply.take(48)}")
                    if (repliesSent >= prefs.apMaxReplies) { stop("وصلت للحد الأقصى (${prefs.apMaxReplies} رد)"); break }
                } else {
                    failures++
                    retryAt[pkg] = System.currentTimeMillis() + 20_000
                    publish("مقدرتش أبعت: ${res.detail}", warn = true)
                    AppLog.d("autopilot", "send failed: ${res.detail}")
                    if (failures >= 3) { stop("وقفت لأني مقدرتش أبعت 3 مرات: ${res.detail}"); break }
                }
            }
        } catch (t: Throwable) {
            AppLog.e("autopilot", "loop crashed", t)
            stop("حصل خطأ: " + (t.message ?: t.javaClass.simpleName).take(80))
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
