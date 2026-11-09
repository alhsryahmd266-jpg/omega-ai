package com.ahmedxg.gamebot

import android.content.Context

/**
 * Touch injection abstraction. Pointer ids are chosen by the caller (0..9).
 * touchDown/touchUp may send immediately; touchMove only records, [flush] sends.
 */
interface InputBackend {
    val name: String
    fun touchDown(id: Int, x: Float, y: Float)
    fun touchMove(id: Int, x: Float, y: Float)
    fun touchUp(id: Int)
    fun flush()
    fun releaseAll()
    fun close()
}

object Backends {
    /** @return backend (or null) and an info/error text. */
    fun create(ctx: Context, pref: Backend): Pair<InputBackend?, String> {
        val order = when (pref) {
            Backend.SHIZUKU -> listOf(Backend.SHIZUKU)
            Backend.ACCESSIBILITY -> listOf(Backend.ACCESSIBILITY)
            Backend.SHELL -> listOf(Backend.SHELL)
            Backend.AUTO -> if (ShellState.paired(ctx)) {
                listOf(Backend.SHELL, Backend.SHIZUKU, Backend.ACCESSIBILITY)
            } else {
                listOf(Backend.SHIZUKU, Backend.ACCESSIBILITY)
            }
        }
        val errs = ArrayList<String>()
        for (b in order) {
            when (b) {
                Backend.SHIZUKU -> {
                    val s = ShizukuInput()
                    val e = s.prepare()
                    if (e == null) return Pair(s, "Shizuku") else errs.add("Shizuku: $e")
                }
                Backend.SHELL -> {
                    val s = AdbShellInput(ctx)
                    val e = s.prepare()
                    if (e == null) return Pair(s, "Shell") else errs.add("Shell: $e")
                }
                Backend.ACCESSIBILITY -> {
                    if (BotAccessibilityService.instance != null) return Pair(AccessibilityInput(), "Accessibility")
                    errs.add("Accessibility: الخدمة مش مفعّلة")
                }
                else -> {
                }
            }
        }
        return Pair(null, errs.joinToString(" | "))
    }
}
