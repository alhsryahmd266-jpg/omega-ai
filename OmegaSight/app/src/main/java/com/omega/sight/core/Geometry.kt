package com.omega.sight.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A point or a movement in the pixels of the analysis frame (x to the right, y down). */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Float) = Vec2(x * k, y * k)
    fun len(): Float = sqrt(x * x + y * y)

    companion object {
        val ZERO = Vec2(0f, 0f)
    }
}

data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = r - l
    val h: Float get() = b - t
    val cx: Float get() = (l + r) * 0.5f
    val cy: Float get() = (t + b) * 0.5f
    val area: Float get() = max(0f, w) * max(0f, h)

    fun iou(o: Box): Float {
        val il = max(l, o.l)
        val it = max(t, o.t)
        val ir = min(r, o.r)
        val ib = min(b, o.b)
        val inter = max(0f, ir - il) * max(0f, ib - it)
        val u = area + o.area - inter
        return if (u <= 0f) 0f else inter / u
    }

    fun contains(x: Float, y: Float) = x >= l && x <= r && y >= t && y <= b
    fun shifted(dx: Float, dy: Float) = Box(l + dx, t + dy, r + dx, b + dy)

    fun inflated(k: Float): Box {
        val dw = w * (k - 1f) * 0.5f
        val dh = h * (k - 1f) * 0.5f
        return Box(l - dw, t - dh, r + dw, b + dh)
    }

    companion object {
        fun ofCenter(cx: Float, cy: Float, w: Float, h: Float) = Box(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }
}

/**
 * What a detection is: a whole body, a head, "something that moves" that no model named, or a glowing red light (the eye the bots
 * carry), which is found by colour and aimed at like a head.
 */
enum class Kind { BODY, HEAD, MOTION, GLOW }

/** One thing the vision model or the motion detector saw in one frame. */
data class Det(val box: Box, val score: Float, val kind: Kind)

data class Circle(val cx: Float, val cy: Float, val r: Float, val score: Float = 0f)
