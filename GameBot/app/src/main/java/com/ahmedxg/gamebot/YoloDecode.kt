package com.ahmedxg.gamebot

import kotlin.math.max
import kotlin.math.min

/**
 * Decodes Ultralytics detection outputs. Pure Kotlin (no Android types) so it can be unit-tested on a JVM.
 *  - YOLO11 / YOLOv8 raw:        [1, 4+nc, N] (or [1, N, 4+nc]); xywh + per-class scores; needs NMS
 *  - YOLO26 end-to-end (NMS-free): [1, 300, 6]; rows = [x1, y1, x2, y2, conf, class_id]
 * Coordinates may be normalized (0..1) or pixels of the model input; both are detected automatically.
 * Result boxes are normalized to the ORIGINAL frame (letterbox removed).
 */
object YoloDecode {
    fun isEnd2End(shape: IntArray): Boolean = shape.size == 3 && shape[2] == 6 && shape[1] <= 1000

    fun decode(
        out: FloatArray,
        shape: IntArray,
        inW: Int,
        inH: Int,
        scale: Float,
        padX: Float,
        padY: Float,
        srcW: Int,
        srcH: Int,
        labels: List<String>,
        conf: Float,
        iou: Float
    ): List<Det> {
        if (shape.size != 3) return emptyList()
        // candidate rows: cls, score, cx, cy, w, h in MODEL INPUT pixels
        val cand = ArrayList<FloatArray>()
        val end2end = isEnd2End(shape)
        if (end2end) {
            val n = shape[1]
            var maxCoord = 0f
            for (i in 0 until n) {
                val o = i * 6
                if (out[o + 4] >= conf) maxCoord = max(maxCoord, max(out[o + 2], out[o + 3]))
            }
            val pixel = maxCoord > 2f
            for (i in 0 until n) {
                val o = i * 6
                val sc = out[o + 4]
                if (sc < conf) continue
                var x1 = out[o]; var y1 = out[o + 1]; var x2 = out[o + 2]; var y2 = out[o + 3]
                if (!pixel) { x1 *= inW; x2 *= inW; y1 *= inH; y2 *= inH }
                cand.add(floatArrayOf(Math.round(out[o + 5]).toFloat(), sc, (x1 + x2) / 2f, (y1 + y2) / 2f, x2 - x1, y2 - y1))
            }
        } else {
            val a = shape[1]
            val b = shape[2]
            val chFirst = a < b
            val ch = if (chFirst) a else b
            val n = if (chFirst) b else a
            val nc = ch - 4
            if (nc < 1) return emptyList()
            var maxCoord = 0f
            val tmp = ArrayList<FloatArray>()
            for (idx in 0 until n) {
                var best = -1
                var bs = 0f
                for (k in 0 until nc) {
                    val sc = if (chFirst) out[(4 + k) * n + idx] else out[idx * ch + 4 + k]
                    if (sc > bs) { bs = sc; best = k }
                }
                if (best < 0 || bs < conf) continue
                val cx: Float
                val cy: Float
                val w: Float
                val h: Float
                if (chFirst) {
                    cx = out[idx]; cy = out[n + idx]; w = out[2 * n + idx]; h = out[3 * n + idx]
                } else {
                    val o = idx * ch
                    cx = out[o]; cy = out[o + 1]; w = out[o + 2]; h = out[o + 3]
                }
                maxCoord = max(maxCoord, max(max(cx, cy), max(w, h)))
                tmp.add(floatArrayOf(best.toFloat(), bs, cx, cy, w, h))
            }
            val pixel = maxCoord > 2f
            for (t in tmp) {
                if (pixel) cand.add(t) else cand.add(floatArrayOf(t[0], t[1], t[2] * inW, t[3] * inH, t[4] * inW, t[5] * inH))
            }
        }
        cand.sortByDescending { it[1] }
        val top = if (cand.size > 80) cand.subList(0, 80) else cand

        val keep = ArrayList<Det>()
        for (t in top) {
            val d = Det(
                t[0].toInt(),
                labels.getOrNull(t[0].toInt()) ?: ("cls" + t[0].toInt()),
                t[1],
                (t[2] - padX) / scale / srcW,
                (t[3] - padY) / scale / srcH,
                t[4] / scale / srcW,
                t[5] / scale / srcH
            )
            var suppressed = false
            if (!end2end) {
                for (k in keep) {
                    if (k.cls == d.cls && iou(k, d) > iou) { suppressed = true; break }
                }
            }
            if (!suppressed) keep.add(d)
            if (keep.size >= 30) break
        }
        return keep
    }

    private fun iou(a: Det, b: Det): Float {
        val ax1 = a.cx - a.w / 2; val ax2 = a.cx + a.w / 2
        val ay1 = a.cy - a.h / 2; val ay2 = a.cy + a.h / 2
        val bx1 = b.cx - b.w / 2; val bx2 = b.cx + b.w / 2
        val by1 = b.cy - b.h / 2; val by2 = b.cy + b.h / 2
        val iw = max(0f, min(ax2, bx2) - max(ax1, bx1))
        val ih = max(0f, min(ay2, by2) - max(ay1, by1))
        val inter = iw * ih
        val u = a.w * a.h + b.w * b.h - inter
        return if (u <= 0f) 0f else inter / u
    }
}
