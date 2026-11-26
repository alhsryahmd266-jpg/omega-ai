package com.omega.sight.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Something the app has been following for a few frames. Positions are in analysis-frame pixels, speeds in pixels per second. */
class Track(val id: Int, var box: Box, var kind: Kind, var score: Float) {
    /** the target's own movement across the picture, with the camera's movement taken out */
    var vx = 0f
    var vy = 0f

    /** frames since it first showed up */
    var age = 0

    /** frames in which a detection matched it */
    var hits = 1

    /** frames in a row without a match */
    var missed = 0

    /** a head box (or the glowing red light) found inside this body, kept smooth */
    var head: Box? = null

    /** the vision model has confirmed this track at least once */
    var viaModel = kind == Kind.BODY || kind == Kind.HEAD

    /** frames in which the glowing red light was found on it */
    var glowSeen = 0

    /** true when a detection matched it in the latest frame */
    var seenNow = true

    /** where the last matched detection was on the screen (not corrected for the camera) */
    var rawX = box.cx
    var rawY = box.cy

    /** it keeps its place on the screen while the whole picture moves: a button or a marker, not something in the world */
    var fixedVotes = 0
    val screenFixed: Boolean get() = fixedVotes >= 4

    /** not confirmed before it has lived this many frames (for things born exactly on the crosshair, like a hit marker) */
    var hold = 0

    val confirmed: Boolean
        get() = !screenFixed && age >= hold && (if (viaModel) hits >= 2 else (hits >= 3 || glowSeen >= 2))
}

/**
 * Follows detections from frame to frame. Each frame the tracks are first moved the way the camera moved (so turning does not make
 * everything look like it is running away), then matched with the new detections, and a filter smooths the position and
 * measures the target's own speed. A target hidden for a moment is kept alive for a few frames and keeps drifting at its last speed.
 */
class Tracker {
    val tracks = ArrayList<Track>()
    private var nextId = 1

    fun reset() {
        tracks.clear()
    }

    /**
     * @param cross the crosshair, if known: things that are born on it and things that sit still on the screen far from it are
     *  treated as screen furniture, not as targets
     * @return the confirmed tracks
     */
    fun update(dets: List<Det>, ego: Ego, dt: Float, frameW: Int, frameH: Int, cross: Vec2? = null): List<Track> {
        val dts = dt.coerceIn(0.005f, 0.25f)

        // 1. predict: the camera's movement, the target's own movement and the zoom of walking
        for (t in tracks) {
            val cx = t.box.cx
            val cy = t.box.cy
            val dx = (if (ego.valid) ego.dx(cx, cy) else 0f) + t.vx * dts
            val dy = (if (ego.valid) ego.dy(cx, cy) else 0f) + t.vy * dts
            val z = if (ego.valid) 1f + ego.s else 1f
            t.box = Box.ofCenter(cx + dx, cy + dy, t.box.w * z, t.box.h * z)
            t.head = t.head?.let { hd ->
                Box.ofCenter(hd.cx + dx, hd.cy + dy, hd.w * z, hd.h * z)
            }
            t.age++
            t.seenNow = false
        }

        // 2. match: bodies and motion first, heads and glows after
        val bodyDets = dets.filter { it.kind != Kind.HEAD && it.kind != Kind.GLOW }
        val markDets = dets.filter { it.kind == Kind.HEAD || it.kind == Kind.GLOW }
        val usedDet = BooleanArray(bodyDets.size)
        val pairs = ArrayList<Triple<Float, Int, Int>>()
        for ((ti, t) in tracks.withIndex()) {
            if (t.kind == Kind.HEAD || t.kind == Kind.GLOW) continue
            for ((di, d) in bodyDets.withIndex()) {
                val c = cost(t.box, d.box)
                if (c < 0.95f) pairs.add(Triple(c, ti, di))
            }
        }
        pairs.sortBy { it.first }
        val takenTrack = BooleanArray(tracks.size)
        for ((_, ti, di) in pairs) {
            if (takenTrack[ti] || usedDet[di]) continue
            takenTrack[ti] = true
            usedDet[di] = true
            apply(tracks[ti], bodyDets[di], dts, ego, cross, frameH)
        }
        // new tracks from what nothing claimed
        for ((di, d) in bodyDets.withIndex()) {
            if (usedDet[di]) continue
            if (tracks.size >= 24) break
            tracks.add(born(d, cross, frameH))
        }

        // 3. heads and glows: inside a body -> its head box; otherwise a track of its own
        for (hd in markDets) {
            val isGlow = hd.kind == Kind.GLOW
            val host = hostFor(hd.box)
            if (host != null) {
                attach(host, hd.box)
                if (isGlow) {
                    host.glowSeen++
                } else {
                    host.viaModel = true
                    if (host.kind == Kind.MOTION) host.kind = Kind.BODY
                }
            } else {
                val own = tracks.filter { it.kind == hd.kind && !it.seenNow }.minByOrNull { cost(it.box, hd.box) }
                if (own != null && cost(own.box, hd.box) < 0.95f) apply(own, hd, dts, ego, cross, frameH)
                else if (tracks.size < 24) tracks.add(born(hd, cross, frameH))
            }
        }

        // 4. a glow that had a track of its own and now sits inside a body that was found later: one target, not two
        val it0 = tracks.iterator()
        while (it0.hasNext()) {
            val g = it0.next()
            if (g.kind != Kind.GLOW) continue
            val host = hostFor(g.box, except = g) ?: continue
            attach(host, g.box)
            host.glowSeen += max(1, g.hits)
            it0.remove()
        }

        // 5. the ones nobody matched: coast for a while, then forget
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t.seenNow) continue
            t.missed++
            t.vx *= 0.92f
            t.vy *= 0.92f
            val limit = if (t.viaModel) 9 else 4
            val out = t.box.r < -20 || t.box.b < -20 || t.box.l > frameW + 20 || t.box.t > frameH + 20
            if (t.missed > limit || out) it.remove()
        }
        return tracks.filter { it.confirmed }
    }

    private fun hostFor(b: Box, except: Track? = null): Track? =
        tracks.filter {
            it !== except && it.kind != Kind.HEAD && it.kind != Kind.GLOW &&
                it.box.inflated(1.15f).contains(b.cx, b.cy) && b.cy < it.box.cy + it.box.h * 0.25f
        }.minByOrNull { abs(it.box.cx - b.cx) }

    private fun attach(host: Track, b: Box) {
        val old = host.head
        host.head = if (old == null || old.iou(b) < 0.05f) b else Box(
            old.l + 0.6f * (b.l - old.l), old.t + 0.6f * (b.t - old.t),
            old.r + 0.6f * (b.r - old.r), old.b + 0.6f * (b.b - old.b),
        )
    }

    private fun born(d: Det, cross: Vec2?, frameH: Int): Track {
        val t = Track(nextId++, d.box, d.kind, d.score)
        // something that shows up exactly on the crosshair (a hit marker) has to prove itself for a few frames
        if (cross != null && (d.kind == Kind.MOTION || d.kind == Kind.GLOW) && hypot(d.box.cx - cross.x, d.box.cy - cross.y) < 0.06f * frameH) t.hold = 12
        return t
    }

    /** low = a good match: overlap and nearness of the centres */
    private fun cost(a: Box, b: Box): Float {
        val iou = a.iou(b)
        // tiny far movers have tiny boxes: the matching radius never gets smaller than a few frame pixels
        val diag = max(max(hypot(a.w, a.h), hypot(b.w, b.h)), 3.3f)
        val dist = hypot(a.cx - b.cx, a.cy - b.cy)
        val near = max(0f, 1f - dist / (0.9f * diag + 1e-3f))
        if (iou < 0.05f && near <= 0f) return 2f
        return 1f - min(1f, iou + 0.35f * near)
    }

    private fun apply(t: Track, d: Det, dt: Float, ego: Ego, cross: Vec2?, frameH: Int) {
        // glued to the glass? It keeps its place on the screen while the whole picture moves. A target that the aim is following also
        // keeps its place near the crosshair, so only things far from the crosshair count.
        if (ego.valid && ego.speed >= 1.2f) {
            val far = cross == null || hypot(d.box.cx - cross.x, d.box.cy - cross.y) > 0.12f * frameH
            val moved = hypot(d.box.cx - t.rawX, d.box.cy - t.rawY)
            if (far && moved < 0.35f * ego.speed) t.fixedVotes++ else t.fixedVotes = max(0, t.fixedVotes - 1)
        }
        t.rawX = d.box.cx
        t.rawY = d.box.cy

        val precise = d.kind != Kind.MOTION
        // a motion blob only keeps a model track alive; it does not move its box (it is much rougher)
        if (t.viaModel && !precise) {
            t.hits++
            t.missed = 0
            t.seenNow = true
            return
        }
        val a = if (precise) 0.65f else 0.45f
        val b = if (precise) 0.35f else 0.22f
        val ex = d.box.cx - t.box.cx
        val ey = d.box.cy - t.box.cy
        val cx = t.box.cx + a * ex
        val cy = t.box.cy + a * ey
        t.vx = (t.vx + b * ex / dt).coerceIn(-900f, 900f)
        t.vy = (t.vy + b * ey / dt).coerceIn(-900f, 900f)
        val w = t.box.w + 0.5f * (d.box.w - t.box.w)
        val h = t.box.h + 0.5f * (d.box.h - t.box.h)
        t.box = Box.ofCenter(cx, cy, w, h)
        t.score = max(d.score, t.score * 0.9f)
        t.hits++
        t.missed = 0
        t.seenNow = true
        if (d.kind == Kind.BODY || d.kind == Kind.HEAD) {
            t.viaModel = true
            if (t.kind == Kind.MOTION && d.kind == Kind.BODY) t.kind = Kind.BODY
        }
    }
}
