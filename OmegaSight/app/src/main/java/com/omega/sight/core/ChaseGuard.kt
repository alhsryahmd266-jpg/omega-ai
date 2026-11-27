package com.omega.sight.core

/**
 * A safety net against a loop that feeds itself: the camera turns, the turning makes something look like a target, the app turns the
 * camera toward it, and round it goes. Whatever the reason (a false target, a target that cannot be reached because the game stops the
 * camera at its limit, a game that answers far slower than expected), a chase that does not end on a target within a few seconds is
 * stopped for a moment. The caller then forgets what it was following and the finger stays away until the hold is over.
 *
 * Time is passed in (seconds per frame), so the rule is tested on a plain JVM without a clock.
 */
class ChaseGuard(private val limit: Float = 3.0f, private val hold: Float = 2.0f) {
    private var chase = 0f
    private var idle = 0f
    private var held = 0f

    /** how many times the guard has stopped a chase */
    var trips = 0
        private set

    /** true while the finger has to stay away */
    val holding: Boolean get() = held > 0f

    /**
     * Call once per frame.
     * @param chasing the finger was told to move toward a target in this frame
     * @param onTarget the target is (nearly) under the crosshair
     * @return true in the frame in which the guard stops a chase
     */
    fun update(chasing: Boolean, onTarget: Boolean, dt: Float): Boolean {
        if (held > 0f) {
            held -= dt
            chase = 0f
            idle = 0f
            return false
        }
        if (!chasing) {
            idle += dt
            // a pause of more than half a second ends the chase: the next one starts from zero
            if (idle > 0.6f) chase = 0f
            return false
        }
        idle = 0f
        if (onTarget) {
            chase = 0f
            return false
        }
        chase += dt
        if (chase < limit) return false
        chase = 0f
        held = hold
        trips++
        return true
    }

    fun reset() {
        chase = 0f
        idle = 0f
        held = 0f
    }
}
