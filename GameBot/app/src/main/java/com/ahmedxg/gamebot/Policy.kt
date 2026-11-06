package com.ahmedxg.gamebot

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

interface Policy {
    fun reset()
    fun decide(p: Perception, now: Long): Action
}

/**
 * Model-free exploration bot ("monkey testing"): wanders, looks around, jumps, crouches, reloads and
 * fires in random bursts. Good for soak / stability / server-load tests of your own game.
 */
class MonkeyPolicy : Policy {
    private val rnd = Random()
    private var moveUntil = 0L
    private var mx = 0f
    private var my = 1f
    private var panUntil = 0L
    private var panDx = 0f
    private var panDy = 0f
    private var fireUntil = 0L
    private var nextFire = 0L
    private var nextJump = 0L
    private var nextCrouch = 0L
    private var nextReload = 0L

    override fun reset() {
        moveUntil = 0L; panUntil = 0L; fireUntil = 0L
        nextFire = 0L; nextJump = 0L; nextCrouch = 0L; nextReload = 0L
    }

    override fun decide(p: Perception, now: Long): Action {
        val a = Action()
        if (now >= moveUntil) {
            moveUntil = now + 700 + rnd.nextInt(1800)
            val ang = (rnd.nextFloat() - 0.5f) * 1.6f
            mx = sin(ang)
            my = cos(ang)
            val r = rnd.nextInt(10)
            if (r == 0) {
                mx = 0f; my = 0f
            } else if (r == 1) {
                mx = 0f; my = -0.6f
            }
        }
        a.moveX = mx
        a.moveY = my
        if (now >= panUntil) {
            panUntil = now + 500 + rnd.nextInt(1500)
            panDx = (rnd.nextFloat() - 0.5f) * 30f
            panDy = (rnd.nextFloat() - 0.5f) * 6f
        }
        a.lookDx = panDx
        a.lookDy = panDy
        if (now >= nextFire) {
            fireUntil = now + 250 + rnd.nextInt(600)
            nextFire = now + 3000 + rnd.nextInt(7000)
        }
        a.fire = now < fireUntil
        if (now >= nextJump) {
            a.taps.add("jump")
            nextJump = now + 3000 + rnd.nextInt(5000)
        } else if (now >= nextCrouch) {
            a.taps.add("crouch")
            nextCrouch = now + 8000 + rnd.nextInt(12000)
        } else if (now >= nextReload) {
            a.taps.add("reload")
            nextReload = now + 15000 + rnd.nextInt(20000)
        }
        a.state = "MONKEY"
        return a
    }
}

/**
 * Detection-driven bot. Needs a detector trained on your game with classes named
 * "enemy" (required) and optionally "enemy_head". Behaviour per skill level:
 * react -> track (smooth look control) -> burst fire when aligned -> strafe; search when nothing is seen.
 */
class TacticalPolicy(
    private val skill: Skill,
    private val aimScale: Float,
    private val crossX: Float,
    private val crossY: Float
) : Policy {
    private val monkey = MonkeyPolicy()
    private val rnd = Random()
    private var firstSeen = 0L
    private var lastSeen = 0L
    private var lastDirX = 0f
    private var strafeDir = 1f
    private var strafeUntil = 0L
    private var burstOn = false
    private var burstUntil = 0L

    override fun reset() {
        monkey.reset()
        firstSeen = 0L; lastSeen = 0L; lastDirX = 0f
        strafeUntil = 0L; burstOn = false; burstUntil = 0L
    }

    override fun decide(p: Perception, now: Long): Action {
        val bodies = ArrayList<Det>()
        val heads = ArrayList<Det>()
        for (d in p.dets) {
            if (d.label == "enemy_head") heads.add(d) else if (d.label.startsWith("enemy")) bodies.add(d)
        }
        var target: Det? = null
        var bestD = Float.MAX_VALUE
        for (d in bodies) {
            val dd = hypot(d.cx - crossX, d.cy - crossY)
            if (dd < bestD) { bestD = dd; target = d }
        }
        var tx = 0f
        var ty = 0f
        var tw = 0f
        var th = 0f
        var bodyH = 0f
        var have = false
        val body = target
        if (body != null) {
            var head: Det? = null
            for (h in heads) {
                if (abs(h.cx - body.cx) <= body.w && h.cy < body.cy) { head = h; break }
            }
            if (head != null) {
                tx = head.cx; ty = head.cy; tw = head.w; th = head.h
            } else {
                tx = body.cx; ty = body.cy - body.h * 0.25f; tw = body.w; th = body.h
            }
            bodyH = body.h
            have = true
        } else if (heads.isNotEmpty()) {
            var hb = heads[0]
            var hd = Float.MAX_VALUE
            for (h in heads) {
                val dd = hypot(h.cx - crossX, h.cy - crossY)
                if (dd < hd) { hd = dd; hb = h }
            }
            tx = hb.cx; ty = hb.cy; tw = hb.w; th = hb.h
            bodyH = hb.h * 6f
            have = true
        }

        if (!have) {
            if (now - lastSeen < 700 && lastSeen != 0L) {
                // just lost the target: keep turning toward where it was
                val a = Action()
                a.lookDx = if (lastDirX >= 0f) 12f else -12f
                a.state = "SWEEP"
                return a
            }
            firstSeen = 0L
            burstOn = false
            burstUntil = 0L
            val a = monkey.decide(p, now)
            a.fire = false
            a.lookDx = a.lookDx * 0.5f
            a.state = "SEARCH"
            return a
        }

        if (firstSeen == 0L || now - lastSeen > 400) firstSeen = now
        lastSeen = now
        val a = Action()
        val ex = tx - crossX
        val ey = ty - crossY
        lastDirX = ex
        if (now - firstSeen < skill.reactionMs) {
            a.state = "REACT"
            return a
        }
        val gain = (skill.aimGain * aimScale).coerceIn(0.05f, 1.2f)
        a.lookDx = (ex * p.screenW * gain).coerceIn(-140f, 140f)
        a.lookDy = (ey * p.screenH * gain).coerceIn(-100f, 100f)

        val aligned = abs(ex) <= max(0.012f, tw * 0.45f) && abs(ey) <= max(0.02f, th * 0.45f)
        if (aligned) {
            if (now >= burstUntil) {
                burstOn = !burstOn
                burstUntil = now + (if (burstOn) skill.burstOnMs else skill.burstOffMs)
            }
            a.fire = burstOn
        } else {
            burstOn = false
            burstUntil = 0L
        }
        if (skill.strafe) {
            if (now >= strafeUntil) {
                strafeDir = -strafeDir
                strafeUntil = now + 350 + rnd.nextInt(750)
            }
            a.moveX = strafeDir * 0.8f
        }
        a.moveY = if (bodyH < 0.18f) 0.5f else if (bodyH > 0.55f) -0.4f else 0f
        a.state = if (aligned) "FIRE" else "TRACK"
        return a
    }
}
