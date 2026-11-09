package com.ahmedxg.gamebot

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Result of reading the safe-zone ring from the minimap.
 * angleDeg: direction of the zone center relative to "up" (clockwise, -180..180) for a heading-up minimap.
 */
class ZoneInfo(
    val valid: Boolean,
    val angleDeg: Float,
    val outside: Boolean,
    val distToCenter: Float,
    val radius: Float
)

/**
 * Classical computer vision, no model: mask the ring color inside the minimap rectangle, fit a circle
 * (least squares), compare with the player position (rectangle center). Needs your game's ring color
 * (hex) and the rectangle set in the layout editor (yellow mapTL / mapBR markers).
 */
class ZoneNav(private val cfg: Config) {
    private var buf = IntArray(0)

    private fun targetColor(): Int {
        return try {
            Color.parseColor("#" + cfg.zoneColor.trim().removePrefix("#"))
        } catch (e: Exception) {
            Color.WHITE
        }
    }

    fun detect(bmp: Bitmap, w: Int, h: Int): ZoneInfo {
        val bad = ZoneInfo(false, 0f, false, 0f, 0f)
        val x0 = (cfg.layout.x("mapTL") * w).toInt().coerceIn(0, w - 2)
        val y0 = (cfg.layout.y("mapTL") * h).toInt().coerceIn(0, h - 2)
        val x1 = (cfg.layout.x("mapBR") * w).toInt().coerceIn(x0 + 1, w - 1)
        val y1 = (cfg.layout.y("mapBR") * h).toInt().coerceIn(y0 + 1, h - 1)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < 24 || rh < 24 || x0 + rw > bmp.width || y0 + rh > bmp.height) return bad
        if (buf.size < rw * rh) buf = IntArray(rw * rh)
        bmp.getPixels(buf, 0, rw, x0, y0, rw, rh)

        val tc = targetColor()
        val tr = Color.red(tc)
        val tg = Color.green(tc)
        val tb = Color.blue(tc)
        val tol = cfg.zoneTol.coerceIn(5, 160)
        val cx0 = rw / 2.0
        val cy0 = rh / 2.0

        var n = 0
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        var sz = 0.0; var sxz = 0.0; var syz = 0.0
        for (j in 0 until rh) {
            for (i in 0 until rw) {
                val p = buf[j * rw + i]
                if (abs(((p shr 16) and 255) - tr) <= tol && abs(((p shr 8) and 255) - tg) <= tol && abs((p and 255) - tb) <= tol) {
                    val x = i - cx0
                    val y = j - cy0
                    val z = x * x + y * y
                    n++
                    sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y
                    sz += z; sxz += x * z; syz += y * z
                }
            }
        }
        if (n < 25 || n > rw * rh * 0.6) return bad

        // least-squares circle (Kasa): x*A + y*B + C = -(x^2 + y^2)
        val m = arrayOf(
            doubleArrayOf(sxx, sxy, sx),
            doubleArrayOf(sxy, syy, sy),
            doubleArrayOf(sx, sy, n.toDouble())
        )
        val rhs = doubleArrayOf(-sxz, -syz, -sz)
        val sol = solve3(m, rhs) ?: return bad
        val ccx = -sol[0] / 2.0
        val ccy = -sol[1] / 2.0
        val r2 = ccx * ccx + ccy * ccy - sol[2]
        if (r2 <= 0.0) return bad
        val r = sqrt(r2)
        if (r < 6.0 || r > maxOf(rw, rh) * 3.0) return bad

        // quality: points must lie close to the fitted circle
        var err = 0.0
        for (j in 0 until rh) {
            for (i in 0 until rw) {
                val p = buf[j * rw + i]
                if (abs(((p shr 16) and 255) - tr) <= tol && abs(((p shr 8) and 255) - tg) <= tol && abs((p and 255) - tb) <= tol) {
                    val d = hypot(i - cx0 - ccx, j - cy0 - ccy)
                    err += (d - r) * (d - r)
                }
            }
        }
        val rms = sqrt(err / n)
        if (rms > 0.2 * r) return bad

        val dist = hypot(ccx, ccy)
        val ang = Math.toDegrees(atan2(ccx, -ccy)).toFloat()
        return ZoneInfo(true, ang, dist > r * 0.9, dist.toFloat(), r.toFloat())
    }

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        fun det(m: Array<DoubleArray>): Double =
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        val d = det(a)
        if (abs(d) < 1e-9) return null
        val res = DoubleArray(3)
        for (c in 0 until 3) {
            val m = Array(3) { r -> DoubleArray(3) { k -> if (k == c) b[r] else a[r][k] } }
            res[c] = det(m) / d
        }
        return res
    }
}

/**
 * Wraps another policy: when the minimap says we are outside the safe zone (and not in a fight),
 * turn toward the zone center and run to it. Needs a heading-up minimap (player icon fixed at the center).
 */
private val FIGHT_STATES = setOf("FIRE", "TRACK", "REACT", "STALK", "STRIKE", "DUEL", "DODGE", "AMBUSH", "SWEEP", "RETREAT", "AVOID", "HEAL", "SCAN")

class ZonePolicy(
    private val inner: Policy,
    private val zone: () -> ZoneInfo?,
    private val aimScale: Float,
    private val headingUp: Boolean
) : Policy {
    override fun reset() {
        inner.reset()
    }

    override fun decide(p: Perception, now: Long): Action {
        val a = inner.decide(p, now)
        val z = zone() ?: return a
        if (!headingUp || !z.valid || !z.outside) return a
        if (a.state in FIGHT_STATES) return a
        val ang = z.angleDeg
        a.lookDx = (ang * 2.2f * aimScale).coerceIn(-90f, 90f)
        a.lookDy = 0f
        a.moveX = 0f
        a.moveY = if (abs(ang) < 30f) 1f else 0.3f
        a.fire = false
        a.taps.clear()
        if (abs(ang) < 30f && (now / 1500L) % 4L == 0L) a.taps.add("jump")
        a.state = "ZONE"
        return a
    }
}
