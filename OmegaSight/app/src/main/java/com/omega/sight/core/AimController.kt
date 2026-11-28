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
 * It does not need to know how touchy the game's camera is, nor how long the way from the finger to the picture takes: it learns that
 * while it works. A finger movement does not show up in the picture at one moment; it is spread over a few frames (the gesture takes
 * some milliseconds, the game draws a frame later, the screen is captured a frame later still). So what is learnt is the answer of the
 * picture over the next few frames to one finger movement (a short list of numbers, the "taps"): the controller compares what the
 * finger was told to do with how far the picture really moved (the shift measured by GlobalMotion) and tries a number of shapes of the
 * answer, a spike one, two, three ... frames later or a spread over a few frames, and keeps the one that explains the picture best. The
 * sum of the taps is how far the picture goes in the end per pixel of finger movement; where the taps lie is the delay.
 *
 * With that answer the controller counts, when it works out the next step, the part of the earlier movements that the picture has not
 * shown yet, so a slow reaction does not make it overshoot.
 *
 * Units: errors are analysis-frame pixels, finger movements are screen pixels. [relX] / [relY] tie them together: in the end the picture
 * moves by `rel * finger`, measured in screen pixels. Most games turn the view so that the picture follows the finger the other way
 * round, 1:1 at a sensitivity of 1, which is where -1 comes from.
 */
class AimController {
    /** screen pixels in one analysis-frame pixel; set again whenever the phone turns */
    var screenPerFrame = 12f

    /** height of the analysis frame, for the speed limit */
    var frameH = 90f

    /** how far the finger may wander from the middle of the aim zone (screen px) before the stroke has to be restarted */
    var zoneHalfW = 300f
    var zoneHalfH = 250f

    /** screen pixels the picture moves in the end per screen pixel of finger movement (the sum of the taps) */
    var relX = -1f
        private set
    var relY = -1f
        private set

    /** frames between a finger movement and the picture showing it (the middle of the taps) */
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

    // the answer of the picture: tap l = screen px of picture movement l+1 frames after the finger moved, per screen px of finger movement
    private val tapX = FloatArray(LT)
    private val tapY = FloatArray(LT)

    // tail[m] = what is still to come m frames after a movement (the sum of the taps from tap m on)
    private val tailX = FloatArray(LT + 1)
    private val tailY = FloatArray(LT + 1)

    // the shapes the answer is tried with: the tap it starts at, how many taps, and their numbers (they add up to 1)
    private val kStart = IntArray(MAXK)
    private val kLen = IntArray(MAXK)
    private val kVal = Array(MAXK) { FloatArray(0) }
    private var nk = 0

    init {
        // a spike, two or three or four equal taps, a small hill of three and one of five, each starting 0..LT-length frames after the finger
        val shapes = arrayOf(
            floatArrayOf(1f),
            floatArrayOf(1f, 1f),
            floatArrayOf(1f, 1f, 1f),
            floatArrayOf(1f, 1f, 1f, 1f),
            floatArrayOf(1f, 2f, 1f),
            floatArrayOf(1f, 2f, 3f, 2f, 1f),
        )
        for (sh in shapes) {
            var sum = 0f
            for (v in sh) sum += v
            for (st in 0..LT - sh.size) {
                kStart[nk] = st
                kLen[nk] = sh.size
                kVal[nk] = FloatArray(sh.size) { sh[it] / sum }
                nk++
            }
        }
        setTaps(-1f, -1f, 2f)
    }

    /** spread a total gain over the taps around the middle [at] (a small triangle) */
    private fun spread(taps: FloatArray, total: Float, at: Float) {
        var sum = 0f
        for (l in 0 until LT) {
            val w = max(0f, 1.5f - abs(l - at))
            taps[l] = w
            sum += w
        }
        if (sum <= 0f) {
            taps.fill(0f)
            taps[min(LT - 1, max(0, at.toInt()))] = total
            return
        }
        for (l in 0 until LT) taps[l] = taps[l] / sum * total
    }

    private fun setTaps(rx: Float, ry: Float, at: Float) {
        spread(tapX, rx, at)
        spread(tapY, ry, at)
        tails()
        relX = rx
        relY = ry
        lag = at
    }

    private fun tails() {
        tailX[LT] = 0f
        tailY[LT] = 0f
        for (l in LT - 1 downTo 0) {
            tailX[l] = tailX[l + 1] + tapX[l]
            tailY[l] = tailY[l + 1] + tapY[l]
        }
    }

    /** start from numbers saved earlier for this game */
    fun loadCalibration(relX: Float, relY: Float, lag: Float, confidence: Float) {
        val rx = if (abs(relX) in 0.05f..40f) relX else this.relX
        val ry = if (abs(relY) in 0.05f..40f) relY else this.relY
        setTaps(rx, ry, lag.coerceIn(0f, (LT - 1).toFloat()))
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

    /**
     * How far the picture should move between the last frame and the next one because of the finger movements already ordered
     * (analysis-frame px, x and y into [out]). Only meaningful once the answer of the game is known; the shift measurement can start
     * its search there, so even a big and sudden movement of the picture is found.
     */
    fun expectedShift(out: FloatArray) {
        var sx = 0f
        var sy = 0f
        if (n >= 0) {
            for (l in 0 until LT) {
                val k = n - l
                if (k < 0 || k <= n - HN) break
                sx += tapX[l] * hux[k % HN]
                sy += tapY[l] * huy[k % HN]
            }
        }
        out[0] = sx / screenPerFrame
        out[1] = sy / screenPerFrame
    }

    /** seconds from the picture to the moment a correction shows up in the picture; the target picker leads by this much */
    fun latency(dt: Float): Float = (lag + 1f) * dt.coerceIn(0.005f, 0.25f)

    /** call once per frame, first: [ego] is how far the whole picture moved since the previous frame */
    fun observe(ego: Ego) {
        n++
        val s = n % HN
        hex[s] = ego.tx
        hey[s] = ego.ty
        // a reading near the end of what the shift measurement can follow is as likely a failure as a measurement: it teaches nothing
        hok[s] = ego.valid && ego.inliers >= 0.5f && abs(ego.tx) <= MAX_READ && abs(ego.ty) <= MAX_READ
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

        // the part of the movements already ordered that the picture has not shown yet: a movement ordered j frames ago has shown its
        // first j taps by now
        var px = 0f
        var py = 0f
        for (j in 1 until LT) {
            val k = n - j
            if (k < 0 || k <= n - HN) break
            px += hux[k % HN] * tailX[j]
            py += huy[k % HN] * tailY[j]
        }

        val rx = safeRel(relX)
        val ry = safeRel(relY)
        // where the target will sit, in screen pixels from the crosshair, once those movements have acted
        val eSx = ex * spf + px
        val eSy = ey * spf + py
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

        // The measurement of how far the picture moved only works up to a few percent of the screen per frame. So the picture may not be
        // asked to go farther than that, counting what is still on its way: the movements ordered a moment ago may reach the picture
        // together (the gesture pieces are sent when the one before has finished), and a slow phone makes every frame long.
        val flight = hypot(px, py)
        val room = min(MAX_STEP * spf, max(0f, MAX_FLIGHT * spf - flight))
        val mag = hypot(rx * ux, ry * uy)
        if (mag > room) {
            val k = if (mag > 1e-3f) room / mag else 0f
            ux *= k
            uy *= k
        }

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

    /**
     * Least squares of "how far the picture moved" (screen px) against "what the finger was told to do a few frames earlier", for a
     * number of shapes of the answer (a spike at 1, 2, 3 ... frames, or a spread over a few frames): the shape that explains the picture
     * best wins, and its gain is the sensitivity. An axis whose movements do not explain the picture (the game's camera stands at a
     * limit, say) is left as it was.
     */
    private fun learn() {
        if (n < 4) return
        val spf = screenPerFrame.toDouble()
        val first = max(1, n - WIN + 1)
        var best = -1
        var bestScore = Double.MAX_VALUE
        var bXEZ = 0.0
        var bXZZ = 0.0
        var bYEZ = 0.0
        var bYZZ = 0.0
        var bXEE = 0.0
        var bYEE = 0.0
        for (c in 0 until nk) {
            val st0 = kStart[c]
            val len = kLen[c]
            val kv = kVal[c]
            var xEZ = 0.0
            var xZZ = 0.0
            var xEE = 0.0
            var yEZ = 0.0
            var yZZ = 0.0
            var yEE = 0.0
            var rows = 0
            for (t in first..n) {
                val s = t % HN
                if (!hok[s]) continue
                var zx = 0.0
                var zy = 0.0
                for (i in 0 until len) {
                    val k = t - 1 - (st0 + i)
                    if (k < 0 || k <= n - HN) break
                    val w = kv[i].toDouble()
                    zx += w * hux[k % HN]
                    zy += w * huy[k % HN]
                }
                val ex = hex[s] * spf
                val ey = hey[s] * spf
                xEZ += ex * zx
                xZZ += zx * zx
                xEE += ex * ex
                yEZ += ey * zy
                yZZ += zy * zy
                yEE += ey * ey
                rows++
            }
            if (rows < 4) continue
            val sx = xEE - (if (xZZ > 1e-6) xEZ * xEZ / xZZ else 0.0)
            val sy = yEE - (if (yZZ > 1e-6) yEZ * yEZ / yZZ else 0.0)
            // a longer shape fits a little better by chance alone: it has to earn its length
            val score = (sx + sy) * (1.0 + 0.02 * (len - 1))
            if (score < bestScore) {
                bestScore = score
                best = c
                bXEZ = xEZ
                bXZZ = xZZ
                bYEZ = yEZ
                bYZZ = yZZ
                bXEE = xEE
                bYEE = yEE
            }
        }
        if (best < 0) return
        val len = kLen[best]
        // the shape's numbers add up to 1, so a long shape sees less of every single movement: judge the movement by the sum over its length
        val excite = (bXZZ + bYZZ) * len
        if (bXEE + bYEE < 1e-3 || excite < 600.0) return
        // a frame whose shift was read wrongly (it happens when the picture jumps) must not decide: go over the rows again, the worse a
        // row fits the less it counts
        for (pass in 0 until 2) {
            val gx0 = if (bXZZ > 1e-6) bXEZ / bXZZ else 0.0
            val gy0 = if (bYZZ > 1e-6) bYEZ / bYZZ else 0.0
            if (!reweigh(best, spf, first, gx0, gy0)) break
            bXEZ = wXEZ
            bXZZ = wXZZ
            bXEE = wXEE
            bYEZ = wYEZ
            bYZZ = wYZZ
            bYEE = wYEE
        }
        val total = bXEE + bYEE
        if (total < 1e-3) return
        val sseX = bXEE - (if (bXZZ > 1e-6) bXEZ * bXEZ / bXZZ else 0.0)
        val sseY = bYEE - (if (bYZZ > 1e-6) bYEZ * bYEZ / bYZZ else 0.0)
        val r2 = (1.0 - (sseX + sseY) / total).toFloat()
        if (r2 < 0.30f) return

        val a = (if (confidence < 0.5f) 0.6f else 0.25f) * r2
        var gotX = false
        if (bXZZ * len >= 250.0) {
            val g = (bXEZ / bXZZ).toFloat()
            val r2x = if (bXEE > 1e-3) (1.0 - sseX / bXEE).toFloat() else 0f
            if (abs(g) in 0.05f..40f && r2x >= 0.30f && (g * relX > 0f || r2x >= 0.6f)) {
                blend(tapX, g, best, a)
                gotX = true
            }
        }
        var gotY = false
        if (bYZZ * len >= 250.0) {
            val g = (bYEZ / bYZZ).toFloat()
            val r2y = if (bYEE > 1e-3) (1.0 - sseY / bYEE).toFloat() else 0f
            if (abs(g) in 0.05f..40f && r2y >= 0.30f && (g * relY > 0f || r2y >= 0.6f)) {
                blend(tapY, g, best, a)
                gotY = true
                yLearnt = true
            }
        }
        if (!gotX && !gotY) return
        if (gotX) relX = sumOf(tapX)
        if (gotY) relY = sumOf(tapY)
        // the vertical sensitivity has not been seen yet: take the horizontal one, keeping the sign the game seemed to have
        if (gotX && !gotY && !yLearnt) {
            val ry = (if (relY < 0f) -1f else 1f) * abs(relX)
            blend(tapY, ry, best, 1f)
            relY = sumOf(tapY)
        }
        tails()
        // the delay = the middle of the taps (weighted by their size)
        var wsum = 0f
        var lsum = 0f
        for (l in 0 until LT) {
            val w = abs(tapX[l]) + abs(tapY[l])
            wsum += w
            lsum += w * l
        }
        if (wsum > 1e-6f) lag += 0.3f * (lsum / wsum - lag)
        confidence = min(1f, confidence + 0.2f * r2)
    }

    // rows of the last fit and the sums made from them with the weights
    private val rEx = DoubleArray(WIN + 1)
    private val rEy = DoubleArray(WIN + 1)
    private val rZx = DoubleArray(WIN + 1)
    private val rZy = DoubleArray(WIN + 1)
    private val rRes = DoubleArray(WIN + 1)
    private val rTmp = DoubleArray(WIN + 1)
    private var wXEZ = 0.0
    private var wXZZ = 0.0
    private var wXEE = 0.0
    private var wYEZ = 0.0
    private var wYZZ = 0.0
    private var wYEE = 0.0

    /** the sums of shape [c] again, with every row weighted by how well it fits the gains (gx0, gy0); false = too few rows */
    private fun reweigh(c: Int, spf: Double, first: Int, gx0: Double, gy0: Double): Boolean {
        val st0 = kStart[c]
        val len = kLen[c]
        val kv = kVal[c]
        var m = 0
        for (t in first..n) {
            val s = t % HN
            if (!hok[s] || m >= rEx.size) continue
            var zx = 0.0
            var zy = 0.0
            for (i in 0 until len) {
                val k = t - 1 - (st0 + i)
                if (k < 0 || k <= n - HN) break
                val w = kv[i].toDouble()
                zx += w * hux[k % HN]
                zy += w * huy[k % HN]
            }
            rEx[m] = hex[s] * spf
            rEy[m] = hey[s] * spf
            rZx[m] = zx
            rZy[m] = zy
            m++
        }
        if (m < 4) return false
        for (i in 0 until m) {
            rRes[i] = hypot(rEx[i] - gx0 * rZx[i], rEy[i] - gy0 * rZy[i])
            rTmp[i] = rRes[i]
        }
        java.util.Arrays.sort(rTmp, 0, m)
        val sigma = 1.4826 * rTmp[m / 2]
        val cut = max(2.0 * sigma, 4.0)
        wXEZ = 0.0
        wXZZ = 0.0
        wXEE = 0.0
        wYEZ = 0.0
        wYZZ = 0.0
        wYEE = 0.0
        for (i in 0 until m) {
            val w = if (rRes[i] <= cut) 1.0 else cut / rRes[i]
            wXEZ += w * rEx[i] * rZx[i]
            wXZZ += w * rZx[i] * rZx[i]
            wXEE += w * rEx[i] * rEx[i]
            wYEZ += w * rEy[i] * rZy[i]
            wYZZ += w * rZy[i] * rZy[i]
            wYEE += w * rEy[i] * rEy[i]
        }
        return true
    }

    /** move the taps towards "this gain, spread the way shape [c] says" */
    private fun blend(taps: FloatArray, gain: Float, c: Int, w: Float) {
        val st0 = kStart[c]
        val kv = kVal[c]
        for (l in 0 until LT) {
            val i = l - st0
            val target = if (i >= 0 && i < kv.size) gain * kv[i] else 0f
            taps[l] += w * (target - taps[l])
        }
    }

    private fun sumOf(a: FloatArray): Float {
        var s = 0f
        for (v in a) s += v
        return s
    }

    companion object {
        private const val HN = 72
        private const val WIN = 36

        /** how many frames the answer of the picture is followed for */
        private const val LT = 10

        /** room for the shapes the answer is tried with */
        private const val MAXK = 64

        /** analysis-frame px: how far one finger movement may move the picture, and how much may be on its way altogether */
        private const val MAX_STEP = 6f
        private const val MAX_FLIGHT = 12f

        /** analysis-frame px: shift readings larger than this are not used for learning */
        private const val MAX_READ = 12.5f
    }
}
