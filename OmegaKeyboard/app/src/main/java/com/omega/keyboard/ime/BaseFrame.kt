package com.omega.keyboard.ime

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.max

/**
 * A frame whose size is the size of its FIRST child. Every other child (key previews, the emoji / clipboard panels)
 * is laid exactly over it and can never make the keyboard taller.
 *
 * Without this, a "match parent" child of a wrap-content keyboard window measures as tall as the whole screen:
 * the window then covers the app and the keys sit at the top of it.
 */
class BaseFrame(ctx: Context) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val base = if (childCount > 0) getChildAt(0) else null
        var w = 0
        var h = 0
        if (base != null && base.visibility != View.GONE) {
            measureChildWithMargins(base, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = base.layoutParams as ViewGroup.MarginLayoutParams
            w = base.measuredWidth + lp.leftMargin + lp.rightMargin
            h = base.measuredHeight + lp.topMargin + lp.bottomMargin
        }
        w += paddingLeft + paddingRight
        h += paddingTop + paddingBottom
        val rw = resolveSize(max(w, suggestedMinimumWidth), widthMeasureSpec)
        val rh = resolveSize(max(h, suggestedMinimumHeight), heightMeasureSpec)
        setMeasuredDimension(rw, rh)
        val cw = MeasureSpec.makeMeasureSpec(max(0, rw - paddingLeft - paddingRight), MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(max(0, rh - paddingTop - paddingBottom), MeasureSpec.EXACTLY)
        for (i in 1 until childCount) {
            val c = getChildAt(i)
            if (c.visibility != View.GONE) c.measure(cw, ch)
        }
    }
}
