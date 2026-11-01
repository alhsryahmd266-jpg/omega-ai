package com.omega.keyboard.core

/** Chooses which on-screen texts are worth translating. */
object ScreenFilter {
    data class Item(val node: UiNode, val text: String)

    private val SKIP_PKGS = setOf("com.android.systemui")

    fun translatable(screen: Screen, targetLang: String, max: Int = 220): List<Item> {
        val minTop = 0
        val cands = ArrayList<Item>()
        for (n in screen.nodes) {
            if (!n.visible || n.editable || n.password) continue
            if (n.pkg in SKIP_PKGS) continue
            if (n.width < screen.dp(12) || n.height < screen.dp(8)) continue
            if (n.right <= 0 || n.bottom <= minTop || n.left >= screen.width || n.top >= screen.height) continue
            var t = n.text.trim()
            if (t.isEmpty() && n.desc.isNotBlank() && !(n.actionable && n.width < screen.dp(56)) && n.desc.length >= 3) t = n.desc.trim()
            if (t.length < 2) continue
            if (!ArabicText.hasLetters(t)) continue
            if (Matchers.isTime(t) || Matchers.isUrlOnly(t)) continue
            if (Languages.guessByScript(t) == targetLang) continue
            if (targetLang == "ar" && ArabicText.isMostlyArabic(t)) continue
            cands.add(Item(n, t))
        }
        // parent + child showing the same string: keep the deepest one
        val deepest = cands.filter { a ->
            cands.none { b -> b.node.id != a.node.id && b.text == a.text && b.node.depth > a.node.depth && a.node.contains(b.node) }
        }
        val seen = HashSet<String>()
        val out = deepest.filter { seen.add("${it.text}|${it.node.left}|${it.node.top}") }
            .sortedWith(compareBy({ it.node.top / 6 }, { it.node.left }))
        return if (out.size > max) out.take(max) else out
    }
}
