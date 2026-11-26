package com.omega.sight.core

import kotlin.math.floor
import kotlin.math.min

/** An 8-bit luma image. */
class Gray(val w: Int, val h: Int, val d: ByteArray = ByteArray(w * h)) {
    operator fun get(x: Int, y: Int): Int = d[y * w + x].toInt() and 255

    /** the pixel at (x, y), the nearest edge pixel when it lies outside */
    fun at(x: Int, y: Int): Int {
        val cx = if (x < 0) 0 else if (x >= w) w - 1 else x
        val cy = if (y < 0) 0 else if (y >= h) h - 1 else y
        return d[cy * w + cx].toInt() and 255
    }

    /** bilinear sample */
    fun sample(x: Float, y: Float): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val a = at(x0, y0)
        val b = at(x0 + 1, y0)
        val c = at(x0, y0 + 1)
        val e = at(x0 + 1, y0 + 1)
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + e * fx) * fy
    }

    fun copyFrom(o: Gray) {
        System.arraycopy(o.d, 0, d, 0, min(d.size, o.d.size))
    }
}

object Resample {
    /**
     * The luma of a region (rx, ry, rw, rh) of an RGBA_8888 buffer (bytes R, G, B, A, [rowStride] bytes per row), area-averaged
     * into [out] (whatever size [out] has). Up to 3x3 source pixels are read per output pixel.
     */
    fun lumaFromRgba(src: ByteArray, srcW: Int, srcH: Int, rowStride: Int, rx: Int, ry: Int, rw: Int, rh: Int, out: Gray) {
        val ow = out.w
        val oh = out.h
        val xs = IntArray(ow * 3)
        val nx = IntArray(ow)
        for (x in 0 until ow) {
            val a = rx + x * rw / ow
            val b = maxOf(a + 1, rx + (x + 1) * rw / ow)
            val n = min(3, b - a)
            nx[x] = n
            for (i in 0 until n) xs[x * 3 + i] = (a + (b - a) * (2 * i + 1) / (2 * n)).coerceIn(0, srcW - 1)
        }
        for (y in 0 until oh) {
            val a = ry + y * rh / oh
            val b = maxOf(a + 1, ry + (y + 1) * rh / oh)
            val n = min(3, b - a)
            val o = y * ow
            for (x in 0 until ow) {
                var sum = 0
                var cnt = 0
                for (j in 0 until n) {
                    val sy = (a + (b - a) * (2 * j + 1) / (2 * n)).coerceIn(0, srcH - 1)
                    val row = sy * rowStride
                    for (i in 0 until nx[x]) {
                        val p = row + xs[x * 3 + i] * 4
                        val r = src[p].toInt() and 255
                        val g = src[p + 1].toInt() and 255
                        val bl = src[p + 2].toInt() and 255
                        sum += (77 * r + 150 * g + 29 * bl) shr 8
                        cnt++
                    }
                }
                out.d[o + x] = (sum / cnt).toByte()
            }
        }
    }

    /** a frame at half the size (2x2 box filter) */
    fun half(src: Gray, dst: Gray) {
        for (y in 0 until dst.h) {
            for (x in 0 until dst.w) {
                val sx = x * 2
                val sy = y * 2
                val s = src.at(sx, sy) + src.at(sx + 1, sy) + src.at(sx, sy + 1) + src.at(sx + 1, sy + 1)
                dst.d[y * dst.w + x] = (s shr 2).toByte()
            }
        }
    }
}
