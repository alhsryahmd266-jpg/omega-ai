package com.omega.keyboard.core

/**
 * Finding the parts of a game's chat on a picture of its screen, by itself: the model is asked to put a box around the chat messages,
 * the chat button, the typing box and the send button, and answers in the form it was trained for, a list of
 * {"box_2d": [y_min, x_min, y_max, x_max], "label": "..."} with every number on a 0..1000 scale of the picture.
 *
 * Pure Kotlin (no Android): the reading of that answer is the part that can go wrong in many small ways, so it is tested on the JVM.
 */
object GameLocate {

    /** what the model is asked, with the picture in front of it */
    const val SCOUT_USER =
        "Detect these user interface elements of this game screen: " +
            "the chat messages (the area where the players' chat lines are shown), " +
            "the chat button (the button or icon that opens the chat or the typing box), " +
            "the message input box (where a message is typed) and the send button. " +
            "Leave out the ones that are not on the screen. Output only ```json"

    /** one box the model found: [box] as fractions (0..1) of the picture */
    class Det(val label: String, val box: FRect)

    enum class Role { CHAT, BUTTON, INPUT, SEND }

    /** the boxes by what they are; a part the model did not find is null */
    class Found(val chat: FRect?, val button: FRect?, val input: FRect?, val send: FRect?) {
        val any: Boolean get() = chat != null || button != null || input != null || send != null

        /** something to type into or to open: without it nothing can be sent */
        val canType: Boolean get() = button != null || input != null
    }

    private val BOX = Regex("""["']?box_?2d["']?\s*[:=]\s*\[\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*]""", RegexOption.IGNORE_CASE)
    private val LABEL = Regex("""["']?label["']?\s*[:=]\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    /** a line such as `chat messages: [120, 40, 480, 300]` (an answer that is not JSON) */
    private val LOOSE = Regex("""([A-Za-z][A-Za-z _\-]{2,40}?)\s*[:=\-]\s*\[?\s*(\d{1,4}(?:\.\d+)?)\s*[, ]\s*(\d{1,4}(?:\.\d+)?)\s*[, ]\s*(\d{1,4}(?:\.\d+)?)\s*[, ]\s*(\d{1,4}(?:\.\d+)?)\s*]?""")

    private fun toRect(a: Double, b: Double, c: Double, d: Double): FRect? {
        val big = maxOf(a, b, c, d)
        val scale = if (big <= 1.0) 1.0 else 1000.0               // already fractions, or the 0..1000 grid
        fun f(v: Double) = (v / scale).coerceIn(0.0, 1.0).toFloat()
        // [y_min, x_min, y_max, x_max]
        var y1 = f(a); var x1 = f(b); var y2 = f(c); var x2 = f(d)
        if (y2 < y1) { val t = y1; y1 = y2; y2 = t }
        if (x2 < x1) { val t = x1; x1 = x2; x2 = t }
        if (x2 - x1 < 0.01f || y2 - y1 < 0.01f) return null
        return FRect(x1, y1, x2, y2)
    }

    /** Every box in the model's answer, in the order given. Anything that is not a box is ignored. */
    fun parse(raw: String): List<Det> {
        val text = raw.replace("```json", "").replace("```", "")
        val out = ArrayList<Det>()
        // objects first: { ... "box_2d": [...], "label": "..." } in either order
        var i = 0
        while (i < text.length) {
            val open = text.indexOf('{', i)
            if (open < 0) break
            val close = text.indexOf('}', open)
            if (close < 0) break
            val seg = text.substring(open, close + 1)
            val m = BOX.find(seg)
            if (m != null) {
                val v = m.groupValues.drop(1).map { it.toDouble() }
                val r = toRect(v[0], v[1], v[2], v[3])
                if (r != null) out.add(Det(LABEL.find(seg)?.groupValues?.get(1)?.trim().orEmpty(), r))
            }
            i = close + 1
        }
        if (out.isNotEmpty()) return out
        // a list of arrays without objects, or plain lines
        for (line in text.lineSequence()) {
            val m = LOOSE.find(line) ?: continue
            val v = (2..5).map { m.groupValues[it].toDouble() }
            val r = toRect(v[0], v[1], v[2], v[3]) ?: continue
            out.add(Det(m.groupValues[1].trim(), r))
        }
        return out
    }

    private fun area(r: FRect) = r.width * r.height

    /** what a labelled box is, from the words of its label and, when the words do not say, from its shape */
    fun roleOf(d: Det): Role? {
        val l = d.label.lowercase()
        val a = area(d.box)
        val small = a <= 0.05f
        return when {
            l.contains("send") || l.contains("ارسال") || l.contains("إرسال") -> Role.SEND
            l.contains("input") || l.contains("text box") || l.contains("textbox") || l.contains("text field") || l.contains("typing") ||
                l.contains("type ") || l.contains("compose") || l.contains("write") || l.contains("entry") -> Role.INPUT
            l.contains("button") || l.contains("icon") || l.contains("open") || l.contains("bubble") || l.contains("speech") -> Role.BUTTON
            l.contains("message") || l.contains("chat") || l.contains("conversation") || l.contains("log") || l.contains("text") ->
                if (small && l.trim() == "chat") Role.BUTTON else Role.CHAT
            else -> null
        }
    }

    /** a box that is plausible for what it is called (the model sometimes boxes the whole screen) */
    private fun plausible(role: Role, r: FRect): Boolean {
        val a = area(r)
        return when (role) {
            Role.CHAT -> a in 0.012f..0.85f && r.width >= 0.08f && r.height >= 0.05f
            Role.BUTTON -> a <= 0.07f
            Role.SEND -> a <= 0.09f
            Role.INPUT -> r.width >= 0.08f && r.height <= 0.25f && a <= 0.35f
        }
    }

    /**
     * The boxes by what they are. [maxCenterY]: our own keyboard covers the screen from this height down (a fraction of the picture),
     * so whatever the model found in there is the keyboard's keys, not the game's buttons.
     */
    fun classify(dets: List<Det>, maxCenterY: Float = 1f): Found {
        var chat: FRect? = null
        var button: FRect? = null
        var input: FRect? = null
        var send: FRect? = null
        for (d in dets) {
            val role = roleOf(d) ?: continue
            if (!plausible(role, d.box)) continue
            if ((d.box.t + d.box.b) / 2f > maxCenterY) continue
            when (role) {
                Role.CHAT -> if (chat == null || area(d.box) > area(chat)) chat = d.box      // the biggest chat area
                Role.BUTTON -> if (button == null) button = d.box
                Role.INPUT -> if (input == null) input = d.box
                Role.SEND -> if (send == null) send = d.box
            }
        }
        return Found(chat, button, input, send)
    }

    /**
     * The touches that bring up a box to type into, in the order to try them. A game whose box shows only after its chat button is
     * touched has [GameProfile.open] and [GameProfile.input]: when the chat is probably open already the box is touched first.
     */
    fun openSteps(p: GameProfile, chatOpen: Boolean): List<FPoint> {
        val out = ArrayList<FPoint>()
        val open = p.open
        val input = p.input
        if (open != null && input != null) {
            if (chatOpen) { out.add(input); out.add(open); out.add(input) } else { out.add(open); out.add(input) }
        } else {
            (input ?: open)?.let { out.add(it) }
        }
        return out
    }

    fun center(r: FRect): FPoint = FPoint(((r.l + r.r) / 2f).coerceIn(0f, 1f), ((r.t + r.b) / 2f).coerceIn(0f, 1f))

    /** The chat area a little bigger than the model drew it: new lines come in at its edge, and a tight box clips letters. */
    fun padChat(r: FRect): FRect {
        val px = 0.03f
        val py = 0.04f
        return FRect((r.l - px).coerceAtLeast(0f), (r.t - py).coerceAtLeast(0f), (r.r + px).coerceAtMost(1f), (r.b + py).coerceAtMost(1f))
    }

    /**
     * What was found, as what the autonomous mode keeps about a game. [open] is the chat button when the typing box shows only after it
     * is touched; [typeSpot] is where to touch to get a box that is ready to type into.
     */
    fun profileOf(found: Found, open: FPoint?, typeSpot: FPoint?, base: GameProfile = GameProfile()): GameProfile =
        GameProfile(
            chat = found.chat?.let { padChat(it) } ?: base.chat,
            input = typeSpot ?: base.input,
            send = found.send?.let { center(it) } ?: base.send,
            open = open ?: base.open,
            auto = true,
        )
}
