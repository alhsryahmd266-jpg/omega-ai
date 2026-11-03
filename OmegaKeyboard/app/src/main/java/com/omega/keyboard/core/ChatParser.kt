package com.omega.keyboard.core

import kotlin.math.abs

enum class Side { ME, THEM, UNKNOWN }

/** Which screen edge my own bubbles are attached to. WhatsApp/Telegram/Messenger/SMS: right. Learned per app. */
enum class Align { LEFT, RIGHT }

data class ChatMessage(val text: String, val side: Side, val top: Int, val bottom: Int, val nodeId: Int)

/** [learnedAlign]: the screen edge my own bubbles hug, when delivery ticks proved it on this very screen (else null). */
class ChatParseResult(val messages: List<ChatMessage>, val input: UiNode?, val learnedAlign: Align? = null) {
    val last: ChatMessage? get() = messages.lastOrNull()
}

/**
 * Turns a flat accessibility dump of a messaging app into an ordered conversation.
 * Heuristics only (no per-app code needed), with extra hints for apps that expose tick/status nodes.
 */
object ChatParser {
    private val BAD_CONTAINER_IDS = listOf(
        "toolbar", "action_bar", "actionbar", "contact_name", "contact_status", "conversation_contact",
        "header", "title_bar", "status_bar", "tab_layout", "bottom_nav", "navigation_bar", "banner"
    )
    private val STATUS_ID_HINTS = listOf("status", "ticks", "tick", "delivery", "receipt", "seen")

    private val INPUT_CLASS_HINTS = listOf("EditText", "AutoCompleteTextView", "TextInput", "ComposerEditText", "MultiAutoComplete")

    fun findInput(screen: Screen): UiNode? {
        val cands = screen.nodes.filter {
            it.visible && it.enabled && !it.password && it.width > screen.dp(60) &&
                (it.editable || INPUT_CLASS_HINTS.any { h -> it.cls.contains(h, ignoreCase = true) })
        }
        if (cands.isEmpty()) return null
        // the composer is the lowest editable field (search boxes live at the top); focused wins a tie
        return cands.sortedWith(compareByDescending<UiNode> { it.bottom }.thenByDescending { it.focused }).first()
    }

    fun parse(screen: Screen, myAlign: Align = Align.RIGHT): ChatParseResult {
        val input = findInput(screen)
        val inputTop = input?.top ?: (screen.height * 0.88).toInt()
        val topLimit = maxOf(screen.dp(72), (screen.height * 0.07).toInt())

        val statusNodes = screen.nodes.filter { n ->
            n.visible && (Matchers.isStatusWord(n.label) || STATUS_ID_HINTS.any { n.shortId.contains(it) }) &&
                n.height < screen.dp(40)
        }

        class Cand(val n: UiNode, val text: String, val ticked: Boolean)
        val cands = ArrayList<Cand>()
        for (n in screen.nodes) {
            if (!n.visible || n.editable || n.password) continue
            if (n.id == input?.id) continue
            if (n.width <= 0 || n.height <= 0) continue
            if (n.bottom > inputTop + screen.dp(4) || n.top < topLimit) continue
            if (BAD_CONTAINER_IDS.any { n.shortId.contains(it) }) continue
            val txt = candidateText(n, screen) ?: continue
            if (Matchers.isChatNoise(txt)) continue
            if (n.childCount > 0 && n.text.isBlank()) continue
            cands.add(Cand(n, txt.trim(), hasTicks(n, screen, statusNodes)))
        }

        // bubbles with delivery ticks are certainly mine: the edge they hug is "my side" in this app, whatever language / layout direction it uses
        var left = 0
        var right = 0
        for (c in cands) if (c.ticked) when (edgeOf(c.n, screen)) { Align.LEFT -> left++; Align.RIGHT -> right++; null -> {} }
        val learned = when {
            left > 0 && left >= right * 3 -> Align.LEFT
            right > 0 && right >= left * 3 -> Align.RIGHT
            else -> null
        }
        val align = learned ?: myAlign

        val raw = ArrayList<ChatMessage>()
        for (c in cands) {
            val side = if (c.ticked) Side.ME else edgeSide(c.n, screen, align)
            raw.add(ChatMessage(c.text, side, c.n.top, c.n.bottom, c.n.id))
        }
        raw.sortWith(compareBy({ it.top }, { it.nodeId }))

        // drop exact duplicates (same text at nearly the same place, e.g. text + its content-description twin)
        val out = ArrayList<ChatMessage>()
        for (m in raw) {
            val dup = out.any { it.text == m.text && abs(it.top - m.top) < screen.dp(6) }
            if (!dup) out.add(m)
        }
        return ChatParseResult(out, input, learned)
    }

    private fun candidateText(n: UiNode, screen: Screen): String? {
        if (n.text.isNotBlank() && !n.showingHint) return n.text
        // some apps (custom-drawn bubbles) only expose a content description: accept big nodes only
        if (n.desc.isNotBlank() && n.width > screen.width * 0.22 && n.height > screen.dp(28) && n.desc.length > 2) {
            if (Matchers.containsAny(n.desc, NON_MESSAGE_DESC)) return null
            return n.desc
        }
        return null
    }

    private val NON_MESSAGE_DESC = listOf(
        "navigate up", "back", "more options", "attach", "camera", "emoji", "voice message", "send", "menu",
        "رجوع", "المزيد", "إرفاق", "ارفاق", "كاميرا", "إيموجي", "رسالة صوتية", "إرسال", "قائمة", "profile photo", "صورة الملف"
    )

    /** delivery ticks next to a bubble mean "outgoing" */
    private fun hasTicks(n: UiNode, screen: Screen, statusNodes: List<UiNode>): Boolean {
        for (s in statusNodes) {
            if (s.centerY >= n.top - screen.dp(2) && s.centerY <= n.bottom + screen.dp(14) &&
                s.centerX >= n.left && s.right <= n.right + screen.dp(40)
            ) return true
        }
        return false
    }

    /**
     * The edge a bubble hugs, or null when it is centred or spans (nearly) the whole width.
     * A real bubble sits against one edge and leaves a clear gap on the other; a plain paragraph of text, a caption or
     * a disclaimer does not, so it never counts as "mine" or "theirs".
     */
    private fun edgeOf(n: UiNode, screen: Screen): Align? {
        val leftGap = n.left
        val rightGap = screen.width - n.right
        val far = (screen.width * 0.13f).toInt()                                 // the free side of a bubble
        val diff = maxOf(screen.dp(20), (screen.width * 0.06f).toInt())          // how much nearer to one edge
        return when {
            rightGap < leftGap && leftGap >= far && leftGap - rightGap >= diff -> Align.RIGHT
            leftGap < rightGap && rightGap >= far && rightGap - leftGap >= diff -> Align.LEFT
            else -> null
        }
    }

    /** my bubbles hug my edge, theirs hug the opposite one */
    private fun edgeSide(n: UiNode, screen: Screen, myAlign: Align): Side {
        val e = edgeOf(n, screen) ?: return Side.UNKNOWN
        return if (e == myAlign) Side.ME else Side.THEM
    }
}

object ReplyPolicy {
    data class Decision(val reply: Boolean, val reason: String, val key: String)

    /** Identity of "the situation we answered": the tail of the conversation. */
    fun key(messages: List<ChatMessage>): String {
        val tail = messages.takeLast(4).joinToString("␞") { "${it.side.name.first()}:${it.text}" }
        return tail.hashCode().toString() + ":" + messages.size.coerceAtMost(6)
    }

    fun decide(res: ChatParseResult, lastHandledKey: String?, force: Boolean, unknownAsThem: Boolean = false): Decision {
        val k = key(res.messages)
        if (res.input == null) return Decision(false, "no_input", k)
        if (force) return Decision(true, "directive", k)      // the user told us what to say: works in an empty chat too
        val last = res.last ?: return Decision(false, "no_messages", k)
        val side = if (last.side == Side.UNKNOWN && unknownAsThem) Side.THEM else last.side
        if (side == Side.ME) return Decision(false, "last_is_mine", k)
        if (side == Side.UNKNOWN) return Decision(false, "unknown_side", k)
        if (k == lastHandledKey) return Decision(false, "already_handled", k)
        return Decision(true, "new_incoming", k)
    }
}
