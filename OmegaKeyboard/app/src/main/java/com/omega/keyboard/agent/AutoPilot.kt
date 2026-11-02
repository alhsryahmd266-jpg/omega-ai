package com.omega.keyboard.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
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
import com.omega.keyboard.core.ReplyPolicy
import com.omega.keyboard.core.SendFinder
import com.omega.keyboard.core.Side
import com.omega.keyboard.ime.OmegaImeService
import com.omega.keyboard.voice.VoiceSession
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Autonomous mode. The keyboard hides, the local model reads the open chat through the accessibility service,
 * writes the next message as the user, presses send, and keeps going until the user presses stop.
 */
object AutoPilot {
    @Volatile var running = false
        private set
    @Volatile var status = ""
        private set
    @Volatile var repliesSent = 0
        private set

    private val lock = Any()
    private val instructions = ArrayList<String>()
    @Volatile private var directive: String? = null
    @Volatile private var stopFlag = false
    private val wake = Semaphore(0)
    private var worker: Thread? = null
    private val listeners = CopyOnWriteArrayList<(Boolean, String, Boolean) -> Unit>()

    fun addListener(l: (running: Boolean, status: String, warn: Boolean) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Boolean, String, Boolean) -> Unit) { listeners.remove(l) }

    private fun publish(text: String, warn: Boolean = false) {
        status = text
        val r = running
        val svc = ScreenAgentService.instance
        if (svc != null && r) Overlays.withBar(svc, true) { it.setStatus(text, warn) }
        for (l in listeners) try { l(r, text, warn) } catch (_: Throwable) {}
    }

    /** Lets the voice layer show what it heard / understood on the control bar. */
    fun note(text: String, warn: Boolean = false) { if (running) publish(text, warn) }

    // ------------------------------------------------------------------ start / stop

    fun start(ctx: Context, firstInstruction: String? = null) {
        val svc = ScreenAgentService.instance
        if (svc == null) { Notice.needAccessibility(ctx); return }
        if (running) { firstInstruction?.let { addInstruction(it) }; return }
        if (!Llm.isConfigured(ctx)) { Notice.needModel(ctx); return }
        ScreenTranslateController.stop()
        val app = ctx.applicationContext

        synchronized(lock) {
            instructions.clear()
            directive = null
        }
        stopFlag = false
        running = true
        repliesSent = 0
        status = "بجهّز..."
        if (!firstInstruction.isNullOrBlank()) addInstruction(firstInstruction)

        Overlays.withBar(svc, keepScreenOn = true) { bar ->
            bar.setTitle("الوضع الذاتي شغال")
            bar.setStatus("بجهّز...")
            bar.setActions(
                listOf(
                    ControlBar.Action("mic", "mic"),
                    ControlBar.Action("stop", "stop", "إيقاف", 0xFFD93025.toInt()),
                )
            )
            bar.onAction = { id ->
                when (id) {
                    "stop" -> stop("وقفته من الزرار")
                    "mic" -> VoiceSession.toggleForAutopilot(app)
                }
            }
        }
        // keep our keyboard (and every other one) out of the way for the whole run
        OmegaImeService.instance?.hideKeyboard()
        try { svc.softKeyboardController.setShowMode(AccessibilityService.SHOW_MODE_HIDDEN) } catch (_: Throwable) {}
        for (l in listeners) try { l(true, status, false) } catch (_: Throwable) {}

        val t = Thread({ loop(app) }, "omega-autopilot")
        t.isDaemon = true
        worker = t
        t.start()
    }

    fun stop(reason: String = "اتوقف", silent: Boolean = false) {
        if (!running) return
        running = false
        stopFlag = true
        wake.release()
        Llm.cancel()
        Bg.ui { VoiceSession.cancel() }
        val svc = ScreenAgentService.instance
        try { svc?.softKeyboardController?.setShowMode(AccessibilityService.SHOW_MODE_AUTO) } catch (_: Throwable) {}
        Overlays.hideBar()
        status = reason
        AppLog.d("autopilot", "stopped: $reason (replies: $repliesSent)")
        for (l in listeners) try { l(false, reason, false) } catch (_: Throwable) {}
        if (!silent && svc != null) Notice.toast(svc, "الوضع الذاتي وقف: $reason")
    }

    // ------------------------------------------------------------------ instructions from the user (voice)

    private val DIRECTIVE_STARTS = listOf(
        "قول", "قوله", "قولها", "قولو", "قولهم", "ابعت", "ابعتله", "ابعتلها", "اكتب", "اكتبله", "اكتبلها", "رد", "ردي", "ارد", "جاوب",
        "tell", "say", "reply", "send", "write", "answer"
    )

    /** "قوله إني جاي بعد ساعة" -> one message right now. "من دلوقتي رد بأسلوب رسمي" -> keeps applying to every reply. */
    fun addInstruction(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val first = ArabicText.normalize(t).substringBefore(' ').lowercase()
        val oneOff = DIRECTIVE_STARTS.any { ArabicText.normalize(it) == first }
        if (oneOff) {
            directive = t
            publish("تمام، هبعت: $t")
        } else {
            synchronized(lock) { instructions.add(t) }
            publish("حفظت التعليمات: $t")
        }
        wake.release()
    }

    fun clearInstructions() {
        synchronized(lock) { instructions.clear() }
        directive = null
        publish("مسحت كل التعليمات")
    }

    fun onScreenEvent() {
        if (running && wake.availablePermits() < 1) wake.release()
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
        return ChatParseResult(fixed, res.input)
    }

    private fun describe(reason: String, ctx: Context, pkg: String): String = when (reason) {
        "no_input" -> "مفيش محادثة مفتوحة. افتح محادثة وأنا هرد (${appLabel(ctx, pkg)})"
        "no_messages" -> "مش شايف رسايل على الشاشة"
        "last_is_mine" -> "مستني رد من الطرف التاني..."
        "already_handled" -> "مستني رسالة جديدة..."
        else -> "براقب المحادثة..."
    }

    private fun loop(ctx: Context) {
        val prefs = Prefs.get(ctx)
        val handled = HashMap<String, String>()
        val retryAt = HashMap<String, Long>()
        val sentTexts = HashMap<String, ArrayList<String>>()
        var failures = 0
        try {
            publish("بحمّل النموذج...")
            try {
                Llm.ensureLoaded(ctx)
            } catch (e: Exception) {
                stop("النموذج مش بيشتغل: " + (e.message ?: ""), silent = false)
                return
            }
            publish(preflight(ctx))

            while (!stopFlag) {
                wake.tryAcquire(4, TimeUnit.SECONDS)
                wake.drainPermits()
                if (stopFlag) break
                if (nap(600)) break

                val svc = ScreenAgentService.instance ?: break
                val snap = ScreenReader.read(svc) ?: continue
                val pkg = snap.screen.pkg
                if (pkg.isBlank() || pkg == ctx.packageName) { snap.release(); continue }
                val sent = sentTexts[pkg] ?: ArrayList()
                val parsed = fixSides(ChatParser.parse(snap.screen, prefs.align(pkg)), sent)
                snap.release()

                val dir = directive
                val decision = ReplyPolicy.decide(parsed, handled[pkg], force = dir != null, unknownAsThem = true)
                if (!decision.reply) {
                    publish(describe(decision.reason, ctx, pkg))
                    continue
                }
                val retry = retryAt[pkg]
                if (dir == null && retry != null && System.currentTimeMillis() < retry) continue

                // a person takes a moment to read and to type
                publish("فيه رسالة جديدة، بكتب الرد...")
                if (dir == null) {
                    val lo = prefs.apDelayMinSec.coerceAtLeast(0)
                    val hi = maxOf(prefs.apDelayMaxSec, lo)
                    if (nap((lo * 1000L) + Random.nextLong((hi - lo) * 1000L + 1))) break
                    // maybe they wrote more in the meantime: decide again on the fresh screen
                    val s2 = ScreenReader.read(svc) ?: continue
                    val p2 = fixSides(ChatParser.parse(s2.screen, prefs.align(pkg)), sent)
                    s2.release()
                    if (ReplyPolicy.key(p2.messages) != decision.key) continue
                }
                if (dir != null) directive = null

                val system = LlmPrompts.replySystem(prefs.apPersona, synchronized(lock) { instructions.toList() })
                val user = LlmPrompts.replyUser(parsed.messages, dir)
                val raw = try {
                    Llm.generate(ctx, system, user, maxTokens = 180, temperature = 0.8f, topK = 40, topP = 0.95f, timeoutMs = 150_000)
                } catch (e: Exception) {
                    if (stopFlag) break
                    if (dir != null && directive == null) directive = dir   // the user's order must not get lost
                    failures++
                    retryAt[pkg] = System.currentTimeMillis() + 15_000
                    publish("النموذج فشل: ${e.message}", warn = true)
                    AppLog.e("autopilot", "generate failed", e)
                    if (failures >= 3) { stop("النموذج فشل 3 مرات: ${e.message}"); break }
                    continue
                }
                if (stopFlag) break
                val reply = LlmPrompts.cleanReply(raw)
                if (reply == null) {
                    handled[pkg] = decision.key
                    publish("مفيش داعي أرد على الرسالة دي")
                    continue
                }

                // last look before sending: a newer message means this reply is already out of date
                val s3 = ScreenReader.read(svc)
                if (s3 != null) {
                    val p3 = fixSides(ChatParser.parse(s3.screen, prefs.align(pkg)), sent)
                    s3.release()
                    if (dir == null && ReplyPolicy.key(p3.messages) != decision.key) {
                        publish("وصلت رسالة أحدث، هرد عليها بدل القديمة")
                        continue
                    }
                }

                if (!prefs.apAutoSend) {
                    val ok = SendAction.typeOnly(svc, reply)
                    handled[pkg] = decision.key
                    publish(if (ok) "كتبت الرد. ابعته إنت: $reply" else "مقدرتش أكتب الرد", warn = !ok)
                    continue
                }

                val res = SendAction.sendText(svc, reply)
                if (res.ok) {
                    failures = 0
                    repliesSent++
                    handled[pkg] = decision.key
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

    /** First look when autonomous mode starts: tells the user right away whether this chat is something we can drive. */
    private fun preflight(ctx: Context): String {
        val svc = ScreenAgentService.instance ?: return "الخدمة مش شغالة"
        val snap = ScreenReader.read(svc) ?: return "افتح محادثة وأنا هرد على الرسايل الجديدة"
        val screen = snap.screen
        val input = ChatParser.findInput(screen)
        snap.release()
        val app = appLabel(ctx, screen.pkg)
        if (input == null) return "افتح محادثة (واتساب مثلًا) وأنا هرد على الرسايل الجديدة"
        val cands = SendFinder.candidates(screen, input)
        return if (cands.isNotEmpty()) "جاهز في $app: شايف زر الإرسال" else "جاهز في $app (زر الإرسال بيظهر لما أكتب)"
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
                if (kotlin.math.abs(left - right) > s.screen.width * 0.08) {
                    prefs.setAlign(pkg, if (left < right) Align.LEFT else Align.RIGHT)
                }
            }
            s.release()
        } catch (_: Throwable) {
        }
    }
}
