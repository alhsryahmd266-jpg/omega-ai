package com.ahmedxg.gamebot

import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * Real multi-touch injection through Shizuku (shell identity): calls IInputManager.injectInputEvent
 * with MotionEvents that carry several pointers at once (stick + look + fire together).
 */
class ShizukuInput : InputBackend {
    override val name = "Shizuku"

    private class Ptr(val id: Int, var x: Float, var y: Float)

    private var im: Any? = null
    private var inject2: Method? = null
    private var inject3: Method? = null
    private val ptrs = ArrayList<Ptr>()
    private var downTime = 0L
    private var dirty = false

    /** @return null if ready, else the reason it is not. */
    fun prepare(): String? {
        try {
            if (!Shizuku.pingBinder()) return "Shizuku مش شغّال"
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return "صلاحية Shizuku مش ممنوحة"
        } catch (t: Throwable) {
            return "Shizuku غير متاح: ${t.javaClass.simpleName}"
        }
        try {
            HiddenApiBypass.addHiddenApiExemptions("L")
            val binder: IBinder = SystemServiceHelper.getSystemService("input") ?: return "خدمة input مش موجودة"
            val wrapper = ShizukuBinderWrapper(binder)
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val svc = asInterface.invoke(null, wrapper) ?: return "IInputManager null"
            im = svc
            val ev = InputEvent::class.java
            val intT = Int::class.javaPrimitiveType
            try {
                inject2 = svc.javaClass.getMethod("injectInputEvent", ev, intT)
            } catch (e: NoSuchMethodException) {
                inject2 = null
            }
            if (inject2 == null) {
                try {
                    inject3 = svc.javaClass.getMethod("injectInputEvent", ev, intT, intT)
                } catch (e: NoSuchMethodException) {
                    inject3 = null
                }
            }
            if (inject2 == null && inject3 == null) return "injectInputEvent مش موجودة في الإصدار ده"
            return null
        } catch (t: Throwable) {
            return "فشل تهيئة Shizuku: ${t.javaClass.simpleName} ${t.message}"
        }
    }

    private fun inject(e: MotionEvent) {
        try {
            val m2 = inject2
            if (m2 != null) {
                m2.invoke(im, e, 0)
            } else {
                inject3?.invoke(im, e, 0, -1)
            }
        } catch (t: Throwable) {
            Logger.i("inject failed: ${t.javaClass.simpleName} ${t.cause?.message ?: t.message}")
        }
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
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(
            downTime, now, action, n, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            inject(ev)
        } finally {
            ev.recycle()
        }
    }

    @Synchronized
    override fun touchDown(id: Int, x: Float, y: Float) {
        val old = ptrs.firstOrNull { it.id == id }
        if (old != null) {
            touchMove(id, x, y)
            return
        }
        val first = ptrs.isEmpty()
        ptrs.add(Ptr(id, x, y))
        if (first) {
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN)
        } else {
            send(MotionEvent.ACTION_POINTER_DOWN or ((ptrs.size - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        }
        dirty = false
    }

    @Synchronized
    override fun touchMove(id: Int, x: Float, y: Float) {
        val p = ptrs.firstOrNull { it.id == id } ?: return
        if (p.x != x || p.y != y) {
            p.x = x
            p.y = y
            dirty = true
        }
    }

    @Synchronized
    override fun touchUp(id: Int) {
        val idx = ptrs.indexOfFirst { it.id == id }
        if (idx < 0) return
        if (dirty) { send(MotionEvent.ACTION_MOVE); dirty = false }
        if (ptrs.size == 1) {
            send(MotionEvent.ACTION_UP)
        } else {
            send(MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        }
        ptrs.removeAt(idx)
    }

    @Synchronized
    override fun flush() {
        if (dirty && ptrs.isNotEmpty()) {
            send(MotionEvent.ACTION_MOVE)
        }
        dirty = false
    }

    @Synchronized
    override fun releaseAll() {
        while (ptrs.isNotEmpty()) touchUp(ptrs[ptrs.size - 1].id)
        dirty = false
    }

    override fun close() {
        releaseAll()
    }
}
