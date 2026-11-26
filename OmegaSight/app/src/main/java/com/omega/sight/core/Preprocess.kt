package com.omega.sight.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Gets a picture ready for the vision model: the wanted region is cut out of the RGBA screenshot, scaled to fit the model's input
 * without stretching, centred on grey bars (the letterbox [YoloDecoder] undoes), and written the way the model wants it.
 */
object Preprocess {
    private const val PAD = 114

    /**
     * Writes the RGB bytes (3 per pixel, row by row) of the letterboxed region into [rgb] (size inW*inH*3).
     * @param src RGBA_8888 bytes, [rowStride] bytes per row
     * @return the letterbox, to map the model's boxes back into the region
     */
    fun letterboxRgb(
        src: ByteArray, srcW: Int, srcH: Int, rowStride: Int,
        rx: Int, ry: Int, rw: Int, rh: Int,
        inW: Int, inH: Int, rgb: ByteArray,
    ): Letterbox {
        val lb = Letterbox(rw, rh, inW, inH)
        java.util.Arrays.fill(rgb, PAD.toByte())
        val ox0 = floor(lb.padX).toInt().coerceIn(0, inW - 1)
        val ox1 = ceil(lb.padX + rw * lb.scale).toInt().coerceIn(ox0 + 1, inW)
        val oy0 = floor(lb.padY).toInt().coerceIn(0, inH - 1)
        val oy1 = ceil(lb.padY + rh * lb.scale).toInt().coerceIn(oy0 + 1, inH)

        val nx = ox1 - ox0
        val cx0 = IntArray(nx)
        val cxq = IntArray(nx)
        for (i in 0 until nx) {
            var u = (ox0 + i + 0.5f - lb.padX) / lb.scale - 0.5f
            u = u.coerceIn(0f, (rw - 1).toFloat())
            val sx = (rx + u).coerceIn(0f, (srcW - 1).toFloat())
            val i0 = min(floor(sx).toInt(), srcW - 2)
            cx0[i] = i0 * 4
            cxq[i] = ((sx - i0) * 256f).toInt().coerceIn(0, 256)
        }
        for (oy in oy0 until oy1) {
            var v = (oy + 0.5f - lb.padY) / lb.scale - 0.5f
            v = v.coerceIn(0f, (rh - 1).toFloat())
            val sy = (ry + v).coerceIn(0f, (srcH - 1).toFloat())
            val j0 = min(floor(sy).toInt(), srcH - 2)
            val fy = ((sy - j0) * 256f).toInt().coerceIn(0, 256)
            val b0 = j0 * rowStride
            val b1 = b0 + rowStride
            var o = (oy * inW + ox0) * 3
            for (i in 0 until nx) {
                val fx = cxq[i]
                val p00 = b0 + cx0[i]
                val p10 = b1 + cx0[i]
                val w00 = (256 - fx) * (256 - fy)
                val w01 = fx * (256 - fy)
                val w10 = (256 - fx) * fy
                val w11 = fx * fy
                for (c in 0..2) {
                    val v00 = src[p00 + c].toInt() and 255
                    val v01 = src[p00 + 4 + c].toInt() and 255
                    val v10 = src[p10 + c].toInt() and 255
                    val v11 = src[p10 + 4 + c].toInt() and 255
                    rgb[o++] = ((v00 * w00 + v01 * w01 + v10 * w10 + v11 * w11 + 32768) shr 16).toByte()
                }
            }
        }
        return lb
    }

    /** float32 input, 0..1: [1, H, W, 3] or, with [nchw], [1, 3, H, W] */
    fun toFloat(rgb: ByteArray, inW: Int, inH: Int, nchw: Boolean, out: FloatArray) {
        val hw = inW * inH
        if (!nchw) {
            for (i in 0 until hw * 3) out[i] = (rgb[i].toInt() and 255) * (1f / 255f)
        } else {
            for (p in 0 until hw) {
                out[p] = (rgb[p * 3].toInt() and 255) * (1f / 255f)
                out[hw + p] = (rgb[p * 3 + 1].toInt() and 255) * (1f / 255f)
                out[2 * hw + p] = (rgb[p * 3 + 2].toInt() and 255) * (1f / 255f)
            }
        }
    }

    /**
     * 8-bit input. The model says how its numbers map to 0..1 (value = (q - zeroPoint) * scale); [signed] = int8, else uint8.
     * The usual image models use scale 1/255 with zero point 0 (uint8) or -128 (int8).
     */
    fun toQuant(rgb: ByteArray, inW: Int, inH: Int, nchw: Boolean, scale: Float, zeroPoint: Int, signed: Boolean, out: ByteArray) {
        val lo = if (signed) -128 else 0
        val hi = if (signed) 127 else 255
        val lut = ByteArray(256)
        val inv = if (scale > 0f) 1f / scale else 255f
        for (v in 0..255) {
            val q = ((v / 255f) * inv).roundToInt() + zeroPoint
            lut[v] = q.coerceIn(lo, hi).toByte()
        }
        val hw = inW * inH
        if (!nchw) {
            for (i in 0 until hw * 3) out[i] = lut[rgb[i].toInt() and 255]
        } else {
            for (p in 0 until hw) {
                out[p] = lut[rgb[p * 3].toInt() and 255]
                out[hw + p] = lut[rgb[p * 3 + 1].toInt() and 255]
                out[2 * hw + p] = lut[rgb[p * 3 + 2].toInt() and 255]
            }
        }
    }
}
