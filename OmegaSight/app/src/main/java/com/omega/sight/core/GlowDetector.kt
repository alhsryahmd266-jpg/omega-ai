package com.omega.sight.core

import kotlin.math.max
import kotlin.math.min

/**
 * Finds the glowing red light the enemy bots carry (an eye with a white-hot centre and a red halo), by its colour, at the full
 * resolution of the capture, so it also shows from far away where the body is only a few pixels. No model and no movement needed.
 *
 * What counts: a small, compact patch of strongly red pixels, usually with a near-white centre, usually on a darker body. What does
 * not: long red bars (health bars), flat red areas without a bright centre, orange fire (it has too much green), anything inside the
 * mask of fixed screen furniture. The score says how glow-like it is; the Tracker still wants several sightings in a row.
 * Other red lights in a game can look the same; this is a hint for the aim, not a proof.
 */
class GlowDetector(val w: Int, val h: Int) {
    private val mask = ByteArray(w * h)
    private val tmp = ByteArray(w * h)
    private val stack = IntArray(w * h)
    private val coreIdx = IntArray(MAX_CORE)

    /** red pixels found in the last call (for the diagnostics screen) */
    var lastRedPixels = 0
        private set

    /**
     * @param rgba RGBA_8888 bytes, [rowStride] bytes per row
     * @param hud optional full-size mask of fixed screen furniture
     * @param sensitivity 0..1 (1 = weaker reds count too)
     * @param minArea the smallest patch, in pixels
     * @return detections in pixels of this frame size, best first
     */
    fun detect(rgba: ByteArray, rowStride: Int, hud: ByteArray?, sensitivity: Float, minArea: Int = 2): List<Det> {
        val sens = sensitivity.coerceIn(0f, 1f)
        val rMin = (150f - 40f * sens).toInt()
        val dMin = (80f - 30f * sens).toInt()
        val peakMin = (190f - 60f * sens).toInt()
        val thr = 0.62f - 0.22f * sens

        // 1. strongly red pixels; near-white pixels are remembered to be joined later if they sit in a red halo
        java.util.Arrays.fill(mask, 0)
        var red = 0
        var nCore = 0
        for (y in 0 until h) {
            var p = y * rowStride
            val row = y * w
            for (x in 0 until w) {
                val r = rgba[p].toInt() and 255
                if (r >= 230) {
                    val g = rgba[p + 1].toInt() and 255
                    val b = rgba[p + 2].toInt() and 255
                    if (g >= 150 && b >= 150 && ((77 * r + 150 * g + 29 * b) shr 8) >= 215) {
                        if (nCore < MAX_CORE && (hud == null || hud[row + x].toInt() == 0)) coreIdx[nCore++] = row + x
                    } else if (r - max(g, b) >= dMin && g * 100 <= r * 62 && b * 100 <= r * 62 && (hud == null || hud[row + x].toInt() == 0)) {
                        mask[row + x] = 1
                        red++
                    }
                } else if (r >= rMin) {
                    val g = rgba[p + 1].toInt() and 255
                    val b = rgba[p + 2].toInt() and 255
                    if (r - max(g, b) >= dMin && g * 100 <= r * 62 && b * 100 <= r * 62 && (hud == null || hud[row + x].toInt() == 0)) {
                        mask[row + x] = 1
                        red++
                    }
                }
                p += 4
            }
        }
        lastRedPixels = red
        if (red == 0) return emptyList()
        // a patch that is 40% of the screen red is a red wall or a damage flash, not a light
        if (red > 0.2f * w * h) return emptyList()

        // 2. the white-hot centre joins when a red pixel is within two pixels
        for (k in 0 until nCore) {
            val i = coreIdx[k]
            val y = i / w
            val x = i - y * w
            var near = false
            loop@ for (j in -2..2) {
                val yy = y + j
                if (yy < 0 || yy >= h) continue
                for (q in -2..2) {
                    val xx = x + q
                    if (xx < 0 || xx >= w) continue
                    if (mask[yy * w + xx].toInt() == 1) {
                        near = true
                        break@loop
                    }
                }
            }
            if (near) mask[i] = 2
        }

        // 3. grow by one pixel so the halo and the centre are one piece, then label the 8-connected pieces
        java.util.Arrays.fill(tmp, 0)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                if (mask[row + x].toInt() == 0) continue
                for (j in -1..1) for (k in -1..1) tmp[row + x + j * w + k] = 1
            }
        }
        val out = ArrayList<Det>()
        val maxArea = (0.02f * w * h).toInt()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val seed = y * w + x
                if (tmp[seed].toInt() != 1) continue
                var sp = 0
                stack[sp++] = seed
                tmp[seed] = 2
                var minX = Int.MAX_VALUE
                var maxX = -1
                var minY = Int.MAX_VALUE
                var maxY = -1
                var area = 0
                var peak = 0
                var sumDom = 0L
                var sumL = 0L
                var sw = 0f
                var sxw = 0f
                var syw = 0f
                while (sp > 0) {
                    val i = stack[--sp]
                    val py = i / w
                    val px = i - py * w
                    if (mask[i].toInt() != 0) {
                        val p = py * rowStride + px * 4
                        val r = rgba[p].toInt() and 255
                        val g = rgba[p + 1].toInt() and 255
                        val b = rgba[p + 2].toInt() and 255
                        val l = (77 * r + 150 * g + 29 * b) shr 8
                        area++
                        if (l > peak) peak = l
                        sumDom += (r - max(g, b))
                        sumL += l
                        // the bright centre pulls the middle towards itself
                        val wgt = 1f + l / 64f
                        sw += wgt
                        sxw += wgt * px
                        syw += wgt * py
                        if (px < minX) minX = px
                        if (px > maxX) maxX = px
                        if (py < minY) minY = py
                        if (py > maxY) maxY = py
                    }
                    for (j in -1..1) for (k in -1..1) {
                        val nx = px + k
                        val ny = py + j
                        if (nx < 1 || ny < 1 || nx >= w - 1 || ny >= h - 1) continue
                        val ni = ny * w + nx
                        if (tmp[ni].toInt() == 1) {
                            tmp[ni] = 2
                            stack[sp++] = ni
                        }
                    }
                }
                if (area < minArea || area > maxArea || sw <= 0f) continue
                val bw = (maxX - minX + 1).toFloat()
                val bh = (maxY - minY + 1).toFloat()
                val aspect = bw / bh
                if (aspect < 0.3f || aspect > 3.3f) continue
                val fill = area / (bw * bh)
                if (fill < 0.2f) continue
                if (peak < peakMin && !(area <= 60 && sumDom / area >= 110)) continue

                val core = ((peak - 120f) / 120f).coerceIn(0f, 1f)
                val redness = ((sumDom.toFloat() / area - 50f) / 80f).coerceIn(0f, 1f)
                val compact = ((fill - 0.2f) / 0.4f).coerceIn(0f, 1f)
                val contrast = ringContrast(rgba, rowStride, minX, minY, maxX, maxY, sumL.toFloat() / area)
                val score = 0.35f * core + 0.25f * redness + 0.20f * compact + 0.20f * contrast
                if (score < thr) continue
                out.add(Det(Box.ofCenter(sxw / sw + 0.5f, syw / sw + 0.5f, max(1f, bw), max(1f, bh)), score.coerceIn(0f, 1f), Kind.GLOW))
            }
        }
        out.sortByDescending { it.score }
        return if (out.size > 8) out.subList(0, 8).toList() else out
    }

    /** how much darker the ring around the patch is than the patch (0 = not at all or brighter, 1 = black around it) */
    private fun ringContrast(rgba: ByteArray, rowStride: Int, minX: Int, minY: Int, maxX: Int, maxY: Int, blobL: Float): Float {
        val t = max(2, (max(maxX - minX, maxY - minY) + 1) / 2)
        val x0 = max(0, minX - t)
        val y0 = max(0, minY - t)
        val x1 = min(w - 1, maxX + t)
        val y1 = min(h - 1, maxY + t)
        var sum = 0L
        var n = 0
        for (y in y0..y1) {
            val inY = y in minY..maxY
            for (x in x0..x1) {
                if (inY && x in minX..maxX) continue
                val p = y * rowStride + x * 4
                sum += ((77 * (rgba[p].toInt() and 255) + 150 * (rgba[p + 1].toInt() and 255) + 29 * (rgba[p + 2].toInt() and 255)) shr 8)
                n++
            }
        }
        if (n == 0 || blobL <= 1f) return 0f
        val ring = sum.toFloat() / n
        return (1f - ring / blobL).coerceIn(0f, 1f)
    }

    companion object {
        private const val MAX_CORE = 20000
    }
}
