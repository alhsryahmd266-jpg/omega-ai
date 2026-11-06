package com.ahmedxg.gamebot

/** One detection, normalized to the screen (0..1). cx,cy = box center. */
data class Det(
    val cls: Int,
    val label: String,
    val score: Float,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float
)

class Perception(
    val now: Long,
    val dets: List<Det>,
    val screenW: Int,
    val screenH: Int
)

/** What the bot wants to do right now. Translated into touches by [Controller]. */
class Action {
    var moveX = 0f      // -1..1 (right positive)
    var moveY = 0f      // -1..1 (forward positive)
    var lookDx = 0f     // look swipe, in screen pixels for this frame
    var lookDy = 0f
    var fire = false
    val taps = ArrayList<String>(2)   // one-shot taps: jump, crouch, reload, ads
    var state = ""

    companion object {
        fun idle(): Action {
            val a = Action()
            a.state = "IDLE"
            return a
        }
    }
}
