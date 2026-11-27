package com.omega.sight

import com.omega.sight.core.Cover
import com.omega.sight.core.Cover.State
import com.omega.sight.core.Cover.Win

/** Is something over the place where the finger works? Made-up window lists, top window first, on a 1600 x 720 screen. */
object CoverTests {
    private const val GAME = "com.mygame"
    private const val SELF = "com.omega.sight"
    private val zone = intArrayOf(880, 108, 1502, 612)

    private fun judge(vararg w: Win, z: IntArray? = zone) = Cover.judge(w.toList(), GAME, SELF, z)
    private val game = Win(GAME, Cover.APP, 0, 0, 1600, 720)
    private val statusBar = Win("com.android.systemui", Cover.SYSTEM, 0, 0, 1600, 48)
    private val navBar = Win("com.android.systemui", Cover.SYSTEM, 0, 672, 1600, 720)
    private val capsule = Win(SELF, Cover.OVERLAY, 700, 4, 900, 40)

    fun run() {
        // nothing over the game
        T.check("game alone", judge(game).state == State.CLEAR)
        T.check("our own capsule is not a cover", judge(capsule, game).state == State.CLEAR)
        T.check("status and navigation bars stay out of the zone", judge(statusBar, navBar, capsule, game).state == State.CLEAR)
        T.check("another app's accessibility overlay lets touches through", judge(Win("com.other", Cover.OVERLAY, 0, 0, 1600, 720), game).state == State.CLEAR)
        T.check("the game's own pop-up window above its main window", judge(Win(GAME, Cover.APP, 300, 100, 1300, 600), game).state == State.CLEAR)
        T.check("the game's own overlay kind of window", judge(Win(GAME, Cover.SYSTEM, 0, 0, 1600, 720)).state == State.CLEAR)

        // something over the zone
        val anr = judge(Win("android", Cover.SYSTEM, 310, 210, 1290, 510), game)
        T.check("a system message in the middle covers", anr.state == State.COVERED && anr.by == "android", { "${anr.state} ${anr.by}" })
        T.check("a system message alone on the list covers", judge(Win("android", Cover.SYSTEM, 310, 210, 1290, 510)).state == State.COVERED)
        val shade = judge(Win("com.android.systemui", Cover.SYSTEM, 0, 0, 1600, 720), game)
        T.check("the shade covers", shade.state == State.COVERED && shade.by == "com.android.systemui")
        val kb = judge(Win("com.keyboard", Cover.IME, 0, 380, 1600, 720), game)
        T.check("a keyboard over the zone covers", kb.state == State.COVERED && kb.by == "com.keyboard")
        val other = judge(Win("com.other.app", Cover.APP, 0, 0, 1600, 720), game)
        T.check("another app in front covers", other.state == State.COVERED && other.by == "com.other.app")
        T.check("our own main screen in front covers", judge(Win(SELF, Cover.APP, 0, 0, 1600, 720), game).state == State.COVERED)
        T.check("a floating button inside the zone covers", judge(Win("com.floaty", Cover.SYSTEM, 1200, 300, 1300, 400), game).state == State.COVERED)
        val nameless = judge(Win("", Cover.SYSTEM, 900, 200, 1400, 500), game)
        T.check("a window that does not say who it is covers as ?", nameless.state == State.COVERED && nameless.by == "?")

        // something that does not reach the zone
        T.check("a window on the left side only", judge(Win("android", Cover.SYSTEM, 100, 100, 500, 300), game).state == State.CLEAR)
        T.check("a floating button at the edge", judge(Win("com.floaty", Cover.SYSTEM, 1520, 300, 1600, 400), game).state == State.CLEAR)
        T.check("touching the zone's edge is not covering it", judge(Win("com.x", Cover.SYSTEM, 1502, 200, 1600, 500), game).state == State.CLEAR)
        T.check("one pixel inside the zone is covering it", judge(Win("com.x", Cover.SYSTEM, 1501, 200, 1600, 500), game).state == State.COVERED)
        T.check("a window with no area covers nothing", judge(Win("com.x", Cover.SYSTEM, 1000, 300, 1000, 300), game).state == State.CLEAR)

        // order: only what is above the game counts
        T.check("an app below the game does not cover it", judge(game, Win("com.other.app", Cover.APP, 0, 0, 1600, 720)).state == State.CLEAR)
        T.check("a message below the game does not cover it", judge(game, Win("android", Cover.SYSTEM, 310, 210, 1290, 510)).state == State.CLEAR)
        T.check("the first cover on the way down is the one named", judge(Win("com.a", Cover.SYSTEM, 900, 200, 1000, 300), Win("com.b", Cover.SYSTEM, 900, 200, 1000, 300), game).by == "com.a")
        T.check("split screen: the other app over the zone covers", judge(Win("com.other.app", Cover.APP, 800, 0, 1600, 720), Win(GAME, Cover.APP, 0, 0, 800, 720)).state == State.COVERED)

        // no information
        T.check("no windows at all", judge().state == State.UNKNOWN)
        T.check("zone not known yet", judge(Win("android", Cover.SYSTEM, 0, 0, 1600, 720), game, z = null).state == State.UNKNOWN)
        T.check("no game window and nothing over the zone", judge(statusBar, navBar).state == State.NO_GAME)
        T.check("no game window but a cover is still named", judge(statusBar, Win("com.other.app", Cover.APP, 0, 0, 1600, 720)).state == State.COVERED)

        // the rectangle test itself
        val z = intArrayOf(10, 10, 20, 20)
        T.check("rect: inside", Cover.hits(Win("p", 3, 12, 12, 15, 15), z))
        T.check("rect: around", Cover.hits(Win("p", 3, 0, 0, 100, 100), z))
        T.check("rect: left of", !Cover.hits(Win("p", 3, 0, 0, 10, 100), z))
        T.check("rect: right of", !Cover.hits(Win("p", 3, 20, 0, 30, 100), z))
        T.check("rect: above", !Cover.hits(Win("p", 3, 0, 0, 100, 10), z))
        T.check("rect: below", !Cover.hits(Win("p", 3, 0, 20, 100, 30), z))
        T.check("rect: corner overlap", Cover.hits(Win("p", 3, 19, 19, 40, 40), z))
    }
}
