package com.ahmedxg.gamebot

/**
 *   kotlinc Types.kt ZoomDetect.kt tools/ZoomSim.kt -d out && java -cp out:<kotlin-stdlib> com.ahmedxg.gamebot.ZoomSimKt
 */
private var failures = 0
private fun check(name: String, ok: Boolean) { println((if (ok) "PASS  " else "FAIL  ") + name); if (!ok) failures++ }
private fun det(cx: Float, cy: Float, w: Float, h: Float, s: Float = 0.8f) = Det(0, "enemy", s, cx, cy, w, h)

fun main() {
    val fw = 1600; val fh = 720
    val c = ZoomDetect.window(fw, fh, 0.5f, 0.5f)
    check("window: 50% x 60% of the frame, centered", c.w == 800 && c.h == 432 && c.x == 400 && c.y == 144)
    val edge = ZoomDetect.window(fw, fh, 0.98f, 0.02f)
    check("window: kept inside the frame at the corner", edge.x + edge.w <= fw && edge.y >= 0 && edge.x >= 0 && edge.y + edge.h <= fh)

    val wins = ZoomDetect.windows(fw, fh, 0.5f, 0.5f)
    var lo = fw; var hi = 0
    for (w in wins) { lo = minOf(lo, w.x); hi = maxOf(hi, w.x + w.w) }
    check("windows: together they cover the full width", lo == 0 && hi == fw && wins.size == 3)

    val r = ZoomDetect.Roi(400, 144, 800, 432)
    val m = ZoomDetect.toFull(listOf(det(0.5f, 0.5f, 0.1f, 0.2f)), r, fw, fh)[0]
    check("toFull: window center maps to frame center", Math.abs(m.cx - 0.5f) < 1e-4f && Math.abs(m.cy - 0.5f) < 1e-4f)
    check("toFull: sizes shrink to frame fractions", Math.abs(m.w - 0.05f) < 1e-4f && Math.abs(m.h - 0.12f) < 1e-4f)
    val corner = ZoomDetect.toFull(listOf(det(0f, 0f, 0f, 0f)), r, fw, fh)[0]
    check("toFull: window corner maps to its place in the frame", Math.abs(corner.cx - 0.25f) < 1e-4f && Math.abs(corner.cy - 0.2f) < 1e-4f)

    val a = listOf(det(0.5f, 0.5f, 0.05f, 0.12f, 0.5f), det(0.2f, 0.4f, 0.04f, 0.1f, 0.6f))
    val b = listOf(det(0.502f, 0.5f, 0.05f, 0.12f, 0.8f), det(0.9f, 0.5f, 0.04f, 0.1f, 0.4f))
    val mg = ZoomDetect.merge(a, b)
    check("merge: same enemy from both passes is kept once", mg.size == 3)
    check("merge: keeps the more confident copy", mg.any { Math.abs(it.cx - 0.502f) < 1e-4f && it.score == 0.8f } && mg.none { Math.abs(it.cx - 0.5f) < 1e-4f })
    check("merge: sorted by confidence", mg.zipWithNext().all { it.first.score >= it.second.score })

    check("needZoom: nothing found -> zoom", ZoomDetect.needZoom(emptyList(), 0.35f))
    check("needZoom: only weak boxes -> zoom", ZoomDetect.needZoom(listOf(det(0.5f, 0.5f, 0.1f, 0.2f, 0.4f)), 0.35f))
    check("needZoom: a confident enemy -> skip (saves time)", !ZoomDetect.needZoom(listOf(det(0.5f, 0.5f, 0.1f, 0.2f, 0.9f)), 0.35f))

    val sch = ZoomDetect.Scheduler()
    val seq = (0 until 6).map { sch.next(wins) }
    check("scheduler: rotates crosshair, left, right", seq[0] === wins[0] && seq[1] === wins[1] && seq[2] === wins[2] && seq[3] === wins[0])

    println(if (failures == 0) "\nALL ZOOM CHECKS PASSED" else "\n$failures ZOOM CHECK(S) FAILED")
    if (failures > 0) System.exit(1)
}
