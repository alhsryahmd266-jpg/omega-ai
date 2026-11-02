package com.omega.keyboard

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/** Tiny helpers for threads and an in-app log (so a problem can be read from the app without a computer). */
object Bg {
    val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "omega-bg").apply { isDaemon = true } }

    /** Run off the main thread. Exceptions are logged, never crash the keyboard. */
    fun io(block: () -> Unit) {
        pool.execute {
            try { block() } catch (t: Throwable) { AppLog.e("bg", "task failed", t) }
        }
    }

    fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun uiDelayed(ms: Long, block: () -> Unit) {
        main.postDelayed(block, ms)
    }
}

object AppLog {
    private val lines = ArrayDeque<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    @Synchronized
    fun d(tag: String, msg: String) {
        Log.d("Omega/$tag", msg)
        lines.addLast("${fmt.format(java.util.Date())} $tag: $msg")
        while (lines.size > 300) lines.removeFirst()
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e("Omega/$tag", msg, t)
        d(tag, "ERROR $msg" + (t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
    }

    @Synchronized
    fun dump(last: Int = 120): String = lines.toList().takeLast(last).joinToString("\n")

    @Synchronized
    fun clear() { lines.clear() }
}
