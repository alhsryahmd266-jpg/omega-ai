package com.omega.keyboard.core

import kotlin.math.abs

enum class Side { ME, THEM, UNKNOWN }

/** Which screen edge my own bubbles are attached to. WhatsApp/Telegram/Messenger/SMS: right. Learned per app. */
enum class Align { LEFT, RIGHT }

data class ChatMessage(val text: String, val side: Side, val top: Int, val bottom: Int, val nodeId: Int)

class ChatParseResult(val messages: List<ChatMessage>, val input: UiNode?) {
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

        val raw = ArrayList<ChatMessage>()
        for (n in screen.nodes) {
            if (!n.visible || n.editable || n.password) continue
            if (n.id == input?.id) continue
            if (n.width <= 0 || n.height <= 0) continue
            if (n.bottom > inputTop + screen.dp(4) || n.top < topLimit) continue
            if (BAD_CONTAINER_IDS.any { n.shortId.contains(it) }) continue
            val txt = candidateText(n, screen) ?: continue
            if (Matchers.isChatNoise(txt)) continue
            if (n.childCount > 0 && n.text.isBlank()) continue
            val side = sideOf(n, screen, myAlign, statusNodes)
            raw.add(ChatMessage(txt.trim(), side, n.top, n.bottom, n.id))
        }
        raw.sortWith(compareBy({ it.top }, { it.nodeId }))

        // drop exact duplicates (same text at nearly the same place, e.g. text + its content-description twin)
        val out = ArrayList<ChatMessage>()
        for (m in raw) {
            val dup = out.any { it.text == m.text && abs(it.top - m.top) < screen.dp(6) }
            if (!dup) out.add(m)
        }
        return ChatParseResult(out, input)
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

    private fun sideOf(n: UiNode, screen: Screen, myAlign: Align, statusNodes: List<UiNode>): Side {
        // 1) delivery ticks next to a bubble mean "outgoing"
        for (s in statusNodes) {
            if (s.centerY >= n.top - screen.dp(2) && s.centerY <= n.bottom + screen.dp(14) &&
                s.centerX >= n.left && s.right <= n.right + screen.dp(40)
            ) return Side.ME
        }
        // 2) horizontal position: my bubbles hug my edge, theirs hug the opposite one
        val leftGap = n.left
        val rightGap = screen.width - n.right
        val diff = rightGap - leftGap                // > 0: nearer to the left edge
        val threshold = maxOf(screen.dp(20), (screen.width * 0.05f).toInt())
        if (abs(diff) < threshold) return Side.UNKNOWN
        val nearLeft = diff > 0
        return if ((myAlign == Align.LEFT) == nearLeft) Side.ME else Side.THEM
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
        val last = res.last ?: return Decision(false, "no_messages", k)
        val side = if (last.side == Side.UNKNOWN && unknownAsThem) Side.THEM else last.side
        if (force) return Decision(true, "directive", k)
        if (side == Side.ME) return Decision(false, "last_is_mine", k)
        if (side == Side.UNKNOWN) return Decision(false, "unknown_side", k)
        if (k == lastHandledKey) return Decision(false, "already_handled", k)
        return Decision(true, "new_incoming", k)
    }
}
