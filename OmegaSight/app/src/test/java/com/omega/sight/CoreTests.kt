package com.omega.sight

import com.omega.sight.core.*
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot

/** The check counter every suite shares. */
object T {
    val failures = ArrayList<String>()
    var checks = 0

    fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
        checks++
        if (!cond) failures.add("FAIL  $name  ${detail()}")
    }

    fun note(s: String) = println("  · $s")
}

/**
 * Plain-Kotlin test suite (no JUnit in the logic itself) so it can be run with kotlinc on any machine and from Gradle through
 * [CoreSuiteTest]. All pictures are synthetic with a known answer.
 */
object CoreTests {
    fun runAll(): List<String> {
        T.failures.clear()
        T.checks = 0
        geometry(); safety(); ego(); motion(); yolo(); preprocess(); tracker(); picker(); brain()
        ScreenTests.run()
        CoverTests.run()
        ChaseTests.run()
        PanTests.run()
        ControlTests.run()
        VisionTests.run()
        GlowTests.run()
        println("checks run: ${T.checks}, failures: ${T.failures.size}")
        return T.failures.toList()
    }

    private val ego0 = Ego(0f, 0f, 0f, 96f, 48f, 0.1f, 1f, true)
    private const val DT = 1f / 30f

    private fun geometry() {
        val a = Box(0f, 0f, 10f, 10f)
        val b = Box(5f, 0f, 15f, 10f)
        T.check("iou half overlap", abs(a.iou(b) - 1f / 3f) < 1e-4f)
        T.check("iou disjoint", a.iou(Box(20f, 20f, 30f, 30f)) == 0f)
        T.check("ofCenter", Box.ofCenter(10f, 20f, 4f, 6f).let { it.l == 8f && it.t == 17f && it.r == 12f && it.b == 23f })
        T.check("inflated", Box(0f, 0f, 10f, 10f).inflated(2f).let { it.l == -5f && it.r == 15f })
    }

    private fun safety() {
        val self = "com.omega.sight"
        T.check("no game", Safety.verdict("", true, "x", self) == Safety.Verdict.NO_GAME)
        T.check("online refused", Safety.verdict("com.tencent.ig", true, "com.tencent.ig", self) == Safety.Verdict.ONLINE_GAME)
        T.check("online refused even when ticked", Safety.verdict("com.activision.callofduty.shooter", true, "com.activision.callofduty.shooter", self) == Safety.Verdict.ONLINE_GAME)
        T.check("not confirmed", Safety.verdict("my.own.game", false, "my.own.game", self) == Safety.Verdict.NOT_CONFIRMED)
        T.check("wrong app", Safety.verdict("my.own.game", true, "com.whatsapp", self) == Safety.Verdict.WRONG_APP)
        T.check("ok in own game", Safety.verdict("my.own.game", true, "my.own.game", self) == Safety.Verdict.OK)
        T.check("ok in own app", Safety.verdict("my.own.game", true, self, self) == Safety.Verdict.OK)
    }

    // ------------------------------------------------------------------ camera movement

    private fun ego() {
        val w = 192
        val h = 96
        val world = World(w + 300, h + 200, 11)
        val rnd = Random(1)
        for ((dx, dy) in listOf(3.4f to -1.7f, -2.2f to 0.9f, 0.6f to 0.3f, 6.1f to 2.5f, -7.3f to -4.1f)) {
            val a = world.frame(w, h, 100f, 80f, 0f, 1.5f, rnd)
            val b = world.frame(w, h, 100f + dx, 80f + dy, 0f, 1.5f, rnd)
            val e = GlobalMotion(w, h).estimate(a, b)
            T.check("ego valid $dx,$dy", e.valid)
            T.check("ego tx for pan $dx", abs(e.tx + dx) < 0.3f, { "got ${e.tx}" })
            T.check("ego ty for pan $dy", abs(e.ty + dy) < 0.3f, { "got ${e.ty}" })
        }
        // walking forwards = zoom about the middle
        run {
            val a = world.frame(w, h, 100f, 80f, 0f, 1.5f, rnd)
            val b = world.frame(w, h, 100f, 80f, 0.02f, 1.5f, rnd)
            val e = GlobalMotion(w, h).estimate(a, b)
            T.check("ego zoom valid", e.valid)
            T.check("ego zoom s", abs(e.s - 0.02f) < 0.006f, { "got ${e.s}" })
            T.check("ego zoom keeps shift near 0", abs(e.tx) < 0.4f && abs(e.ty) < 0.4f, { "got ${e.tx},${e.ty}" })
        }
        // something big moving on its own must not drag the answer
        run {
            val a = world.frame(w, h, 100f, 80f, 0f, 1.5f, rnd)
            val b = world.frame(w, h, 103f, 81f, 0f, 1.5f, rnd)
            paintRect(a, 60f, 30f, 84f, 56f, 250f)
            paintRect(b, 68f, 34f, 92f, 60f, 250f)
            val e = GlobalMotion(w, h).estimate(a, b)
            T.check("ego robust to a mover", e.valid && abs(e.tx + 3f) < 0.4f && abs(e.ty + 1f) < 0.4f, { "got ${e.tx},${e.ty}" })
        }
        // two unrelated pictures: no answer rather than a wrong one
        run {
            val a = world.frame(w, h, 20f, 20f, 0f, 1.5f, rnd)
            val b = world.frame(w, h, 250f, 150f, 0f, 1.5f, rnd)
            val e = GlobalMotion(w, h).estimate(a, b)
            T.check("ego refuses unrelated frames", !e.valid || e.mad > 1.5f, { "valid=${e.valid} tx=${e.tx} mad=${e.mad}" })
        }
        // a follow-up call starts from the last answer and keeps tracking a steady pan
        run {
            val gm = GlobalMotion(w, h)
            var prev = world.frame(w, h, 60f, 60f, 0f, 1.5f, rnd)
            var worst = 0f
            for (t in 1..12) {
                val cur = world.frame(w, h, 60f + 5.5f * t, 60f + 1.2f * t, 0f, 1.5f, rnd)
                val e = gm.estimate(prev, cur)
                worst = maxOf(worst, abs(e.tx + 5.5f), abs(e.ty + 1.2f))
                prev = cur
            }
            T.check("ego follows a steady fast pan", worst < 0.35f, { "worst error $worst" })
        }
    }

    // ------------------------------------------------------------------ moving things (no model)

    private fun motion() {
        val w = 192
        val h = 96
        val world = World(w + 200, h + 120, 12)
        val rnd = Random(2)
        // static camera, a 7x12 body walking
        run {
            val md = MotionDetector(w, h)
            val gm = GlobalMotion(w, h)
            var prev = world.frame(w, h, 50f, 40f, 0f, 1.5f, rnd)
            var hit = 0
            var stray = 0
            for (t in 1..12) {
                val cur = world.frame(w, h, 50f, 40f, 0f, 1.5f, rnd)
                val ox = 50f + 3f * t
                val oy = 30f + 1f * t
                paintRect(cur, ox, oy, ox + 7f, oy + 12f, 25f)
                val truth = Box(ox, oy, ox + 7f, oy + 12f)
                val dets = md.detect(prev, cur, gm.estimate(prev, cur), null, 0.6f, 0.35f)
                if (dets.any { it.box.iou(truth) >= 0.2f }) hit++
                stray += dets.count { it.box.iou(truth) < 0.05f }
                prev = cur
            }
            T.check("motion finds a walker with a still camera ($hit/12)", hit >= 10)
            T.check("motion has no stray blobs on a still scene ($stray)", stray <= 1)
        }
        // nothing moves, camera still: silence
        run {
            val md = MotionDetector(w, h)
            val gm = GlobalMotion(w, h)
            var prev = world.frame(w, h, 50f, 40f, 0f, 1.5f, rnd)
            var n = 0
            for (t in 1..20) {
                val cur = world.frame(w, h, 50f, 40f, 0f, 1.5f, rnd)
                n += md.detect(prev, cur, gm.estimate(prev, cur), null, 0.6f, 0.35f).size
                prev = cur
            }
            T.check("motion is silent on an empty still scene ($n)", n == 0)
        }
        // the camera pans while a body runs the other way: the world must not light up, the body must
        run {
            val md = MotionDetector(w, h)
            val gm = GlobalMotion(w, h)
            var prev = world.frame(w, h, 50f, 40f, 0f, 1.5f, rnd)
            paintRect(prev, 140f, 40f, 147f, 52f, 25f)
            var hit = 0
            var stray = 0
            for (t in 1..14) {
                val cur = world.frame(w, h, 50f + 2.3f * t, 40f + 0.7f * t, 0f, 1.5f, rnd)
                // 4.5 px per frame on the screen against the camera's 2.3: it moves against the world by 2.2 px per frame
                val ox = 140f - 4.5f * t
                val oy = 40f
                paintRect(cur, ox, oy, ox + 7f, oy + 12f, 25f)
                val truth = Box(ox, oy, ox + 7f, oy + 12f)
                val e = gm.estimate(prev, cur)
                val dets = md.detect(prev, cur, e, null, 0.6f, 0.35f)
                if (t >= 2) {
                    if (dets.any { it.box.iou(truth) >= 0.15f }) hit++
                    stray += dets.count { it.box.iou(truth) < 0.02f && it.box.area > 30f }
                }
                prev = cur
            }
            T.check("motion finds a runner while the camera pans ($hit/13)", hit >= 9)
            T.check("motion does not light up the world while panning ($stray)", stray <= 2)
        }
        // a whipping camera: nothing is reported
        run {
            val md = MotionDetector(w, h)
            val gm = GlobalMotion(w, h)
            val a = world.frame(w, h, 20f, 40f, 0f, 1.5f, rnd)
            val b = world.frame(w, h, 20f + 30f, 40f, 0f, 1.5f, rnd)
            val dets = md.detect(a, b, gm.estimate(a, b), null, 0.6f, 0.35f)
            T.check("motion reports nothing while the camera whips", dets.isEmpty())
        }
        // fixed screen furniture is ignored
        run {
            val md = MotionDetector(w, h)
            val gm = GlobalMotion(w, h)
            val prev = world.frame(w, h, 50f, 40f, 0f, 1.5f, rnd)
            val cur = world.frame(w, h, 50f, 40f, 0f, 1.5f, rnd)
            paintRect(cur, 150f, 60f, 170f, 80f, 255f)
            val hud = ByteArray(w * h)
            for (y in 55 until 85) for (x in 145 until 175) hud[y * w + x] = 1
            val withMask = md.detect(prev, cur, gm.estimate(prev, cur), hud, 0.6f, 0.35f)
            val without = md.detect(prev, cur, gm.estimate(prev, cur), null, 0.6f, 0.35f)
            T.check("motion sees a flash without a mask", without.isNotEmpty())
            T.check("motion ignores it inside the screen-furniture mask", withMask.isEmpty())
        }
    }

    // ------------------------------------------------------------------ model output

    private fun yolo() {
        val lb = Letterbox(1280, 576, 640, 640)
        T.check("letterbox scale", abs(lb.scale - 0.5f) < 1e-6f && abs(lb.padY - 176f) < 1e-3f && lb.padX == 0f)
        // end to end [1, 3, 6]
        run {
            val out = floatArrayOf(
                100f, 200f, 200f, 300f, 0.9f, 0f,
                300f, 250f, 340f, 330f, 0.8f, 1f,
                10f, 10f, 20f, 20f, 0.1f, 0f,
            )
            val d = YoloDecoder.decode(out, intArrayOf(1, 3, 6), lb, emptyList(), 0.35f)
            T.check("e2e count", d.size == 2, { "got ${d.size}" })
            val a = d.firstOrNull { it.kind == Kind.BODY }
            T.check("e2e box mapped back", a != null && abs(a.box.l - 200f) < 1f && abs(a.box.r - 400f) < 1f && abs(a.box.t - 48f) < 1f && abs(a.box.b - 248f) < 1f, { "got ${a?.box}" })
            T.check("e2e head class", d.any { it.kind == Kind.HEAD })
        }
        // layouts told apart by shape
        T.check("layout [1,300,6]", YoloDecoder.layoutOf(intArrayOf(1, 300, 6)) == YoloDecoder.Layout.E2E_NC)
        T.check("layout [1,6,300]", YoloDecoder.layoutOf(intArrayOf(1, 6, 300)) == YoloDecoder.Layout.E2E_CN)
        T.check("layout [1,6,8400]", YoloDecoder.layoutOf(intArrayOf(1, 6, 8400)) == YoloDecoder.Layout.RAW_CN)
        T.check("layout [1,8400,6]", YoloDecoder.layoutOf(intArrayOf(1, 8400, 6)) == YoloDecoder.Layout.RAW_NC)
        T.check("layout [1,5,8400]", YoloDecoder.layoutOf(intArrayOf(1, 5, 8400)) == YoloDecoder.Layout.RAW_CN)
        T.check("layout [1,84,8400]", YoloDecoder.layoutOf(intArrayOf(1, 84, 8400)) == YoloDecoder.Layout.RAW_CN)
        // raw [1, 6, A] (cx, cy, w, h, c0, c1) with a duplicate that NMS must drop
        run {
            val a = 20
            val out = FloatArray(6 * a)
            fun put(i: Int, cx: Float, cy: Float, w: Float, h: Float, c0: Float, c1: Float) {
                out[0 * a + i] = cx; out[1 * a + i] = cy; out[2 * a + i] = w; out[3 * a + i] = h; out[4 * a + i] = c0; out[5 * a + i] = c1
            }
            put(0, 320f, 320f, 100f, 200f, 0.9f, 0.05f)
            put(1, 324f, 322f, 100f, 200f, 0.8f, 0.05f)
            put(2, 500f, 400f, 30f, 30f, 0.05f, 0.7f)
            val d = YoloDecoder.decode(out, intArrayOf(1, 6, a), lb, emptyList(), 0.35f)
            T.check("raw NMS keeps one of two copies", d.count { it.kind == Kind.BODY } == 1, { "got $d" })
            T.check("raw head found", d.count { it.kind == Kind.HEAD } == 1)
        }
        // raw with logits, [1, A, 5] (one class)
        run {
            val a = 10
            val out = FloatArray(a * 5)
            for (i in 0 until a) {
                out[i * 5 + 4] = -6f
            }
            out[3 * 5] = 320f; out[3 * 5 + 1] = 320f; out[3 * 5 + 2] = 60f; out[3 * 5 + 3] = 120f; out[3 * 5 + 4] = 3f
            val d = YoloDecoder.decode(out, intArrayOf(1, a, 5), lb, emptyList(), 0.35f)
            T.check("logits turned into probabilities", d.size == 1 && d[0].score > 0.9f, { "got $d" })
        }
        // 0..1 boxes
        run {
            val out = floatArrayOf(0.25f, 0.5f, 0.5f, 0.75f, 0.9f, 0f)
            val d = YoloDecoder.decode(out, intArrayOf(1, 1, 6), lb, emptyList(), 0.35f)
            T.check("unit boxes scaled to the input", d.size == 1 && abs(d[0].box.l - 320f) < 1f && abs(d[0].box.r - 640f) < 1f, { "got $d" })
        }
        // names decide what is a target
        run {
            val out = floatArrayOf(100f, 200f, 200f, 300f, 0.9f, 1f, 300f, 250f, 340f, 330f, 0.8f, 0f)
            val d = YoloDecoder.decode(out, intArrayOf(1, 2, 6), lb, listOf("enemy", "friend"), 0.35f)
            T.check("a class called friend is never a target", d.size == 1 && d[0].kind == Kind.BODY)
            val d2 = YoloDecoder.decode(out, intArrayOf(1, 2, 6), lb, listOf("body", "head"), 0.35f)
            T.check("a class called head is a head", d2.any { it.kind == Kind.HEAD } && d2.any { it.kind == Kind.BODY })
        }
    }

    private fun preprocess() {
        val sw = 400
        val sh = 200
        val src = ByteArray(sw * sh * 4)
        for (y in 0 until sh) for (x in 0 until sw) {
            val i = (y * sw + x) * 4
            src[i] = 10; src[i + 1] = 20; src[i + 2] = 30; src[i + 3] = -1
            if (x in 100 until 200 && y in 50 until 150) {
                src[i] = -56 // 200
                src[i + 1] = 100
                src[i + 2] = 50
            }
        }
        val rgb = ByteArray(64 * 64 * 3)
        val lb = Preprocess.letterboxRgb(src, sw, sh, sw * 4, 0, 0, sw, sh, 64, 64, rgb)
        T.check("preprocess scale", abs(lb.scale - 0.16f) < 1e-6f)
        T.check("preprocess grey bars", (rgb[0].toInt() and 255) == 114 && (rgb[(63 * 64 + 5) * 3].toInt() and 255) == 114)
        // the patch (x 100..200, y 50..150) lands at x 16..32, y 32 - 16 + 8 ..
        val px = (lb.padX + 150f * lb.scale).toInt()
        val py = (lb.padY + 100f * lb.scale).toInt()
        val o = (py * 64 + px) * 3
        T.check("preprocess keeps colours", (rgb[o].toInt() and 255) == 200 && (rgb[o + 1].toInt() and 255) == 100 && (rgb[o + 2].toInt() and 255) == 50, { "got ${rgb[o].toInt() and 255},${rgb[o + 1].toInt() and 255},${rgb[o + 2].toInt() and 255}" })
        val f = FloatArray(64 * 64 * 3)
        Preprocess.toFloat(rgb, 64, 64, false, f)
        T.check("preprocess float 0..1 NHWC", abs(f[o] - 200f / 255f) < 1e-4f && abs(f[o + 1] - 100f / 255f) < 1e-4f)
        val g = FloatArray(64 * 64 * 3)
        Preprocess.toFloat(rgb, 64, 64, true, g)
        val p = py * 64 + px
        T.check("preprocess float NCHW", abs(g[p] - 200f / 255f) < 1e-4f && abs(g[4096 + p] - 100f / 255f) < 1e-4f && abs(g[8192 + p] - 50f / 255f) < 1e-4f)
        val q = ByteArray(64 * 64 * 3)
        Preprocess.toQuant(rgb, 64, 64, false, 1f / 255f, -128, true, q)
        T.check("preprocess int8", q[o].toInt() == 200 - 128 && q[o + 1].toInt() == 100 - 128, { "got ${q[o]}" })
        Preprocess.toQuant(rgb, 64, 64, false, 1f / 255f, 0, false, q)
        T.check("preprocess uint8", (q[o].toInt() and 255) == 200)
        // a region
        val lb2 = Preprocess.letterboxRgb(src, sw, sh, sw * 4, 100, 50, 100, 100, 50, 50, rgb)
        T.check("preprocess region fills the input", (rgb[0].toInt() and 255) == 200 && lb2.padX == 0f && lb2.padY == 0f)
    }

    // ------------------------------------------------------------------ following things

    private fun tracker() {
        // a body walking right at 60 px/s
        run {
            val tr = Tracker()
            var confirmedAt = -1
            for (t in 0 until 24) {
                val x = 40f + 2f * t
                val conf = tr.update(listOf(Det(Box.ofCenter(x, 50f, 10f, 24f), 0.9f, Kind.BODY)), ego0, DT, 192, 96)
                if (confirmedAt < 0 && conf.isNotEmpty()) confirmedAt = t
            }
            T.check("tracker confirms a model detection on the 2nd sighting", confirmedAt == 1, { "at $confirmedAt" })
            T.check("tracker keeps one track", tr.tracks.size == 1)
            val v = tr.tracks.first().vx
            T.check("tracker measures the speed", abs(v - 60f) < 10f, { "vx=$v" })
        }
        // a stationary body seen while the camera pans: its own speed must stay near zero
        run {
            val tr = Tracker()
            for (t in 0 until 24) {
                val x = 150f - 3f * t
                val ego = Ego(-3f, 0f, 0f, 96f, 48f, 0.1f, 1f, true)
                tr.update(listOf(Det(Box.ofCenter(x, 50f, 10f, 24f), 0.9f, Kind.BODY)), ego, DT, 192, 96)
            }
            T.check("tracker removes the camera movement", tr.tracks.size == 1 && abs(tr.tracks[0].vx) < 15f, { "vx=${tr.tracks.firstOrNull()?.vx} n=${tr.tracks.size}" })
        }
        // motion-only things need three sightings
        run {
            val tr = Tracker()
            var counts = ""
            for (t in 0 until 4) {
                val conf = tr.update(listOf(Det(Box.ofCenter(80f + t, 40f, 6f, 10f), 0.6f, Kind.MOTION)), ego0, DT, 192, 96)
                counts += conf.size
            }
            T.check("tracker needs three sightings for a motion blob", counts == "0011", { "got $counts" })
        }
        // coasting through a gap, then forgetting
        run {
            val tr = Tracker()
            for (t in 0 until 6) tr.update(listOf(Det(Box.ofCenter(60f + 2f * t, 40f, 8f, 20f), 0.9f, Kind.BODY)), ego0, DT, 192, 96)
            for (t in 0 until 5) tr.update(emptyList(), ego0, DT, 192, 96)
            T.check("tracker coasts through a short gap", tr.tracks.size == 1)
            val back = tr.update(listOf(Det(Box.ofCenter(60f + 2f * 11f, 40f, 8f, 20f), 0.9f, Kind.BODY)), ego0, DT, 192, 96)
            T.check("tracker picks the same target up again", back.size == 1 && tr.tracks.size == 1 && tr.tracks[0].id == 1)
            for (t in 0 until 14) tr.update(emptyList(), ego0, DT, 192, 96)
            T.check("tracker forgets after a long gap", tr.tracks.isEmpty())
        }
        // two targets keep their own tracks
        run {
            val tr = Tracker()
            for (t in 0 until 10) {
                tr.update(
                    listOf(
                        Det(Box.ofCenter(40f + t, 40f, 8f, 20f), 0.9f, Kind.BODY),
                        Det(Box.ofCenter(150f - t, 60f, 8f, 20f), 0.9f, Kind.BODY),
                    ),
                    ego0, DT, 192, 96,
                )
            }
            T.check("tracker follows two targets", tr.tracks.size == 2 && tr.tracks.map { it.id }.toSet().size == 2)
        }
        // a head inside a body is attached to it
        run {
            val tr = Tracker()
            for (t in 0 until 4) {
                tr.update(
                    listOf(
                        Det(Box.ofCenter(80f, 50f, 14f, 36f), 0.9f, Kind.BODY),
                        Det(Box.ofCenter(80f, 36f, 5f, 5f), 0.8f, Kind.HEAD),
                    ),
                    ego0, DT, 192, 96,
                )
            }
            val b = tr.tracks.firstOrNull { it.kind == Kind.BODY }
            T.check("tracker attaches the head to the body", tr.tracks.size == 1 && b?.head != null, { "tracks=${tr.tracks.size}" })
        }
        // a tiny far mover is still matched frame to frame
        run {
            val tr = Tracker()
            var n = 0
            for (t in 0 until 8) n = tr.update(listOf(Det(Box.ofCenter(100f + 0.9f * t, 30f, 0.6f, 0.6f), 0.5f, Kind.MOTION)), ego0, DT, 192, 96).size
            T.check("tracker follows a tiny mover", tr.tracks.size == 1 && n == 1, { "tracks=${tr.tracks.size}" })
        }
    }

    private fun tr(x: Float, y: Float, w: Float = 8f, h: Float = 20f, kind: Kind = Kind.BODY): Track =
        Track(1, Box.ofCenter(x, y, w, h), kind, 0.9f).also { it.hits = 5 }

    private fun picker() {
        val cross = Vec2(96f, 48f)
        val tu = Tuning()
        run {
            val p = TargetPicker()
            val near = Track(1, Box.ofCenter(110f, 50f, 8f, 20f), Kind.BODY, 0.9f)
            val far = Track(2, Box.ofCenter(170f, 20f, 8f, 20f), Kind.BODY, 0.9f)
            T.check("picker takes the nearer one", p.pick(listOf(far, near), cross, 96f, tu, 0.1f)?.track?.id == 1)
            T.check("picker returns nothing outside the field", p.pick(listOf(far), cross, 96f, tu.copy(fov = 0.2f), 0.1f) == null)
        }
        run {
            val p = TargetPicker()
            val a = Track(1, Box.ofCenter(120f, 48f, 8f, 20f), Kind.BODY, 0.9f)
            val b = Track(2, Box.ofCenter(75f, 48f, 8f, 20f), Kind.BODY, 0.9f)
            T.check("picker starts with a", p.pick(listOf(a), cross, 96f, tu, 0f)?.track?.id == 1)
            T.check("picker sticks to it when b is only a little better", p.pick(listOf(a, b), cross, 96f, tu, 0f)?.track?.id == 1)
            val c = Track(3, Box.ofCenter(98f, 48f, 8f, 20f), Kind.BODY, 0.9f)
            T.check("picker switches when another is much better", p.pick(listOf(a, c), cross, 96f, tu, 0f)?.track?.id == 3)
        }
        run {
            val p = TargetPicker()
            val body = Track(1, Box.ofCenter(100f, 50f, 14f, 36f), Kind.BODY, 0.9f)
            body.head = Box.ofCenter(100f, 36f, 5f, 5f)
            val a = p.aimPoint(body, 0.5f)
            T.check("aim at the head box when there is one", abs(a.y - 36f) < 0.01f && abs(a.x - 100f) < 0.01f)
            body.head = null
            val chest = p.aimPoint(body, 0f)
            val head = p.aimPoint(body, 1f)
            T.check("headBias moves the aim point up the body", head.y < chest.y && head.y > 32f && chest.y < 50f, { "head=${head.y} chest=${chest.y}" })
            val mv = Track(2, Box.ofCenter(100f, 50f, 6f, 10f), Kind.MOTION, 0.5f)
            T.check("a motion blob is aimed at its middle", abs(p.aimPoint(mv, 0.5f).y - 50f) < 0.01f)
        }
        run {
            val p = TargetPicker()
            val t = Track(1, Box.ofCenter(120f, 48f, 8f, 20f), Kind.BODY, 0.9f)
            t.vx = 60f
            val pk = p.pick(listOf(t), cross, 96f, tu, 0.1f)
            val base = p.aimPoint(t, tu.headBias)
            T.check("picker leads a moving target", pk != null && abs(pk.aim.x - (base.x + 6f)) < 0.01f, { "aim ${pk?.aim?.x} base ${base.x}" })
            val none = p.pick(listOf(t), cross, 96f, tu.copy(lead = 0f), 0.1f)
            T.check("lead can be switched off", none != null && abs(none.aim.x - base.x) < 0.01f)
        }
    }

    private fun brain() {
        val picker = TargetPicker()
        val tracks = listOf(
            Track(1, Box.ofCenter(110f, 48f, 8f, 20f), Kind.BODY, 0.9f),
            Track(2, Box.ofCenter(60f, 40f, 4f, 4f), Kind.MOTION, 0.5f),
        )
        val st = FloatArray(BrainSpec.IN)
        Brain.buildState(tracks, picker, Vec2(96f, 48f), 96f, ego0, DT, 1.5f, 10f, -5f, 1080f, 0.5f, st)
        T.check("brain state size", BrainSpec.IN == 40)
        T.check("brain state nearest first", st[0] == 1f && abs(st[7] - 0.5f) < 1e-6f && st[BrainSpec.PER] == 1f && st[BrainSpec.PER + 7] == 0f)
        val aim = picker.aimPoint(tracks[0], 0.5f)
        T.check("brain state dx, dy in frame heights", abs(st[1] - (aim.x - 96f) / 96f) < 1e-4f && abs(st[2] - (aim.y - 48f) / 96f) < 1e-4f)
        T.check("brain state missing targets are zero", st[2 * BrainSpec.PER] == 0f && st[3 * BrainSpec.PER + 3] == 0f)
        val o = BrainSpec.K * BrainSpec.PER
        T.check("brain own state", abs(st[o + 3] - 0.5f) < 1e-6f && st[o + 6] == 0.5f && st[o + 7] == 1f)
        T.check("brain output of 3", Brain.interpret(floatArrayOf(0.1f, -0.2f, 0.9f), 96f)?.let { abs(it.dx - 9.6f) < 1e-3f && abs(it.dy + 19.2f) < 1e-3f && it.engage == 0.9f } == true)
        T.check("brain output of 2 engages", Brain.interpret(floatArrayOf(0.1f, 0.1f), 96f)?.engage == 1f)
        T.check("brain rejects wrong size", Brain.interpret(floatArrayOf(1f), 96f) == null && Brain.interpret(FloatArray(5), 96f) == null)
        T.check("brain rejects NaN", Brain.interpret(floatArrayOf(Float.NaN, 0f, 1f), 96f) == null)
        val cl = Vec2(100f, 50f)
        val bo = BrainOut(20f, 0f, 1f)
        val m = Brain.mix(cl, Vec2(96f, 48f), bo, 0.5f)
        T.check("brain mix halfway", abs(m.x - 108f) < 1e-3f && abs(m.y - 49f) < 1e-3f, { "got $m" })
        T.check("brain mix 0 keeps the classical aim", Brain.mix(cl, Vec2(96f, 48f), bo, 0f) == cl)
        T.check("brain that does not engage is ignored", Brain.mix(cl, Vec2(96f, 48f), BrainOut(20f, 0f, 0.2f), 1f) == cl)
        T.check("brain spec text exists", BrainSpec.describe().contains("[1, 40]"))
    }

    internal fun dist(a: Vec2, b: Vec2) = hypot(a.x - b.x, a.y - b.y)
}
