package com.ahmedxg.gamebot

import android.content.Context
import com.ahmedxg.gamebot.adb.GbAdbManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Built-in shell backend: the app talks to the phone's own wireless-debugging daemon (paired once, no
 * Shizuku), starts [ShellServer] as shell and sends it touch commands over the ADB stream.
 */
class AdbShellInput(private val ctx: Context) : InputBackend {
    override val name = "Shell"

    private var stream: AdbStream? = null
    private var out: OutputStream? = null
    private val pending = StringBuilder()

    @Volatile
    private var ready = false

    @Volatile
    private var error: String? = null

    @Volatile
    private var broken = false

    /** @return null when ready, otherwise the reason. */
    fun prepare(): String? {
        try {
            val m = GbAdbManager.getInstance(ctx)
            if (!m.isConnected()) {
                val ok = try {
                    m.autoConnect(ctx, 6000L)
                } catch (e: AdbPairingRequiredException) {
                    return "لازم تفعّل الشل مرة واحدة (اقتران)"
                }
                if (!ok) return "مش قادر أوصل للـ ADB: شغّل Wireless debugging وكون على Wi-Fi"
            }
            val apk = ctx.applicationInfo.sourceDir
            val s = m.openStream("exec:CLASSPATH=$apk exec app_process /system/bin com.ahmedxg.gamebot.ShellServer")
            stream = s
            out = s.openOutputStream()
            val latch = CountDownLatch(1)
            Thread {
                try {
                    val r = BufferedReader(InputStreamReader(s.openInputStream()))
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.startsWith("READY")) {
                            ready = true
                            latch.countDown()
                        } else if (line.startsWith("ERR")) {
                            if (!ready) error = line
                            Logger.i("shell server: $line")
                            latch.countDown()
                        }
                    }
                } catch (t: Throwable) {
                } finally {
                    broken = true
                    latch.countDown()
                }
            }.start()
            if (!latch.await(7, TimeUnit.SECONDS)) return "السيرفر ما ردّش"
            if (!ready) return error ?: "السيرفر وقف"
            broken = false
            return null
        } catch (t: Throwable) {
            return "فشل الشل: ${t.javaClass.simpleName} ${t.message}"
        }
    }

    @Synchronized
    override fun touchDown(id: Int, x: Float, y: Float) {
        pending.append("D ").append(id).append(' ').append(x.toInt()).append(' ').append(y.toInt()).append('\n')
    }

    @Synchronized
    override fun touchMove(id: Int, x: Float, y: Float) {
        pending.append("M ").append(id).append(' ').append(x.toInt()).append(' ').append(y.toInt()).append('\n')
    }

    @Synchronized
    override fun touchUp(id: Int) {
        pending.append("U ").append(id).append('\n')
    }

    @Synchronized
    override fun flush() {
        pending.append("F\n")
        write()
    }

    @Synchronized
    override fun releaseAll() {
        pending.setLength(0)
        pending.append("X\n")
        write()
    }

    private fun write() {
        val o = out
        if (o == null || broken) {
            pending.setLength(0)
            return
        }
        try {
            o.write(pending.toString().toByteArray(Charsets.UTF_8))
            o.flush()
        } catch (t: Throwable) {
            broken = true
            Logger.i("shell write failed: ${t.javaClass.simpleName} ${t.message}")
        }
        pending.setLength(0)
    }

    override fun close() {
        try {
            pending.setLength(0)
            pending.append("X\nQ\n")
            write()
        } catch (t: Throwable) {
        }
        try { stream?.close() } catch (t: Throwable) { }
        stream = null
        out = null
    }
}
