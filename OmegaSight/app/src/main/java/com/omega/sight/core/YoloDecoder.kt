package com.omega.sight.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * What the vision model file looks like, read from the file itself: the input size and layout and the shape of the output tensor.
 * [names] are the class names the owner put next to the model (a text file with one name per line); no names = class 0 is the
 * body and class 1, if there is one, is the head.
 */
class ModelSpec(
    val inW: Int,
    val inH: Int,
    /** false = the input is [1, H, W, 3] (NHWC), true = [1, 3, H, W] */
    val nchw: Boolean,
    val outShape: IntArray,
    val names: List<String> = emptyList(),
)

/** The way the picture is fitted into the square the model wants: shrunk to fit and centred, grey bars on the sides. */
class Letterbox(val srcW: Int, val srcH: Int, val inW: Int, val inH: Int) {
    val scale: Float = min(inW / srcW.toFloat(), inH / srcH.toFloat())
    val padX: Float = (inW - srcW * scale) / 2f
    val padY: Float = (inH - srcH * scale) / 2f
    fun toSrcX(x: Float) = ((x - padX) / scale).coerceIn(0f, srcW.toFloat())
    fun toSrcY(y: Float) = ((y - padY) / scale).coerceIn(0f, srcH.toFloat())
}

/**
 * Turns the output of the vision model into boxes. Three layouts are understood and told apart by the output shape alone:
 *  - end-to-end, [1, N, 6] or [1, 6, N] with N up to 1000: x1, y1, x2, y2, score, class (no NMS needed)
 *  - raw, [1, 4+C, A]: centre-x, centre-y, width, height and one score per class, for A anchors (NMS done here)
 *  - raw, [1, A, 4+C]: the same, turned over
 * Boxes may be in input pixels or 0..1; scores may be already 0..1 or raw logits.
 */
object YoloDecoder {
    enum class Layout { E2E_NC, E2E_CN, RAW_CN, RAW_NC }

    fun layoutOf(shape: IntArray): Layout {
        val s = when (shape.size) {
            3 -> intArrayOf(shape[1], shape[2])
            2 -> shape
            else -> throw IllegalArgumentException("output shape ${shape.toList()} is not [1,N,C] or [1,C,N]")
        }
        val a = s[0]
        val b = s[1]
        return when {
            b == 6 && a <= 1000 -> Layout.E2E_NC
            a == 6 && b <= 1000 -> Layout.E2E_CN
            a < b -> Layout.RAW_CN
            else -> Layout.RAW_NC
        }
    }

    /** Class names that must never be aimed at. */
    private val ignoreWords = listOf("friend", "ally", "team", "civil", "hostage", "ignore", "نفس", "صديق", "حليف", "مدني")
    private val headWords = listOf("head", "face", "رأس", "راس", "وجه")

    /** null = this class is not a target */
    fun kindOf(cls: Int, names: List<String>, classCount: Int): Kind? {
        if (names.isEmpty()) return if (cls == 1 && classCount >= 2) Kind.HEAD else if (cls == 0) Kind.BODY else null
        if (cls !in names.indices) return null
        val n = names[cls].trim().lowercase()
        if (ignoreWords.any { n.contains(it) }) return null
        if (headWords.any { n.contains(it) }) return Kind.HEAD
        return Kind.BODY
    }

    /**
     * @param out the output tensor, flattened in row-major order
     * @return boxes in pixels of the original frame (through [lb]), best first
     */
    fun decode(out: FloatArray, shape: IntArray, lb: Letterbox, names: List<String>, scoreThr: Float, nmsIou: Float = 0.5f, maxDet: Int = 40): List<Det> {
        var layout = layoutOf(shape)
        // [1, 6, N] and [1, N, 6] can also be a raw head with two classes: look at the numbers to tell
        if (layout == Layout.E2E_NC && !looksEndToEnd(out, layout, shape[shape.size - 2])) layout = Layout.RAW_NC
        if (layout == Layout.E2E_CN && !looksEndToEnd(out, layout, shape[shape.size - 1])) layout = Layout.RAW_CN
        val raw = ArrayList<Cand>()
        var classCount = 1
        when (layout) {
            Layout.E2E_NC, Layout.E2E_CN -> {
                val n = if (layout == Layout.E2E_NC) shape[shape.size - 2] else shape[shape.size - 1]
                classCount = 2
                for (i in 0 until n) {
                    val v = FloatArray(6) { c -> if (layout == Layout.E2E_NC) out[i * 6 + c] else out[c * n + i] }
                    val score = v[4]
                    if (score < scoreThr) continue
                    raw.add(Cand(v[0], v[1], v[2], v[3], score, v[5].toInt(), true))
                }
            }
            Layout.RAW_CN, Layout.RAW_NC -> {
                val chans = if (layout == Layout.RAW_CN) shape[shape.size - 2] else shape[shape.size - 1]
                val n = if (layout == Layout.RAW_CN) shape[shape.size - 1] else shape[shape.size - 2]
                classCount = chans - 4
                if (classCount < 1) return emptyList()
                // raw logits instead of probabilities? (any class value outside 0..1 on the way)
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                for (i in 0 until n) for (c in 4 until chans) {
                    val v = if (layout == Layout.RAW_CN) out[c * n + i] else out[i * chans + c]
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
                val logits = lo < -0.001f || hi > 1.001f
                for (i in 0 until n) {
                    var best = -1
                    var bs = -Float.MAX_VALUE
                    for (c in 0 until classCount) {
                        var v = if (layout == Layout.RAW_CN) out[(4 + c) * n + i] else out[i * chans + 4 + c]
                        if (logits) v = 1f / (1f + exp(-v))
                        if (v > bs) {
                            bs = v
                            best = c
                        }
                    }
                    if (bs < scoreThr) continue
                    val cxv = if (layout == Layout.RAW_CN) out[i] else out[i * chans]
                    val cyv = if (layout == Layout.RAW_CN) out[n + i] else out[i * chans + 1]
                    val wv = if (layout == Layout.RAW_CN) out[2 * n + i] else out[i * chans + 2]
                    val hv = if (layout == Layout.RAW_CN) out[3 * n + i] else out[i * chans + 3]
                    raw.add(Cand(cxv - wv / 2, cyv - hv / 2, cxv + wv / 2, cyv + hv / 2, bs, best, false))
                }
            }
        }
        if (raw.isEmpty()) return emptyList()
        raw.sortByDescending { it.score }
        val top = if (raw.size > 300) raw.subList(0, 300) else raw

        // 0..1 boxes (the whole input is 1.0) or pixels?
        var maxCoord = 0f
        for (c in top) maxCoord = max(maxCoord, max(max(c.a, c.b), max(c.c, c.d)))
        val unit = maxCoord <= 1.5f
        val sx = if (unit) lb.inW.toFloat() else 1f
        val sy = if (unit) lb.inH.toFloat() else 1f

        val kept = ArrayList<Cand>()
        for (c in top) {
            val kind = kindOf(c.cls, names, max(classCount, c.cls + 1)) ?: continue
            c.kind = kind
            c.box = Box(
                lb.toSrcX(c.a * sx), lb.toSrcY(c.b * sy),
                lb.toSrcX(c.c * sx), lb.toSrcY(c.d * sy),
            )
            if (c.box.w < 1f || c.box.h < 1f) continue
            var drop = false
            for (k in kept) if (k.cls == c.cls && k.box.iou(c.box) > nmsIou) {
                drop = true
                break
            }
            if (drop) continue
            kept.add(c)
            if (kept.size >= maxDet) break
        }
        return kept.map { Det(it.box, it.score, it.kind) }
    }

    /**
     * End-to-end rows are (x1, y1, x2, y2, score, class): the class is a whole number and the corners are in order. A raw row with two
     * classes is (cx, cy, w, h, p0, p1): the last value is a probability and "x2 < x1" shows up at once.
     */
    private fun looksEndToEnd(out: FloatArray, layout: Layout, n: Int): Boolean {
        for (i in 0 until n) {
            val nc = layout == Layout.E2E_NC
            val score = if (nc) out[i * 6 + 4] else out[4 * n + i]
            if (score <= 0.001f) continue
            val x1 = if (nc) out[i * 6] else out[i]
            val y1 = if (nc) out[i * 6 + 1] else out[n + i]
            val x2 = if (nc) out[i * 6 + 2] else out[2 * n + i]
            val y2 = if (nc) out[i * 6 + 3] else out[3 * n + i]
            val cls = if (nc) out[i * 6 + 5] else out[5 * n + i]
            if (cls < -0.001f || cls > 200f || abs(cls - Math.round(cls)) > 1e-3f) return false
            if (x2 < x1 || y2 < y1) return false
        }
        return true
    }

    private class Cand(val a: Float, val b: Float, val c: Float, val d: Float, val score: Float, val cls: Int, val corners: Boolean) {
        var box: Box = Box(0f, 0f, 0f, 0f)
        var kind: Kind = Kind.BODY
    }
}
