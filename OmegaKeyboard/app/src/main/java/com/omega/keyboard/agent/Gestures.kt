package com.omega.keyboard.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.omega.keyboard.AppLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Touches we perform ourselves through the accessibility service. Blocking: call from a background thread. */
object Gestures {

    /** A straight finger swipe from (x, y1) to (x, y2). A fast one flings the list it lands on. */
    fun swipeVertical(svc: AccessibilityService, x: Int, y1: Int, y2: Int, durationMs: Long = 220): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x.toFloat(), y1.toFloat())
                lineTo(x.toFloat(), y2.toFloat())
            }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
            val latch = CountDownLatch(1)
            var ok = false
            val dispatched = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            }, null)
            if (!dispatched) false else { latch.await(durationMs + 1500, TimeUnit.MILLISECONDS); ok }
        } catch (t: Throwable) {
            AppLog.e("gesture", "swipe failed", t)
            false
        }
    }
}
