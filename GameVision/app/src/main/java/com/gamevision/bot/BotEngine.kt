package com.gamevision.bot

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import com.gamevision.bot.act.GestureActor
import com.gamevision.bot.act.NoopStrategy
import com.gamevision.bot.act.PlannedAction
import com.gamevision.bot.act.Strategy
import com.gamevision.bot.detect.ButtonFinder
import com.gamevision.bot.util.ScreenshotUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The loop: capture a frame → find candidate buttons → ask the [Strategy] what to do → act.
 * Runs only between [start] and [stop], which the floating button calls — never on its own.
 */
class BotEngine(
    private val service: AccessibilityService,
    private val strategy: Strategy = NoopStrategy(),
    private val onTick: (TickInfo) -> Unit = {},
) {
    data class TickInfo(val detections: Int, val actionsTaken: Int, val tick: Long, val tookMs: Long)

    private val scope = CoroutineScope(Dispatchers.Default)
    private val actor = GestureActor(service)
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start(periodMs: Long = 120) {
        if (isRunning) return
        job = scope.launch {
            var tick = 0L
            while (true) {
                val t0 = System.currentTimeMillis()
                runCatching { stepOnce(tick) }
                    .onSuccess { info -> onTick(info) }
                tick++
                val elapsed = System.currentTimeMillis() - t0
                delay(maxOf(0, periodMs - elapsed))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun stepOnce(tick: Long): TickInfo {
        val t0 = System.currentTimeMillis()
        val frame: Bitmap = ScreenshotUtil.capture(service) ?: return TickInfo(0, 0, tick, 0)
        val detections = try {
            ButtonFinder.find(frame)
        } finally {
            frame.recycle()
        }
        val actions = strategy.decide(detections, frame.width, frame.height, tick)
        for (a in actions) perform(a)
        return TickInfo(detections.size, actions.size, tick, System.currentTimeMillis() - t0)
    }

    private suspend fun perform(action: PlannedAction) {
        when (action) {
            is PlannedAction.Tap -> actor.tap(action.x, action.y)
            is PlannedAction.Swipe -> actor.swipe(action.x0, action.y0, action.x1, action.y1)
            is PlannedAction.Hold -> actor.hold(action.x, action.y, action.durationMs)
        }
    }
}
