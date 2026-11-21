package com.omega.keyboard.core

import kotlin.math.abs

/**
 * The round button that floats at the edge of the screen is not drawn by this keyboard: Android itself draws it for the
 * keyboard's accessibility service (its "accessibility shortcut"). An app cannot remove it directly; the place that can is the
 * service's page in the system Settings, where its "Shortcut" switch is. Reading that Settings screen, this decides what to
 * press there. Pure (no Android classes), so it is tested without a phone.
 *
 * It is careful on purpose: it only presses inside the page of THIS service, never the service's own on/off switch
 * (that would switch the service off), and never anything of another app.
 */
object ShortcutRows {

    enum class Kind {
        /** the switch of the shortcut row, which is on */
        SWITCH,

        /** the shortcut row itself, when it has no switch of its own and opens a list of choices */
        ROW,

        /** a ticked choice in that list that puts the button on the screen */
        OPTION,

        /** the list's save button */
        SAVE,

        /** the service's entry in the list of accessibility services (its own page is not open yet) */
        LIST_ENTRY,
    }

    sealed class Plan {
        /** the shortcut is off: nothing (more) to do */
        object Done : Plan()

        class Press(val node: UiNode, val kind: Kind) : Plan()

        /** not in view: move the page towards its end */
        object Scroll : Plan()

        /** nothing here that we recognise */
        object Lost : Plan()
    }

    private val SHORTCUT = Regex("shortcut|اختصار|accessibility button|زر امكانية الوصول|floating button|الزر العائم|الزر العايم")
    private val USE = Regex("^(use|استخدام|تشغيل|تفعيل|turn on)\\b")
    private val SOFT = Regex("accessibility button|زر امكانية الوصول|floating|العائم|العايم|\\bswipe\\b|اسحب|gesture|ايماءة|\\btap\\b")
    private val VOLUME = Regex("volume|مستوي الصوت|مفتاحي الصوت|مفاتيح الصوت")
    private val SAVE = setOf("save", "حفظ", "done", "تم", "ok", "موافق", "حسنا", "confirm", "تاكيد")

    /** lower case, Arabic without marks, hamza forms and alef maqsura unified, invisible spacing as blanks */
    fun norm(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s) {
            val c = when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                else -> ch.lowercaseChar()
            }
            if (c in 'ً'..'ٟ' || c == 'ـ' || c == 'ٰ') continue
            if (c in '​'..'‏' || c in '‪'..'‮' || c == ' ' || c == ' ' || c == ' ') { b.append(' '); continue }
            b.append(c)
        }
        return b.toString()
    }

    private fun isShortcutLabel(n: UiNode): Boolean = n.label.isNotBlank() && n.label.length <= 90 && SHORTCUT.containsMatchIn(norm(n.label))

    /**
     * Is [n] something a finger can press: itself or a few levels up (the text of a list row is not clickable, its row is)?
     * It stops at a scrolling list, which is not a button however it reports itself.
     */
    private fun pressable(n: UiNode, byId: Map<Int, UiNode>): Boolean {
        var c: UiNode? = n
        var hops = 0
        while (c != null && hops < 6) {
            if (c.actionable) return true
            if (c.scrollDown || c.scrollUp) return false
            c = byId[c.parentId]
            hops++
        }
        return false
    }

    /**
     * What to press on the Settings screen [screen] to switch the shortcut of the service named [service] off.
     * Called again after every press with a fresh read; it returns Done as soon as the shortcut's switch reads "off".
     */
    fun plan(screen: Screen, service: String): Plan {
        val dp = screen.density.coerceAtLeast(1f)
        val byId = screen.nodes.associateBy { it.id }
        val all = screen.nodes.filter { it.visible }
        val name = norm(service).trim()
        if (name.isEmpty()) return Plan.Lost
        val named = all.filter { it.label.isNotBlank() && norm(it.label).contains(name) }

        // the service's own page shows its name at least twice (title, its on/off bar) or as something one does not press;
        // in the list of services its name is a single entry that can be pressed
        val onPage = named.size >= 2 || named.any { !pressable(it, byId) }
        if (!onPage) {
            val entry = named.filter { pressable(it, byId) }.minByOrNull { it.top }
            if (entry != null) return Plan.Press(entry, Kind.LIST_ENTRY)
            return if (ScrollLogic.listWithMoreBelow(screen) != null) Plan.Scroll else Plan.Lost
        }

        val labels = all.filter { isShortcutLabel(it) }.sortedBy { it.top }
        val checks = all.filter { it.checkable }
        val band = dp * 44
        for (l in labels) {
            val s = checks
                .filter { c -> abs(c.centerY - l.centerY) <= band && !isUseBar(c, all, band) }
                .minByOrNull { abs(it.centerY - l.centerY) }
            if (s != null) return if (s.checked) Plan.Press(s, Kind.SWITCH) else Plan.Done
        }
        // a shortcut row without a switch of its own opens the choices when pressed
        val row = labels.firstOrNull { pressable(it, byId) }
        if (row != null) return Plan.Press(row, Kind.ROW)
        return if (ScrollLogic.listWithMoreBelow(screen) != null) Plan.Scroll else Plan.Lost
    }

    /** the service's own on/off bar ("Use <service>"): pressing it would switch the service off, so it is never a candidate */
    private fun isUseBar(c: UiNode, all: List<UiNode>, band: Float): Boolean {
        for (x in all) {
            if (x.label.isBlank() || abs(x.centerY - c.centerY) > band) continue
            val t = norm(x.label).trim()
            if (USE.containsMatchIn(t) && !SHORTCUT.containsMatchIn(t)) return true
        }
        return false
    }

    /**
     * The list of choices a shortcut row opens (a dialog): untick whatever puts the button on the screen, leave the volume keys
     * alone, then save. Null when this does not look like that list.
     */
    fun planDialog(screen: Screen): Plan? {
        val dp = screen.density.coerceAtLeast(1f)
        val byId = screen.nodes.associateBy { it.id }
        val all = screen.nodes.filter { it.visible }
        val checks = all.filter { it.checkable }
        if (checks.isEmpty()) return null
        fun rowText(c: UiNode): String {
            val near = all.filter { it.label.isNotBlank() && abs(it.centerY - c.centerY) <= dp * 40 }
            return norm((listOf(c) + near).joinToString(" ") { it.label })
        }
        // it is that list only if the words of a shortcut are on it
        val onList = all.any { isShortcutLabel(it) } || checks.any { SOFT.containsMatchIn(rowText(it)) }
        if (!onList) return null
        val on = checks.filter { it.checked }.firstOrNull { c ->
            val t = rowText(c)
            SOFT.containsMatchIn(t) && !VOLUME.containsMatchIn(t)
        }
        if (on != null) return Plan.Press(on, Kind.OPTION)
        val save = all.filter { it.enabled && norm(it.label).trim() in SAVE && pressable(it, byId) }.maxByOrNull { it.top }
        return if (save != null) Plan.Press(save, Kind.SAVE) else null
    }
}
