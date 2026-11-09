package com.tacticai.bot.detect

import android.graphics.Rect

enum class Team { FRIENDLY, ENEMY, UNKNOWN }

/**
 * One object the vision model found in the game world (as opposed to [com.tacticai.bot.Detection], which
 * is a candidate UI control). [team] starts UNKNOWN and is filled in by [com.tacticai.bot.tactical.TeamColorFilter].
 */
data class WorldDetection(
    val box: Rect,
    val label: String,
    val score: Float,
    val team: Team = Team.UNKNOWN,
) {
    val centerX: Int get() = box.centerX()
    val centerY: Int get() = box.centerY()
}
