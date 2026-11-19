package com.omega.keyboard.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * The pictures that come with the keyboard (assets/themes/<id>.jpg, drawn by tools/themes): the backgrounds of the picture themes.
 * They are read only when a theme needs them, no bigger than they will be shown.
 */
object Backdrops {
    val ids = listOf("beach", "waves", "paint", "lagoon", "blush", "ruby", "navy", "aqua")

    fun has(id: String): Boolean = id in ids

    /**
     * How much a picture of [w] x [h] pixels can be shrunk (a power of two) while it stays at least [needW] x [needH]: the picture is drawn
     * "cover", so it must not get smaller than the area in either direction. 1 when the size is not known.
     */
    fun sampleFor(w: Int, h: Int, needW: Int, needH: Int): Int {
        if (w <= 0 || h <= 0 || needW <= 0 || needH <= 0) return 1
        var s = 1
        while (w / (s * 2) >= needW && h / (s * 2) >= needH) s *= 2
        return s
    }

    /** the picture of [id], or null when there is none (or it cannot be read) */
    fun load(ctx: Context, id: String, needW: Int = 0, needH: Int = 0): Bitmap? {
        if (!has(id)) return null
        return try {
            val path = "themes/$id.jpg"
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, needW, needH) }
            ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Throwable) {
            null
        }
    }
}

/** the picture behind a picture theme: one of the keyboard's own backgrounds, or a picture of the user's own */
object Pictures {
    fun load(ctx: Context, scene: String, needW: Int = 0, needH: Int = 0): Bitmap? {
        if (Backdrops.has(scene)) return Backdrops.load(ctx, scene, needW, needH)
        val slot = Photos.slotOf(scene)
        return if (slot >= 0) Photos.load(ctx, slot, needW, needH) else null
    }
}
