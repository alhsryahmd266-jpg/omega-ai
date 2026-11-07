package com.gamevision.bot

/**
 * One candidate on-screen control found in the latest captured frame.
 *
 * [box] is in the ORIGINAL screenshot's pixel coordinates (not the scaled-down bitmap used for
 * analysis), so it can be fed straight into [com.gamevision.bot.act.GestureActor] without conversion.
 */
data class Detection(
    val box: android.graphics.Rect,
    /** 0f..1f — how strongly this region looks like a distinct UI icon (edge density, contrast, shape). */
    val confidence: Float,
    /** True when the region sits where mobile shooters usually put touch controls (screen edges / lower half). */
    val likelyControl: Boolean,
    /** Set once a template library exists for this game (see README "Teaching it your game's buttons"); null until then. */
    val label: String? = null,
) {
    val centerX: Int get() = box.centerX()
    val centerY: Int get() = box.centerY()
}
