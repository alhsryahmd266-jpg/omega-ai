package com.ahmedxg.gamebot

import android.content.Context

/** Whether the built-in ADB shell has been paired once (the pairing itself lives in the app's own ADB key). */
object ShellState {
    fun paired(ctx: Context): Boolean =
        ctx.getSharedPreferences("gamebot", Context.MODE_PRIVATE).getBoolean("shellPaired", false)

    fun setPaired(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences("gamebot", Context.MODE_PRIVATE).edit().putBoolean("shellPaired", v).apply()
    }
}
