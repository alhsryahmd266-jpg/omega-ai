package com.ahmedxg.gamebot

/**
 *   kotlinc Skill.kt Types.kt Tracker.kt MapMath.kt WorldMemory.kt Brain.kt Policy.kt tools/BrainSim.kt -d out
 *   java -cp out:<kotlin-stdlib> com.ahmedxg.gamebot.BrainSimKt
 */
private var failures = 0
private fun check(name: String, ok: Boolean) { println((if (ok) "PASS  " else "FAIL  ") + name); if (!ok) failures++ }
private fun det(cx: Float, cy: Float, w: Float, h: Float, s: Float) = Det(0, "enemy", s, cx, cy, w, h)
private class Fixed(val out: FloatArray) : BrainModel { override fun run(x: FloatArray) = out }
private fun near(a: Float, b: Float) = Math.abs(a - b) < 1e-5f

fun main() {
    // 1) the feature vector is bit-for-bit what the Python trainer builds (golden values from brain_lib.enemy_features)
    run {
        val r = BrainRunner(Fixed(floatArrayOf(0.1f, -0.2f, 0.3f, -0.4f)))
        r.step(emptyList(), 1000L)                                    // sets the previous output
        val dets = listOf(det(0.7f, 0.4f, 0.03f, 0.12f, 0.8f), det(0.45f, 0.55f, 0.05f, 0.2f, 0.6f),
                          det(0.2f, 0.3f, 0.01f, 0.05f, 0.4f), det(0.9f, 0.9f, 0.02f, 0.07f, 0.3f))
        val f = r.features(dets, 1100L)
        val golden = floatArrayOf(1f, -0.1f, 0.1f, 0.4f, 0.8f, 0.6f,  1f, 0.4f, -0.2f, 0.24f, 0.48f, 0.8f,  1f, -0.6f, -0.4f, 0.08f, 0.2f, 0.4f,
                                  0f, 0.8f,  0.1f, -0.2f, 0.3f, -0.4f)
        check("features: 24 numbers", f.size == 24)
        check("features: match the Python reference exactly", f.indices.all { near(f[it], golden[it]) })
        val r2 = BrainRunner(Fixed(floatArrayOf(0f, 0f, 0f, 0f)))
        r2.features(listOf(det(0.5f, 0.5f, 0.05f, 0.2f, 0.9f)), 1000L)
        val g = r2.features(emptyList(), 1300L)
        check("features: no enemy -> zeros, since_seen = 0.3 s / 10", g.indices.all { it == 18 || near(g[it], 0f) } && near(g[18], 0.03f))
        val h = BrainRunner(Fixed(floatArrayOf(0f, 0f, 0f, 0f))).features(emptyList(), 5000L)
        check("features: never seen an enemy -> since_seen saturates at 1.0", near(h[18], 1f))
    }

    // 2) the brain's output is bounded and bad models are ignored
    run {
        val r = BrainRunner(Fixed(floatArrayOf(5f, -7f, 0.5f, 0f)))
        val o = r.step(emptyList(), 100L)!!
        check("step: outputs clamped to -1..1", near(o.moveX, 1f) && near(o.moveY, -1f) && near(o.yawRate, 0.5f))
        val bad = BrainRunner(object : BrainModel { override fun run(x: FloatArray): FloatArray = throw IllegalStateException("boom") })
        check("step: a model that throws is ignored", bad.step(emptyList(), 100L) == null && bad.failures == 1)
        val nan = BrainRunner(Fixed(floatArrayOf(Float.NaN, 0f, 0f, 0f)))
        check("step: NaN output is rejected", nan.step(emptyList(), 100L) == null)
    }

    // 3) blending: searching with the brain pulls movement toward the brain, the camera follows its yaw
    run {
        fun explore(blend: Float, brainOut: FloatArray?): Pair<Float, Float> {
            val t = Tactics(memory = true, brainBlend = blend, jump = false)
            val pol = TacticalPolicy(Skill.PRO, 1f, 0.5f, 0.5f, t, brainOut?.let { BrainRunner(Fixed(it)) })
            var now = 100_000L; var my = 0f; var n = 0; var yaw0 = 0f; var yaw1 = 0f
            repeat(120) {
                val a = pol.decide(Perception(now, emptyList(), 1920, 1080), now); now += 33
                if (it == 10) yaw0 = pol.worldMemory!!.yaw
                if (it == 119) yaw1 = pol.worldMemory!!.yaw
                if (it >= 30 && a.state == "EXPLORE") { my += a.moveY; n++ }
            }
            return Pair(my / n, yaw1 - yaw0)
        }
        val base = explore(0f, null)
        val half = explore(0.5f, floatArrayOf(0f, -1f, 0.8f, 0f))          // brain says: walk back and turn right
        check("blend: rules walk forward when searching (" + "%.2f".format(base.first) + ")", base.first > 0.5f)
        check("blend 0.5: forward walking is cut by the brain (" + "%.2f".format(half.first) + ")", half.first < base.first - 0.3f)
        val zero = explore(0f, floatArrayOf(0f, -1f, 0.8f, 0f))
        check("blend 0: the brain has no effect", Math.abs(zero.first - base.first) < 0.15f && Math.abs(zero.second - base.second) < 5f)
        check("blend 0.5: the brain's yaw turns the camera right while searching (" + "%.0f".format(half.second) + " deg vs " + "%.0f".format(base.second) + ")", half.second > base.second + 20f)
    }

    // 4) the rules keep the last word when it is dangerous: low health with an enemy in sight -> still backs away
    run {
        val t = Tactics(memory = true, brainBlend = 1f)
        val pol = TacticalPolicy(Skill.PRO, 1f, 0.5f, 0.5f, t, BrainRunner(Fixed(floatArrayOf(0f, 1f, 0f, 0f))))   // brain insists: run forward
        var now = 100_000L
        val states = ArrayList<Pair<String, Float>>()
        repeat(90) {
            val a = pol.decide(Perception(now, listOf(det(0.5f, 0.5f, 0.08f, 0.22f, 0.9f)), 1920, 1080, hit = false, health = 0.12f), now); now += 33
            states.add(Pair(a.state, a.moveY))
        }
        val safe = states.filter { it.first in setOf("RETREAT", "AVOID", "HEAL") }
        check("safety: low health -> retreat/avoid/heal states appear", safe.isNotEmpty())
        check("safety: the brain cannot push a retreating bot forward", safe.all { it.second <= 0.05f })
    }

    println(if (failures == 0) "\nALL BRAIN CHECKS PASSED" else "\n$failures BRAIN CHECK(S) FAILED")
    if (failures > 0) System.exit(1)
}
