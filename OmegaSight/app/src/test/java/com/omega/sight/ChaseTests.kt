package com.omega.sight

import com.omega.sight.core.ChaseGuard

/** The safety net against a chase that never ends. */
object ChaseTests {
    private const val DT = 0.05f

    /** runs [seconds] of frames, returns the number of frames in which the guard tripped */
    private fun run(g: ChaseGuard, seconds: Float, chasing: Boolean, onTarget: Boolean): Int {
        var trips = 0
        var t = 0f
        while (t < seconds) {
            if (g.update(chasing, onTarget, DT)) trips++
            t += DT
        }
        return trips
    }

    fun run() {
        run {
            val g = ChaseGuard()
            T.check("a chase of two seconds is left alone", run(g, 2.0f, chasing = true, onTarget = false) == 0 && !g.holding)
        }
        run {
            val g = ChaseGuard()
            val n = run(g, 3.2f, chasing = true, onTarget = false)
            T.check("a chase of more than three seconds is stopped once", n == 1 && g.holding && g.trips == 1, { "$n ${g.holding}" })
        }
        run {
            val g = ChaseGuard()
            run(g, 3.2f, chasing = true, onTarget = false)
            // while holding, even a "chase" changes nothing and does not trip again
            T.check("nothing trips during the hold", run(g, 1.5f, chasing = true, onTarget = false) == 0 && g.holding)
            run(g, 0.6f, chasing = false, onTarget = false)
            T.check("the hold ends after about two seconds", !g.holding)
        }
        run {
            val g = ChaseGuard()
            // on target for a moment every second: the chase never gets long
            var tripped = 0
            for (round in 0 until 12) {
                tripped += run(g, 0.9f, chasing = true, onTarget = false)
                tripped += run(g, 0.1f, chasing = true, onTarget = true)
            }
            T.check("a chase that reaches the target now and then is never stopped", tripped == 0 && !g.holding)
        }
        run {
            val g = ChaseGuard()
            // two seconds of chase, a pause of a second, two more seconds: each counts on its own
            var tripped = run(g, 2.0f, chasing = true, onTarget = false)
            tripped += run(g, 1.0f, chasing = false, onTarget = false)
            tripped += run(g, 2.0f, chasing = true, onTarget = false)
            T.check("a pause ends the chase", tripped == 0)
        }
        run {
            val g = ChaseGuard()
            // a gap shorter than half a second does not end it
            var tripped = run(g, 2.0f, chasing = true, onTarget = false)
            tripped += run(g, 0.3f, chasing = false, onTarget = false)
            tripped += run(g, 1.5f, chasing = true, onTarget = false)
            T.check("a short gap does not end the chase", tripped == 1)
        }
        run {
            val g = ChaseGuard()
            var tripped = 0
            tripped += run(g, 3.2f, chasing = true, onTarget = false)
            tripped += run(g, 2.2f, chasing = false, onTarget = false)
            tripped += run(g, 3.2f, chasing = true, onTarget = false)
            T.check("after the hold a new chase can be stopped again", tripped == 2 && g.trips == 2, { "$tripped ${g.trips}" })
        }
        run {
            val g = ChaseGuard()
            run(g, 3.2f, chasing = true, onTarget = false)
            g.reset()
            T.check("reset clears the hold", !g.holding)
        }
        T.check("not chasing at all never trips", run(ChaseGuard(), 30f, chasing = false, onTarget = false) == 0)
        T.check("sitting on the target never trips", run(ChaseGuard(), 30f, chasing = true, onTarget = true) == 0)
    }
}
