package com.ahmedxg.gamebot

/** Difficulty profile of the bot (QA bots usually need several levels). Pure Kotlin: no Android types. */
enum class Skill(
    val label: String,
    val reactionMs: Int,
    val aimGain: Float,
    val burstOnMs: Int,
    val burstOffMs: Int,
    val strafe: Boolean,
    val sneak: Boolean,       // may stalk / flank before striking
    val jumpProb: Float,      // chance to jump-peek each time the strafe direction flips
    val ambushMs: Int         // how long it holds an angle after losing an enemy at close range
) {
    EASY("Easy", 450, 0.30f, 120, 600, false, false, 0.00f, 0),
    NORMAL("Normal", 250, 0.50f, 160, 350, true, true, 0.10f, 1500),
    PRO("Pro", 120, 0.70f, 220, 180, true, true, 0.20f, 2500),
    MAX("Max", 0, 0.92f, 450, 80, true, true, 0.30f, 3500);

    companion object {
        fun from(name: String?): Skill = values().firstOrNull { it.name == name } ?: NORMAL
    }
}

/** Match plan / personality. Pure Kotlin. */
enum class Style(
    val label: String,
    val stalk: Boolean,        // sneak up on unaware enemies
    val ambush: Boolean,       // hold the angle after losing a close enemy
    val retreatHp: Float,      // below this health fraction it retreats instead of pushing
    val healAt: Float,         // heals when nobody is around and health is below this fraction
    val avoidFar: Boolean      // avoids unaware far enemies (zone-first, survive)
) {
    BALANCED("Balanced", true, true, 0.30f, 0.55f, false),
    AGGRESSIVE("Aggressive", false, false, 0.15f, 0.35f, false),
    STEALTH("Stealth", true, true, 0.35f, 0.65f, false),
    SURVIVOR("Survivor", false, true, 0.50f, 0.75f, true);

    companion object {
        fun from(name: String?): Style = values().firstOrNull { it.name == name } ?: BALANCED
    }
}
