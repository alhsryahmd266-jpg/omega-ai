package com.omega.sight

import com.omega.sight.core.*
import java.util.Random
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The glowing red light of the bots. The colours are the ones measured on the owner's screenshot of a bot: a white-hot centre
 * (255,236,233), a bright pink-red ring (255,157,161) / (214,67,77), red (190,45,40) / (156,37,33) and a dark edge (89,3,4), on a dark
 * body of about (45,35,30).
 */
object GlowTests {
    class Rgba(val w: Int, val h: Int) {
        val d = ByteArray(w * h * 4)

        fun set(x: Int, y: Int, r: Int, g: Int, b: Int) {
            if (x < 0 || y < 0 || x >= w || y >= h) return
            val p = (y * w + x) * 4
            d[p] = r.coerceIn(0, 255).toByte(); d[p + 1] = g.coerceIn(0, 255).toByte(); d[p + 2] = b.coerceIn(0, 255).toByte(); d[p + 3] = -1
        }

        fun rgb(x: Int, y: Int): IntArray {
            val p = (y * w + x) * 4
            return intArrayOf(d[p].toInt() and 255, d[p + 1].toInt() and 255, d[p + 2].toInt() and 255)
        }
    }

    private fun scene(w: Int, h: Int, rnd: Random, bg: IntArray = intArrayOf(32, 27, 24)): Rgba {
        val im = Rgba(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val n = (rnd.nextGaussian() * 5).toInt()
            im.set(x, y, bg[0] + n, bg[1] + n, bg[2] + n)
        }
        return im
    }

    private fun body(im: Rgba, cx: Int, cy: Int, bw: Int, bh: Int, rnd: Random) {
        for (y in cy - bh / 2..cy + bh / 2) for (x in cx - bw / 2..cx + bw / 2) {
            val dx = (x - cx) / (bw / 2f)
            val dy = (y - cy) / (bh / 2f)
            if (dx * dx + dy * dy <= 1f) {
                val n = (rnd.nextGaussian() * 6).toInt()
                im.set(x, y, 47 + n, 36 + n, 28 + n)
            }
        }
    }

    private fun lerp(a: Int, b: Int, t: Float) = (a + (b - a) * t).toInt()

    /** the glow as measured: core, pink ring, red, dark red edge fading into what is behind it */
    private fun glow(im: Rgba, cx: Float, cy: Float, radius: Float) {
        val ext = radius.toInt() + 2
        for (y in (cy - ext).toInt()..(cy + ext).toInt()) for (x in (cx - ext).toInt()..(cx + ext).toInt()) {
            if (x < 0 || y < 0 || x >= im.w || y >= im.h) continue
            val t = hypot(x + 0.5f - cx, y + 0.5f - cy) / radius
            if (t > 1f) continue
            val c = when {
                t < 0.18f -> intArrayOf(255, 236, 233)
                t < 0.40f -> { val u = (t - 0.18f) / 0.22f; intArrayOf(lerp(255, 214, u), lerp(157, 67, u), lerp(161, 77, u)) }
                t < 0.70f -> { val u = (t - 0.40f) / 0.30f; intArrayOf(lerp(206, 156, u), lerp(47, 37, u), lerp(51, 33, u)) }
                else -> { val u = (t - 0.70f) / 0.30f; intArrayOf(lerp(139, 89, u), lerp(44, 3, u), lerp(38, 4, u)) }
            }
            val old = im.rgb(x, y)
            val a = if (t < 0.85f) 1f else (1f - t) / 0.15f
            im.set(x, y, lerp(old[0], c[0], a), lerp(old[1], c[1], a), lerp(old[2], c[2], a))
        }
    }

    private fun fillRect(im: Rgba, x0: Int, y0: Int, x1: Int, y1: Int, r: Int, g: Int, b: Int) {
        for (y in y0..y1) for (x in x0..x1) im.set(x, y, r, g, b)
    }

    private fun found(dets: List<Det>, cx: Float, cy: Float, tol: Float = 1.6f) = dets.firstOrNull { it.kind == Kind.GLOW && hypot(it.box.cx - cx, it.box.cy - cy) <= tol }

    fun run() {
        val w = 320
        val h = 180
        val rnd = Random(9)
        val det = GlowDetector(w, h)
        // glows of several sizes on a dark body
        for (radius in listOf(14f, 9f, 5f, 3.5f)) {
            val im = scene(w, h, rnd)
            body(im, 160, 100, 44, 70, rnd)
            glow(im, 158.5f, 88.5f, radius)
            val dets = det.detect(im.d, w * 4, null, 0.6f)
            val g = found(dets, 158.5f, 88.5f)
            T.check("glow of radius $radius is found in the middle", g != null && g.score >= 0.55f, { "dets ${dets.map { "(${it.box.cx},${it.box.cy},${it.score})" }}" })
            T.check("glow of radius $radius gives one detection", dets.size == 1, { "got ${dets.size}" })
        }
        // far away: a couple of pixels
        run {
            var hits = 0
            for (seed in 0 until 10) {
                val r2 = Random(100L + seed)
                val im = scene(w, h, r2)
                body(im, 160, 100, 8, 14, r2)
                glow(im, 160f + r2.nextFloat(), 97f + r2.nextFloat(), 2.2f)
                if (found(det.detect(im.d, w * 4, null, 0.6f), 160.5f, 97.5f, 2.5f) != null) hits++
            }
            T.check("a glow of radius 2 px is found ($hits/10)", hits >= 8)
            var hits3 = 0
            for (seed in 0 until 10) {
                val r2 = Random(200L + seed)
                val im = scene(w, h, r2)
                glow(im, 160f + r2.nextFloat(), 97f + r2.nextFloat(), 1.4f)
                if (found(det.detect(im.d, w * 4, null, 0.6f), 160.5f, 97.5f, 2.5f) != null) hits3++
            }
            T.note("a glow of radius 1.4 px (about 3 px across) found in $hits3/10 scenes")
        }
        // things that must not count
        run {
            val im = scene(w, h, rnd)
            fillRect(im, 100, 20, 153, 28, 225, 75, 55)
            T.check("a flat orange-red bar is not a glow", det.detect(im.d, w * 4, null, 0.6f).isEmpty())
            val hb = scene(w, h, rnd)
            fillRect(hb, 20, 150, 110, 156, 200, 30, 30)
            T.check("a red health bar is not a glow", det.detect(hb.d, w * 4, null, 0.6f).isEmpty())
            val fire = scene(w, h, rnd)
            for (y in 60..72) for (x in 200..212) if (hypot(x - 206f, y - 66f) <= 6.5f) fire.set(x, y, 255, 170, 40)
            T.check("orange fire is not a glow", det.detect(fire.d, w * 4, null, 0.6f).isEmpty())
            val wall = scene(w, h, rnd)
            fillRect(wall, 40, 40, 160, 120, 190, 30, 30)
            T.check("a red wall is not a glow", det.detect(wall.d, w * 4, null, 0.6f).isEmpty())
            val sky = scene(w, h, rnd, intArrayOf(120, 170, 230))
            T.check("a plain sky has no glow", det.detect(sky.d, w * 4, null, 0.6f).isEmpty())
            val white = scene(w, h, rnd)
            fillRect(white, 100, 60, 130, 80, 255, 255, 255)
            T.check("white lights alone are not a glow", det.detect(white.d, w * 4, null, 0.6f).isEmpty())
            val bright = scene(w, h, rnd, intArrayOf(200, 190, 180))
            for (y in 90..92) for (x in 150..152) bright.set(x, y, 230, 30, 30)
            T.check("a red dot on a bright background is not a glow", det.detect(bright.d, w * 4, null, 0.6f).isEmpty())
        }
        // a small dark-red dot far away on a dark scene is accepted at the default and refused at the lowest sensitivity
        run {
            val im = scene(w, h, rnd)
            for (y in 90..92) for (x in 150..152) im.set(x, y, 230, 30, 30)
            T.check("a bright red dot on a dark scene is accepted at the default", found(det.detect(im.d, w * 4, null, 0.6f), 151.5f, 91.5f) != null)
            T.check("and refused at the lowest sensitivity", det.detect(im.d, w * 4, null, 0f).isEmpty())
        }
        // fixed screen furniture is ignored
        run {
            val im = scene(w, h, rnd)
            glow(im, 40.5f, 40.5f, 8f)
            val hud = ByteArray(w * h)
            for (y in 25 until 56) for (x in 25 until 56) hud[y * w + x] = 1
            T.check("a red light on the screen furniture is ignored", det.detect(im.d, w * 4, hud, 0.6f).isEmpty())
            T.check("the same light without the mask is found", det.detect(im.d, w * 4, null, 0.6f).isNotEmpty())
        }
        // two bots
        run {
            val im = scene(w, h, rnd)
            body(im, 80, 100, 30, 60, rnd)
            body(im, 230, 110, 20, 40, rnd)
            glow(im, 79.5f, 88.5f, 7f)
            glow(im, 229.5f, 102.5f, 4f)
            val dets = det.detect(im.d, w * 4, null, 0.6f)
            T.check("two glows, two detections", dets.size == 2 && found(dets, 79.5f, 88.5f) != null && found(dets, 229.5f, 102.5f) != null, { "got ${dets.map { "(${it.box.cx},${it.box.cy})" }}" })
        }
        // a full-size frame: how long does it take
        run {
            val bw = 1280
            val bh = 576
            val big = scene(bw, bh, rnd)
            body(big, 640, 330, 120, 220, rnd)
            glow(big, 640f, 280f, 22f)
            val gd = GlowDetector(bw, bh)
            gd.detect(big.d, bw * 4, null, 0.6f)
            val t0 = System.nanoTime()
            var dets = emptyList<Det>()
            repeat(10) { dets = gd.detect(big.d, bw * 4, null, 0.6f) }
            val ms = (System.nanoTime() - t0) / 1e7
            T.note("glow detector on a 1280x576 frame: %.1f ms per frame (this machine, JVM)".format(ms))
            T.check("glow found in a full-size frame", found(dets, 640f, 280f, 2f) != null)
            T.check("glow detector is fast enough (${ms.toInt()} ms)", ms < 60.0)
        }
        // the tracker with glows
        run {
            val ego0 = Ego(0f, 0f, 0f, 96f, 48f, 0.1f, 1f, true)
            val dt = 1f / 30f
            // a glow on its own needs three sightings
            val tr = Tracker()
            var counts = ""
            for (t in 0 until 4) counts += tr.update(listOf(Det(Box.ofCenter(60f + t, 30f, 3f, 3f), 0.7f, Kind.GLOW)), ego0, dt, 192, 96).size
            T.check("a lone glow needs three sightings", counts == "0011", { "got $counts" })
            // a glow inside a body is its head
            val tr2 = Tracker()
            var c2 = ""
            for (t in 0 until 4) {
                c2 += tr2.update(
                    listOf(
                        Det(Box.ofCenter(80f, 50f, 16f, 36f), 0.9f, Kind.MOTION),
                        Det(Box.ofCenter(80f, 40f, 3f, 3f), 0.7f, Kind.GLOW),
                    ),
                    ego0, dt, 192, 96,
                ).size
            }
            val host = tr2.tracks.firstOrNull { it.kind != Kind.GLOW }
            T.check("a glow inside a moving body becomes its head mark", tr2.tracks.size == 1 && host?.head != null && host.glowSeen >= 2, { "tracks ${tr2.tracks.size} head ${host?.head}" })
            T.check("two glow sightings confirm a moving body", c2.endsWith("1"), { "got $c2" })
            val pk = TargetPicker().aimPoint(host!!, 0.5f)
            T.check("the aim goes to the glow", hypot(pk.x - 80f, pk.y - 40f) < 1f, { "aim $pk" })
            // a glow with a track of its own, then a body appears around it
            val tr3 = Tracker()
            for (t in 0 until 3) tr3.update(listOf(Det(Box.ofCenter(80f, 40f, 3f, 3f), 0.7f, Kind.GLOW)), ego0, dt, 192, 96)
            for (t in 0 until 3) tr3.update(listOf(Det(Box.ofCenter(80f, 50f, 16f, 36f), 0.9f, Kind.BODY)), ego0, dt, 192, 96)
            T.check("a lone glow merges into the body found later", tr3.tracks.count { it.kind != Kind.GLOW } == 1 && tr3.tracks.none { it.kind == Kind.GLOW }, { "tracks ${tr3.tracks.map { it.kind }}" })
        }
        // screen furniture and hit markers
        run {
            val dt = 1f / 30f
            val cross = Vec2(96f, 48f)
            val moving = Ego(-3f, 0f, 0f, 96f, 48f, 0.1f, 1f, true)
            // a marker that keeps its place on the screen while the picture moves, far from the crosshair
            val tr = Tracker()
            var seen = ""
            for (t in 0 until 12) seen += tr.update(listOf(Det(Box.ofCenter(30f, 20f, 4f, 4f), 0.8f, Kind.GLOW)), moving, dt, 192, 96, cross).size
            T.check("a red mark glued to the screen is dropped while the camera moves ($seen)", seen.endsWith("000000"))
            // a real target held on the crosshair by the aim while the camera follows it is kept
            val tr2 = Tracker()
            var kept = ""
            var prevX = 130f
            for (t in 0 until 12) {
                // the target walks to the right at 3 px per frame. The camera first turns faster than that (the aim catches up, so the
                // target slides to the crosshair), then follows it at its own speed, so it stays on the crosshair while the picture moves.
                // The measured shift of the picture changes together with the target's place on the screen, as it does in reality.
                val x = max(97f, 130f - 6f * t)
                val shift = if (t == 0) -3f else (x - prevX) - 3f
                prevX = x
                val ego = Ego(shift, 0f, 0f, 96f, 48f, 0.1f, 1f, true)
                kept += tr2.update(listOf(Det(Box.ofCenter(x, 49f, 4f, 4f), 0.8f, Kind.GLOW)), ego, dt, 192, 96, cross).size
            }
            T.check("a target held on the crosshair is kept ($kept)", kept.endsWith("1111"))
            // something born exactly on the crosshair (a hit marker) has to last a while
            val tr3 = Tracker()
            var early = 0
            var late = 0
            for (t in 0 until 16) {
                val n = tr3.update(listOf(Det(Box.ofCenter(96f, 48f, 4f, 4f), 0.8f, Kind.GLOW)), Ego(0f, 0f, 0f, 96f, 48f, 0.1f, 1f, true), dt, 192, 96, cross).size
                if (t in 3..8) early += n
                if (t >= 14) late += n
            }
            T.check("a mark born on the crosshair is held back at first", early == 0, { "early $early" })
            T.check("and accepted if it stays", late >= 1, { "late $late" })
        }
        val x = max(min(1, 2), 0)
        T.check("glow suite ran", x == 1)
    }
}
