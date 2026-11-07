package com.gamevision.bot.act

import com.gamevision.bot.Detection

/** What [GestureActor] should do next, decided by a [Strategy] from the latest [Detection] list. */
sealed class PlannedAction {
    data class Tap(val x: Int, val y: Int) : PlannedAction()
    data class Swipe(val x0: Int, val y0: Int, val x1: Int, val y1: Int) : PlannedAction()
    data class Hold(val x: Int, val y: Int, val durationMs: Long) : PlannedAction()
}

/**
 * Turns "here is what the camera/detector sees right now" into "here is what to touch next".
 * This is the ONLY piece that needs to know anything about a specific game; [com.gamevision.bot.BotEngine]
 * and [com.gamevision.bot.detect.ButtonFinder] stay generic.
 */
interface Strategy {
    fun decide(detections: List<Detection>, screenW: Int, screenH: Int, tick: Long): List<PlannedAction>
}

/**
 * Ships as the default on purpose: it looks, logs what it sees, and touches nothing. A strategy tuned on
 * nothing but a generic "icon-like region" heuristic would tap at random in WHATEVER app is on screen —
 * worse than doing nothing. Replace this once real gameplay reference footage is available (see README,
 * "Teaching it your game").
 */
class NoopStrategy : Strategy {
    override fun decide(detections: List<Detection>, screenW: Int, screenH: Int, tick: Long): List<PlannedAction> = emptyList()
}
