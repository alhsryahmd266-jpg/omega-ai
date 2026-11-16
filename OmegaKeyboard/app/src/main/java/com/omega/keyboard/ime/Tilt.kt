package com.omega.keyboard.ime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * How far the phone is tipped from the way it is usually held, -1..1 on each axis, for the metal of the luxe look to catch the light.
 * It listens only while the keyboard is on the screen (the caller starts and stops it) and needs no permission. The "usual way of
 * holding it" is followed slowly, so lying down or sitting up never leaves the shine in a corner.
 */
class Tilt(ctx: Context, private val onTilt: (Float, Float) -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var on = false
    private var seeded = false
    private var fx = 0f
    private var fy = 0f
    private var bx = 0f
    private var by = 0f

    fun start() {
        if (on) return
        val s = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        seeded = false
        on = try { sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI) } catch (_: Throwable) { false }
    }

    fun stop() {
        if (!on) return
        on = false
        try { sm?.unregisterListener(this) } catch (_: Throwable) {}
        onTilt(0f, 0f)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        if (!seeded) { fx = x; fy = y; bx = x; by = y; seeded = true }
        fx += (x - fx) * 0.35f
        fy += (y - fy) * 0.35f
        bx += (fx - bx) * 0.02f
        by += (fy - by) * 0.02f
        // the right edge going down moves the shine to the right, the top edge going away moves it up
        onTilt((-(fx - bx) / 3.2f).coerceIn(-1f, 1f), ((fy - by) / 3.2f).coerceIn(-1f, 1f))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
