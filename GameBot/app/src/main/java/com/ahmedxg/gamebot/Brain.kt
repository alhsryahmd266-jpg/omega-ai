package com.ahmedxg.gamebot

import kotlin.math.max
import kotlin.math.min

/**
 * The learned tactical brain: a small network trained (on Kaggle) from recorded gameplay. Pure Kotlin so the feature
 * layout can be unit-tested; the Android side only supplies a [BrainModel] (see TfliteBrain).
 *
 * CONTRACT with the Python trainer (brain_lib.py):
 *   in  (24): for the 3 enemies nearest the crosshair [present, dx, dy, w*8, h*4, conf] x3, then [since_seen/10, n_enemies/5],
 *             then the brain's previous output [moveX, moveY, yaw, pitch]
 *   out (4) : [moveX (right +), moveY (forward +), yaw rate (right +), pitch rate (up +)], each -1..1
 *             yaw is in units of YAW_SCALE screen widths per second, pitch in PITCH_SCALE screen heights per second
 */
object BrainSpec {
    const val IN = 24
    const val OUT = 4
    const val YAW_SCALE = 1.5f
    const val PITCH_SCALE = 1.0f
}

interface BrainModel {
    /** @param x BrainSpec.IN numbers; @return BrainSpec.OUT numbers */
    fun run(x: FloatArray): FloatArray
}

class BrainOut(val moveX: Float, val moveY: Float, val yawRate: Float, val pitchRate: Float)

class BrainRunner(private val model: BrainModel) {
    private val prev = FloatArray(4)
    private var lastSeenMs = 0L
    var failures = 0; private set

    fun reset() { prev.fill(0f); lastSeenMs = 0L; failures = 0 }

    private fun clamp1(v: Float) = max(-1f, min(1f, v))

    fun features(dets: List<Det>, nowMs: Long): FloatArray {
        val f = FloatArray(BrainSpec.IN)
        val ds = dets.sortedBy { (it.cx - 0.5f) * (it.cx - 0.5f) + (it.cy - 0.5f) * (it.cy - 0.5f) }.take(3)
        for (i in ds.indices) {
            val d = ds[i]
            val o = i * 6
            f[o] = 1f
            f[o + 1] = clamp1((d.cx - 0.5f) * 2f)
            f[o + 2] = clamp1((d.cy - 0.5f) * 2f)
            f[o + 3] = min(1f, d.w * 8f)
            f[o + 4] = min(1f, d.h * 4f)
            f[o + 5] = d.score
        }
        if (dets.isNotEmpty()) lastSeenMs = nowMs
        val since = if (dets.isNotEmpty()) 0f else if (lastSeenMs == 0L) 99f else (nowMs - lastSeenMs) / 1000f
        f[18] = min(since, 10f) / 10f
        f[19] = min(dets.size, 5) / 5f
        for (i in 0 until 4) f[20 + i] = prev[i]
        return f
    }

    /** Runs the network for this frame. Returns null (and keeps the rules in charge) if the model misbehaves. */
    fun step(dets: List<Det>, nowMs: Long): BrainOut? {
        val x = features(dets, nowMs)
        val y = try { model.run(x) } catch (t: Throwable) { failures++; return null }
        if (y.size < BrainSpec.OUT || y.any { it.isNaN() || it.isInfinite() }) { failures++; return null }
        for (i in 0 until 4) prev[i] = clamp1(y[i])
        return BrainOut(prev[0], prev[1], prev[2], prev[3])
    }
}
