package com.gamevision.bot.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Sends touches the same way a finger would — through [AccessibilityService.dispatchGesture], Android's
 * public API for synthesizing input. This does not touch the target app's process, memory, or files: the
 * game receives these exactly as it would a real touch from the glass.
 */
class GestureActor(private val service: AccessibilityService) {

    suspend fun tap(x: Int, y: Int, durationMs: Long = 45): Boolean = dispatch(
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(pointPath(x, y), 0, durationMs))
            .build(),
    )

    /** Press-and-hold, e.g. a "crouch" or "ADS" button some games require held rather than tapped. */
    suspend fun hold(x: Int, y: Int, durationMs: Long): Boolean = dispatch(
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(pointPath(x, y), 0, durationMs))
            .build(),
    )

    /** For a look/aim joystick: drag from (x0,y0) to (x1,y1). */
    suspend fun swipe(x0: Int, y0: Int, x1: Int, y1: Int, durationMs: Long = 90): Boolean {
        val path = Path().apply { moveTo(x0.toFloat(), y0.toFloat()); lineTo(x1.toFloat(), y1.toFloat()) }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build())
    }

    private fun pointPath(x: Int, y: Int) = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

    private suspend fun dispatch(gesture: GestureDescription): Boolean = suspendCoroutine { cont ->
        val ok = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { cont.resume(true) }
                override fun onCancelled(g: GestureDescription?) { cont.resume(false) }
            },
            null,
        )
        if (!ok) cont.resume(false)
    }
}
