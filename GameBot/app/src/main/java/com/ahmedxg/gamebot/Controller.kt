package com.ahmedxg.gamebot

import android.os.Handler
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Turns an [Action] into touches on the calibrated on-screen controls. */
class Controller(
    private val backend: InputBackend,
    private val layout: ControlLayout,
    private val handler: Handler
) {
    private val STICK = 0
    private val LOOK = 1
    private val FIRE = 2
    private val TAP = 3

    private var stickDown = false
    private var lookDown = false
    private var lookX = 0f
    private var lookY = 0f
    private var lastLookMs = 0L
    private var fireDown = false
    private var tapBusy = false

    @Synchronized
    fun apply(a: Action, now: Long, w: Int, h: Int) {
        val short = min(w, h).toFloat()

        // movement stick
        val mag = hypot(a.moveX, a.moveY)
        if (mag > 0.05f) {
            val r = layout.stickRadius * short
            val cx = layout.x("stick") * w
            val cy = layout.y("stick") * h
            val k = if (mag > 1f) 1f / mag else 1f
            if (!stickDown) {
                backend.touchDown(STICK, cx, cy)
                stickDown = true
            }
            backend.touchMove(STICK, cx + a.moveX * k * r, cy - a.moveY * k * r)
        } else if (stickDown) {
            backend.touchUp(STICK)
            stickDown = false
        }

        // look / aim swipe
        if (abs(a.lookDx) > 0.4f || abs(a.lookDy) > 0.4f) {
            val lr = layout.lookRadius * short
            val cx = layout.x("look") * w
            val cy = layout.y("look") * h
            if (!lookDown) {
                lookX = cx
                lookY = cy
                backend.touchDown(LOOK, lookX, lookY)
                lookDown = true
            }
            var nx = lookX + a.lookDx
            var ny = lookY + a.lookDy
            if (hypot(nx - cx, ny - cy) > lr) {
                // reached the edge of the look area: lift and start again from the center
                backend.touchUp(LOOK)
                lookX = cx
                lookY = cy
                backend.touchDown(LOOK, lookX, lookY)
                nx = cx + a.lookDx
                ny = cy + a.lookDy
                val d = hypot(nx - cx, ny - cy)
                if (d > lr) {
                    nx = cx + (nx - cx) * lr / d
                    ny = cy + (ny - cy) * lr / d
                }
            }
            lookX = nx
            lookY = ny
            backend.touchMove(LOOK, lookX, lookY)
            lastLookMs = now
        } else if (lookDown && now - lastLookMs > 150) {
            backend.touchUp(LOOK)
            lookDown = false
        }

        // fire (held)
        if (a.fire && !fireDown) {
            backend.touchDown(FIRE, layout.x("fire") * w, layout.y("fire") * h)
            fireDown = true
        } else if (!a.fire && fireDown) {
            backend.touchUp(FIRE)
            fireDown = false
        }

        // one-shot taps
        if (!tapBusy && a.taps.isNotEmpty()) {
            val name = a.taps[0]
            tapBusy = true
            backend.touchDown(TAP, layout.x(name) * w, layout.y(name) * h)
            handler.postDelayed({
                synchronized(this) {
                    backend.touchUp(TAP)
                    backend.flush()
                    tapBusy = false
                }
            }, 70L)
        }
        backend.flush()
    }

    @Synchronized
    fun releaseAll() {
        backend.releaseAll()
        stickDown = false
        lookDown = false
        fireDown = false
        tapBusy = false
    }
}
