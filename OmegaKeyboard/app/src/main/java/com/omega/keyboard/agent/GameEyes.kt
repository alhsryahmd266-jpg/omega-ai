package com.omega.keyboard.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.view.WindowManager
import com.omega.keyboard.AppLog
import com.omega.keyboard.core.FRect
import java.io.File
import java.io.FileOutputStream

/**
 * The eyes of the autonomous mode inside a game. Games draw their own screens, so none of their text can be read through the
 * accessibility service: instead a picture is taken of the screen (only the chat part of it once the game was taught), saved for
 * the model to read, together with a tiny gray copy that tells when the chat changed.
 */
object GameEyes {
    const val GRID = 32
    private const val MAX_SIDE = 1280

    /**
     * [blank]: the picture of the chat part is (nearly) all black. [screenBlank]: the whole screen is: a game that forbids screenshots
     * is drawn black (the system hides it, nothing is bypassed here), or nothing is drawn yet.
     */
    class Look(val file: File, val grid: IntArray, val width: Int, val height: Int, val blank: Boolean = false, val screenBlank: Boolean = false)

    /** share (0..1) of the middle of the screen that is black: the bars at the top and the bottom belong to the system and stay visible on a protected screen */
    fun blackShare(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        if (w < 8 || h < 8) return 0f
        val cols = 40
        val rows = 60
        var dark = 0
        var n = 0
        for (r in 0 until rows) {
            val y = (h * (0.08f + 0.84f * (r + 0.5f) / rows)).toInt().coerceIn(0, h - 1)
            for (c in 0 until cols) {
                val x = (w * (c + 0.5f) / cols).toInt().coerceIn(0, w - 1)
                val px = bmp.getPixel(x, y)
                n++
                if (Color.red(px) + Color.green(px) + Color.blue(px) < 24) dark++
            }
        }
        return if (n == 0) 0f else dark.toFloat() / n
    }

    /** a chat area is shown to the model at about this many pixels on its long side: small letters get big enough to read */
    private const val CROP_TARGET = 1100

    /** The real size of the screen in pixels (what screenshots and gestures use), in the current orientation. */
    @Suppress("DEPRECATION")
    fun realSize(ctx: Context): Pair<Int, Int> {
        return try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val p = Point()
            wm.defaultDisplay.getRealSize(p)
            if (p.x > 0 && p.y > 0) p.x to p.y else ctx.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        } catch (_: Throwable) {
            ctx.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
    }

    /**
     * A picture of the chat area [region] of the screen (all of it when null) saved as a PNG, and its gray grid. Waits for the
     * screenshot, so call it from a background thread. Null when the system refused the screenshot (a protected screen, Android 10 or older).
     */
    fun look(ctx: Context, svc: ScreenAgentService, region: FRect?, name: String = "game_eye"): Look? {
        var shot: Bitmap? = null
        Overlays.withBarHidden { shot = Shot.take(svc, 3500) }
        val full = shot ?: return null
        val screenBlank = blackShare(full) >= 0.97f
        try {
            val r = (region ?: FRect(0f, 0f, 1f, 1f)).px(full.width, full.height)
            val whole = r[0] == 0 && r[1] == 0 && r[2] == full.width && r[3] == full.height
            val crop = if (whole) full else Bitmap.createBitmap(full, r[0], r[1], r[2] - r[0], r[3] - r[1])
            try {
                val longest = maxOf(crop.width, crop.height)
                val scaled = if (longest > MAX_SIDE) {
                    Bitmap.createScaledBitmap(
                        crop, (crop.width * MAX_SIDE / longest).coerceAtLeast(1), (crop.height * MAX_SIDE / longest).coerceAtLeast(1), true
                    )
                } else if (!whole && longest < CROP_TARGET) {
                    // a small piece of the screen (the chat): bigger, so the model's picture reader sees its letters well
                    val k = minOf(3f, CROP_TARGET.toFloat() / longest)
                    Bitmap.createScaledBitmap(crop, (crop.width * k).toInt().coerceAtLeast(1), (crop.height * k).toInt().coerceAtLeast(1), true)
                } else crop
                try {
                    val grid = grayGrid(scaled)
                    val f = File(ctx.cacheDir, "$name.png")
                    FileOutputStream(f).use { out -> scaled.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    val dark = grid.max() < 14
                    AppLog.d("gameeyes", "picture ${scaled.width}x${scaled.height} of ${full.width}x${full.height}" + (if (dark) " (all black)" else ""))
                    return Look(f, grid, scaled.width, scaled.height, dark, screenBlank)
                } finally {
                    if (scaled !== crop) scaled.recycle()
                }
            } finally {
                if (crop !== full) crop.recycle()
            }
        } catch (t: Throwable) {
            AppLog.e("gameeyes", "could not make the picture", t)
            return null
        } finally {
            full.recycle()
        }
    }

    /**
     * The same picture with more contrast and stronger colours: coloured letters on a busy background (chat lines in yellow, pink or green)
     * stand out more. Used when the plain picture gave no chat lines.
     */
    fun enhanced(ctx: Context, look: Look): Look? {
        val src = BitmapFactory.decodeFile(look.file.absolutePath) ?: return null
        try {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val k = 1.5f
            val t = (-0.5f * k + 0.5f) * 255f + 14f
            val m = ColorMatrix()
            m.setSaturation(1.6f)
            m.postConcat(ColorMatrix(floatArrayOf(
                k, 0f, 0f, 0f, t,
                0f, k, 0f, 0f, t,
                0f, 0f, k, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )))
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) }
            Canvas(out).drawBitmap(src, 0f, 0f, paint)
            val f = File(ctx.cacheDir, "game_eye_hi.png")
            FileOutputStream(f).use { o -> out.compress(Bitmap.CompressFormat.PNG, 100, o) }
            out.recycle()
            return Look(f, look.grid, look.width, look.height, look.blank, look.screenBlank)
        } catch (t: Throwable) {
            AppLog.e("gameeyes", "could not sharpen the picture", t)
            return null
        } finally {
            src.recycle()
        }
    }

    /**
     * The picture with its background taken away: whatever stands out from its surroundings (letters of any colour) is black on white
     * (see [com.omega.keyboard.core.TextMask]). The last try when neither the plain nor the sharper picture showed a chat line.
     */
    fun masked(ctx: Context, look: Look): Look? {
        val src = BitmapFactory.decodeFile(look.file.absolutePath) ?: return null
        try {
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            val made = com.omega.keyboard.core.TextMask.make(px, w, h)
            val out = Bitmap.createBitmap(made, w, h, Bitmap.Config.ARGB_8888)
            val f = File(ctx.cacheDir, "game_eye_mask.png")
            FileOutputStream(f).use { o -> out.compress(Bitmap.CompressFormat.PNG, 100, o) }
            out.recycle()
            return Look(f, look.grid, look.width, look.height, look.blank, look.screenBlank)
        } catch (t: Throwable) {
            AppLog.e("gameeyes", "could not make the letters stand out", t)
            return null
        } finally {
            src.recycle()
        }
    }

    /** The picture as 32 x 32 average gray values: enough to notice a new chat line, cheap to compare. */
    private fun grayGrid(bmp: Bitmap): IntArray {
        val side = GRID * 4
        val mid = Bitmap.createScaledBitmap(bmp, side, side, true)
        val px = IntArray(side * side)
        mid.getPixels(px, 0, side, 0, 0, side, side)
        if (mid !== bmp) mid.recycle()
        val out = IntArray(GRID * GRID)
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                var sum = 0
                for (dy in 0 until 4) {
                    for (dx in 0 until 4) {
                        val c = px[(gy * 4 + dy) * side + gx * 4 + dx]
                        sum += (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                    }
                }
                out[gy * GRID + gx] = sum / 16
            }
        }
        return out
    }
}
