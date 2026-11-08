package com.ahmedxg.gamebot

import kotlin.math.hypot
import kotlin.math.max

class Track(val id: Int, var cx: Float, var cy: Float, var w: Float, var h: Float) {
    var vh = 0f            // growth of the box height per second (>0: coming closer)
    var firstSeen = 0L
    var lastSeen = 0L
    var hits = 1
}

/** Tiny nearest-neighbour tracker over normalized detections. Pure Kotlin. */
class Tracker {
    private val tracks = ArrayList<Track>()
    private var nextId = 1

    fun reset() {
        tracks.clear()
    }

    /** @return the tracks seen in this very frame. */
    fun update(dets: List<Det>, now: Long): List<Track> {
        val used = HashSet<Int>()
        val seen = ArrayList<Track>()
        for (d in dets) {
            var best: Track? = null
            var bd = 0.2f
            for (t in tracks) {
                if (t.id in used) continue
                val dd = hypot(d.cx - t.cx, d.cy - t.cy)
                if (dd < bd) {
                    bd = dd
                    best = t
                }
            }
            val tr = best
            if (tr != null) {
                val dt = max(0.016f, (now - tr.lastSeen) / 1000f)
                tr.vh = tr.vh * 0.6f + ((d.h - tr.h) / dt) * 0.4f
                tr.cx = d.cx
                tr.cy = d.cy
                tr.w = d.w
                tr.h = d.h
                tr.lastSeen = now
                tr.hits++
                used.add(tr.id)
                seen.add(tr)
            } else {
                val t = Track(nextId++, d.cx, d.cy, d.w, d.h)
                t.firstSeen = now
                t.lastSeen = now
                tracks.add(t)
                used.add(t.id)
                seen.add(t)
            }
        }
        tracks.removeAll { now - it.lastSeen > 800L }
        return seen
    }
}
