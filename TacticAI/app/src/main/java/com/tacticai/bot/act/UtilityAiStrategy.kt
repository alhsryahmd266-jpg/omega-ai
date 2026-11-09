package com.tacticai.bot.act

import com.tacticai.bot.Detection
import com.tacticai.bot.detect.Team
import com.tacticai.bot.detect.WorldDetection
import com.tacticai.bot.tactical.MatchState
import com.tacticai.bot.tactical.Phase
import com.tacticai.bot.tactical.SpatialMemory
import com.tacticai.bot.tactical.TacticalStateMachine
import com.tacticai.bot.tactical.TeamColorFilter

/** One tick's full reasoning, for the status screen / logs — not just the final taps. */
data class TacticalReport(
    val phase: Phase,
    val enemies: List<WorldDetection>,
    val friendlies: List<WorldDetection>,
    val retreatTarget: Pair<Float, Float>?,
    val note: String,
)

/**
 * The "brain": combines the three deterministic systems (team colour, spatial memory, state machine —
 * see their own files for why none of them is a trained model) with whatever [WorldDetection]s the vision
 * model found this tick, to decide the bot's POSTURE (scout / engage / regroup) and, when retreating,
 * WHERE to retreat to.
 *
 * What this class deliberately does NOT do yet: turn that posture into actual taps. [PlannedAction] needs
 * real screen coordinates for "the move stick", "the fire button", etc., and those only exist once this
 * game's controls are calibrated (manually, or via [com.tacticai.bot.detect.ButtonFinder] once it is
 * wired to a calibration step). Until then this class is exactly as safe as [NoopStrategy] for real taps,
 * while doing and reporting the real reasoning — wire [onReport] to see it working.
 */
class UtilityAiStrategy(
    private val friendlyColorCalibrated: Boolean,
    private val lookupFriendlyHint: (WorldDetection) -> Boolean = { false }, // e.g. MinimapTeamReader, once wired
    private val onReport: (TacticalReport) -> Unit = {},
) : Strategy {

    private val memory = SpatialMemory()
    private val fsm = TacticalStateMachine()
    private val colorFilter = TeamColorFilter()

    private var lastEnemySeenAtTick = Long.MIN_VALUE
    private var lastHealthPct = 1f // unknown until a real health-bar reader exists: never forces a false retreat

    /** World-space detections, classified by colour/hint. The action layer still owns [Detection] (UI controls). */
    fun observe(world: List<WorldDetection>, tick: Long): List<WorldDetection> =
        world.map { d ->
            val team = when {
                lookupFriendlyHint(d) -> Team.FRIENDLY
                friendlyColorCalibrated -> Team.UNKNOWN // colour check happens in classify(); kept separate so callers can pass raw pixels
                else -> Team.UNKNOWN
            }
            d.copy(team = team)
        }

    /** Call once per tick with this tick's classified world detections; [ButtonFinder] results are ignored here on purpose (see class doc). */
    override fun decide(detections: List<Detection>, screenW: Int, screenH: Int, tick: Long): List<PlannedAction> {
        // No WorldDetections reach this overload (BotEngine only passes button Detections today) — report
        // an idle tick rather than guess. decideWithWorld() below is the real entry point once the engine
        // is updated to also pass vision-model detections (tracked separately from UI-control detections).
        onReport(TacticalReport(fsm.phase, emptyList(), emptyList(), null, "لسه مفيش قراءة من نموذج الرؤية للعالم — بس بوصة الأزرار."))
        return emptyList()
    }

    fun decideWithWorld(world: List<WorldDetection>, screenW: Int, screenH: Int, tick: Long): List<PlannedAction> {
        val enemies = world.filter { it.team == Team.ENEMY }
        val friendlies = world.filter { it.team == Team.FRIENDLY }

        if (enemies.isNotEmpty()) lastEnemySeenAtTick = tick
        val msSinceEnemy = if (lastEnemySeenAtTick == Long.MIN_VALUE) Long.MAX_VALUE else (tick - lastEnemySeenAtTick) * TICK_MS

        val phase = fsm.update(MatchState(lastHealthPct, enemies.size, msSinceEnemy))

        var retreatTarget: Pair<Float, Float>? = null
        val note = when (phase) {
            Phase.REGROUPING -> {
                // crude self-position proxy until a real player-marker reader exists: screen centre.
                retreatTarget = memory.nearestSafe(0.5f, 0.5f)
                "تراجع: الصحة ${(lastHealthPct * 100).toInt()}%، هتوجه للنقطة (${"%.2f".format(retreatTarget.first)}, ${"%.2f".format(retreatTarget.second)})"
            }
            Phase.ENGAGING -> {
                val nearest = enemies.minByOrNull { d -> distanceToCenter(d, screenW, screenH) }
                if (nearest != null) "اشتباك: أقرب عدو عند (${nearest.centerX}, ${nearest.centerY})، ${friendlies.size} صديق قريب"
                else "اشتباك: مفيش عدو واضح الفريم ده (ضمن فترة سماح)"
            }
            Phase.SCOUTING -> "استطلاع: ${enemies.size} عدو ظاهر، ${friendlies.size} صديق"
        }
        onReport(TacticalReport(phase, enemies, friendlies, retreatTarget, note))

        // See class doc: no calibrated control positions yet, so no real taps are produced.
        return emptyList()
    }

    /** Wire this once a health-bar reader exists; until then the state machine never retreats on health alone. */
    fun reportHealth(pct: Float) { lastHealthPct = pct.coerceIn(0f, 1f) }

    fun decay() = memory.decay()
    fun markDeathHere(nx: Float, ny: Float) = memory.markDeath(nx, ny)

    private fun distanceToCenter(d: WorldDetection, w: Int, h: Int): Double =
        kotlin.math.hypot((d.centerX - w / 2).toDouble(), (d.centerY - h / 2).toDouble())

    companion object {
        private const val TICK_MS = 55L // keep in sync with BotEngine.start()'s default periodMs
    }
}

/**
 * Ships as the default on purpose: it looks, logs what it sees, and touches nothing. A strategy tuned on
 * nothing but a generic "icon-like region" heuristic would tap at random in WHATEVER app is on screen —
 * worse than doing nothing. Replace this once real gameplay reference footage is available (see README,
 * "Teaching it your game").
 */
class NoopStrategy : Strategy {
    override fun decide(detections: List<Detection>, screenW: Int, screenH: Int, tick: Long): List<PlannedAction> = emptyList()
}
