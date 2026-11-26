package com.omega.sight.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * What the finger has to do in this step: move by (dx, dy) screen pixels. [restart] = the finger first has to be lifted and put down
 * again in the middle of the aim zone, because it ran out of room.
 */
class AimCmd(val dx: Float, val dy: Float, val restart: Boolean = false) {
    val active: Boolean get() = dx != 0f || dy != 0f

    companion object {
        val NONE = AimCmd(0f, 0f)
    }
}

/**
 * Turns "the target is this far from the crosshair" into finger movements.
 *
 * It does not need to know how touchy the game's camera is: it learns that while it works, by comparing what it asked the finger to do
 * with how far the picture really moved (the shift measured by GlobalMotion). It also learns the delay between the two, and counts
 * the movements that are still on their way when it works out the next one, so a slow reaction does not make it overshoot.
 *
 * Units: errors are analysis-frame pixels, finger movements are screen pixels. [relX] / [relY] tie them together: the picture moves by
 * `rel * finger`, measured in screen pixels. Most games turn the view so that the picture follows the finger the other way round,
 * 1:1 at a sensitivity of 1, which is where -1 comes from.
 */
class AimController {
    /** screen pixels in one analysis-frame pixel; set again whenever the phone turns */
    var screenPerFrame = 12f

    /** height of the analysis frame, for the speed limit */
    var frameH = 90f

    /** how far the finger may wander from the middle of the aim zone (screen px) before the stroke has to be restarted */
    var zoneHalfW = 300f
    var zoneHalfH = 250f

    var relX = -1f
        private set
    var relY = -1f
        private set

    /** frames between a finger movement and the picture showing it */
    var lag = 2f
        private set

    /** 0..1, how sure the learnt numbers are; below 0.5 the steps are kept small (a probe) */
    var confidence = 0f
        private set
    val calibrated: Boolean get() = confidence >= 0.5f

    /** last error the controller saw, frame px (for the diagnostics screen) */
    var errX = 0f
        private set
    var errY = 0f
        private set

    // finger position inside the aim zone, relative to its middle
    private var fx = 0f
    private var fy = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var yLearnt = false

    // history of the last HN frames: what the picture did and what the finger was told
    private val hux = FloatArray(HN)
    private val huy = FloatArray(HN)
    private val hex = FloatArray(HN)
    private val hey = FloatArray(HN)
    private val hok = BooleanArray(HN)
    private var n = -1

    private val xEU = DoubleArray(LMAX + 1)
    private val xUU = DoubleArray(LMAX + 1)
    private val xEE = DoubleArray(LMAX + 1)
    private val yEU = DoubleArray(LMAX + 1)
    private val yUU = DoubleArray(LMAX + 1)
    private val yEE = DoubleArray(LMAX + 1)
    private val cnt = IntArray(LMAX + 1)

    /** start from numbers saved earlier for this game */
    fun loadCalibration(relX: Float, relY: Float, lag: Float, confidence: Float) {
        if (abs(relX) in 0.05f..40f) this.relX = relX
        if (abs(relY) in 0.05f..40f) this.relY = relY
        this.lag = lag.coerceIn(0f, LMAX.toFloat())
        this.confidence = confidence.coerceIn(0f, 1f)
        yLearnt = this.confidence >= 0.5f
    }

    /** the finger lifted (fire released, target lost for good): the next stroke starts in the middle of the zone */
    fun endStroke() {
        fx = 0f
        fy = 0f
        lastX = 0f
        lastY = 0f
    }

    /** seconds from the picture to the moment a correction shows up in the picture; the target picker leads by this much */
    fun latency(dt: Float): Float = (lag + 1f) * dt.coerceIn(0.005f, 0.25f)

    /** call once per frame, first: [ego] is how far the whole picture moved since the previous frame */
    fun observe(ego: Ego) {
        n++
        val s = n % HN
        hex[s] = ego.tx
        hey[s] = ego.ty
        hok[s] = ego.valid && ego.inliers >= 0.5f
        hux[s] = 0f
        huy[s] = 0f
        learn()
    }

    /**
     * call once per frame, after [observe].
     * @param pick the chosen target, null = nothing to do (the finger stays where it is)
     * @param cross where the shots land, frame pixels
     */
    fun step(pick: Pick?, cross: Vec2, t: Tuning, dt: Float): AimCmd {
        if (n < 0) return AimCmd.NONE
        if (pick == null) {
            lastX = 0f
            lastY = 0f
            return AimCmd.NONE
        }
        val slot = n % HN
        val dts = dt.coerceIn(0.005f, 0.25f)
        val spf = screenPerFrame
        val ex = pick.aim.x - cross.x
        val ey = pick.aim.y - cross.y
        errX = ex
        errY = ey

        // movements already ordered that the picture has not shown yet
        var px = 0f
        var py = 0f
        val whole = lag.toInt()
        for (j in 1..whole) {
            val k = n - j
            if (k < 0 || k <= n - HN) break
            px += hux[k % HN]
            py += huy[k % HN]
        }
        val frac = lag - whole
        val kf = n - whole - 1
        if (frac > 0f && kf >= 0 && kf > n - HN) {
            px += frac * hux[kf % HN]
            py += frac * huy[kf % HN]
        }

        val rx = safeRel(relX)
        val ry = safeRel(relY)
        // where the target will sit, in screen pixels from the crosshair, once those movements have acted
        val eSx = ex * spf + rx * px
        val eSy = ey * spf + ry * py
        val remaining = hypot(eSx, eSy) / spf

        // close enough = inside a tenth of the target's height, but never looser than 2 frame pixels nor tighter than a quarter pixel
        val dead = (0.10f * pick.track.box.h).coerceIn(0.25f, 2.0f)
        val soft = ((remaining - dead) / dead).coerceIn(0f, 1f)
        val kp = 0.30f + 0.65f * t.strength.coerceIn(0f, 1f)
        var ux = -eSx / rx * kp * soft
        var uy = -eSy / ry * kp * soft

        val alpha = 1f - 0.65f * t.smooth.coerceIn(0f, 1f)
        ux = lastX + alpha * (ux - lastX)
        uy = lastY + alpha * (uy - lastY)

        // never ask the picture to move faster than the owner allowed; keep the first steps small until the numbers are learnt
        val vmax = t.maxSpeed * frameH * spf * dts
        var capX = vmax / abs(rx)
        var capY = vmax / abs(ry)
        if (!calibrated) {
            // a small first step: if the sensitivity or the direction is not what was guessed, the damage stays small
            val probe = zoneHalfW * (0.07f + 0.36f * (confidence / 0.5f).coerceIn(0f, 1f))
            capX = min(capX, probe)
            capY = min(capY, probe)
        }
        val limX = 0.92f * zoneHalfW
        val limY = 0.92f * zoneHalfH
        capX = min(capX, limX)
        capY = min(capY, limY)
        ux = ux.coerceIn(-capX, capX)
        uy = uy.coerceIn(-capY, capY)
        if (abs(ux) < 0.05f) ux = 0f
        if (abs(uy) < 0.05f) uy = 0f

        var restart = false
        var nfx = fx + ux
        var nfy = fy + uy
        if (abs(nfx) > limX || abs(nfy) > limY) {
            restart = true
            nfx = ux
            nfy = uy
        }
        fx = nfx
        fy = nfy
        hux[slot] = ux
        huy[slot] = uy
        lastX = ux
        lastY = uy
        return if (ux == 0f && uy == 0f) AimCmd.NONE else AimCmd(ux, uy, restart)
    }

    private fun safeRel(r: Float): Float = if (abs(r) < 0.05f) (if (r < 0f) -0.05f else 0.05f) else r

    /** least squares of "how far the picture moved" against "what the finger did L frames earlier", for every L; the best L wins */
    private fun learn() {
        val first = max(1, n - WIN + 1)
        for (l in 0..LMAX) {
            xEU[l] = 0.0
            xUU[l] = 0.0
            xEE[l] = 0.0
            yEU[l] = 0.0
            yUU[l] = 0.0
            yEE[l] = 0.0
            cnt[l] = 0
        }
        for (t in first..n) {
            val st = t % HN
            if (!hok[st]) continue
            val ex = hex[st].toDouble()
            val ey = hey[st].toDouble()
            for (l in 0..LMAX) {
                val k = t - 1 - l
                if (k < 0 || k <= n - HN) break
                val sk = k % HN
                val ux = hux[sk].toDouble()
                val uy = huy[sk].toDouble()
                xEU[l] += ex * ux
                xUU[l] += ux * ux
                xEE[l] += ex * ex
                yEU[l] += ey * uy
                yUU[l] += uy * uy
                yEE[l] += ey * ey
                cnt[l]++
            }
        }
        var best = -1
        var bestSse = Double.MAX_VALUE
        for (l in 0..LMAX) {
            if (cnt[l] < 4) continue
            val sx = xEE[l] - (if (xUU[l] > 1e-6) xEU[l] * xEU[l] / xUU[l] else 0.0)
            val sy = yEE[l] - (if (yUU[l] > 1e-6) yEU[l] * yEU[l] / yUU[l] else 0.0)
            if (sx + sy < bestSse) {
                bestSse = sx + sy
                best = l
            }
        }
        if (best < 0) return
        val total = xEE[best] + yEE[best]
        val excite = xUU[best] + yUU[best]
        if (total < 1e-3 || excite < 600.0) return
        val r2 = (1.0 - bestSse / total).toFloat()
        if (r2 < 0.30f) return

        val a = (if (confidence < 0.5f) 0.6f else 0.25f) * r2
        lag += 0.3f * (best - lag)
        var gotX = false
        if (xUU[best] >= 250.0) {
            val g = (xEU[best] / xUU[best]).toFloat() * screenPerFrame
            if (abs(g) >= 0.05f && (g * relX > 0f || r2 >= 0.6f)) {
                relX += a * (g.coerceIn(-40f, 40f) - relX)
                gotX = true
            }
        }
        var gotY = false
        if (yUU[best] >= 250.0) {
            val g = (yEU[best] / yUU[best]).toFloat() * screenPerFrame
            if (abs(g) >= 0.05f && (g * relY > 0f || r2 >= 0.6f)) {
                relY += a * (g.coerceIn(-40f, 40f) - relY)
                gotY = true
                yLearnt = true
            }
        }
        // the vertical sensitivity has not been seen yet: take the horizontal one, keeping the sign the game seemed to have
        if (gotX && !gotY && !yLearnt) relY = (if (relY < 0f) -1f else 1f) * abs(relX)
        if (gotX || gotY) confidence = min(1f, confidence + 0.2f * r2)
    }

    companion object {
        private const val HN = 48
        private const val WIN = 36
        private const val LMAX = 6
    }
}
