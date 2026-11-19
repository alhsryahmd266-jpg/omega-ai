package com.omega.keyboard.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.omega.keyboard.core.PhotoLook
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * The user's own pictures for the "صورتي" looks: up to [SLOTS] of them, chosen in the gallery, shrunk once and kept inside the app (nothing
 * leaves the phone). Slot 0 is the theme "photo", slot 1 is "photo2" and so on. Every picture also gives its theme its colours ([look]).
 */
object Photos {
    const val SLOTS = 6
    private const val LONG_SIDE = 1600

    /** the theme that shows the picture of [slot] */
    fun idOf(slot: Int): String = if (slot <= 0) "photo" else "photo" + (slot + 1)

    /** the slot a theme id belongs to, or -1 when the id is not one of the user's pictures */
    fun slotOf(id: String): Int = when {
        id == "photo" -> 0
        id.length == 6 && id.startsWith("photo") && id[5] in '2'..'6' -> id[5] - '1'
        else -> -1
    }

    /** the name under the card of a picture of one's own */
    fun nameOf(slot: Int): String = if (slot <= 0) "صورتي" else "صورتي " + (slot + 1)

    fun file(ctx: Context, slot: Int) = File(ctx.filesDir, if (slot <= 0) "bg_photo.jpg" else "bg_photo" + (slot + 1) + ".jpg")

    fun has(ctx: Context, slot: Int): Boolean = slot in 0 until SLOTS && file(ctx, slot).exists()

    /** the first slot with no picture in it, or -1 when all are used */
    fun firstFree(ctx: Context): Int {
        for (s in 0 until SLOTS) if (!has(ctx, s)) return s
        return -1
    }

    /**
     * The picture of [slot], or null when none was chosen (or it cannot be read). With [needW] and [needH] the picture is read no bigger than
     * it will be shown (it is drawn "cover", so it never needs to be smaller than the area in either direction).
     */
    fun load(ctx: Context, slot: Int, needW: Int = 0, needH: Int = 0): Bitmap? = try {
        val f = file(ctx, slot)
        if (!f.exists()) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = Backdrops.sampleFor(bounds.outWidth, bounds.outHeight, needW, needH) }
            BitmapFactory.decodeFile(f.path, opts)
        }
    } catch (_: Throwable) {
        null
    }

    fun remove(ctx: Context, slot: Int) {
        try { file(ctx, slot).delete() } catch (_: Throwable) {}
        looks.remove(slot)
    }

    // ------------------------------------------------------------------ colours worked out of the picture

    private class Cached(val stamp: Long, val look: PhotoLook.Look)

    private val looks = HashMap<Int, Cached>()

    /** the colours of the theme made from the picture of [slot]: worked out from a tiny copy once, and again only when the picture changes */
    @Synchronized
    fun look(ctx: Context, slot: Int): PhotoLook.Look {
        val f = file(ctx, slot)
        if (!f.exists()) return PhotoLook.neutral()
        val stamp = f.lastModified() * 31 + f.length()
        looks[slot]?.let { if (it.stamp == stamp) return it.look }
        val made = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            // a copy of about 48 pixels on the long side is plenty for an average of colours
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 48) sample *= 2
            val small = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            if (small == null) PhotoLook.neutral() else {
                val n = 24
                val tiny = Bitmap.createScaledBitmap(small, n, n, true)
                val px = IntArray(n * n)
                tiny.getPixels(px, 0, n, 0, 0, n, n)
                PhotoLook.from(px, n, n)
            }
        } catch (_: Throwable) {
            PhotoLook.neutral()
        }
        looks[slot] = Cached(stamp, made)
        return made
    }

    // ------------------------------------------------------------------ saving

    /** reads the picture at [uri], turns it the right way up, shrinks it and keeps it in [slot]; false when it could not be read */
    fun save(ctx: Context, uri: Uri, slot: Int): Boolean {
        if (slot !in 0 until SLOTS) return false
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false
            val turn = try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Throwable) {
                0f
            }
            if (turn != 0f) {
                val m = Matrix().apply { postRotate(turn) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val longSide = max(bmp.width, bmp.height)
            if (longSide > LONG_SIDE) {
                val k = LONG_SIDE / longSide.toFloat()
                bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
            }
            FileOutputStream(file(ctx, slot)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            looks.remove(slot)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
