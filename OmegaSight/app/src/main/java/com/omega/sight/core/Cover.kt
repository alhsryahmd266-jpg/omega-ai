package com.omega.sight.core

/**
 * Is something on top of the game, in the place where the aiming finger works?
 *
 * A touch lands on whatever window is on top at that point: a system message ("the app is not responding"), the notification shade, a
 * keyboard, somebody's floating button. The app must never send its finger onto any of those, so it asks the accessibility service for
 * the list of windows of the screen (from the top down) and decides here from the package, the kind and the rectangle of each window.
 * Nothing about what a window shows is looked at, and there are no Android classes in this file, so the rule runs in the JVM tests.
 */
object Cover {
    /** the same numbers as AccessibilityWindowInfo.TYPE_* */
    const val APP = 1
    const val IME = 2
    const val SYSTEM = 3
    const val OVERLAY = 4

    enum class State {
        /** the game is the window in front and nothing else is over the aim zone */
        CLEAR,

        /** another window is over the aim zone */
        COVERED,

        /** the list has windows but none of them is the game (and nothing visible covers the zone) */
        NO_GAME,

        /** no list at all (the screen is off, or the system does not give windows) */
        UNKNOWN,
    }

    /** one window: its package ("" = not known), kind and rectangle on the screen */
    class Win(val pkg: String, val kind: Int, val l: Int, val t: Int, val r: Int, val b: Int) {
        override fun toString(): String = "${pkg.ifEmpty { "?" }}/$kind[$l,$t,$r,$b]"
    }

    class Verdict(val state: State, val by: String)

    private val clear = Verdict(State.CLEAR, "")
    private val unknown = Verdict(State.UNKNOWN, "")
    private val noGame = Verdict(State.NO_GAME, "")

    /**
     * @param wins the windows of the screen, the one on top first
     * @param game the package of the owner's game
     * @param self this app's own package (its status capsule is a window too)
     * @param zone [l, t, r, b] where the aiming finger works, null = not known yet
     */
    fun judge(wins: List<Win>, game: String, self: String, zone: IntArray?): Verdict {
        if (wins.isEmpty() || zone == null) return unknown
        for (w in wins) {
            // accessibility overlays (this app's status capsule is one) let touches through to what is below; a window of this app that is
            // a whole screen of its own (its main screen) is not skipped: when it is in front of the game it covers it like any other app
            if (w.kind == OVERLAY || (w.pkg == self && w.kind != APP)) continue
            if (w.pkg == game && w.kind != IME) return clear
            // a window above the game is harmless only when it stays out of the place where the finger works
            if (hits(w, zone)) return Verdict(State.COVERED, w.pkg.ifEmpty { "?" })
        }
        return noGame
    }

    /** the window has some area and shares some of it with the zone */
    fun hits(w: Win, z: IntArray): Boolean = w.r > w.l && w.b > w.t && w.l < z[2] && w.r > z[0] && w.t < z[3] && w.b > z[1]
}
