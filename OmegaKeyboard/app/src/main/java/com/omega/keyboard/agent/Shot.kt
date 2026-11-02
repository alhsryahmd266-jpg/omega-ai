package com.omega.keyboard.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.Display
import com.omega.keyboard.AppLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Screenshots (Android 11+) used only to copy the colours behind the text, so translations blend in. */
object Shot {
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var lastAt = 0L

    fun take(service: AccessibilityService, timeoutMs: Long = 2500): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        // the system refuses screenshots that come too close together
        val wait = 600 - (System.currentTimeMillis() - lastAt)
        if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) { return null }
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        hb.close()
                    } catch (t: Throwable) {
                        AppLog.e("shot", "decode failed", t)
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    AppLog.d("shot", "screenshot refused, code $errorCode")
                    latch.countDown()
                }
            })
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            AppLog.e("shot", "take failed", t)
        }
        lastAt = System.currentTimeMillis()
        return bmp
    }

    /** The colour most of the edge pixels of [r] have (= the background behind the text). */
    fun backgroundAround(bmp: Bitmap, r: Rect): Int {
        val pts = ArrayList<Int>()
        fun px(x: Int, y: Int) {
            if (x in 0 until bmp.width && y in 0 until bmp.height) pts.add(bmp.getPixel(x, y))
        }
        val l = r.left + 2
        val rt = r.right - 3
        val t = r.top + 2
        val b = r.bottom - 3
        val cx = r.centerX()
        val cy = r.centerY()
        px(l, cy); px(rt, cy); px(cx, t); px(cx, b)
        px(l, t); px(rt, t); px(l, b); px(rt, b)
        px(r.left - 3, cy); px(r.right + 3, cy)
        if (pts.isEmpty()) return Color.TRANSPARENT
        // quantize so anti-aliasing noise votes together
        val votes = HashMap<Int, Int>()
        for (c in pts) {
            val q = (Color.red(c) shr 4 shl 4) shl 16 or ((Color.green(c) shr 4 shl 4) shl 8) or (Color.blue(c) shr 4 shl 4)
            votes[q] = (votes[q] ?: 0) + 1
        }
        val best = votes.entries.maxByOrNull { it.value }!!.key
        // return a real sampled colour of that bucket
        val real = pts.first {
            val q = (Color.red(it) shr 4 shl 4) shl 16 or ((Color.green(it) shr 4 shl 4) shl 8) or (Color.blue(it) shr 4 shl 4)
            q == best
        }
        return Color.rgb(Color.red(real), Color.green(real), Color.blue(real))
    }

    fun luminance(c: Int): Double = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
}
