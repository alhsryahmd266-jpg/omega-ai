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
 * Detection-driven bot (needs a detector trained on YOUR game: classes "enemy", optional "enemy_head" and "ally").
 * Behaviors: search -> avoid / stalk (sneak attack) -> strike -> duel (track, burst-fire, counter-strafe,
 * jump-peeks) -> dodge when hit -> retreat + heal when health is low -> hold the angle (ambush) after losing a
 * close enemy. Never fires while an ally covers the aim point. The match plan ([Style]), the skill level and the
 * [Tactics] switches decide what is used and how aggressively.
 */
class TacticalPolicy(
    private val skill: Skill,
    private val aimScale: Float,
    private val crossX: Float,
    private val crossY: Float,
    private val tactics: Tactics = Tactics()
) : Policy {
    private val style = tactics.style
    private val monkey = MonkeyPolicy()
    private val tracker = Tracker()
    private val rnd = Random()

    private var firstSeen = 0L
    private var lastSeen = 0L
    private var lastDirX = 0f
    private var lastBodyH = 0f
    private var strafeDir = 1f
    private var strafeUntil = 0L
    private var burstOn = false
    private var burstUntil = 0L
    private var settleUntil = 0L
    private var dodgeUntil = 0L
    private var dodgeDir = 1f
    private var dodgeJumped = false
    private var ambushUntil = 0L
    private var engagedAt = 0L
    private var stalkSide = 1f
    private var inStalk = false
    private var crouched = false
    private var strikeUntil = 0L
    private var prevHit = false
    private var lastHealTap = 0L
    private var healUntil = 0L

    override fun reset() {
        monkey.reset()
        tracker.reset()
        firstSeen = 0L; lastSeen = 0L; lastDirX = 0f; lastBodyH = 0f
        strafeUntil = 0L; burstOn = false; burstUntil = 0L; settleUntil = 0L
        dodgeUntil = 0L; dodgeJumped = false; ambushUntil = 0L; engagedAt = 0L
        inStalk = false; crouched = false; strikeUntil = 0L; prevHit = false
        lastHealTap = 0L; healUntil = 0L
    }

    private fun covers(a: Det, px: Float, py: Float): Boolean =
        abs(px - a.cx) <= a.w / 2f + 0.01f && abs(py - a.cy) <= a.h / 2f + 0.01f

    override fun decide(p: Perception, now: Long): Action {
        val hitEdge = p.hit && !prevHit
        prevHit = p.hit

        val bodies = ArrayList<Det>()
        val heads = ArrayList<Det>()
        val allies = ArrayList<Det>()
        for (d in p.dets) {
            if (d.label == "enemy_head") heads.add(d)
            else if (d.label.startsWith("enemy")) bodies.add(d)
            else if (d.label.startsWith("ally") || d.label.startsWith("friend")) allies.add(d)
        }
        val seen = tracker.update(bodies, now)

        var target: Track? = null
        var bestD = Float.MAX_VALUE
        for (t in seen) {
            val dd = hypot(t.cx - crossX, t.cy - crossY)
            if (dd < bestD) { bestD = dd; target = t }
        }
        var tx = 0f
        var ty = 0f
        var tw = 0f
        var th = 0f
        var bodyH = 0f
        var approach = 0f
        var have = false
        val body = target
        if (body != null) {
            var head: Det? = null
            for (hd in heads) {
                if (abs(hd.cx - body.cx) <= body.w && hd.cy < body.cy) { head = hd; break }
            }
            if (head != null) {
                tx = head.cx; ty = head.cy; tw = head.w; th = head.h
            } else {
                tx = body.cx; ty = body.cy - body.h * 0.25f; tw = body.w; th = body.h
            }
            bodyH = body.h
            approach = body.vh
            have = true
        } else if (heads.isNotEmpty()) {
            var hb = heads[0]
            var hdist = Float.MAX_VALUE
            for (hd in heads) {
                val dd = hypot(hd.cx - crossX, hd.cy - crossY)
                if (dd < hdist) { hdist = dd; hb = hd }
            }
            tx = hb.cx; ty = hb.cy; tw = hb.w; th = hb.h
            bodyH = hb.h * 6f
            have = true
        }

        // friendly-fire guard: an ally in front of the crosshair or of the target
        var friendly = false
        for (al in allies) {
            if (covers(al, crossX, crossY) || (have && covers(al, tx, ty))) { friendly = true; break }
        }

        val wasAmbush = now < ambushUntil
        if (have) {
            if (firstSeen == 0L || now - lastSeen > 400L) firstSeen = now
            lastSeen = now
            lastBodyH = bodyH
        }

        val hp = p.health
        val hpKnown = hp >= 0f
        val lowHp = hpKnown && hp < style.retreatHp

        // took damage: evasive maneuver (rising edge only)
        if (hitEdge && tactics.dodge && skill != Skill.EASY && now >= dodgeUntil) {
            dodgeUntil = now + 650L
            dodgeDir = if (rnd.nextBoolean()) 1f else -1f
            dodgeJumped = false
            engagedAt = now
        }

        val a = Action()
        var aligned = false
        if (have) {
            val ex = tx - crossX
            val ey = ty - crossY
            lastDirX = ex
            val gain = (skill.aimGain * aimScale).coerceIn(0.05f, 1.2f)
            a.lookDx = (ex * p.screenW * gain).coerceIn(-140f, 140f)
            a.lookDy = (ey * p.screenH * gain).coerceIn(-100f, 100f)
            aligned = abs(ex) <= max(0.012f, tw * 0.45f) && abs(ey) <= max(0.02f, th * 0.45f)
        }
        val canFire = aligned && !friendly

        // ---- DODGE -------------------------------------------------------------------------
        if (now < dodgeUntil) {
            a.moveX = dodgeDir
            a.moveY = if (have && bodyH > 0.3f) -0.4f else 0.2f
            if (crouched) {
                a.taps.add("crouch")
                crouched = false
            } else if (!dodgeJumped && tactics.jump) {
                a.taps.add("jump")
                dodgeJumped = true
            }
            if (have) {
                a.fire = canFire
            } else {
                a.lookDx = if (lastDirX >= 0f) 18f else -18f
            }
            a.state = "DODGE"
            return a
        }

        // ---- nobody in sight -----------------------------------------------------------------
        if (!have) {
            if (now < healUntil) {
                a.state = "HEAL"          // stand still while healing
                return a
            }
            if (hpKnown && hp < style.healAt && now - lastHealTap > 3500L) {
                lastHealTap = now
                healUntil = now + 1200L
                a.taps.add("heal")
                a.state = "HEAL"
                return a
            }
            if (now < ambushUntil) {
                a.state = "AMBUSH"
                if (now - (ambushUntil - skill.ambushMs) < 350L) a.lookDx = if (lastDirX >= 0f) 10f else -10f
                if (tactics.crouch && !crouched) {
                    a.taps.add("crouch")
                    crouched = true
                }
                return a
            }
            if (lastSeen != 0L && now - lastSeen < 700L) {
                if (tactics.ambush && style.ambush && skill.ambushMs > 0 && lastBodyH >= 0.15f) {
                    ambushUntil = now + skill.ambushMs
                    a.state = "AMBUSH"
                    return a
                }
                a.lookDx = if (lastDirX >= 0f) 12f else -12f
                a.state = "SWEEP"
                return a
            }
            firstSeen = 0L
            burstOn = false
            burstUntil = 0L
            inStalk = false
            if (crouched) {
                a.taps.add("crouch")
                crouched = false
                a.state = "SEARCH"
                return a
            }
            val m = monkey.decide(p, now)
            m.fire = false
            m.lookDx = m.lookDx * 0.5f
            m.state = "SEARCH"
            return m
        }

        // ---- an enemy is in sight --------------------------------------------------------------
        val reactMs = if (wasAmbush) 0 else skill.reactionMs
        if (now - firstSeen < reactMs) {
            a.lookDx = 0f
            a.lookDy = 0f
            a.state = "REACT"
            return a
        }
        ambushUntil = 0L

        val near = bodyH >= 0.28f
        val alerted = p.hit || approach > 0.12f || now - engagedAt < 1500L

        // low health: do not push, back off while covering
        if (lowHp) {
            inStalk = false
            if (now >= strafeUntil) {
                strafeDir = -strafeDir
                strafeUntil = now + 300L + rnd.nextInt(500)
            }
            a.moveY = -0.8f
            a.moveX = strafeDir * 0.6f
            if (tactics.jump && now >= strikeUntil && rnd.nextFloat() < 0.04f) a.taps.add("jump")
            a.fire = canFire && near
            a.state = "RETREAT"
            return a
        }

        // survivor plan: do not wake up far enemies that have not noticed us
        if (style.avoidFar && !alerted && bodyH < 0.14f) {
            if (now >= strafeUntil) {
                strafeDir = -strafeDir
                strafeUntil = now + 900L + rnd.nextInt(900)
            }
            a.lookDx = 0f
            a.lookDy = 0f
            a.moveX = strafeDir * 0.8f
            a.moveY = -0.2f
            a.state = "AVOID"
            return a
        }

        if (tactics.sneak && skill.sneak && style.stalk && !alerted && !near) {
            // sneak attack: approach on an arc, no shooting yet
            if (!inStalk) {
                inStalk = true
                stalkSide = if (rnd.nextBoolean()) 1f else -1f
                if (tactics.crouch && !crouched) {
                    a.taps.add("crouch")
                    crouched = true
                }
            }
            a.moveY = 0.5f
            a.moveX = stalkSide * 0.35f
            a.state = "STALK"
            return a
        }
        if (inStalk) {
            // close enough (or spotted): strike
            inStalk = false
            strikeUntil = now + 1000L
            if (crouched) {
                a.taps.add("crouch")
                crouched = false
            } else if (tactics.jump && rnd.nextFloat() < skill.jumpProb * 2f) {
                a.taps.add("jump")
            }
        } else if (crouched) {
            a.taps.add("crouch")
            crouched = false
        }

        val striking = now < strikeUntil
        if (canFire) {
            if (now >= burstUntil) {
                burstOn = !burstOn
                val on = if (striking) skill.burstOnMs * 2 else skill.burstOnMs
                val off = if (striking) skill.burstOffMs / 2 else skill.burstOffMs
                burstUntil = now + (if (burstOn) on else off)
                if (burstOn) settleUntil = now + 100L
            }
            a.fire = burstOn
            if (burstOn) engagedAt = now
        } else {
            burstOn = false
            burstUntil = 0L
        }

        if (skill.strafe) {
            if (now >= strafeUntil) {
                strafeDir = -strafeDir
                strafeUntil = now + 250L + rnd.nextInt(650)
                if (tactics.jump && rnd.nextFloat() < skill.jumpProb) a.taps.add("jump")
            }
            a.moveX = if (now < settleUntil) 0f else strafeDir * 0.85f
        }
        a.moveY = if (bodyH < 0.18f) 0.5f else if (bodyH > 0.55f) -0.4f else 0f
        a.state = if (striking) "STRIKE" else if (aligned) "FIRE" else "DUEL"
        return a
    }
}
