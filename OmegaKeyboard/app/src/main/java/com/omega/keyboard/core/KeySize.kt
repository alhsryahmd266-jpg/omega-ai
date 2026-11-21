package com.omega.keyboard.core

import kotlin.math.max
import kotlin.math.min

/**
 * The size of the keyboard. There are two models, and the view says which one is in use.
 *
 * SQUARE KEYS (portrait, the normal case). There is ONE size: how much of the screen's width the keys area takes (1 = all of it).
 * The keys are squares whose side is that width divided by the 11 keys of the widest (Arabic) row, and the rows are exactly that tall,
 * so the keys have the same shape at every size. The gaps, letters and icons are drawn at that size too: making the keyboard bigger or
 * smaller is zooming one picture, nothing is stretched. At 100% the keys area already fills the whole width of the screen, so that is
 * the biggest the keyboard can be; it can only be made smaller (one-handed typing) and made bigger again up to there.
 *
 * FREE HEIGHT (landscape). Squares would make the keyboard fill the screen there, so the rows keep a height of their own: a scale of
 * the normal row ([heightScale], 1 = normal) and the width of the keys area, tied together by [MAX_KEY_ASPECT] so a key never becomes
 * a slab.
 *
 * Pure (no Android classes), so it is tested without a phone.
 */
object KeySize {
    const val MIN_HEIGHT_PCT = 60
    const val MAX_HEIGHT_PCT = 140
    const val MIN_WIDTH_PCT = 60

    /**
     * Free height only: the tallest a key may be compared with its width. Taller than this it looks like a slab.
     * (This is the shape of the keys of the Gboard the user brought: 1.75.)
     */
    const val MAX_KEY_ASPECT = 1.75f

    /** the height of a normal row of the free-height model, dp */
    const val BASE_ROW_DP = 52f

    /** the free margin at each side of the keys area, dp */
    const val SIDE_DP = 2f

    /**
     * Arabic pages are 11 key-widths wide, the narrowest keys of any page. Every page is held to the same height (the one that
     * suits these keys), so the keyboard does not jump in height when the language changes.
     */
    const val REF_UNITS = 11f

    /** square keys: on a wide screen (a tablet) a square would be too tall, so the rows are never taller than this, dp */
    const val MAX_SQUARE_ROW_DP = 56f

    /** square keys: the free space round each key (half of the gap between two keys) at the biggest size, dp; it zooms with the keys */
    const val SQUARE_GAP_DP = 3f

    private const val MIN_H = MIN_HEIGHT_PCT / 100f
    private const val MAX_H = MAX_HEIGHT_PCT / 100f
    private const val MIN_W = MIN_WIDTH_PCT / 100f

    /** a height and a width, both as scales (not percentages) */
    class Size(val h: Float, val w: Float)

    /** the width of one key's cell, px, when the keys area is all of a view that is [viewW] wide */
    fun cellAtFull(viewW: Float, density: Float): Float = (viewW - 2f * SIDE_DP * density) / REF_UNITS

    // ------------------------------------------------------------------ square keys

    /** the keys area's size as it is really used: a share of the width between the smallest and all of it */
    fun zoom(w: Float): Float = w.coerceIn(MIN_W, 1f)

    /** the height of a row, px, when the keys area is the whole width: the side of the squares (never beyond [MAX_SQUARE_ROW_DP]) */
    fun squareRowAtFull(viewW: Float, density: Float): Float = min(cellAtFull(viewW, density), MAX_SQUARE_ROW_DP * density)

    /** the height of a row, px, when the keys area is [w] of the width: it is the width's share of the full one, so the keys keep their shape */
    fun squareRowPx(viewW: Float, density: Float, w: Float): Float = squareRowAtFull(viewW, density) * zoom(w)

    /**
     * The top handle's finger asks for rows [wantRowPx] tall: the keys area gets the size at which the rows are that tall (the keys
     * area widens or narrows with the rows, from where it is), held between the smallest size and all of the screen's width.
     */
    fun dragTopSquare(wantRowPx: Float, viewW: Float, density: Float): Float =
        (wantRowPx / squareRowAtFull(viewW, density)).coerceIn(MIN_W, 1f)

    /** did the finger ask for clearly more than the screen's width can give? (the size the finger asked for, as a share of the width) */
    fun beyondWidth(askedSize: Float): Boolean = askedSize > 1.04f

    /** is the keyboard as big as the screen's width lets it be? */
    fun fullWidth(w: Float): Boolean = w >= 0.995f

    // ------------------------------------------------------------------ free height (landscape)

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

    /** What the height can reach on a screen that is [viewW] px wide (portrait), in percent. */
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
