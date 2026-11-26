package com.omega.sight.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.omega.sight.engine.Hub

/**
 * One finger that drags inside the aim zone, built from short gesture pieces that continue each other (the finger stays down between
 * pieces). Every piece is sent when the one before it has finished, so there is never a queue; what the aim loop asks for in between is
 * added up and goes out in the next piece.
 *
 * Android cancels a gesture that an accessibility service sent whenever a real finger touches the screen. That shows up here as
 * [onCancelled]: the finger is forgotten, [epoch] grows (the aim loop starts its stroke again from the middle) and [userTouching] says
 * "yes" for a short while, so the app never fights the owner's own finger. All calls come on the main thread.
 */
class Stroker(private val svc: AccessibilityService, private val main: Handler) {
    /** grows every time the finger goes up or is cancelled */
    @Volatile
    var epoch = 0
        private set

    @Volatile
    private var lastCancelAt = 0L

    private var stroke: GestureDescription.StrokeDescription? = null
    private var fx = 0f
    private var fy = 0f
    private var busy = false
    private var inflightLift = false
    private var px = 0f
    private var py = 0f
    private var zx = 0f
    private var zy = 0f
    private var restartPending = false
    private var wantLift = false
    private var lastDispatch = 0L
    private var lastCmdAt = 0L
    private var sw = 1080f
    private var sh = 2400f

    private val idleLift = Runnable {
        val now = SystemClock.uptimeMillis()
        if (stroke != null && now - lastCmdAt >= IDLE_MS) {
            wantLift = true
            pump()
        } else if (stroke != null) {
            schedIdle()
        }
    }

    /** a real finger cancelled our gesture a moment ago */
    fun userTouching(): Boolean = SystemClock.uptimeMillis() - lastCancelAt < BACKOFF_MS

    fun aim(dx: Float, dy: Float, restart: Boolean, zoneCx: Float, zoneCy: Float) {
        zx = zoneCx
        zy = zoneCy
        lastCmdAt = SystemClock.uptimeMillis()
        if (restart) {
            restartPending = true
            px = dx
            py = dy
        } else {
            px += dx
            py += dy
        }
        pump()
    }

    /** the finger goes up (nothing to aim at, fire released, test stopped) */
    fun lift() {
        px = 0f
        py = 0f
        restartPending = false
        if (stroke != null) {
            wantLift = true
            pump()
        }
    }

    private fun screen() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels.toFloat()
        sh = dm.heightPixels.toFloat()
    }

    private fun schedIdle() {
        main.removeCallbacks(idleLift)
        main.postDelayed(idleLift, IDLE_MS)
    }

    private fun pump() {
        if (busy) return
        val cur = stroke
        if (cur != null && (restartPending || wantLift)) {
            restartPending = false
            wantLift = false
            val p = Path()
            p.moveTo(fx, fy)
            send(cur.continueStroke(p, 0, 1, false), true)
            return
        }
        wantLift = false
        restartPending = false
        if (px == 0f && py == 0f) return
        screen()
        val startX = if (cur != null) fx else zx
        val startY = if (cur != null) fy else zy
        val ex = (startX + px).coerceIn(2f, sw - 3f)
        val ey = (startY + py).coerceIn(2f, sh - 3f)
        val p = Path()
        p.moveTo(startX, startY)
        p.lineTo(ex, ey)
        val now = SystemClock.uptimeMillis()
        val dur = if (cur == null || lastDispatch == 0L) 24L else (now - lastDispatch).coerceIn(16L, 48L)
        val s = if (cur == null) GestureDescription.StrokeDescription(p, 0, dur, true) else cur.continueStroke(p, 0, dur, true)
        px = 0f
        py = 0f
        fx = ex
        fy = ey
        lastDispatch = now
        send(s, false)
    }

    private fun send(s: GestureDescription.StrokeDescription, lift: Boolean) {
        val g = GestureDescription.Builder().addStroke(s).build()
        busy = true
        inflightLift = lift
        if (!lift) stroke = s
        Hub.stats.strokesSent++
        val ok = try {
            svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    finished(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    finished(false)
                }
            }, main)
        } catch (_: Throwable) {
            false
        }
        if (!ok) failed()
    }

    /** the system refused the gesture (the service is not ready, or it is being shut down) */
    private fun failed() {
        busy = false
        stroke = null
        epoch++
        lastCancelAt = SystemClock.uptimeMillis()
        Hub.stats.strokesFailed++
        px = 0f
        py = 0f
        restartPending = false
        wantLift = false
        main.removeCallbacks(idleLift)
    }

    private fun finished(ok: Boolean) {
        busy = false
        if (!ok) {
            stroke = null
            epoch++
            lastCancelAt = SystemClock.uptimeMillis()
            Hub.stats.strokesCancelled++
            px = 0f
            py = 0f
            restartPending = false
            wantLift = false
            main.removeCallbacks(idleLift)
            return
        }
        Hub.stats.strokesDone++
        if (inflightLift) {
            stroke = null
            epoch++
            main.removeCallbacks(idleLift)
        } else {
            schedIdle()
        }
        pump()
    }

    companion object {
        /** the finger goes up when nothing asked for movement for this long (ms) */
        private const val IDLE_MS = 160L

        /** after a cancel, nothing is sent for this long (ms) */
        private const val BACKOFF_MS = 450L
    }
}
