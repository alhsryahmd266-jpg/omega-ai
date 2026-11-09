package com.tacticai.bot.tactical

import android.graphics.Bitmap
import android.graphics.Rect

/** One numbered marker found on the minimap. */
data class MinimapMarker(val number: Int, val position: Rect)

/**
 * Reads friendly-unit numbers off the minimap (you said friendlies are shown there labelled with English
 * digits). This is template matching, not OCR: digit shapes in a game's own HUD font are extremely
 * consistent frame to frame, so matching a handful of reference crops (one per digit 0-9, cut once from a
 * real screenshot) is both simpler and more reliable here than a general-purpose text recognizer.
 *
 * NOT WIRED TO REAL TEMPLATES YET — [digitTemplates] is empty until real templates exist, and
 * [findMarkers] returns an empty list in that case rather than guessing. To finish this:
 *   1. send a screenshot of the minimap with the numbered markers visible
 *   2. crop each visible digit (0-9) as a small bitmap and register it with [registerDigit]
 *   3. this class is already called from [UtilityAiStrategy] — no other wiring needed once templates exist
 */
class MinimapTeamReader(private val minimapRegion: Rect) {

    private val digitTemplates = mutableMapOf<Int, Bitmap>()

    val isCalibrated: Boolean get() = digitTemplates.isNotEmpty()

    fun registerDigit(digit: Int, template: Bitmap) {
        digitTemplates[digit] = template
    }

    fun findMarkers(frame: Bitmap): List<MinimapMarker> {
        if (!isCalibrated) return emptyList()
        // TODO once templates exist: crop minimapRegion out of `frame`, slide each digit template over it
        // (normalised cross-correlation, same spirit as ButtonFinder's grid scan), keep matches above a
        // confidence threshold, cluster adjacent digit matches into multi-digit numbers (e.g. "1", "2").
        return emptyList()
    }
}
