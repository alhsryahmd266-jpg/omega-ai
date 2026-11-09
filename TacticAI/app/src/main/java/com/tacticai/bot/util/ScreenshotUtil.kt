package com.tacticai.bot.util

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.view.Display
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Wraps [AccessibilityService.takeScreenshot] (API 30+) as a suspend function. This reads the
 * compositor's current frame the same way the system screenshot shortcut does — it is not reading the
 * target app's memory or files, and it works for any visible app including GL/Vulkan-rendered games.
 */
object ScreenshotUtil {

    suspend fun capture(service: AccessibilityService): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCoroutine { cont ->
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            cont.resume(toBitmap(result))
                        }

                        override fun onFailure(errorCode: Int) {
                            cont.resume(null)
                        }
                    },
                )
            } catch (_: Throwable) {
                cont.resume(null)
            }
        }
    }

    private fun toBitmap(result: AccessibilityService.ScreenshotResult): Bitmap? = try {
        val hb: HardwareBuffer = result.hardwareBuffer
        val bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
        hb.close()
        // Software copy: HARDWARE bitmaps can't be read pixel-by-pixel (ButtonFinder needs getPixels()).
        bmp?.copy(Bitmap.Config.ARGB_8888, false)
    } catch (_: Throwable) {
        null
    }
}
