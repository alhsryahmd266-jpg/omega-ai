package com.omega.keyboard.core

import java.util.Locale

/** A rectangle on the screen as fractions (0..1) of its width and height, so it survives another screen size. */
data class FRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val width: Float get() = r - l
    val height: Float get() = b - t

    /** left, top, right, bottom in pixels of a [w] x [h] screen, always inside it and never empty */
    fun px(w: Int, h: Int): IntArray {
        val left = (l * w).toInt().coerceIn(0, (w - 1).coerceAtLeast(0))
        val top = (t * h).toInt().coerceIn(0, (h - 1).coerceAtLeast(0))
        val right = (r * w).toInt().coerceIn(left + 1, w.coerceAtLeast(left + 1))
        val bottom = (b * h).toInt().coerceIn(top + 1, h.coerceAtLeast(top + 1))
        return intArrayOf(left, top, right, bottom)
    }

    fun encode(): String = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", l, t, r, b)

    companion object {
        /** from two pixel corners (in any order) of a [w] x [h] screen */
        fun fromPx(x1: Float, y1: Float, x2: Float, y2: Float, w: Int, h: Int): FRect {
            val ww = w.coerceAtLeast(1).toFloat()
            val hh = h.coerceAtLeast(1).toFloat()
            return FRect(
                (minOf(x1, x2) / ww).coerceIn(0f, 1f), (minOf(y1, y2) / hh).coerceIn(0f, 1f),
                (maxOf(x1, x2) / ww).coerceIn(0f, 1f), (maxOf(y1, y2) / hh).coerceIn(0f, 1f),
            )
        }

        fun decode(s: String): FRect? {
            val p = s.split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (p.size != 4) return null
            val r = FRect(p[0], p[1], p[2], p[3])
            return if (r.width > 0.02f && r.height > 0.02f) r else null
        }
    }
}

/** A spot on the screen as fractions (0..1) of its width and height. */
data class FPoint(val x: Float, val y: Float) {
    fun pxX(w: Int): Float = (x * w).coerceIn(0f, (w - 1).coerceAtLeast(0).toFloat())
    fun pxY(h: Int): Float = (y * h).coerceIn(0f, (h - 1).coerceAtLeast(0).toFloat())

    fun encode(): String = String.format(Locale.US, "%.4f,%.4f", x, y)

    companion object {
        fun fromPx(x: Float, y: Float, w: Int, h: Int): FPoint =
            FPoint((x / w.coerceAtLeast(1)).coerceIn(0f, 1f), (y / h.coerceAtLeast(1)).coerceIn(0f, 1f))

        fun decode(s: String): FPoint? {
            val p = s.split(',').mapNotNull { it.trim().toFloatOrNull() }
            return if (p.size == 2) FPoint(p[0].coerceIn(0f, 1f), p[1].coerceIn(0f, 1f)) else null
        }
    }
}

/**
 * What the autonomous mode knows about one game (and one screen orientation): where its chat messages are, where to touch to get a
 * box that is ready to type into ([input]), where its send button is. Games draw their own screens, so none of this can be read from
 * them: it was either shown by the user, or found by the model on a picture of the screen ([auto]).
 * [open] is the chat button, for games whose typing box shows only after it is touched ([input] is then the box itself).
 */
data class GameProfile(
    val chat: FRect? = null,
    val input: FPoint? = null,
    val send: FPoint? = null,
    val open: FPoint? = null,
    val auto: Boolean = false,
) {
    val taught: Boolean get() = chat != null || input != null || send != null || open != null

    /** there is a place to touch that brings up a box to type into: without it nothing can be sent */
    val canType: Boolean get() = input != null || open != null

    fun encode(): String = listOfNotNull(
        chat?.let { "c=" + it.encode() },
        input?.let { "i=" + it.encode() },
        send?.let { "s=" + it.encode() },
        open?.let { "o=" + it.encode() },
        if (auto) "a=1" else null,
    ).joinToString(";")

    companion object {
        fun decode(s: String): GameProfile {
            var c: FRect? = null
            var i: FPoint? = null
            var d: FPoint? = null
            var o: FPoint? = null
            var a = false
            for (part in s.split(';')) {
                val eq = part.indexOf('=')
                if (eq < 1) continue
                val v = part.substring(eq + 1)
                when (part.substring(0, eq)) {
                    "c" -> c = FRect.decode(v)
                    "i" -> i = FPoint.decode(v)
                    "s" -> d = FPoint.decode(v)
                    "o" -> o = FPoint.decode(v)
                    "a" -> a = v.trim() == "1"
                }
            }
            return GameProfile(c, i, d, o, a)
        }

        fun key(pkg: String, landscape: Boolean): String = pkg + if (landscape) "|L" else "|P"
    }
}

/**
 * Reading a game's chat from a picture of the screen: the prompt that makes the model copy the messages, the parsing of what it
 * copied into a conversation, and the decision whether the last message still needs an answer. Pure Kotlin, tested on the JVM.
 */
object GameChat {

    const val VISION_USER = "Copy the chat messages in this picture."

    fun visionSystem(myName: String): String {
        val sb = StringBuilder()
        sb.append("You read screenshots of a chat inside a game or an app and copy the chat messages exactly as they are written, ")
        sb.append("so that another assistant can reply to them.\n")
        sb.append("Rules:\n")
        sb.append("- Output one line per message, oldest first (top to bottom), in the form: Name: message\n")
        sb.append("- If a message shows no sender name, output only the message.\n")
        sb.append("- Keep the original language, spelling, emoji and numbers. Never translate and never correct anything.\n")
        sb.append("- The chat lines can be in any colour (white, yellow, red, green, blue, pink, orange, rainbow) and may have an outline or a shadow, ")
        sb.append("over a dark, bright or busy background. Read every one of them, whatever its colour. A player's name or rank tag is often ")
        sb.append("in another colour than the message that follows it.\n")
        sb.append("- Copy only what players or friends typed in the chat. Ignore buttons, menus, health bars, scores, timers, ")
        sb.append("names floating in the game world, rewards, system notices (joined, left, kills) and anything else that is not a typed message.\n")
        sb.append("- If you can see no chat messages, output exactly: NONE\n")
        sb.append("- Output nothing else: no comments, no numbering, no quotation marks.")
        val me = myName.trim()
        if (me.isNotEmpty()) sb.append("\nMy own name in this game is \"").append(me).append("\".")
        return sb.toString()
    }

    private val PREAMBLE = Regex("(?i)^(here (are|is)|these are|the (chat )?messages|chat messages|transcript|sure|okay|ok,)\\b.*")
    private val LIST_MARK = Regex("^\\s*(?:[-*•>]+|\\d{1,2}[.)])\\s+")

    /** Splits "Name: text" into its two parts, or null when the line has no sender name in front. */
    fun splitName(line: String): Pair<String, String>? {
        var at = -1
        for ((i, ch) in line.withIndex()) {
            if (ch == ':' || ch == '：') { at = i; break }
            if (i > 24) break
        }
        if (at < 1) return null
        val name = line.substring(0, at).trim()
        val rest = line.substring(at + 1).trim()
        if (name.isEmpty() || rest.isEmpty()) return null
        if (name.last().isDigit() && rest.first().isDigit()) return null     // a clock time such as 5:30
        if (rest.startsWith("//")) return null                               // a web address
        if (name.count { it == ' ' } > 3) return null                        // a sentence, not a name
        return name to rest
    }

    /** Matching key: normalised Arabic / Latin text with only letters and digits left. */
    fun key(s: String): String {
        val n = ArabicText.normalize(s)
        val sb = StringBuilder(n.length)
        for (c in n) if (c.isLetterOrDigit()) sb.append(c) else if (c == ' ') sb.append(' ')
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    /**
     * The same message read twice, with a letter or two off. Exact after normalising; longer texts also match when one holds the
     * other or when only about a quarter of the letters differ.
     */
    fun same(a: String, b: String): Boolean {
        val x = key(a)
        val y = key(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val shorter = minOf(x.length, y.length)
        if (shorter < 8) return false
        if (x.contains(y) || y.contains(x)) return true
        val allowed = maxOf(2, shorter / 4)
        return ArabicText.editDistance(x, y, allowed) <= allowed
    }

    /** True when this chat line is mine: my name in front of it, or the very text I sent a moment ago. */
    fun isMine(name: String?, text: String, myName: String, sent: List<String>): Boolean {
        val me = key(myName)
        if (!name.isNullOrBlank() && me.length >= 2) {
            val n = key(name)
            if (n.isNotEmpty() && (n == me || (minOf(n.length, me.length) >= 3 && (n.contains(me) || me.contains(n))))) return true
        }
        return sent.any { same(it, text) }
    }

    /**
     * What the model copied from the picture, as a conversation (oldest first). Lines of other people keep their name in front,
     * so a group chat stays readable; my own lines are just the text. [sent] = what I typed in this session (newest last).
     */
    fun parseTranscript(raw: String, myName: String, sent: List<String>): List<ChatMessage> {
        var s = raw.replace("<end_of_turn>", "").replace("```", "").trim()
        if (s.isEmpty()) return emptyList()
        if (s.equals("NONE", ignoreCase = true) || s.equals("NONE.", ignoreCase = true)) return emptyList()
        val out = ArrayList<ChatMessage>()
        var index = 0
        for (rawLine in s.lineSequence()) {
            var line = rawLine.trim()
            if (line.isEmpty()) continue
            line = LIST_MARK.replace(line, "").trim().trim('"', '“', '”').trim()
            if (line.isEmpty()) continue
            if (line.equals("NONE", ignoreCase = true)) continue
            if (index == 0 && out.isEmpty() && PREAMBLE.matches(line)) continue
            val split = splitName(line)
            val name = split?.first
            val text = split?.second ?: line
            val mine = isMine(name, text, myName, sent)
            val shown = if (!mine && name != null) "$name: $text" else text
            out.add(ChatMessage(shown, if (mine) Side.ME else Side.THEM, index * 10, index * 10 + 9, index))
            index++
        }
        return out
    }

    /**
     * Does the last message still need an answer? [handled] = the messages already answered (newest last). A last line of mine
     * never does, and neither does one that was already answered, even when the picture was read slightly differently this time.
     */
    fun decide(messages: List<ChatMessage>, handled: List<String>): ReplyPolicy.Decision {
        val last = messages.lastOrNull() ?: return ReplyPolicy.Decision(false, "no_messages", "")
        val k = key(last.text)
        if (last.side == Side.ME) return ReplyPolicy.Decision(false, "last_is_mine", k)
        if (handled.any { same(it, last.text) }) return ReplyPolicy.Decision(false, "already_handled", k)
        return ReplyPolicy.Decision(true, "new_incoming", k)
    }

    /**
     * Two small pictures (gray values, same size) compared: how many of their cells differ clearly. A chat that scrolled changes a
     * lot of cells, a still one almost none.
     */
    fun changedCells(a: IntArray, b: IntArray, tolerance: Int = 28): Int {
        if (a.size != b.size) return a.size
        var n = 0
        for (i in a.indices) if (kotlin.math.abs(a[i] - b[i]) > tolerance) n++
        return n
    }
}
