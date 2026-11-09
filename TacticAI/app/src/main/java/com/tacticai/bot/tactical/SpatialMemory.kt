package com.tacticai.bot.tactical

import kotlin.math.abs
import kotlin.math.hypot

/**
 * A coarse mental map of the current match, in NORMALISED coordinates (0f..1f across the screen) so it
 * doesn't care about screen resolution or where the camera happens to be pointing. Plain arithmetic, no
 * model — see the architecture note in [UtilityAiStrategy] for why this is deliberate.
 *
 * Verified with a standalone 11-case test before being ported here (decay, clamping, out-of-range
 * coordinates, nearest-safe-cell search) — see the project's test notes.
 */
class SpatialMemory(private val cols: Int = 24, private val rows: Int = 24) {

    enum class Tag { NEUTRAL, DANGER, SAFE }

    private val danger = FloatArray(cols * rows)
    private val safe = FloatArray(cols * rows)

    private fun idx(gx: Int, gy: Int) = gy.coerceIn(0, rows - 1) * cols + gx.coerceIn(0, cols - 1)
    private fun toGrid(nx: Float, ny: Float): Pair<Int, Int> =
        (nx.coerceIn(0f, 0.999f) * cols).toInt() to (ny.coerceIn(0f, 0.999f) * rows).toInt()

    /** Call once per engine tick so old marks fade instead of permanently dominating the map. */
    fun decay(factor: Float = 0.995f) {
        for (i in danger.indices) { danger[i] *= factor; safe[i] *= factor }
    }

    /** The bot (or a teammate, once that signal exists) died around here. */
    fun markDeath(nx: Float, ny: Float, strength: Float = 1f) {
        val (gx, gy) = toGrid(nx, ny)
        for (dy in -1..1) for (dx in -1..1) {
            val g = idx(gx + dx, gy + dy)
            val falloff = if (dx == 0 && dy == 0) 1f else 0.4f
            danger[g] = (danger[g] + strength * falloff).coerceAtMost(3f)
        }
    }

    /** Somewhere the bot successfully held position / regrouped without taking damage. */
    fun markSafe(nx: Float, ny: Float, strength: Float = 0.5f) {
        val (gx, gy) = toGrid(nx, ny)
        val g = idx(gx, gy)
        safe[g] = (safe[g] + strength).coerceAtMost(3f)
    }

    fun dangerAt(nx: Float, ny: Float): Float {
        val (gx, gy) = toGrid(nx, ny)
        return danger[idx(gx, gy)]
    }

    fun tagAt(nx: Float, ny: Float): Tag {
        val d = dangerAt(nx, ny)
        val (gx, gy) = toGrid(nx, ny)
        val s = safe[idx(gx, gy)]
        return when { d > 0.8f -> Tag.DANGER; s > 0.8f -> Tag.SAFE; else -> Tag.NEUTRAL }
    }

    /** Nearest low-danger, preferably marked-safe cell to (nx,ny) — where RetreatIntent should head. */
    fun nearestSafe(nx: Float, ny: Float): Pair<Float, Float> {
        val (gx0, gy0) = toGrid(nx, ny)
        var best = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (gy in 0 until rows) for (gx in 0 until cols) {
            val g = idx(gx, gy)
            val dist = hypot((gx - gx0).toFloat(), (gy - gy0).toFloat())
            val score = safe[g] * 2f - danger[g] * 3f - dist * 0.08f
            if (score > bestScore) { bestScore = score; best = g }
        }
        val gx = best % cols
        val gy = best / cols
        return (gx + 0.5f) / cols to (gy + 0.5f) / rows
    }
}
