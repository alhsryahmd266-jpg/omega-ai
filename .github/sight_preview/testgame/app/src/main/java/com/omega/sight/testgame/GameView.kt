package com.omega.sight.testgame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * A tiny stand-in for an offline shooter, used ONLY by the CI emulator run to test Omega Sight end to end on a real Android:
 * a dark panorama with buildings, patrolling bots (near, far, a tiny one and one half hidden behind a wall) that have a glowing red eye,
 * a fire button, a joystick and a map that stay still on the screen, a crosshair in the middle, and a look-drag that pans the camera.
 * It counts how long the crosshair is on a bot, as if the fire button were held all the time (autoFire), and logs that under SightTestGame.
 */
class GameView(ctx: Context) : View(ctx) {
    var running = false
    var autoFire = true

    private class Bot(var x: Float, val scale: Float, val vx: Float, val cover: Boolean) {
        var phase = 0f
        var cx = 0f          // where the body was drawn on the screen last frame
        var cy = 0f
        var halfW = 0f
        var halfH = 0f
        var halo: RadialGradient? = null
        var haloR = 0f
    }

    private class Building(val x: Float, val w: Float, val h: Float, val color: Int, val lit: BooleanArray, val cols: Int, val rows: Int)

    private val bots = ArrayList<Bot>()
    private val buildings = ArrayList<Building>()
    private val starX = FloatArray(70)
    private val starY = FloatArray(70)

    private var w = 1f
    private var h = 1f
    private var worldW = 1f
    private var groundY = 1f
    private var camX = 0f
    private var camY = 0f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    // look-drag and buttons
    private var lookId = -1
    private var lookX = 0f
    private var lookY = 0f
    private var fireId = -1
    private var looks = 0
    private var moves = 0

    // measurements
    private var lastNs = 0L
    private var startNs = 0L
    private var frames = 0
    private var fireFrames = 0
    private var hits = 0
    private var onTarget = false
    private var lockStartNs = 0L
    private var locks = 0
    private var longestLock = 0f
    private var firstLockAt = -1f
    private var logNs = 0L
    private var statText = ""
    private var statNs = 0L
    private var nearest = 0f

    init {
        text.color = Color.WHITE
        text.typeface = Typeface.DEFAULT_BOLD
        text.setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        super.onSizeChanged(nw, nh, ow, oh)
        w = nw.toFloat()
        h = nh.toFloat()
        worldW = 4f * w
        groundY = 0.70f * h
        text.textSize = 0.04f * h
        build()
    }

    private fun build() {
        val r = Random(7)
        buildings.clear()
        var x = 0f
        while (x < worldW) {
            val bw = (0.07f + 0.12f * r.nextFloat()) * w
            val bh = (0.18f + 0.34f * r.nextFloat()) * h
            val tone = 0x14 + r.nextInt(0x16)
            val cols = max(2, (bw / (0.018f * w)).toInt())
            val rows = max(2, (bh / (0.03f * h)).toInt())
            buildings.add(Building(x, bw, bh, Color.rgb(tone, tone + 6, tone + 18), BooleanArray(cols * rows) { r.nextFloat() < 0.3f }, cols, rows))
            x += bw + (0.01f + 0.05f * r.nextFloat()) * w
        }
        for (i in starX.indices) {
            starX[i] = r.nextFloat() * worldW
            starY[i] = r.nextFloat() * groundY * 0.8f
        }
        bots.clear()
        // x in screen widths from the start of the panorama; scale 1 = a near bot
        bots.add(Bot(0.30f * w, 1.0f, 90f, false))
        bots.add(Bot(0.75f * w, 0.6f, -70f, false))
        bots.add(Bot(1.40f * w, 0.30f, 45f, false))
        bots.add(Bot(0.55f * w, 0.20f, -35f, false))
        bots.add(Bot(2.10f * w, 0.45f, 6f, true))
        bots.add(Bot(2.80f * w, 0.80f, 200f, false))
        bots.add(Bot(3.40f * w, 0.35f, -110f, false))
        for (b in bots) {
            val bh = bodyHeight(b)
            val er = eyeRadius(b)
            b.haloR = 3.2f * er + 2f
            b.halo = RadialGradient(0f, 0f, b.haloR, intArrayOf(0xFFFF6A6A.toInt(), 0x88FF2020.toInt(), 0x00FF0000), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            b.halfW = 0.225f * bh
            b.halfH = 0.5f * (bh + 1.6f * headRadius(b))
        }
    }

    private fun bodyHeight(b: Bot) = 0.30f * h * b.scale
    private fun headRadius(b: Bot) = 0.11f * bodyHeight(b)
    private fun eyeRadius(b: Bot) = max(1.2f, 0.14f * headRadius(b))
    private fun feetY(b: Bot) = groundY + 0.10f * h * b.scale

    /** the screen x of something at world x (the panorama wraps around) */
    private fun sx(worldX: Float): Float {
        var d = (worldX - camX) % worldW
        if (d < 0f) d += worldW
        if (d > worldW - 0.5f * w) d -= worldW
        return d
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        if (startNs == 0L) startNs = now
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceAtMost(0.1f)
        lastNs = now
        step(dt)
        // where the camera is at every picture drawn (the emulator run compares this with what the app saw and what it ordered)
        Log.i("SightGameTrace", "d t=${SystemClock.uptimeMillis()} x=%.1f y=%.1f".format(camX, camY))
        p.style = Paint.Style.FILL
        p.strokeWidth = 1f

        c.save()
        c.translate(0f, -camY)
        // sky and ground
        p.shader = LinearGradient(0f, -0.6f * h, 0f, groundY, 0xFF1B2540.toInt(), 0xFF0E1424.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, -0.6f * h, w, groundY, p)
        p.shader = LinearGradient(0f, groundY, 0f, 1.6f * h, 0xFF141B28.toInt(), 0xFF080B12.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, groundY, w, 1.6f * h, p)
        p.shader = null
        // stars
        p.color = 0xAAFFFFFF.toInt()
        for (i in starX.indices) {
            val x = sx(starX[i])
            if (x in 0f..w) c.drawCircle(x, starY[i], 1.4f, p)
        }
        // buildings (the windows are what the picture needs to show camera movement)
        for (b in buildings) {
            val x = sx(b.x)
            if (x + b.w < 0f || x > w) continue
            p.color = b.color
            c.drawRect(x, groundY - b.h, x + b.w, groundY, p)
            val cw = b.w / b.cols
            val ch = b.h / b.rows
            for (j in 0 until b.rows) for (i in 0 until b.cols) {
                p.color = if (b.lit[j * b.cols + i]) 0xFFB9A45E.toInt() else 0xFF222E48.toInt()
                val wx = x + i * cw + cw * 0.28f
                val wy = groundY - b.h + j * ch + ch * 0.25f
                c.drawRect(wx, wy, wx + cw * 0.44f, wy + ch * 0.5f, p)
            }
        }
        // ground lines
        p.color = 0x22FFFFFF
        var gl = groundY + 0.02f * h
        var gap = 0.012f * h
        while (gl < 1.6f * h) {
            c.drawRect(0f, gl, w, gl + 1.5f, p)
            gl += gap
            gap *= 1.35f
        }
        // bots, far ones first
        for (b in bots.sortedBy { it.scale }) drawBot(c, b)
        c.restore()

        drawHud(c, now)
        postInvalidateOnAnimation()
    }

    private fun drawBot(c: Canvas, b: Bot) {
        val x = sx(b.x)
        val bh = bodyHeight(b)
        val bw = 0.45f * bh
        val hr = headRadius(b)
        val feet = feetY(b)
        val bob = (sin(b.phase) * 0.02f * bh)
        if (x + bw < 0f || x - bw > w) {
            b.cx = x
            return
        }
        // legs and torso
        p.color = 0xFF2A2F3C.toInt()
        c.drawRect(x - bw * 0.42f, feet - bh * 0.42f, x - bw * 0.06f, feet - bob, p)
        c.drawRect(x + bw * 0.06f, feet - bh * 0.42f, x + bw * 0.42f, feet + bob, p)
        p.color = 0xFF3A3340.toInt()
        c.drawRect(x - bw * 0.5f, feet - bh, x + bw * 0.5f, feet - bh * 0.4f, p)
        // head
        val hy = feet - bh - hr * 0.6f
        p.color = 0xFF2C3038.toInt()
        c.drawCircle(x, hy, hr, p)
        // the glowing red eye, on the side the bot walks to
        val dir = if (b.vx >= 0f) 1f else -1f
        val ex = x + dir * 0.35f * hr
        val er = eyeRadius(b)
        c.save()
        c.translate(ex, hy)
        p.shader = b.halo
        c.drawCircle(0f, 0f, b.haloR, p)
        p.shader = null
        c.restore()
        p.color = 0xFFFF3030.toInt()
        c.drawCircle(ex, hy, er, p)
        p.color = 0xFFFFE0E0.toInt()
        c.drawCircle(ex, hy, er * 0.45f, p)
        // a wall in front hides the lower half of the cover bot
        if (b.cover) {
            p.color = 0xFF1F2733.toInt()
            c.drawRect(x - bw * 1.1f, feet - bh * 0.55f, x + bw * 1.1f, feet + 6f, p)
            p.color = 0xFF3A4660.toInt()
            c.drawRect(x - bw * 1.1f, feet - bh * 0.55f, x + bw * 1.1f, feet - bh * 0.55f + 3f, p)
        }
        b.cx = x
        b.cy = feet - 0.5f * (bh + 1.6f * hr)
    }

    private fun drawHud(c: Canvas, now: Long) {
        // the fire button, the joystick and the map never move on the screen (a real game's HUD)
        p.style = Paint.Style.FILL
        p.color = 0x55E04040
        c.drawCircle(fireX(), fireY(), fireR(), p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = 0xCCFF8080.toInt()
        c.drawCircle(fireX(), fireY(), fireR(), p)
        p.color = 0x44FFFFFF
        c.drawCircle(0.13f * w, 0.68f * h, 0.13f * h, p)
        p.style = Paint.Style.FILL
        p.color = 0x66FFFFFF
        c.drawCircle(0.13f * w, 0.68f * h, 0.06f * h, p)
        rect.set(0.86f * w, 0.04f * h, 0.98f * w, 0.24f * h)
        p.color = 0x66101826
        c.drawRoundRect(rect, 10f, 10f, p)
        p.color = 0xFF3DE0FF.toInt()
        c.drawCircle(0.92f * w, 0.14f * h, 4f, p)
        // health bar
        rect.set(0.03f * w, 0.04f * h, 0.23f * w, 0.07f * h)
        p.color = 0x88000000.toInt()
        c.drawRoundRect(rect, 8f, 8f, p)
        rect.set(0.03f * w, 0.04f * h, 0.19f * w, 0.07f * h)
        p.color = 0xFF39D97A.toInt()
        c.drawRoundRect(rect, 8f, 8f, p)
        // crosshair
        p.color = 0xFFE6FFE6.toInt()
        p.strokeWidth = 3f
        val cx = w / 2f
        val cy = h / 2f
        c.drawLine(cx - 22f, cy, cx - 7f, cy, p)
        c.drawLine(cx + 7f, cy, cx + 22f, cy, p)
        c.drawLine(cx, cy - 22f, cx, cy - 7f, p)
        c.drawLine(cx, cy + 7f, cx, cy + 22f, p)
        p.strokeWidth = 1f
        // the numbers change twice a second, like an ammo counter
        if (now - statNs > 500_000_000L) {
            statNs = now
            val pct = if (fireFrames > 0) 100f * hits / fireFrames else 0f
            statText = "on target %.0f%%   hits %d/%d   looks %d".format(pct, hits, fireFrames, looks)
        }
        text.textAlign = Paint.Align.CENTER
        c.drawText(statText, w / 2f, 0.09f * h, text)
    }

    // ------------------------------------------------------------------ the world

    private fun step(dt: Float) {
        for (b in bots) {
            b.x += b.vx * dt
            if (b.x < 0f) b.x += worldW
            if (b.x >= worldW) b.x -= worldW
            b.phase += dt * 6f
        }
        frames++
        // where is the crosshair: on a bot's body?
        val cx = w / 2f
        val cy = h / 2f   // bots keep their y in world pixels: the screen y is that minus camY
        var on = false
        var near = Float.MAX_VALUE
        for (b in bots) {
            val d = hypot(b.cx - cx, b.cy - camY - cy)
            if (d < near) near = d
            if (abs(b.cx - cx) <= b.halfW && abs(b.cy - camY - cy) <= b.halfH) on = true
        }
        nearest = near
        val firing = autoFire || fireId >= 0
        if (firing) {
            fireFrames++
            if (on) hits++
        }
        val now = System.nanoTime()
        if (on && !onTarget) {
            lockStartNs = now
            locks++
            if (firstLockAt < 0f) firstLockAt = (now - startNs) / 1e9f
        }
        if (!on && onTarget) longestLock = max(longestLock, (now - lockStartNs) / 1e9f)
        onTarget = on
        if (now - logNs > 1_000_000_000L) {
            logNs = now
            val t = (now - startNs) / 1e9f
            val pct = if (fireFrames > 0) 100f * hits / fireFrames else 0f
            val cur = if (onTarget) (now - lockStartNs) / 1e9f else 0f
            Log.i(
                "SightTestGame",
                "t=%.1f frames=%d fireFrames=%d hits=%d pct=%.1f locks=%d firstLock=%.1f longestLock=%.1f nearestPx=%.0f looks=%d moves=%d camX=%.0f camY=%.0f onTarget=%b cur=%.1f"
                    .format(t, frames, fireFrames, hits, pct, locks, firstLockAt, max(longestLock, cur), nearest, looks, moves, camX, camY, onTarget, cur)
            )
        }
    }

    // ------------------------------------------------------------------ touch

    private fun fireX() = 0.88f * w
    private fun fireY() = 0.68f * h
    private fun fireR() = 0.095f * h

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                when {
                    hypot(x - fireX(), y - fireY()) < fireR() * 1.4f -> fireId = id
                    x < 0.30f * w && y > 0.42f * h -> {}                       // the joystick: not needed here
                    else -> {
                        lookId = id
                        lookX = x
                        lookY = y
                        looks++
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    if (e.getPointerId(i) != lookId) continue
                    val x = e.getX(i)
                    val y = e.getY(i)
                    // dragging the finger right turns the view right: the picture moves left
                    Log.i("SightGameTrace", "m t=${e.eventTime} dx=%.1f dy=%.1f".format(x - lookX, y - lookY))
                    camX += (x - lookX) * GAIN
                    camY = (camY + (y - lookY) * GAIN).coerceIn(-0.25f * h, 0.25f * h)
                    lookX = x
                    lookY = y
                    moves++
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(e.actionIndex)
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    lookId = -1
                    fireId = -1
                } else {
                    if (id == lookId) lookId = -1
                    if (id == fireId) fireId = -1
                }
            }
        }
        return true
    }

    companion object {
        /** world pixels the picture moves per pixel the finger moves */
        const val GAIN = 1.0f
    }
}
