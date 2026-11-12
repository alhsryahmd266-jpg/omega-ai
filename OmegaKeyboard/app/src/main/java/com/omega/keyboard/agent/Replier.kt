package com.omega.keyboard.agent

import android.content.Context
import android.os.SystemClock
import com.omega.keyboard.AppLog
import com.omega.keyboard.Prefs
import com.omega.keyboard.ai.Llm
import com.omega.keyboard.core.ChatMessage
import com.omega.keyboard.core.LlmPrompts
import com.omega.keyboard.core.ReplyCraft

/**
 * Writes my next message with the local model.
 *
 * The message is planned from the conversation (what kind of message it answers, how long the answer should be, in which language),
 * the model writes it as the next turn of the chat, and the result is checked: a reply that is too short, in the wrong language, a
 * parrot of their message, a repeat of what I already wrote, or help-desk talk is thrown away and the model is told what was wrong.
 * Three tries at most; a reply that is merely a little short still beats no reply.
 */
object Replier {
    /** [text] null = nothing usable; [modelFailed] = the model itself did not answer (died, timed out), so nothing was written */
    class Written(val text: String?, val modelFailed: Boolean, val tries: Int, val flaws: String)

    private const val THINK_TOKENS = 256
    private val TEMPS = floatArrayOf(0.6f, 0.8f, 0.9f)

    /** the model refused the chat as real turns: from then on the chat is handed over as one text */
    @Volatile private var turnsBroken = false

    private fun retryNote(flaw: ReplyCraft.Flaw?): String = when (flaw) {
        ReplyCraft.Flaw.TOO_SHORT -> "الرد كان قصير، بكتبه تاني أوضح..."
        ReplyCraft.Flaw.WRONG_SCRIPT -> "الرد جه بلغة غلط، بكتبه تاني..."
        ReplyCraft.Flaw.ECHO -> "الرد كان تكرار لكلامهم، بكتبه تاني..."
        ReplyCraft.Flaw.REPEAT -> "الرد كان تكرار لكلامي، بكتب حاجة جديدة..."
        ReplyCraft.Flaw.ASSISTANT -> "الرد طلع رسمي زيادة، بكتبه بأسلوبي..."
        else -> "بحسّن الرد..."
    }

    private fun ask(
        ctx: Context, system: String, messages: List<ChatMessage>, plan: ReplyCraft.Plan, fix: String?,
        asTurns: Boolean, maxTokens: Int, temperature: Float, think: Int
    ): String {
        return if (asTurns) {
            val c = ReplyCraft.conversation(messages, plan, fix)
            AppLog.d("replier", "turns=${c.history.size} reading: " + c.user.replace('\n', '⏎').takeLast(500))
            Llm.generate(
                ctx, system, c.user, maxTokens = maxTokens + think, temperature = temperature, topK = 40, topP = 0.9f,
                timeoutMs = 240_000, history = c.history, think = think
            )
        } else {
            val user = LlmPrompts.replyUser(messages, ReplyCraft.noteWithFix(plan, fix))
            AppLog.d("replier", "text reading: " + user.replace('\n', '⏎').takeLast(500))
            Llm.generate(
                ctx, system, user, maxTokens = maxTokens + think, temperature = temperature, topK = 40, topP = 0.9f,
                timeoutMs = 240_000, think = think
            )
        }
    }

    /**
     * Blocking: call it from a background thread. [say] tells the user what is going on when a reply has to be written again.
     * A game's chat ([game]) is handed over as one text, because there are several players and no single "other side".
     */
    fun write(
        ctx: Context, prefs: Prefs, messages: List<ChatMessage>, game: Boolean, stop: () -> Boolean, say: (String) -> Unit = {}
    ): Written {
        val plan = ReplyCraft.plan(messages, game, prefs.apPersona)
        val system = if (game) LlmPrompts.replySystemForGame(prefs.apPersona, plan.script) else LlmPrompts.replySystem(prefs.apPersona, plan.script)
        val maxChars = if (game) 200 else 700
        val quick = when (plan.kind) {
            ReplyCraft.Kind.EMOJI, ReplyCraft.Kind.ACK, ReplyCraft.Kind.GREETING, ReplyCraft.Kind.THANKS, ReplyCraft.Kind.GAME -> true
            else -> false
        }
        val maxTokens = if (quick) 140 else 380
        var think = if (prefs.apThink && !game && !quick) THINK_TOKENS else 0

        val rejected = ArrayList<ReplyCraft.Candidate>()
        val flaws = StringBuilder()
        var fix: String? = null
        var tries = 0
        var failed = false
        while (tries < TEMPS.size && !stop()) {
            tries++
            if (tries > 1) say(retryNote(rejected.lastOrNull()?.verdict?.flaw))
            val asTurns = !game && !turnsBroken
            var raw: String? = null
            val t0 = SystemClock.elapsedRealtime()
            try {
                raw = ask(ctx, system, messages, plan, fix, asTurns, maxTokens, TEMPS[tries - 1], think)
            } catch (e: Exception) {
                if (stop()) break
                AppLog.e("replier", "the model failed (turns=$asTurns)", e)
                if (asTurns && SystemClock.elapsedRealtime() - t0 < 20_000) {
                    // an immediate refusal may mean the chat as turns is not accepted: once more, as one text
                    try {
                        raw = ask(ctx, system, messages, plan, fix, false, maxTokens, TEMPS[tries - 1], think)
                        turnsBroken = true
                    } catch (e2: Exception) {
                        if (stop()) break
                        AppLog.e("replier", "the model failed again", e2)
                        failed = true
                        break
                    }
                } else {
                    failed = true
                    break
                }
            }
            val reply = LlmPrompts.cleanReply(raw.orEmpty(), maxChars)
            val verdict = ReplyCraft.judge(reply, messages, plan)
            AppLog.d("replier", "try $tries (${plan.kind}): ${verdict.flaw ?: "ok"} · ${reply?.replace('\n', '⏎')?.take(90)}")
            if (verdict.ok && reply != null) return Written(reply, false, tries, flaws.toString().trim())
            flaws.append(verdict.flaw).append(' ')
            if (reply != null) rejected.add(ReplyCraft.Candidate(reply, verdict))
            // an empty answer after thinking: the thinking ate the room, so ask again without it
            if (verdict.flaw == ReplyCraft.Flaw.EMPTY) think = 0
            fix = verdict.fix
        }
        val best = ReplyCraft.pickBest(rejected)
        return Written(best, failed && best == null, tries, flaws.toString().trim())
    }
}
