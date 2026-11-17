package com.omega.keyboard.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** The user's own picture for the "صورتي" look: chosen in the gallery, shrunk once and kept inside the app (nothing leaves the phone). */
object Photos {
    private const val FILE = "bg_photo.jpg"
    private const val LONG_SIDE = 1600

    fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun has(ctx: Context): Boolean = file(ctx).exists()

    /** the kept picture, or null when none was chosen (or it cannot be read) */
    fun load(ctx: Context): Bitmap? = try {
        val f = file(ctx)
        if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    } catch (_: Throwable) {
        null
    }

    fun remove(ctx: Context) {
        try { file(ctx).delete() } catch (_: Throwable) {}
    }

    /** reads the picture at [uri], turns it the right way up, shrinks it and keeps it; false when it could not be read */
    fun save(ctx: Context, uri: Uri): Boolean {
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
            FileOutputStream(file(ctx)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
