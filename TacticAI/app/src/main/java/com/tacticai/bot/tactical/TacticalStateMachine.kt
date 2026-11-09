package com.tacticai.bot.tactical

enum class Phase { SCOUTING, ENGAGING, REGROUPING }

/** One tick's read of the match, as reported to the state machine — not Android types, so it stays testable. */
data class MatchState(
    val healthPct: Float,       // 0f..1f; 1f when unknown/not wired up yet (never forces a false retreat)
    val visibleEnemies: Int,
    val msSinceEnemySeen: Long, // Long.MAX_VALUE if no enemy has been seen this life
)

/**
 * Decides WHICH posture the bot should take; [UtilityAiStrategy] decides what that posture means in terms
 * of taps. Every threshold has a deliberate gap from its opposite (hysteresis) so a value sitting right on
 * a boundary, or an enemy blinking out of the detector for one frame, can't cause rapid flip-flopping.
 * Verified with a standalone 9-case test (including a 200-tick anti-flap check) before being ported here.
 */
class TacticalStateMachine(
    private val retreatHealth: Float = 0.30f,
    private val recoveredHealth: Float = 0.60f,  // must stay well above retreatHealth — that gap IS the hysteresis
    private val minHealthToEngage: Float = 0.22f,
    private val loseEnemyGraceMs: Long = 2500L,
) {
    var phase: Phase = Phase.SCOUTING
        private set

    fun update(s: MatchState): Phase {
        phase = when (phase) {
            Phase.REGROUPING ->
                if (s.healthPct >= recoveredHealth && s.visibleEnemies == 0) Phase.SCOUTING else Phase.REGROUPING
            Phase.ENGAGING -> when {
                s.healthPct < retreatHealth -> Phase.REGROUPING
                s.visibleEnemies > 0 -> Phase.ENGAGING
                s.msSinceEnemySeen < loseEnemyGraceMs -> Phase.ENGAGING // still "recently" in a fight
                else -> Phase.SCOUTING
            }
            Phase.SCOUTING -> when {
                s.healthPct < retreatHealth -> Phase.REGROUPING
                s.visibleEnemies > 0 && s.healthPct >= minHealthToEngage -> Phase.ENGAGING
                else -> Phase.SCOUTING
            }
        }
        return phase
    }
}
