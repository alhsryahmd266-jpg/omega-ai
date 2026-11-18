package com.ahmedxg.gamebot

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/** An enemy that is no longer on screen. [bearing] is in the bot's world yaw frame (deg, clockwise). */
class Ghost(var bearing: Float, var rangeM: Float, val firstSeen: Long) {
    var lastSeen = firstSeen
    var lateral = 0f        // bearing change in deg/s (which way it was moving across our view)
    var sightings = 1
    // last ~0.7 s of (time, bearing relative to the first sample): the rate is a least-squares slope, which averages out
    // noise and the short mismatch between our commands and the camera actually turning
    val hist = ArrayList<Pair<Long, Float>>()
    var histBase = 0f
}

/**
 * The bot's short-term "map in its head". Pure Kotlin (no Android types).
 *
 * It has no map data, so it builds one the way a person does: it remembers where it turned and walked
 * (dead reckoning from its own look/move commands), where it saw or got hit by enemies (a danger map),
 * where enemies were last seen and which way they were moving (ghosts), and where it has already been
 * (a visit map). The visit map drives exploration (go where you have not been), the danger map decides
 * where to hunt or to avoid depending on the match plan, and the ghosts let it keep tracking an enemy
 * that stepped out of view. It also measures how many hits it takes per fight and gets more careful
 * when it keeps losing exchanges. Everything resets with [reset] (a new match).
 *
 * Own look sensitivity is learned online: whenever a tracked enemy shifts on screen while we turn,
 * degrees-per-pixel is refined, so no manual calibration is needed.
 */
class WorldMemory(private val fovDeg: Float) {
    companion object {
        const val SECTORS = 12
        const val SPEED_MPS = 5f             // forward speed at full stick (only the scale matters)
        const val CELL_M = 6f
        const val GHOST_USEFUL_MS = 9000L
        const val GHOST_KEEP_MS = 20000L
    }

    var yaw = 0f; private set                // deg, clockwise, world frame
    var x = 0f; private set                  // meters, +y = initial forward
    var y = 0f; private set
    var heading = 0f; private set            // exploration heading (world frame)
    var degPerPx = 0.04f; private set
    var engagements = 0; private set
    var hitsTaken = 0; private set
    var calibUpdates = 0; private set        // how many look-sensitivity measurements we have (lead needs plenty)

    private var baseDegPerPx = 0.04f
    private var calibrated = false
    private val danger = FloatArray(SECTORS)
    private val visits = HashMap<Long, Float>()
    private val ghosts = ArrayList<Ghost>()
    private var lastT = 0L
    private var prevLookDx = 0f
    private var prevCx = HashMap<Int, Float>()
    private var lastHitAt = 0L
    private var lastAnyEnemyAt = 0L
    private var headingUntil = 0L
    private var nextJumpAt = 0L
    private var glanceUntil = 0L
    private var nextGlanceAt = 0L

    fun reset() {
        yaw = 0f; x = 0f; y = 0f; heading = 0f
        danger.fill(0f); visits.clear(); ghosts.clear()
        lastT = 0L; prevLookDx = 0f; prevCx = HashMap()
        lastHitAt = 0L; lastAnyEnemyAt = 0L; headingUntil = 0L; nextJumpAt = 0L
        glanceUntil = 0L; nextGlanceAt = 0L
        engagements = 0; hitsTaken = 0
        degPerPx = baseDegPerPx
        // calibUpdates is kept on purpose: the phone's look sensitivity does not change between matches
    }

    // ---- bookkeeping -------------------------------------------------------------------------

    private fun norm(a: Float): Float {
        var d = a % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private fun key(px: Float, py: Float): Long {
        val cx = Math.floor((px / CELL_M).toDouble()).toLong()
        val cy = Math.floor((py / CELL_M).toDouble()).toLong()
        return (cx + 100000L) * 200003L + (cy + 100000L)
    }

    private fun sectorOf(worldDeg: Float): Int {
        var d = worldDeg % 360f
        if (d < 0f) d += 360f
        return ((d + 15f) / 30f).toInt() % SECTORS
    }

    /** Where am I heading relative to my current facing: positive = to the right. */
    fun relBearing(world: Float): Float = norm(world - yaw)

    fun toPx(relDeg: Float): Float = relDeg / degPerPx

    /** Call once per frame with the action that was executed since the previous call. */
    fun integrate(now: Long, prev: Action?, screenW: Int) {
        if (!calibrated && screenW > 0) {
            baseDegPerPx = fovDeg / screenW
            degPerPx = baseDegPerPx
            calibrated = true
        }
        if (lastT == 0L) { lastT = now; return }
        val dt = ((now - lastT) / 1000f).coerceIn(0f, 0.25f)
        lastT = now
        if (prev != null) {
            yaw = norm(yaw + prev.lookDx * degPerPx)
            val r = Math.toRadians(yaw.toDouble())
            val fwd = prev.moveY * SPEED_MPS * dt
            val side = prev.moveX * SPEED_MPS * 0.8f * dt
            x += (sin(r) * fwd + cos(r) * side).toFloat()
            y += (cos(r) * fwd - sin(r) * side).toFloat()
            prevLookDx = prev.lookDx
        } else {
            prevLookDx = 0f
        }
        val k = key(x, y)
        visits[k] = (visits[k] ?: 0f) + dt
        val dv = 0.5.pow((dt / 90f).toDouble()).toFloat()
        val dd = 0.5.pow((dt / 30f).toDouble()).toFloat()
        if (visits.size > 600) visits.entries.removeAll { it.value < 0.3f }
        for (e in visits.entries) e.setValue(e.value * dv)
        for (i in 0 until SECTORS) danger[i] *= dd
        ghosts.removeAll { now - it.lastSeen > GHOST_KEEP_MS }
    }

    /** Enemies that are visible in this frame. [crossX] is the screen x of the crosshair. */
    fun observe(seen: List<Track>, now: Long, crossX: Float, screenW: Int, screenH: Int) {
        // learn the look sensitivity from tracked targets while the camera is turning
        for (t in seen) {
            val px = prevCx[t.id]
            if (px != null && t.hits > 4 && abs(prevLookDx) > 20f) {
                val kObs = (-(t.cx - px) * fovDeg) / prevLookDx
                if (kObs > 0f) {
                    degPerPx = (degPerPx * 0.97f + kObs * 0.03f).coerceIn(baseDegPerPx * 0.3f, baseDegPerPx * 3f)
                    calibUpdates++
                }
            }
        }
        val m = HashMap<Int, Float>()
        for (t in seen) m[t.id] = t.cx
        prevCx = m

        if (seen.isEmpty()) return
        if (now - lastAnyEnemyAt > 3000L) engagements++
        lastAnyEnemyAt = now
        val vfov = fovDeg * screenH.toFloat() / max(1, screenW).toFloat()
        for (t in seen) {
            val rel = (t.cx - crossX) * fovDeg
            val world = norm(yaw + rel)
            val range = 1.8f / (2f * tan(Math.toRadians((vfov / 2f).toDouble())).toFloat() * max(0.03f, t.h))
            var g: Ghost? = null
            var bd = 28f
            for (c in ghosts) {
                val dg = abs(norm(c.bearing - world))
                if (dg < bd) { bd = dg; g = c }
            }
            if (g == null) {
                val ng = Ghost(world, range, now)
                ng.histBase = world
                ng.hist.add(Pair(now, 0f))
                ghosts.add(ng)
            } else {
                g.hist.add(Pair(now, norm(world - g.histBase)))
                while (g.hist.size > 1 && now - g.hist[0].first > 700L) g.hist.removeAt(0)
                g.lateral = slope(g.hist)
                g.bearing = world
                g.rangeM = range
                g.lastSeen = now
                g.sightings++
            }
            val s = sectorOf(world)
            danger[s] = min(6f, danger[s] + 0.06f)
        }
    }

    /** deg/s of the least-squares line through (time, bearing); 0 when there are too few/short samples. */
    private fun slope(h: List<Pair<Long, Float>>): Float {
        if (h.size < 6 || h.last().first - h.first().first < 300L) return 0f
        val t0 = h.first().first
        var st = 0f; var sb = 0f
        for (p in h) { st += (p.first - t0) / 1000f; sb += p.second }
        val mt = st / h.size; val mb = sb / h.size
        var num = 0f; var den = 0f
        for (p in h) {
            val dt = (p.first - t0) / 1000f - mt
            num += dt * (p.second - mb); den += dt * dt
        }
        return if (den < 1e-6f) 0f else (num / den).coerceIn(-90f, 90f)
    }

    fun onHit(now: Long) {
        lastHitAt = now
        hitsTaken++
        val g = freshestGhost(now, 4000L)
        if (g != null) {
            val s = sectorOf(g.bearing)
            danger[s] = min(6f, danger[s] + 1.5f)
        } else {
            for (i in 0 until SECTORS) danger[i] = min(6f, danger[i] + 0.15f)
        }
    }

    fun freshestGhost(now: Long, maxAgeMs: Long = GHOST_USEFUL_MS): Ghost? {
        var best: Ghost? = null
        for (g in ghosts) {
            if (now - g.lastSeen > maxAgeMs) continue
            val b = best
            if (b == null || g.lastSeen > b.lastSeen) best = g
        }
        return best
    }

    fun ghostCount(): Int = ghosts.size

    /**
     * How far (screen fraction) to aim ahead of the enemy at screen offset [relScreen] from the crosshair: its
     * world bearing rate (our own turning already removed) times the loop latency. Small rates are ignored as noise.
     */
    fun leadFrac(relScreen: Float, latencySec: Float, maxFrac: Float): Float {
        val world = norm(yaw + relScreen * fovDeg)
        var best: Ghost? = null
        var bd = 8f
        for (g in ghosts) {
            val d = abs(norm(g.bearing - world))
            if (d < bd) { bd = d; best = g }
        }
        val g = best ?: return 0f
        if (calibUpdates < 80) return 0f                 // own turning not measured well enough yet: a wrong yaw would fake target motion
        if (g.sightings < 8 || abs(g.lateral) < 6f) return 0f
        return (g.lateral * latencySec / fovDeg).coerceIn(-maxFrac, maxFrac)
    }

    /** 0..0.15: how much earlier to retreat after taking many hits per fight. */
    fun cautionBonus(): Float {
        if (engagements < 3) return 0f
        val perFight = hitsTaken.toFloat() / engagements
        return (0.06f * (perFight - 1f)).coerceIn(0f, 0.15f)
    }

    fun dangerAt(worldDeg: Float): Float {
        val s = sectorOf(worldDeg)
        return danger[s] + 0.5f * (danger[(s + 1) % SECTORS] + danger[(s + SECTORS - 1) % SECTORS])
    }

    // ---- behavior when nobody is on screen ---------------------------------------------------------

    /**
     * Memory-driven behavior while no enemy is visible. Returns null to let the caller fall back to its
     * default search. [now] must be the same clock as in [integrate]/[observe].
     */
    fun search(p: Perception, now: Long, style: Style, skill: Skill, tactics: Tactics, aimScale: Float, rnd: Random): Action? {
        if (skill == Skill.EASY) return null
        val a = Action()
        val g = freshestGhost(now)
        val sinceHit = now - lastHitAt

        // 1) took damage from somebody we cannot see: look around to find the attacker
        if (lastHitAt != 0L && sinceHit in 650L..2600L && (g == null || now - g.lastSeen > 2000L)) {
            val phase = ((sinceHit - 650L) / 650L) % 2L
            a.lookDx = (if (phase == 0L) 1f else -1f) * 40f * aimScale
            a.state = "HITSCAN"
            return a
        }

        // 2) an enemy stepped out of view a moment ago: keep tracking where it went
        if (g != null) {
            val age = now - g.lastSeen
            val predicted = norm(g.bearing + (g.lateral.coerceIn(-40f, 40f) * min(age, 2000L) / 1000f).coerceIn(-30f, 30f))
            val rel = relBearing(predicted)
            if (style.avoidFar) {
                val away = norm(rel + 180f)
                a.lookDx = toPx(away).coerceIn(-90f, 90f) * aimScale
                a.moveY = if (abs(away) < 40f) 0.8f else 0.15f
                a.state = "EVADE"
                return a
            }
            if (abs(rel) > 14f) {
                a.lookDx = toPx(rel).coerceIn(-110f, 110f) * aimScale
                a.state = "TRACK"
                return a
            }
            // facing where it went: advance carefully and peek left/right
            a.lookDx = (sin(now / 420.0) * 16.0).toFloat() / max(0.5f, degPerPx / baseDegPerPx.coerceAtLeast(0.001f))
            a.moveY = if (style.stalk || style.ambush) 0.4f else 0.75f
            a.state = "PEEK"
            return a
        }

        // 3) nothing known: explore where we have not been, toward the safe zone, away from danger
        if (now >= headingUntil) pickHeading(now, p, style, rnd)
        var rel = relBearing(heading)
        if (now < glanceUntil) {
            rel += (if (((now / 300L) % 2L) == 0L) 25f else -25f)
        } else if (now >= nextGlanceAt) {
            glanceUntil = now + 900L
            nextGlanceAt = now + 5000L + rnd.nextInt(4000)
        }
        a.lookDx = toPx(rel).coerceIn(-70f, 70f) * aimScale
        a.moveY = if (abs(rel) < 35f) 1f else 0.15f
        if (tactics.jump && now >= nextJumpAt) {
            nextJumpAt = now + 6000L + rnd.nextInt(4000)
            if (nextJumpAt > 0L && abs(rel) < 35f) a.taps.add("jump")
        }
        a.state = "EXPLORE"
        return a
    }

    private fun pickHeading(now: Long, p: Perception, style: Style, rnd: Random) {
        if (headingUntil == 0L) heading = yaw          // first choice: start from where we are facing
        val zoneKnown = !p.zoneAngle.isNaN()
        val zoneWorld = if (zoneKnown) norm(yaw + p.zoneAngle) else 0f
        var best = heading
        var bestScore = -1e9f
        for (i in 0 until SECTORS) {
            val h = i * 30f
            val r = Math.toRadians(h.toDouble())
            var s = 0f
            for (k in 1..3) {
                val px = x + sin(r).toFloat() * CELL_M * k
                val py = y + cos(r).toFloat() * CELL_M * k
                s -= min((visits[key(px, py)] ?: 0f) / 4f, 3f) / k
            }
            val dg = dangerAt(h)
            s += when {
                style.avoidFar -> -1.6f * dg
                style == Style.AGGRESSIVE -> 1.0f * dg
                style == Style.STEALTH -> -0.8f * dg
                else -> -0.4f * dg
            }
            if (zoneKnown) s += 1.4f * cos(Math.toRadians(norm(h - zoneWorld).toDouble())).toFloat()
            s += 0.9f * cos(Math.toRadians(norm(h - heading).toDouble())).toFloat()
            s += (rnd.nextFloat() - 0.5f) * 0.6f
            if (s > bestScore) { bestScore = s; best = h }
        }
        heading = best
        headingUntil = now + 3500L + rnd.nextInt(3500)
    }
}
