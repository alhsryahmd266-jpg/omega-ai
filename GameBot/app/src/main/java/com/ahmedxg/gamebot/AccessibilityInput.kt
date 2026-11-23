package com.ahmedxg.gamebot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper

/**
 * Fallback backend: AccessibilityService.dispatchGesture. Slower than Shizuku (each dispatch is a
 * ~40 ms gesture), pointers are kept down by chaining "continued" strokes.
 */
class AccessibilityInput : InputBackend {
    override val name = "Accessibility"

    private class P(val id: Int, var x: Float, var y: Float) {
        var stroke: GestureDescription.StrokeDescription? = null
        var lastX = x
        var lastY = y
        var ending = false
    }

    private val ptrs = LinkedHashMap<Int, P>()
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var again = false
    private val durMs = 40L

    @Synchronized
    override fun touchDown(id: Int, x: Float, y: Float) {
        val p = ptrs[id]
        if (p != null && !p.ending) {
            p.x = x
            p.y = y
        } else {
            ptrs[id] = P(id, x, y)
        }
    }

    @Synchronized
    override fun touchMove(id: Int, x: Float, y: Float) {
        val p = ptrs[id] ?: return
        p.x = x
        p.y = y
    }

    @Synchronized
    override fun touchUp(id: Int) {
        val p = ptrs[id] ?: return
        p.ending = true
    }

    override fun flush() {
        main.post { pump() }
    }

    @Synchronized
    private fun pump() {
        if (ptrs.isEmpty()) return
        if (busy) {
            again = true
            return
        }
        val svc = BotAccessibilityService.instance ?: return
        val b = GestureDescription.Builder()
        val finished = ArrayList<Int>()
        var count = 0
        val maxStrokes = GestureDescription.getMaxStrokeCount()
        for (p in ptrs.values) {
            if (count >= maxStrokes) break
            val path = Path()
            path.moveTo(p.lastX, p.lastY)
            if (p.x != p.lastX || p.y != p.lastY) path.lineTo(p.x, p.y)
            val willContinue = !p.ending
            val prev = p.stroke
            val stroke = if (prev == null) {
                GestureDescription.StrokeDescription(path, 0L, durMs, willContinue)
            } else {
                prev.continueStroke(path, 0L, durMs, willContinue)
            }
            p.stroke = stroke
            p.lastX = p.x
            p.lastY = p.y
            b.addStroke(stroke)
            if (p.ending) finished.add(p.id)
            count++
        }
        if (count == 0) return
        busy = true
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                done(false)
            }

            override fun onCancelled(g: GestureDescription?) {
                done(true)
            }
        }
        val ok = try {
            svc.dispatchGesture(b.build(), cb, null)
        } catch (t: Throwable) {
            Logger.i("dispatchGesture failed: ${t.message}")
            false
        }
        if (!ok) busy = false
        for (id in finished) ptrs.remove(id)
    }

    @Synchronized
    private fun done(cancelled: Boolean) {
        busy = false
        if (cancelled) {
            // continued strokes were cancelled: start them again as new strokes
            for (p in ptrs.values) p.stroke = null
        }
        if (again) {
            again = false
            main.post { pump() }
        }
    }

    @Synchronized
    override fun releaseAll() {
        for (p in ptrs.values) p.ending = true
        main.post { pump() }
    }

    override fun close() {
        releaseAll()
    }
}
