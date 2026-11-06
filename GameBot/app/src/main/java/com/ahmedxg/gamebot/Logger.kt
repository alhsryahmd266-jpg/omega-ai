package com.ahmedxg.gamebot

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private val lines = ArrayList<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun init(ctx: Context) {
        if (file == null) {
            val f = File(ctx.filesDir, "gamebot.log")
            if (f.exists() && f.length() > 512 * 1024) f.delete()
            file = f
        }
    }

    @Synchronized
    fun i(msg: String) {
        Log.i("GameBot", msg)
        val line = fmt.format(Date()) + " " + msg
        lines.add(line)
        while (lines.size > 150) lines.removeAt(0)
        try {
            file?.appendText(line + "\n")
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun tail(n: Int): String = lines.takeLast(n).joinToString("\n")
}
