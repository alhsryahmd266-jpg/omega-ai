package com.ahmedxg.gamebot

import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * Runs in a separate process with SHELL privileges, started by the app through its built-in ADB connection:
 *   CLASSPATH=<this apk> app_process /system/bin com.ahmedxg.gamebot.ShellServer
 * It reads simple text commands from stdin and injects real multi-touch events (same technique scrcpy uses):
 *   D id x y   pointer down        M id x y   pointer moved (sent on F)
 *   F          flush moves         U id       pointer up
 *   X          release everything  Q          quit
 * It exits (and releases all pointers) when the ADB stream closes.
 */
object ShellServer {
    private class P(val id: Int, var x: Float, var y: Float)

    private val ptrs = ArrayList<P>()
    private var downTime = 0L
    private var dirty = false
    private var im: Any? = null
    private var inject: Method? = null

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val get = InputManager::class.java.getDeclaredMethod("getInstance")
            get.isAccessible = true
            im = get.invoke(null)
            inject = InputManager::class.java.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )
        } catch (t: Throwable) {
            println("ERR init " + t.javaClass.simpleName + " " + (t.cause?.message ?: t.message))
            System.out.flush()
            return
        }
        println("READY")
        System.out.flush()
        val r = BufferedReader(InputStreamReader(System.`in`))
        try {
            while (true) {
                val line = r.readLine() ?: break
                if (!handle(line)) break
            }
        } catch (t: Throwable) {
        }
        releaseAll()
    }

    /** @return false to quit */
    private fun handle(line: String): Boolean {
        val s = line.trim().split(' ')
        if (s.isEmpty() || s[0].isEmpty()) return true
        try {
            when (s[0]) {
                "D" -> down(s[1].toInt(), s[2].toFloat(), s[3].toFloat())
                "M" -> move(s[1].toInt(), s[2].toFloat(), s[3].toFloat())
                "U" -> up(s[1].toInt())
                "F" -> flush()
                "X" -> releaseAll()
                "Q" -> return false
                else -> {
                }
            }
        } catch (t: Throwable) {
            println("ERR cmd " + t.javaClass.simpleName)
            System.out.flush()
        }
        return true
    }

    private fun send(action: Int) {
        val n = ptrs.size
        if (n == 0) return
        val props = Array(n) { i ->
            val p = MotionEvent.PointerProperties()
            p.id = ptrs[i].id
            p.toolType = MotionEvent.TOOL_TYPE_FINGER
            p
        }
        val coords = Array(n) { i ->
            val c = MotionEvent.PointerCoords()
            c.x = ptrs[i].x
            c.y = ptrs[i].y
            c.pressure = 1f
            c.size = 1f
            c
        }
        val ev = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, n, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            inject?.invoke(im, ev, 0)
        } catch (t: Throwable) {
            println("ERR inject " + (t.cause?.message ?: t.message))
            System.out.flush()
        } finally {
            ev.recycle()
        }
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (ptrs.any { it.id == id }) {
            move(id, x, y)
            return
        }
        val first = ptrs.isEmpty()
        ptrs.add(P(id, x, y))
        if (first) {
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN)
        } else {
            send(MotionEvent.ACTION_POINTER_DOWN or ((ptrs.size - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        }
        dirty = false
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = ptrs.firstOrNull { it.id == id } ?: return
        if (p.x != x || p.y != y) {
            p.x = x
            p.y = y
            dirty = true
        }
    }

    private fun up(id: Int) {
        val idx = ptrs.indexOfFirst { it.id == id }
        if (idx < 0) return
        if (dirty) {
            send(MotionEvent.ACTION_MOVE)
            dirty = false
        }
        if (ptrs.size == 1) {
            send(MotionEvent.ACTION_UP)
        } else {
            send(MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        }
        ptrs.removeAt(idx)
    }

    private fun flush() {
        if (dirty && ptrs.isNotEmpty()) send(MotionEvent.ACTION_MOVE)
        dirty = false
    }

    private fun releaseAll() {
        while (ptrs.isNotEmpty()) up(ptrs[ptrs.size - 1].id)
        dirty = false
    }
}
