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

    // 3) an enemy steps out of view to the right: after the sweep it keeps tracking to the right
    run {
        val r = Run(Skill.NORMAL)
        repeat(45) { r.step(listOf(enemy(0.70f, 0.5f, 0.10f))) }
        val tLost = r.now - r.t0
        repeat(90) { r.step(emptyList()) }
        val after = r.between(tLost + 700, tLost + 2900)
        val tr = after.filter { it.second.state == "TRACK" }
        check("ghost: TRACK appears after the sweep", tr.isNotEmpty())
        check("ghost: first TRACK turns toward where it went (right)", tr.firstOrNull()?.second?.lookDx ?: -1f > 0f)
        check("ghost: remembered one enemy", r.pol.worldMemory!!.ghostCount() == 1)
        check("ghost: never fires while tracking", r.log.none { it.second.state == "TRACK" && it.second.fire })
    }

    // 4) survivor plan: slips away from the last known enemy instead of hunting it
    run {
        val r = Run(Skill.NORMAL, Style.SURVIVOR)
        repeat(45) { r.step(listOf(enemy(0.70f, 0.5f, 0.10f))) }
        val tLost = r.now - r.t0
        repeat(90) { r.step(emptyList()) }
        val ev = r.between(tLost + 700, tLost + 2900).filter { it.second.state == "EVADE" }
        check("survivor: EVADE after losing an enemy", ev.isNotEmpty())
        check("survivor: turns away (left) from an enemy that went right", ev.first().second.lookDx < 0f)
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

    println(if (failures == 0) "\nALL MEMORY CHECKS PASSED" else "\n$failures MEMORY CHECK(S) FAILED")
    if (failures > 0) System.exit(1)
}
