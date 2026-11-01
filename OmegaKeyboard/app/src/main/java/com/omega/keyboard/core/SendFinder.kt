package com.omega.keyboard.core

import kotlin.math.abs

/**
 * Finds the "send" button of a chat composer and checks that a message really left.
 * This is the part of autonomous mode that must never silently fail, so it is deliberately defensive:
 * several independent signals (view id, label in many languages, geometry) and a hard "never the microphone".
 */
object SendFinder {
    data class Candidate(val node: UiNode, val score: Int, val why: String)

    private val SEND_WORDS = listOf(
        "send", "إرسال", "ارسال", "أرسل", "ارسل", "إرسل", "envoyer", "senden", "enviar", "invia", "отправ", "gönder",
        "verzend", "wyślij", "skicka", "送信", "发送", "發送", "보내", "gửi", "kirim", "שלח", "भेज", "ส่ง", "post", "نشر"
    )

    /** a node whose label/id contains one of these is never offered as the send button */
    private val NEGATIVE = listOf(
        "voice", "record", "microphone", "mic ", "audio", "صوت", "تسجيل", "ميكروفون", "hold to", "اضغط مع الاستمرار",
        "attach", "إرفاق", "ارفاق", "camera", "كاميرا", "emoji", "إيموجي", "ايموجي", "sticker", "ملصق", "gif",
        "gallery", "معرض", "photo", "صورة", "video", "فيديو", "location", "الموقع", "contact", "جهة اتصال", "gift",
        "more", "المزيد", "menu", "قائمة", "keyboard", "لوحة المفاتيح", "call", "اتصال", "add", "إضافة", "plus"
    )
    private val NEGATIVE_IDS = listOf(
        "voice", "record", "mic", "audio", "attach", "camera", "emoji", "sticker", "gif", "gallery", "entry", "plus", "add_", "more"
    )

    private fun norm(s: String) = s.lowercase()

    fun candidates(screen: Screen, input: UiNode): List<Candidate> {
        val out = ArrayList<Candidate>()
        val band = maxOf(input.height, screen.dp(44))
        val endsRight = !screen.rtl
        val unlabeled = ArrayList<UiNode>()

        for (n in screen.nodes) {
            if (n.id == input.id || !n.visible || !n.enabled) continue
            if (input.contains(n) && n.id != input.id && n.width >= input.width * 0.8) continue   // the field's own wrapper
            if (n.width < screen.dp(20) || n.height < screen.dp(20)) continue
            if (n.width > screen.dp(160) || n.height > screen.dp(160)) continue
            if (abs(n.centerY - input.centerY) > band * 1.2 + screen.dp(24)) continue

            val label = norm(n.label)
            val id = n.shortId
            if (NEGATIVE.any { label.contains(it) } || NEGATIVE_IDS.any { id.contains(it) }) continue

            val idHit = id.contains("send") && !id.contains("sending")
            val labelHit = SEND_WORDS.any { label == it || label.startsWith(it) || label.contains(" $it") || label.contains("$it ") || (it.length > 3 && label.contains(it)) }

            if (!idHit && !labelHit) {
                if (label.isBlank() && n.actionable && n.width in screen.dp(26)..screen.dp(110)) unlabeled.add(n)
                continue
            }
            var s = 0
            val why = ArrayList<String>()
            if (idHit) { s += 90; why.add("id") }
            if (labelHit) { s += 80; why.add("label") }
            if (n.actionable) s += 10 else s -= 25
            val gap = when {
                n.left >= input.right -> n.left - input.right
                n.right <= input.left -> input.left - n.right
                else -> 0
            }
            if (gap <= screen.dp(96)) { s += 15; why.add("adjacent") }
            if (n.width in screen.dp(28)..screen.dp(110)) s += 5
            out.add(Candidate(n, s, why.joinToString("+")))
        }

        // last resort: an unlabeled button on the far side of the input (icon-only send buttons)
        val endSide = unlabeled.filter { if (endsRight) it.centerX >= input.right - screen.dp(8) else it.centerX <= input.left + screen.dp(8) }
        val pick = if (endsRight) endSide.maxByOrNull { it.centerX } else endSide.minByOrNull { it.centerX }
        if (pick != null) out.add(Candidate(pick, 55, "unlabeled-end"))

        return out.filter { it.score >= 40 }.sortedByDescending { it.score }
    }
}

enum class SendState { STILL_IN_FIELD, CLEARED, UNKNOWN }

object SendVerifier {
    private fun norm(s: String) = ArabicText.normalize(s).replace(Regex("\\s+"), " ").trim()

    fun fieldState(after: Screen, typed: String): SendState {
        val input = ChatParser.findInput(after) ?: return SendState.UNKNOWN
        val cur = if (input.showingHint) "" else norm(input.text)
        val t = norm(typed)
        if (cur.isEmpty()) return SendState.CLEARED
        if (cur == t) return SendState.STILL_IN_FIELD
        val head = t.take(20)
        if (head.length >= 4 && cur.contains(head)) return SendState.STILL_IN_FIELD
        // typed text is gone and something else is there (a hint text that is not flagged as hint)
        return SendState.CLEARED
    }

    /** true when a non-editable node on screen shows (the start of) the text we just sent */
    fun appeared(after: Screen, typed: String): Boolean {
        val head = norm(typed).take(24)
        if (head.length < 3) return false
        return after.nodes.any { !it.editable && it.visible && norm(it.label).contains(head) }
    }

    fun isSent(after: Screen, typed: String): Boolean {
        return when (fieldState(after, typed)) {
            SendState.CLEARED -> true
            SendState.STILL_IN_FIELD -> false
            SendState.UNKNOWN -> appeared(after, typed)
        }
    }
}
