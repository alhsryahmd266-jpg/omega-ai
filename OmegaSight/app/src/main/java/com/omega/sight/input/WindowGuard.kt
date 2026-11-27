package com.omega.sight.input

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import com.omega.sight.Prefs
import com.omega.sight.core.Cover
import com.omega.sight.engine.Hub
import java.util.concurrent.ConcurrentHashMap

/**
 * Looks, a few times a second while the capture runs, at which windows are on the screen and whether any of them lies over the place
 * where the aiming finger works. It reads only the package, the kind and the rectangle of each window, never what a window shows.
 * The decision itself is [Cover.judge]; this class gathers the list and keeps the newest answer for the aim loop.
 *
 * Everything runs on its own thread: asking a window for its package can wait for that app, and that must never hold up the screen
 * or the aim loop. If an answer does not come in time the aim loop treats it as "covered" and stays still.
 */
class WindowGuard(private val svc: AccessibilityService) {
    private val thread = HandlerThread("sight-guard", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
    private val handler = Handler(thread.looper)
    private val prefs = Prefs(svc)

    /** window id -> package, learned from the window events; asking the window itself is the slow way and only done for an unknown id */
    private val pkgs = ConcurrentHashMap<Int, String>()

    @Volatile
    var state: Cover.State = Cover.State.UNKNOWN
        private set

    /** the package of the window that covers the zone ("?" = a window that did not say) */
    @Volatile
    var by: String = ""
        private set

    /** when the newest answer was made (uptime ms) */
    @Volatile
    var at = 0L
        private set

    private var stopped = false
    private var lastShown = ""
    private val debugLog = (svc.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            try {
                scan()
            } catch (_: Throwable) {
                publish(Cover.State.UNKNOWN, "", "")
            }
            if (!stopped) handler.postDelayed(this, if (Hub.stats.running) SCAN_MS else IDLE_MS)
        }
    }

    fun start() {
        handler.post(tick)
    }

    /** the capture has just started: look now, not at the next slow tick */
    fun poke() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    /** a window-change event told which package owns a window */
    fun learn(windowId: Int, pkg: String) {
        if (windowId >= 0) pkgs[windowId] = pkg
    }

    private fun scan() {
        if (!Hub.stats.running) {
            publish(Cover.State.UNKNOWN, "", "")
            // nothing has been looked at: until the first real look the aim loop stays still
            at = 0L
            return
        }
        val list: List<AccessibilityWindowInfo>? = try {
            svc.windows
        } catch (_: Throwable) {
            null
        }
        if (list.isNullOrEmpty()) {
            publish(Cover.State.UNKNOWN, "", "no windows")
            return
        }
        val game = prefs.gamePkg
        val rect = Rect()
        val live = HashSet<Int>()
        val wins = ArrayList<Cover.Win>(list.size)
        var firstApp = true
        for (w in list) {
            live.add(w.id)
            w.getBoundsInScreen(rect)
            val kind = w.type
            var pkg: String? = pkgs[w.id]
            if (pkg == null && kind != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                pkg = packageOf(w)
                if (pkg != null) pkgs[w.id] = pkg
            }
            // a window of the app in front that never said its package: the events say that the game is in front, and it is the top one
            if (pkg == null && kind == AccessibilityWindowInfo.TYPE_APPLICATION && firstApp && SightService.foreground == game) pkg = game
            if (kind == AccessibilityWindowInfo.TYPE_APPLICATION) firstApp = false
            wins.add(Cover.Win(pkg ?: "", kind, rect.left, rect.top, rect.right, rect.bottom))
        }
        pkgs.keys.retainAll(live)
        val v = Cover.judge(wins, game, svc.packageName, Hub.zone)
        publish(v.state, v.by, if (debugLog) wins.take(5).joinToString(" ") else "")
    }

    /** the package of a window, from its root node; only the package name is read from it */
    @Suppress("DEPRECATION")
    private fun packageOf(w: AccessibilityWindowInfo): String? {
        val root = try {
            w.root
        } catch (_: Throwable) {
            null
        } ?: return null
        val p = root.packageName?.toString()
        try {
            root.recycle()
        } catch (_: Throwable) {
        }
        return p
    }

    private fun publish(s: Cover.State, who: String, detail: String) {
        by = who
        state = s
        at = SystemClock.uptimeMillis()
        if (debugLog) {
            val shown = "$s $who"
            if (shown != lastShown) {
                lastShown = shown
                Log.i("SightLive", "guard: $s by=${who.ifEmpty { "-" }} windows: $detail")
            }
        }
    }

    companion object {
        private const val SCAN_MS = 200L
        private const val IDLE_MS = 1000L
    }
}
