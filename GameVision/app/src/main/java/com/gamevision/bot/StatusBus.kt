package com.gamevision.bot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide, in-memory only — nothing here is written to disk or sent anywhere. */
object StatusBus {
    data class State(
        val accessibilityConnected: Boolean = false,
        val running: Boolean = false,
        val lastTick: BotEngine.TickInfo? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setServiceConnected(connected: Boolean) {
        _state.value = _state.value.copy(accessibilityConnected = connected, running = connected && _state.value.running)
    }

    fun setRunning(running: Boolean) {
        _state.value = _state.value.copy(running = running)
    }

    fun postTick(info: BotEngine.TickInfo) {
        _state.value = _state.value.copy(lastTick = info)
    }
}
