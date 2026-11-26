package com.omega.sight

import com.omega.sight.core.Gray
import java.util.Random
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A big made-up world picture (soft noise at four scales plus hard-edged patches). Frames are cut out of it at any shift and zoom, so the
 * tests can move a "camera" by exact fractions of a pixel and know the true answer.
 */
class World(val ww: Int, val wh: Int, seed: Long) {
    private val px = FloatArray(ww * wh)

    init {
        val rnd = Random(seed)
        val scales = intArrayOf(64, 24, 9, 4)
        val amps = floatArrayOf(38f, 26f, 16f, 9f)
        for (o in scales.indices) {
            val s = scales[o]
            val gw = ww / s + 3
            val gh = wh / s + 3
            val g = FloatArray(gw * gh) { rnd.nextFloat() }
            for (y in 0 until wh) {
                for (x in 0 until ww) {
                    val fx = x / s.toFloat()
                    val fy = y / s.toFloat()
                    val ix = fx.toInt()
                    val iy = fy.toInt()
                    val tx = smooth(fx - ix)
                    val ty = smooth(fy - iy)
                    val a = g[iy * gw + ix]
                    val b = g[iy * gw + ix + 1]
                    val c = g[(iy + 1) * gw + ix]
                    val d = g[(iy + 1) * gw + ix + 1]
                    px[y * ww + x] += amps[o] * ((a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty)
                }
            }
        }
        // hard-edged patches
        repeat(ww * wh / 700) {
            val x0 = rnd.nextInt(ww - 12)
            val y0 = rnd.nextInt(wh - 12)
            val pw = 3 + rnd.nextInt(14)
            val ph = 3 + rnd.nextInt(14)
            val dv = (rnd.nextFloat() - 0.5f) * 70f
            for (y in y0 until min(wh, y0 + ph)) for (x in x0 until min(ww, x0 + pw)) px[y * ww + x] += dv
        }
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in px) {
            lo = min(lo, v)
            hi = max(hi, v)
        }
        for (i in px.indices) px[i] = 45f + 160f * (px[i] - lo) / (hi - lo)
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    private fun at(x: Int, y: Int): Float = px[y.coerceIn(0, wh - 1) * ww + x.coerceIn(0, ww - 1)]

    fun sample(x: Float, y: Float): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        return (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) + (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy
    }

    /**
     * The frame the camera sees when it looks at world point (ox + w/2, oy + h/2) with a magnification of 1 + z, plus noise of [noise]
     * luma steps (gaussian).
     */
    fun frame(w: Int, h: Int, ox: Float, oy: Float, z: Float, noise: Float, rnd: Random, out: Gray = Gray(w, h)): Gray {
        val cx = w / 2f
        val cy = h / 2f
        val k = 1f / (1f + z)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = sample(ox + cx + (x - cx) * k, oy + cy + (y - cy) * k)
                if (noise > 0f) v += noise * rnd.nextGaussian().toFloat()
                out.d[y * w + x] = v.coerceIn(0f, 255f).toInt().toByte()
            }
        }
        return out
    }
}

/** paints a rectangle given in float pixel coordinates with exact coverage (so it can sit between pixels) */
fun paintRect(g: Gray, x0: Float, y0: Float, x1: Float, y1: Float, value: Float) {
    val ix0 = max(0, floor(x0).toInt())
    val iy0 = max(0, floor(y0).toInt())
    val ix1 = min(g.w - 1, floor(x1).toInt())
    val iy1 = min(g.h - 1, floor(y1).toInt())
    for (y in iy0..iy1) {
        val cy = min(y + 1f, y1) - max(y.toFloat(), y0)
        if (cy <= 0f) continue
        for (x in ix0..ix1) {
            val cx = min(x + 1f, x1) - max(x.toFloat(), x0)
            if (cx <= 0f) continue
            val cov = cx * cy
            val old = g.d[y * g.w + x].toInt() and 255
            g.d[y * g.w + x] = (old * (1f - cov) + value * cov).coerceIn(0f, 255f).toInt().toByte()
        }
    }
}

/** paints a soft ring (outline) with the given centre, radius and thickness; [alpha] = how strongly it covers what is below */
fun paintRing(g: Gray, cx: Float, cy: Float, r: Float, thick: Float, value: Float, alpha: Float) {
    val ext = (r + thick + 2f).toInt()
    for (y in max(0, (cy - ext).toInt())..min(g.h - 1, (cy + ext).toInt())) {
        for (x in max(0, (cx - ext).toInt())..min(g.w - 1, (cx + ext).toInt())) {
            val d = Math.hypot((x + 0.5f - cx).toDouble(), (y + 0.5f - cy).toDouble()).toFloat()
            val cov = (1f - (Math.abs(d - r) - thick / 2f).coerceIn(0f, 1f)) * alpha
            if (cov <= 0f) continue
            val old = g.d[y * g.w + x].toInt() and 255
            g.d[y * g.w + x] = (old * (1f - cov) + value * cov).coerceIn(0f, 255f).toInt().toByte()
        }
    }
}

/** paints a filled disc */
fun paintDisc(g: Gray, cx: Float, cy: Float, r: Float, value: Float, alpha: Float) {
    val ext = (r + 2f).toInt()
    for (y in max(0, (cy - ext).toInt())..min(g.h - 1, (cy + ext).toInt())) {
        for (x in max(0, (cx - ext).toInt())..min(g.w - 1, (cx + ext).toInt())) {
            val d = Math.hypot((x + 0.5f - cx).toDouble(), (y + 0.5f - cy).toDouble()).toFloat()
            val cov = (r + 0.5f - d).coerceIn(0f, 1f) * alpha
            if (cov <= 0f) continue
            val old = g.d[y * g.w + x].toInt() and 255
            g.d[y * g.w + x] = (old * (1f - cov) + value * cov).coerceIn(0f, 255f).toInt().toByte()
        }
    }
}
