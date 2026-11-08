package com.ahmedxg.gamebot

/**
 * Offline logic check of the bot policies on a plain JVM (no Android needed):
 *   kotlinc Skill.kt Types.kt Tracker.kt Policy.kt tools/PolicySim.kt -d out && java -cp out:<kotlin-stdlib> com.ahmedxg.gamebot.PolicySimKt
 */
private var failures = 0

private fun check(name: String, ok: Boolean) {
    println((if (ok) "PASS  " else "FAIL  ") + name)
    if (!ok) failures++
}

private fun enemy(cx: Float, cy: Float, h: Float) = Det(0, "enemy", 0.9f, cx, cy, h * 0.45f, h)
private fun ally(cx: Float, cy: Float, h: Float) = Det(2, "ally", 0.9f, cx, cy, h * 0.45f, h)

private class Run(val skill: Skill, val tactics: Tactics = Tactics()) {
    val pol = TacticalPolicy(skill, 1f, 0.5f, 0.5f, tactics)
    var now = 100_000L
    val log = ArrayList<Pair<Long, Action>>()
    var t0 = now

    fun step(dets: List<Det>, hit: Boolean = false, hp: Float = -1f): Action {
        val a = pol.decide(Perception(now, dets, 1920, 1080, hit, hp), now)
        log.add(Pair(now - t0, a))
        now += 33
        return a
    }

    fun states(): List<String> = log.map { it.second.state }
    fun first(state: String): Long = log.firstOrNull { it.second.state == state }?.first ?: -1L
}

fun main() {
    // 1) nothing around: explore, never shoot
    run {
        val r = Run(Skill.NORMAL)
        repeat(60) { r.step(emptyList()) }
        check("search: only SEARCH state", r.states().all { it == "SEARCH" })
        check("search: never fires", r.log.none { it.second.fire })
    }

    // 2) far enemy to the right, NORMAL skill: react, then stalk (no shooting), turn right, walk forward
    run {
        val r = Run(Skill.NORMAL)
        repeat(60) { r.step(listOf(enemy(0.62f, 0.5f, 0.10f))) }
        check("stalk: starts with REACT", r.states().first() == "REACT")
        check("stalk: reaches STALK after the reaction delay", r.first("STALK") in 200L..400L)
        check("stalk: does not fire while stalking", r.log.filter { it.second.state == "STALK" }.none { it.second.fire })
        check("stalk: moves forward", r.log.filter { it.second.state == "STALK" }.all { it.second.moveY > 0.4f })
        check("stalk: turns toward the enemy (right)", r.log.filter { it.second.state == "STALK" }.all { it.second.lookDx > 0f })
    }

    // 3) enemy gets close while stalking -> strike with a long burst, then duel
    run {
        val r = Run(Skill.PRO)
        repeat(30) { r.step(listOf(enemy(0.5f, 0.5f, 0.10f))) }
        repeat(60) { r.step(listOf(enemy(0.5f, 0.5f, 0.32f))) }
        check("strike: STALK first", r.states().contains("STALK"))
        check("strike: switches to STRIKE when close", r.states().contains("STRIKE"))
        check("strike: fires when aligned", r.log.any { it.second.state == "STRIKE" && it.second.fire })
        val i = r.states().indexOf("STRIKE")
        check("strike: ends in duel/fire phase", r.states().drop(i).any { it == "FIRE" || it == "DUEL" })
    }

    // 4) taking damage in a duel -> dodge for ~650ms (hard strafe + a jump), then back to the fight
    run {
        val r = Run(Skill.NORMAL)
        repeat(30) { r.step(listOf(enemy(0.5f, 0.5f, 0.35f))) }
        val tHit = r.now - r.t0
        repeat(3) { r.step(listOf(enemy(0.5f, 0.5f, 0.35f)), hit = true) }
        repeat(40) { r.step(listOf(enemy(0.5f, 0.5f, 0.35f))) }
        val dodge = r.log.filter { it.second.state == "DODGE" }
        check("dodge: triggered once by a hit", dodge.isNotEmpty())
        check("dodge: lasts about 650ms", dodge.size in 17..22)
        check("dodge: full-speed strafe", dodge.all { Math.abs(it.second.moveX) >= 0.99f })
        check("dodge: jumps", dodge.any { it.second.taps.contains("jump") })
        check("dodge: back to fighting afterwards", r.log.last().second.state in setOf("FIRE", "DUEL", "STRIKE"))
        check("dodge: starts right after the hit", r.first("DODGE") >= tHit - 1)
    }

    // 5) lose a close enemy -> hold the angle (ambush), then resume searching; re-detect = instant engage
    run {
        val r = Run(Skill.NORMAL)
        repeat(30) { r.step(listOf(enemy(0.55f, 0.5f, 0.30f))) }
        repeat(90) { r.step(emptyList()) }
        val amb = r.log.filter { it.second.state == "AMBUSH" }
        check("ambush: holds the angle after losing a close enemy", amb.size > 20)
        check("ambush: stands still and does not shoot", amb.all { it.second.moveX == 0f && it.second.moveY == 0f && !it.second.fire })
        check("ambush: ends and resumes searching", r.states().last() == "SEARCH")

        val r2 = Run(Skill.NORMAL)
        repeat(30) { r2.step(listOf(enemy(0.55f, 0.5f, 0.30f))) }
        repeat(20) { r2.step(emptyList()) }   // now ambushing
        val back = r2.step(listOf(enemy(0.5f, 0.5f, 0.30f)))
        check("ambush: re-detection skips the reaction delay", back.state != "REACT")
    }

    // 6) skill differences
    run {
        val easy = Run(Skill.EASY)
        repeat(40) { easy.step(listOf(enemy(0.6f, 0.5f, 0.10f))) }
        check("easy: no sneaking (sneak=false)", !easy.states().contains("STALK"))
        val hitEasy = Run(Skill.EASY)
        repeat(10) { hitEasy.step(listOf(enemy(0.5f, 0.5f, 0.35f))) }
        repeat(3) { hitEasy.step(listOf(enemy(0.5f, 0.5f, 0.35f)), hit = true) }
        check("easy: no dodge", !hitEasy.states().contains("DODGE"))
        val max = Run(Skill.MAX)
        val a = max.step(listOf(enemy(0.5f, 0.5f, 0.35f)))
        check("max: zero reaction time", a.state != "REACT")
    }

    // 7) switches really switch things off
    run {
        val r = Run(Skill.PRO, Tactics(sneak = false, ambush = false, dodge = false, jump = false, crouch = false))
        repeat(30) { r.step(listOf(enemy(0.6f, 0.5f, 0.10f))) }
        repeat(4) { r.step(listOf(enemy(0.5f, 0.5f, 0.35f)), hit = true) }
        repeat(60) { r.step(emptyList()) }
        check("switches: no STALK / DODGE / AMBUSH when disabled", r.states().none { it == "STALK" || it == "DODGE" || it == "AMBUSH" })
        check("switches: no jump taps when disabled", r.log.none { it.second.taps.contains("jump") && it.second.state != "SEARCH" })
    }

    // 8) friendly fire guard: an ally in front of the crosshair -> never fire
    run {
        val r = Run(Skill.PRO)
        repeat(60) { r.step(listOf(enemy(0.5f, 0.5f, 0.35f), ally(0.5f, 0.5f, 0.4f))) }
        check("ally: never fires with an ally covering the aim point", r.log.none { it.second.fire })
        val r2 = Run(Skill.PRO)
        repeat(60) { r2.step(listOf(enemy(0.5f, 0.5f, 0.35f), ally(0.9f, 0.5f, 0.4f))) }
        check("ally: fires when the ally is elsewhere", r2.log.any { it.second.fire })
    }

    // 9) health: retreat when low, heal when nobody is around, no healing at full health
    run {
        val r = Run(Skill.NORMAL)
        repeat(30) { r.step(listOf(enemy(0.5f, 0.5f, 0.35f)), hp = 0.15f) }
        val ret = r.log.filter { it.second.state == "RETREAT" }
        check("health: retreats when low and an enemy is close", ret.size > 10)
        check("health: retreat moves backward", ret.all { it.second.moveY < -0.5f })
        val r2 = Run(Skill.NORMAL)
        repeat(40) { r2.step(emptyList(), hp = 0.3f) }
        check("health: heals when alone and hurt", r2.log.any { it.second.taps.contains("heal") })
        check("health: stands still while healing", r2.log.filter { it.second.state == "HEAL" }.all { it.second.moveX == 0f && it.second.moveY == 0f })
        val r3 = Run(Skill.NORMAL)
        repeat(60) { r3.step(emptyList(), hp = 0.95f) }
        check("health: no healing at high health", r3.log.none { it.second.taps.contains("heal") })
    }

    // 10) match plans
    run {
        val agg = Run(Skill.PRO, Tactics(style = Style.AGGRESSIVE))
        repeat(40) { agg.step(listOf(enemy(0.6f, 0.5f, 0.10f))) }
        check("plan aggressive: never stalks", !agg.states().contains("STALK"))
        val sur = Run(Skill.PRO, Tactics(style = Style.SURVIVOR))
        repeat(40) { sur.step(listOf(enemy(0.6f, 0.5f, 0.10f))) }
        check("plan survivor: avoids far unaware enemies", sur.states().contains("AVOID") && !sur.log.any { it.second.fire })
        val sur2 = Run(Skill.PRO, Tactics(style = Style.SURVIVOR))
        repeat(20) { sur2.step(listOf(enemy(0.5f, 0.5f, 0.33f))) }
        check("plan survivor: still fights close enemies", sur2.log.any { it.second.fire })
        val ste = Run(Skill.PRO, Tactics(style = Style.STEALTH))
        repeat(40) { ste.step(listOf(enemy(0.6f, 0.5f, 0.10f))) }
        check("plan stealth: stalks", ste.states().contains("STALK"))
    }

    // 11) YOLO11 raw and YOLO26 end-to-end decoding
    run {
        val inW = 320; val inH = 320
        val srcW = 480; val srcH = 270
        val scale = Math.min(inW.toFloat() / srcW, inH.toFloat() / srcH)
        val padX = (inW - srcW * scale) / 2f; val padY = (inH - srcH * scale) / 2f
        val labels = listOf("enemy", "enemy_head", "ally")

        // YOLO11 raw, channels-first [1, 4+3, 100], normalized xywh
        val n = 100; val ch = 7
        val raw = FloatArray(ch * n)
        fun put(idx: Int, cx: Float, cy: Float, w: Float, h: Float, cls: Int, s: Float) {
            raw[0 * n + idx] = cx; raw[1 * n + idx] = cy; raw[2 * n + idx] = w; raw[3 * n + idx] = h; raw[(4 + cls) * n + idx] = s
        }
        put(7, 0.5f, 0.5f, 0.2f, 0.4f, 0, 0.9f)
        put(9, 0.51f, 0.5f, 0.2f, 0.4f, 0, 0.6f)    // duplicate -> removed by NMS
        put(20, 0.25f, 0.5f, 0.1f, 0.2f, 2, 0.8f)   // ally elsewhere
        val d1 = YoloDecode.decode(raw, intArrayOf(1, ch, n), inW, inH, scale, padX, padY, srcW, srcH, labels, 0.35f, 0.5f)
        check("yolo11: 2 detections after NMS", d1.size == 2)
        check("yolo11: best box center mapped back to the frame", d1.isNotEmpty() && Math.abs(d1[0].cx - 0.5f) < 0.01f && Math.abs(d1[0].cy - 0.5f) < 0.01f)
        check("yolo11: letterbox removed from the height", d1.isNotEmpty() && Math.abs(d1[0].h - 0.711f) < 0.01f)
        check("yolo11: labels mapped", d1.map { it.label }.toSet() == setOf("enemy", "ally"))

        // YOLO26 end-to-end [1, 300, 6] in model-input pixels
        val e2e = FloatArray(300 * 6)
        e2e[0] = 120f; e2e[1] = 100f; e2e[2] = 200f; e2e[3] = 220f; e2e[4] = 0.88f; e2e[5] = 0f      // box centered at (160,160)
        e2e[6] = 10f; e2e[7] = 10f; e2e[8] = 30f; e2e[9] = 30f; e2e[10] = 0.10f; e2e[11] = 1f       // below threshold
        val d2 = YoloDecode.decode(e2e, intArrayOf(1, 300, 6), inW, inH, scale, padX, padY, srcW, srcH, labels, 0.35f, 0.5f)
        check("yolo26: detected as end-to-end", YoloDecode.isEnd2End(intArrayOf(1, 300, 6)) && !YoloDecode.isEnd2End(intArrayOf(1, 6, 3549)))
        check("yolo26: one detection above the threshold", d2.size == 1)
        check("yolo26: pixel xyxy converted back to the frame", d2.isNotEmpty() && Math.abs(d2[0].cx - 0.5f) < 0.01f && Math.abs(d2[0].cy - 0.5f) < 0.01f)

        // YOLO26 end-to-end with normalized coordinates
        val e2n = FloatArray(300 * 6)
        e2n[0] = 0.375f; e2n[1] = 0.3125f; e2n[2] = 0.625f; e2n[3] = 0.6875f; e2n[4] = 0.9f; e2n[5] = 1f
        val d3 = YoloDecode.decode(e2n, intArrayOf(1, 300, 6), inW, inH, scale, padX, padY, srcW, srcH, labels, 0.35f, 0.5f)
        check("yolo26: normalized coordinates handled", d3.size == 1 && d3[0].label == "enemy_head" && Math.abs(d3[0].cx - 0.5f) < 0.01f)
    }

    println(if (failures == 0) "\nALL POLICY CHECKS PASSED" else "\n$failures CHECK(S) FAILED")
    if (failures != 0) System.exit(1)
}
