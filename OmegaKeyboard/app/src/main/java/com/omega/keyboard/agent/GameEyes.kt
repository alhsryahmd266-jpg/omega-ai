package com.omega.keyboard.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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

    class Look(val file: File, val grid: IntArray, val width: Int, val height: Int)

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
    fun look(ctx: Context, svc: ScreenAgentService, region: FRect?): Look? {
        var shot: Bitmap? = null
        Overlays.withBarHidden { shot = Shot.take(svc, 3500) }
        val full = shot ?: return null
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
                } else crop
                try {
                    val grid = grayGrid(scaled)
                    val f = File(ctx.cacheDir, "game_eye.png")
                    FileOutputStream(f).use { out -> scaled.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    return Look(f, grid, scaled.width, scaled.height)
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
