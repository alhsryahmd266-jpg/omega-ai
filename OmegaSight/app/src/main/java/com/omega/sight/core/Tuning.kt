package com.omega.sight.core

/**
 * Everything the owner can tune. All sliders are 0..1 so the screen stays simple; the engine turns them into real numbers
 * (see AimController, MotionDetector, TargetPicker).
 */
data class Tuning(
    /** how much of the needed correction is applied (0 = nothing, 1 = all of it) */
    val strength: Float = 0.9f,
    /** how far from the crosshair a target may be and still be picked, as a share of the frame height (0.1 .. 1.2; 1.2 = the whole screen) */
    val fov: Float = 1.2f,
    /** 0 = quick and sharp, 1 = soft and slow */
    val smooth: Float = 0.25f,
    /** the fastest image motion the correction may ask for, in frame heights per second */
    val maxSpeed: Float = 3.2f,
    /** 0 = chest, 1 = head: where inside a body box the aim point sits when no head box exists */
    val headBias: Float = 0.5f,
    /** how much of the target's own movement is led (0 = none, 1 = the measured delay, 2 = double) */
    val lead: Float = 1.0f,
    /** 0 = only strong movement, 1 = every small change */
    val motionSensitivity: Float = 0.6f,
    /** 0 = tiny far movers count, 1 = only big ones */
    val minBlob: Float = 0.35f,
    /** moving shapes the model does not know are targets as well */
    val motionTargets: Boolean = true,
    /** look again at a higher zoom around the crosshair (far targets) */
    val farMode: Boolean = true,
    /** the model's confidence needed for a detection */
    val modelScore: Float = 0.35f,
    /** the glowing red light the bots carry counts as an enemy mark (found by its colour) */
    val glow: Boolean = true,
    /** 0 = only strong red lights, 1 = weak ones too */
    val glowSens: Float = 0.6f,
    /** the vision model runs every n-th frame while no target is locked (it always runs while firing) */
    val idleEvery: Int = 3,
)
