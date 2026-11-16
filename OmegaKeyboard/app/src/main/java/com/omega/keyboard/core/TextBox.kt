package com.omega.keyboard.core

/**
 * The small text box inside the keyboard's translate bar: what is typed in that mode lands here (never in the app's own field),
 * and it is translated from here. One line; the caret can be anywhere in it. Positions are UTF-16 offsets, always on a code point
 * boundary (an emoji is never cut in half).
 */
class TextBox {
    private val sb = StringBuilder()

    var caret: Int = 0
        private set

    val text: String get() = sb.toString()
    val isEmpty: Boolean get() = sb.isEmpty()
    val length: Int get() = sb.length

    fun clear() {
        sb.setLength(0)
        caret = 0
    }

    fun set(value: String, caretAt: Int = value.length) {
        sb.setLength(0)
        sb.append(clean(value).take(MAX))
        caret = snap(caretAt)
    }

    /** puts [s] at the caret; line breaks become spaces (the box has one line) */
    fun insert(s: String) {
        val c = clean(s)
        if (c.isEmpty()) return
        val room = MAX - sb.length
        if (room <= 0) return
        val add = if (c.length <= room) c else c.substring(0, snap(c, room))
        sb.insert(caret, add)
        caret += add.length
    }

    /** removes the character before the caret (a mark before it first, like a normal text box); false when there is none */
    fun backspace(): Boolean {
        if (caret == 0) return false
        val n = Character.charCount(sb.codePointBefore(caret))
        sb.delete(caret - n, caret)
        caret -= n
        return true
    }

    /** removes the character after the caret */
    fun deleteForward(): Boolean {
        if (caret >= sb.length) return false
        val n = Character.charCount(sb.codePointAt(caret))
        sb.delete(caret, caret + n)
        return true
    }

    /** moves the caret by [steps] characters (negative = towards the start of the text) */
    fun move(steps: Int) {
        var n = steps
        while (n < 0 && caret > 0) { caret -= Character.charCount(sb.codePointBefore(caret)); n++ }
        while (n > 0 && caret < sb.length) { caret += Character.charCount(sb.codePointAt(caret)); n-- }
    }

    fun moveTo(pos: Int) {
        caret = snap(pos)
    }

    private fun snap(pos: Int): Int = snap(sb, pos)

    private fun snap(s: CharSequence, pos: Int): Int {
        var p = pos.coerceIn(0, s.length)
        if (p in 1 until s.length && Character.isHighSurrogate(s[p - 1]) && Character.isLowSurrogate(s[p])) p--
        return p
    }

    private fun clean(s: String): String = s.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')

    companion object {
        /** a hard limit: the translation engines are made for sentences, not for pages */
        const val MAX = 1500
    }
}
