package com.omega.keyboard.core

/**
 * Coloured text cannot travel as plain text, but some games and chat apps read colour codes written inside the message
 * ("[FF0000]hello" in Free Fire, <color=#FF0000>hello</color> in many Unity games ...). This turns the letters the user typed
 * in colour into such a message. Pure, so it is unit-tested on a plain JVM.
 */
object ColorCodes {

    enum class Format(val id: String, val label: String) {
        /** the app decides: the format of the apps listed in [forPackage], plain text everywhere else */
        AUTO("auto", "تلقائي (حسب التطبيق)"),
        NONE("none", "بدون كود (نص عادي)"),
        FREEFIRE("ff", "Free Fire  [FF0000]"),
        UNITY("unity", "ألعاب Unity  <color=#FF0000>"),
        DISCORD("discord", "Discord  (كمبيوتر)"),
        MINECRAFT("mc", "Minecraft  &c"),
        BBCODE("bb", "BBCode  [color=#FF0000]"),
        HTML("html", "HTML  <font color>");

        companion object {
            fun of(id: String?): Format = values().firstOrNull { it.id == id } ?: NONE
        }
    }

    /** the colour-code format an app is known to read; none for every other app (its text stays plain) */
    fun forPackage(pkg: String?): Format = when (pkg) {
        "com.discord" -> Format.DISCORD
        "com.dts.freefireth", "com.dts.freefiremax" -> Format.FREEFIRE
        else -> Format.NONE
    }

    /** [chosen] is what the user picked: [Format.AUTO] leaves it to the app [pkg] */
    fun resolve(chosen: Format, pkg: String?): Format = if (chosen == Format.AUTO) forPackage(pkg) else chosen

    private const val ESC = "\u001B"

    /** Discord's ANSI palette: code 30..37 -> the colour its client draws */
    private val DISCORD = listOf(
        30 to 0x4F545C, 31 to 0xDC322F, 32 to 0x859900, 33 to 0xB58900,
        34 to 0x268BD2, 35 to 0xD33682, 36 to 0x2AA198, 37 to 0xFFFFFF,
    )

    /** Minecraft's sixteen formatting colours, code -> colour */
    private val MINECRAFT = listOf(
        '0' to 0x000000, '1' to 0x0000AA, '2' to 0x00AA00, '3' to 0x00AAAA, '4' to 0xAA0000, '5' to 0xAA00AA,
        '6' to 0xFFAA00, '7' to 0xAAAAAA, '8' to 0x555555, '9' to 0x5555FF, 'a' to 0x55FF55, 'b' to 0x55FFFF,
        'c' to 0xFF5555, 'd' to 0xFF55FF, 'e' to 0xFFFF55, 'f' to 0xFFFFFF,
    )

    private fun dist(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    fun discordCode(rgb: Int): Int = DISCORD.minByOrNull { dist(rgb and 0xFFFFFF, it.second) }!!.first
    fun minecraftCode(rgb: Int): Char = MINECRAFT.minByOrNull { dist(rgb and 0xFFFFFF, it.second) }!!.first

    private fun hex(rgb: Int) = String.format("%06X", rgb and 0xFFFFFF)

    private class Run(val color: Int, val body: StringBuilder)

    /**
     * [colorAt] gives the colour (ARGB or RGB) of the n-th visible letter: the same colour for every letter, or the rainbow.
     * Letters of one colour share one code; spaces and line breaks ride along and never start a code.
     */
    fun wrap(text: String, format: Format, colorAt: (Int) -> Int): String {
        if (format == Format.NONE || format == Format.AUTO || text.isEmpty()) return text
        val lead = StringBuilder()
        val runs = ArrayList<Run>()
        var n = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            val piece = text.substring(i, i + len)
            if (Character.isWhitespace(cp)) {
                if (runs.isEmpty()) lead.append(piece) else runs.last().body.append(piece)
            } else {
                val c = colorAt(n++) and 0xFFFFFF
                val last = runs.lastOrNull()
                if (last != null && last.color == c) last.body.append(piece) else runs.add(Run(c, StringBuilder(piece)))
            }
            i += len
        }
        if (runs.isEmpty()) return text
        val sb = StringBuilder(lead)
        when (format) {
            Format.FREEFIRE -> for (r in runs) sb.append('[').append(hex(r.color)).append(']').append(r.body)
            Format.UNITY -> for (r in runs) sb.append("<color=#").append(hex(r.color)).append('>').append(r.body).append("</color>")
            Format.BBCODE -> for (r in runs) sb.append("[color=#").append(hex(r.color)).append(']').append(r.body).append("[/color]")
            Format.HTML -> for (r in runs) sb.append("<font color=\"#").append(hex(r.color)).append("\">").append(r.body).append("</font>")
            Format.MINECRAFT -> for (r in runs) sb.append('&').append(minecraftCode(r.color)).append(r.body)
            Format.DISCORD -> {
                sb.setLength(0)
                sb.append("```ansi\n").append(lead.toString().trimEnd(' ', '\t'))
                for (r in runs) sb.append(ESC).append("[1;").append(discordCode(r.color)).append('m').append(r.body)
                sb.append(ESC).append("[0m\n```")
            }
            Format.NONE, Format.AUTO -> return text
        }
        return sb.toString()
    }
}
