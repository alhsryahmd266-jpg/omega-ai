package com.omega.keyboard.core

import kotlin.math.max
import kotlin.math.min

/**
 * The size of the keyboard is two numbers the user can change: how tall the rows are (a scale of the normal row, 1 = normal) and
 * how wide the keys area is (a share of the screen's width, 1 = all of it).
 *
 * At 100% the keys area already fills the whole width of the screen, so it can never be wider, and rows made taller on their own
 * turn the keys into slabs. So the two are tied together: a key is never taller than [MAX_KEY_ASPECT] times its width. That gives
 * every width a tallest height (the wider the keys area, the taller the rows may be), and when the user drags to make the keyboard
 * BIGGER, whatever is narrower than the screen widens along with the height, so the keys keep their shape: bigger keys, not taller
 * ones. Making it smaller through the side edges shrinks the height with the width, again with the same key shape.
 *
 * Pure (no Android classes), so it is tested without a phone.
 */
object KeySize {
    const val MIN_HEIGHT_PCT = 60
    const val MAX_HEIGHT_PCT = 140
    const val MIN_WIDTH_PCT = 60

    /** the tallest a key may be compared with its width (the normal one is about 1.5): taller than this it looks like a slab */
    const val MAX_KEY_ASPECT = 1.8f

    /** the height of a normal row, dp */
    const val BASE_ROW_DP = 52f

    /** the free margin at each side of the keys area, dp */
    const val SIDE_DP = 2f

    /**
     * Arabic pages are 11 key-widths wide, the narrowest keys of any page. Every page is held to the same height (the one that
     * suits these keys), so the keyboard does not jump in height when the language changes.
     */
    const val REF_UNITS = 11f

    private const val MIN_H = MIN_HEIGHT_PCT / 100f
    private const val MAX_H = MAX_HEIGHT_PCT / 100f
    private const val MIN_W = MIN_WIDTH_PCT / 100f

    /** a height and a width, both as scales (not percentages) */
    class Size(val h: Float, val w: Float)

    /** the width of one key's cell, px, when the keys area is all of a view that is [viewW] wide */
    fun cellAtFull(viewW: Float, density: Float): Float = (viewW - 2f * SIDE_DP * density) / REF_UNITS

    /**
     * The tallest the rows may be (a scale of the normal row) when the keys area is the whole width: never below normal, never
     * beyond [MAX_HEIGHT_PCT], and never so tall that a key is more than [MAX_KEY_ASPECT] times as tall as it is wide.
     */
    fun tallestAtFull(viewW: Float, density: Float): Float {
        val byLook = MAX_KEY_ASPECT * cellAtFull(viewW, density) / (BASE_ROW_DP * density)
        return min(MAX_H, max(byLook, 1f))
    }

    /** The same, when the keys area is [w] of the width: the keys are narrower by that much, so the rows may be shorter by as much. */
    fun tallest(viewW: Float, density: Float, w: Float): Float = tallestAtFull(viewW, density) * w.coerceIn(MIN_W, 1f)

    /** The height the rows really have when [h] is asked for. */
    fun rowScale(h: Float, viewW: Float, density: Float, w: Float): Float = h.coerceIn(MIN_H, max(tallest(viewW, density, w), MIN_H))

    /** What the height slider can reach on a screen that is [viewW] px wide (portrait), in percent. */
    fun maxHeightPct(viewW: Float, density: Float): Int = (tallestAtFull(viewW, density) * 100f + 0.0001f).toInt()

    /**
     * The top handle's finger asks for rows [want] times the normal height; the keyboard was [h0] tall and [w0] wide when the drag
     * began (both as they really were). Not bigger than it was: the height follows the finger and the width stays. Bigger: the keys
     * area widens by the same factor, up to the whole screen, so the keys keep the shape they had, and what the screen's width
     * cannot take any more goes into the height, up to the tallest rows the keys can have.
     */
    fun dragTop(want: Float, h0: Float, w0: Float, viewW: Float, density: Float): Size {
        val c = tallestAtFull(viewW, density)
        val top = max(c, MIN_H)
        val ws = w0.coerceIn(MIN_W, 1f)
        val hs = h0.coerceIn(MIN_H, top)
        val h = want.coerceIn(MIN_H, top)
        if (h <= hs) return Size(h, ws)
        return Size(h, min(1f, ws * h / hs))
    }

    /**
     * A side handle's finger put the keys area at [newW] of the width. The height goes with it so the keys keep the shape they had
     * when the drag began ([h0] tall, [w0] wide, both as they were then): the keyboard shrinks and grows as one piece.
     */
    fun dragSide(newW: Float, h0: Float, w0: Float, viewW: Float, density: Float): Size {
        val w = newW.coerceIn(MIN_W, 1f)
        val ratio = h0 / w0.coerceIn(MIN_W, 1f)
        val h = (w * ratio).coerceIn(MIN_H, max(tallest(viewW, density, w), MIN_H))
        return Size(h, w)
    }

    /** is the keyboard as big as this screen lets it be (the whole width, the tallest rows)? */
    fun atMost(h: Float, w: Float, viewW: Float, density: Float): Boolean =
        w >= 0.995f && h >= tallestAtFull(viewW, density) - 0.004f
}
