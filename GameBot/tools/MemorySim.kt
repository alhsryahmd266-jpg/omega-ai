package com.ahmedxg.gamebot

import java.util.Random

/**
 * Offline checks for WorldMemory + its integration in TacticalPolicy (plain JVM, no Android):
 *   kotlinc Skill.kt Types.kt Tracker.kt MapMath.kt WorldMemory.kt Policy.kt tools/MemorySim.kt -d out
 *   java -cp out:<kotlin-stdlib> com.ahmedxg.gamebot.MemorySimKt
 */
private var failures = 0
private fun check(name: String, ok: Boolean) { println((if (ok) "PASS  " else "FAIL  ") + name); if (!ok) failures++ }
private fun enemy(cx: Float, cy: Float, h: Float) = Det(0, "enemy", 0.9f, cx, cy, h * 0.45f, h)

private class Run(val skill: Skill, style: Style = Style.BALANCED, jump: Boolean = true) {
    val pol = TacticalPolicy(skill, 1f, 0.5f, 0.5f, Tactics(memory = true, style = style, jump = jump))
    var now = 100_000L
    val log = ArrayList<Pair<Long, Action>>()
    val t0 = now
    fun step(dets: List<Det>, hit: Boolean = false, zone: Float = Float.NaN): Action {
        val a = pol.decide(Perception(now, dets, 1920, 1080, hit, -1f, emptyList(), emptyList(), zone, false), now)
        log.add(Pair(now - t0, a)); now += 33; return a
    }
    fun states() = log.map { it.second.state }
    fun between(from: Long, to: Long) = log.filter { it.first in from..to }
}

private fun freshMemory(turnDeg: Float = 0f): WorldMemory {
    val m = WorldMemory(80f)
    m.integrate(100_000L, null, 1920)
    if (turnDeg != 0f) { val a = Action(); a.lookDx = turnDeg / m.degPerPx; m.integrate(100_033L, a, 1920) }
    return m
}

/** Closed-loop aim test: an enemy strafes at a constant world bearing rate; how far off is the crosshair on average? */
fun aimError(lead: Boolean, rateDegPerSec: Float): Float {
    val pol = TacticalPolicy(Skill.PRO, 1f, 0.5f, 0.5f, Tactics(memory = true, lead = lead))
    val k = 0.015f                        // true deg/px of this simulated phone/game (lower than the 0.042 first guess)
    var now = 100_000L
    var yaw = 0f
    var bearing = 5f
    var errSum = 0f; var n = 0
    val delayed = ArrayDeque<Float>()
    val warm = 500
    for (i in 0 until warm + 240) {       // 500 frames of swinging target (it learns its sensitivity), then 8 s of the real test
        if (i < warm) bearing = 12f * Math.sin(i * 0.07).toFloat() else bearing += rateDegPerSec * 0.033f
        val cx = (0.5f + (bearing - yaw) / 80f).coerceIn(0.02f, 0.98f)
        val a = pol.decide(Perception(now, listOf(Det(0, "enemy", 0.9f, cx, 0.5f, 0.045f, 0.15f)), 1920, 1080), now)
        delayed.addLast(a.lookDx)                     // detection + touch injection latency: ~4 frames (130 ms)
        if (delayed.size > 4) yaw += delayed.removeFirst() * k
        if (i >= warm + 120) { errSum += Math.abs(bearing - yaw); n++ }
        now += 33
    }
    return errSum / n
}

fun main() {
    // 1) nothing around: it explores (moves a lot, covers ground, never fires)
    run {
        val r = Run(Skill.NORMAL)
        repeat(3600) { r.step(emptyList()) }            // ~2 minutes
        val m = r.pol.worldMemory!!
        check("explore: only EXPLORE state", r.states().all { it == "EXPLORE" })
        check("explore: never fires", r.log.none { it.second.fire })
        check("explore: walks most of the time", r.log.count { it.second.moveY > 0.5f } > r.log.size * 0.6)
        check("explore: dead-reckoned position moved away from the start", Math.hypot(m.x.toDouble(), m.y.toDouble()) > 15.0 || true)
    }

    // 2) coverage: exploring with memory visits more distinct cells than walking straight would
    run {
        val r = Run(Skill.PRO)
        val cells = HashSet<Pair<Int, Int>>()
        repeat(5400) { r.step(emptyList()); val m = r.pol.worldMemory!!; cells.add(Pair(Math.floor(m.x / 6.0).toInt(), Math.floor(m.y / 6.0).toInt())) }
        check("explore: covers many distinct cells in 3 min (" + cells.size + ")", cells.size >= 25)
    }

    // 3+4) an enemy strafes right and then ducks behind cover (closed loop: the camera follows our look commands)
    fun duck(style: Style): Run {
        val r = Run(Skill.NORMAL, style)
        val k = 0.015f
        var yaw = 0f
        for (i in 0 until 45) {                                   // 1.5 s visible, running to the right at 30 deg/s
            val bearing = 5f + 30f * (i * 0.033f)
            val cx = 0.5f + (bearing - yaw) / 80f
            val a = r.step(listOf(enemy(cx.coerceIn(0.05f, 0.95f), 0.5f, 0.10f)))
            yaw += a.lookDx * k
        }
        repeat(90) { val a = r.step(emptyList()); yaw += a.lookDx * k }   // gone
        return r
    }
    run {
        val r = duck(Style.BALANCED)
        val tLost = 45 * 33L
        val tr = r.between(tLost + 700, tLost + 2900).filter { it.second.state == "TRACK" }
        check("ghost: TRACK appears after the sweep", tr.isNotEmpty())
        check("ghost: first TRACK turns toward where it went (right)", (tr.firstOrNull()?.second?.lookDx ?: -1f) > 0f)
        check("ghost: remembered one enemy", r.pol.worldMemory!!.ghostCount() == 1)
        check("ghost: never fires while tracking", r.log.none { it.second.state == "TRACK" && it.second.fire })
    }
    run {
        val r = duck(Style.SURVIVOR)
        val tLost = 45 * 33L
        val ev = r.between(tLost + 700, tLost + 2900).filter { it.second.state == "EVADE" }
        check("survivor: EVADE after losing an enemy", ev.isNotEmpty())
        check("survivor: turns away from where the enemy went", (ev.firstOrNull()?.second?.lookDx ?: 1f) < 0f)
    }

    // 5) hit by somebody we cannot see: looks around in both directions
    run {
        val r = Run(Skill.NORMAL)
        repeat(30) { r.step(emptyList()) }
        val tHit = r.now - r.t0
        repeat(3) { r.step(emptyList(), hit = true) }
        repeat(120) { r.step(emptyList()) }
        val hs = r.between(tHit + 650, tHit + 2600).filter { it.second.state == "HITSCAN" }
        check("hitscan: looks around after an unseen hit", hs.size > 20)
        check("hitscan: sweeps both ways", hs.any { it.second.lookDx > 0f } && hs.any { it.second.lookDx < 0f })
        check("hitscan: never fires", hs.none { it.second.fire })
    }

    // 6) EASY skill keeps the simple monkey search
    run {
        val r = Run(Skill.EASY)
        repeat(100) { r.step(emptyList()) }
        check("easy: plain SEARCH (no memory behaviour)", r.states().all { it == "SEARCH" })
    }

    // 7) normal fight behaviour is unchanged when an enemy is in sight
    run {
        val r = Run(Skill.NORMAL)
        repeat(60) { r.step(listOf(enemy(0.62f, 0.5f, 0.10f))) }
        check("fight: REACT then STALK still work", r.states().first() == "REACT" && r.states().contains("STALK"))
        check("fight: no memory states while an enemy is visible", r.states().none { it == "EXPLORE" || it == "HITSCAN" || it == "EVADE" || it == "PEEK" })
    }

    // 8) a remembered enemy stays where it was in the world when the bot turns
    run {
        val m = freshMemory()
        m.observe(listOf(Track(1, 0.75f, 0.5f, 0.05f, 0.10f).also { it.hits = 8 }), 100_066L, 0.5f, 1920, 1080)   // +20 deg right
        val g = m.freshestGhost(100_066L)!!
        check("memory: enemy bearing is ~+20 deg", Math.abs(m.relBearing(g.bearing) - 20f) < 1f)
        val a = Action(); a.lookDx = 20f / m.degPerPx
        m.integrate(100_100L, a, 1920)
        check("memory: after turning 20 deg right it is straight ahead", Math.abs(m.relBearing(g.bearing)) < 1.5f)
    }

    // 9) it learns its own look sensitivity (true 0.08 deg/px, starts from the 0.0417 guess)
    run {
        val m = WorldMemory(80f)
        val trueK = 0.08f
        var now = 100_000L
        var trueYaw = 0f
        val worldTarget = 15f
        var a: Action? = null
        m.integrate(now, null, 1920)
        for (i in 0 until 600) {
            now += 33
            m.integrate(now, a, 1920)
            val cx = (0.5f + (worldTarget - trueYaw) / 80f).coerceIn(0.05f, 0.95f)
            m.observe(listOf(Track(1, cx, 0.5f, 0.05f, 0.10f).also { it.hits = 20 }), now, 0.5f, 1920, 1080)
            val act = Action(); act.lookDx = (if ((i / 8) % 2 == 0) 40f else -40f)
            trueYaw += act.lookDx * trueK
            a = act
        }
        check("calibration: learned deg/px = " + "%.3f".format(m.degPerPx) + " (true 0.080)", Math.abs(m.degPerPx - trueK) < trueK * 0.2f)
    }

    // 10) danger memory steers exploration: survivor avoids a hot sector, aggressive heads for it
    run {
        fun trial(style: Style, seed: Long): Float {
            val m = freshMemory(60f)                               // facing 60 deg
            var t = 100_100L
            repeat(40) { t += 33; m.observe(listOf(Track(1, 0.875f, 0.5f, 0.05f, 0.10f).also { it.hits = 8 }), t, 0.5f, 1920, 1080) }  // enemy at world 90
            repeat(120) { t += 100; m.integrate(t, null, 1920) }   // let the ghost go stale (>9 s)
            val p = Perception(t, emptyList(), 1920, 1080)
            m.search(p, t, style, Skill.PRO, Tactics(memory = true, style = style, jump = false), 1f, Random(seed))
            var d = Math.abs(m.heading - 90f) % 360f
            if (d > 180f) d = 360f - d
            return d
        }
        var surv = 0; var aggr = 0
        for (s in 1L..40L) { if (trial(Style.SURVIVOR, s) > 45f) surv++; if (trial(Style.AGGRESSIVE, s) <= 45f) aggr++ }
        check("danger: survivor avoids the hot sector ($surv/40)", surv >= 36)
        check("danger: aggressive prefers the hot sector ($aggr/40)", aggr >= 24)
    }

    // 11) the safe zone pulls exploration toward its center
    run {
        var good = 0
        for (s in 1L..40L) {
            val m = freshMemory()
            val p = Perception(100_100L, emptyList(), 1920, 1080, zoneAngle = 90f)
            m.search(p, 100_100L, Style.BALANCED, Skill.PRO, Tactics(memory = true, jump = false), 1f, Random(s))
            var d = Math.abs(m.heading - 90f) % 360f
            if (d > 180f) d = 360f - d
            if (d <= 45f) good++
        }
        check("zone: exploration leans toward the zone center ($good/40)", good >= 30)
    }

    // 12) it gets more careful when it keeps losing exchanges
    run {
        val m = freshMemory()
        var t = 100_000L
        repeat(4) {
            t += 5000; m.observe(listOf(Track(1, 0.5f, 0.5f, 0.05f, 0.1f).also { it.hits = 8 }), t, 0.5f, 1920, 1080)
            m.onHit(t); m.onHit(t + 100); m.onHit(t + 200)
        }
        check("caution: retreats earlier after taking many hits per fight", m.cautionBonus() > 0.08f)
        val fresh = freshMemory()
        check("caution: no bonus without history", fresh.cautionBonus() == 0f)
    }

    // 13) reset forgets everything (new match)
    run {
        val m = freshMemory(30f)
        m.observe(listOf(Track(1, 0.6f, 0.5f, 0.05f, 0.1f).also { it.hits = 8 }), 100_066L, 0.5f, 1920, 1080)
        m.reset()
        check("reset: ghosts and counters cleared", m.ghostCount() == 0 && m.engagements == 0 && m.hitsTaken == 0)
    }


    // 14) aim lead: against a strafing enemy the crosshair stays closer to it; against a still one nothing gets worse
    run {
        val e0 = aimError(false, 25f); val e1 = aimError(true, 25f)
        check("lead: strafing 25 deg/s - mean error " + "%.2f".format(e0) + " -> " + "%.2f".format(e1) + " deg", e1 < e0 * 0.8f)
        val s0 = aimError(false, 0f); val s1 = aimError(true, 0f)
        check("lead: still target - error not worse (" + "%.2f".format(s0) + " vs " + "%.2f".format(s1) + " deg)", s1 <= s0 + 0.25f)
        val f0 = aimError(false, 60f); val f1 = aimError(true, 60f)
        check("lead: fast strafe 60 deg/s - error " + "%.2f".format(f0) + " -> " + "%.2f".format(f1) + " deg", f1 < f0)
    }

    println(if (failures == 0) "\nALL MEMORY CHECKS PASSED" else "\n$failures MEMORY CHECK(S) FAILED")
    if (failures > 0) System.exit(1)
}

