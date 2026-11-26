package com.omega.sight.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import com.omega.sight.core.ScreenMath

/**
 * The size of the screen the way it is turned right now, in the pixels that touches and the screen picture use.
 *
 * Why this is not just Display.getRealMetrics: on newer Android versions that call, made from a service, may take its numbers from a
 * configuration that is not updated when the phone turns, so it could still say "portrait" after a game turned the screen to landscape.
 * The display's rotation is always fresh and the panel's own shape never changes, so the pixel counts come from the system and only
 * their direction is decided by the rotation. (The first emulator run on Android 14 showed the capture staying portrait while the game
 * ran in landscape, and every touch the app sent landed outside the real screen.)
 */
object ScreenInfo {
    /** [width, height, densityDpi, rotation] of the main display now */
    fun read(ctx: Context): IntArray {
        val display: Display? = try {
            (ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        } catch (_: Throwable) {
            null
        }
        val m = DisplayMetrics()
        var rot = 0
        var naturalLandscape: Boolean? = null
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(m)
            rot = display.rotation
            naturalLandscape = try {
                val mode = display.mode
                if (mode.physicalWidth == mode.physicalHeight) null else mode.physicalWidth > mode.physicalHeight
            } catch (_: Throwable) {
                null
            }
        } else {
            val r = ctx.resources.displayMetrics
            m.widthPixels = r.widthPixels
            m.heightPixels = r.heightPixels
            m.densityDpi = r.densityDpi
        }
        val quarterTurn = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
        val o = ScreenMath.oriented(m.widthPixels, m.heightPixels, naturalLandscape, quarterTurn)
        val w = o[0]
        val h = o[1]
        return intArrayOf(w, h, if (m.densityDpi > 0) m.densityDpi else 420, rot)
    }
}
