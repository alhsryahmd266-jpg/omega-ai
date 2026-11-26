package com.omega.sight.core

import kotlin.math.hypot
import kotlin.math.min

/** The chosen target and the exact point to aim at (frame pixels), already moved ahead by the target's own speed. */
class Pick(val track: Track, val aim: Vec2, val dist: Float)

/**
 * Decides which of the followed things the correction chases, and where on it. Close to the crosshair, big enough, and recognised by
 * the model beat far, small and "just moving"; once chosen, a target is kept unless another one is clearly better (no flicking
 * back and forth between two enemies).
 */
class TargetPicker {
    private var currentId = -1

    fun reset() {
        currentId = -1
    }

    /** where on [t] to aim: the head box if there is one, else a point down from the top of the body (0 = head, 1 = chest) */
    fun aimPoint(t: Track, headBias: Float): Vec2 {
        val hd = t.head
        if (t.kind == Kind.HEAD || t.kind == Kind.GLOW) return Vec2(t.box.cx, t.box.cy)
        if (hd != null && headBias >= 0.35f) return Vec2(hd.cx, hd.cy)
        if (t.kind == Kind.MOTION) return Vec2(t.box.cx, t.box.t + 0.5f * t.box.h)
        val f = 0.40f + (0.12f - 0.40f) * headBias.coerceIn(0f, 1f)
        return Vec2(t.box.cx, t.box.t + f * t.box.h)
    }

    /**
     * @param cross the crosshair, frame pixels
     * @param latency seconds between the picture and the moment a correction shows up (the lead looks this far ahead)
     */
    fun pick(tracks: List<Track>, cross: Vec2, frameH: Float, t: Tuning, latency: Float): Pick? {
        val fovPx = t.fov.coerceIn(0.1f, 0.9f) * frameH
        var best: Pick? = null
        var bestScore = -1e9f
        var currentScore = -1e9f
        var currentPick: Pick? = null
        for (tr in tracks) {
            val base = aimPoint(tr, t.headBias)
            val ahead = latency * t.lead
            val aim = Vec2(base.x + tr.vx * ahead, base.y + tr.vy * ahead)
            val d = hypot(aim.x - cross.x, aim.y - cross.y)
            if (d > fovPx) continue
            val kindW = when (tr.kind) {
                Kind.HEAD -> 1.0f
                Kind.GLOW -> 0.9f
                Kind.BODY -> 0.85f
                Kind.MOTION -> 0.4f
            }
            val size = min(1f, tr.box.h / (0.25f * frameH))
            val s = -d / fovPx + 0.25f * kindW + 0.10f * size + 0.15f * tr.score + (if (tr.id == currentId) 0.22f else 0f)
            val p = Pick(tr, aim, d)
            if (tr.id == currentId) {
                currentScore = s
                currentPick = p
            }
            if (s > bestScore) {
                bestScore = s
                best = p
            }
        }
        // keep the current one unless the best is clearly better (the bonus above already adds some stickiness)
        if (currentPick != null && bestScore < currentScore + 0.12f) best = currentPick
        currentId = best?.track?.id ?: -1
        return best
    }
}
