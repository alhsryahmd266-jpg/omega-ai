package com.omega.keyboard.core

/** Chooses which on-screen texts are worth translating. */
object ScreenFilter {
    data class Item(val node: UiNode, val text: String)

    private val SKIP_PKGS = setOf("com.android.systemui")

    /** nodes that are pictures or controls: their content description names an icon, it is not text on the screen */
    private val GRAPHIC_CLASSES = listOf("ImageView", "ImageButton", "Switch", "CheckBox", "RadioButton", "ProgressBar", "SeekBar", "ToggleButton")

    /**
     * A node without text of its own is only worth translating through its content description when it is a small plain
     * leaf (a label). A big container or a picture described as "Conversation with John" must not get a box painted
     * over everything inside it.
     */
    private fun labelOnly(n: UiNode, screen: Screen): Boolean =
        n.desc.length >= 3 && n.childCount == 0 &&
            GRAPHIC_CLASSES.none { n.cls.contains(it, ignoreCase = true) } &&
            !(n.actionable && n.width < screen.dp(56)) &&
            n.height <= screen.height * 0.18

    fun translatable(screen: Screen, targetLang: String, max: Int = 220): List<Item> {
        val minTop = 0
        val cands = ArrayList<Item>()
        for (n in screen.nodes) {
            if (!n.visible || n.editable || n.password) continue
            if (n.pkg in SKIP_PKGS) continue
            if (n.width < screen.dp(12) || n.height < screen.dp(8)) continue
            if (n.right <= 0 || n.bottom <= minTop || n.left >= screen.width || n.top >= screen.height) continue
            var t = n.text.trim()
            if (t.isEmpty() && labelOnly(n, screen)) t = n.desc.trim()
            if (t.length < 2) continue
            if (!ArabicText.hasLetters(t)) continue
            if (Matchers.isTime(t) || Matchers.isUrlOnly(t)) continue
            if (Languages.guessByScript(t) == targetLang) continue
            if (targetLang == "ar" && ArabicText.isMostlyArabic(t)) continue
            cands.add(Item(n, t))
        }
        // a container that shows text and also holds other texts: its children are translated one by one, the container is not
        // (a parent and a child with the same string: the deepest one stays)
        val deepest = cands.filter { a ->
            cands.none { b ->
                b.node.id != a.node.id && b.node.depth > a.node.depth && a.node.contains(b.node) &&
                    (b.text == a.text || a.node.childCount > 0)
            }
        }
        val seen = HashSet<String>()
        val out = deepest.filter { seen.add("${it.text}|${it.node.left}|${it.node.top}") }
            .sortedWith(compareBy({ it.node.top / 6 }, { it.node.left }))
        return if (out.size > max) out.take(max) else out
    }
}
