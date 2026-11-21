package com.omega.keyboard.agent

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.omega.keyboard.AppLog
import com.omega.keyboard.Bg
import com.omega.keyboard.core.ScrollLogic
import com.omega.keyboard.core.ShortcutRows
import com.omega.keyboard.core.UiNode

/**
 * Switches off the shortcut of this keyboard's accessibility service: the round button that floats at the edge of the screen.
 * Android draws that button by itself and no app can remove it directly; the one place that can is the service's page in the system
 * Settings, where the "Shortcut" switch is. This opens that page and presses the switch the way a person would.
 *
 * It only acts when it was started on purpose (a tap on the button in the app), only inside the Settings app, only on this service's
 * page, and never presses the service's own on/off switch (see [ShortcutRows], which decides what to press). When it cannot do it,
 * it says so and leaves the page open, so the switch can be pressed by hand. Nothing is bypassed: it is an ordinary Settings page.
 */
object ShortcutHider {
    enum class Start { OK, NEED_SERVICE, BUSY }

    /** how the last try ended: "" = not tried, "ok", "already" (it was off), "fail" */
    @Volatile var result: String = ""
        private set

    @Volatile private var running = false

    private const val MAX_MS = 75_000L
    private const val POLL_MS = 650L
    private const val AWAY_MS = 20_000L

    /** the pages that show the service's shortcut, the most direct first (the second is the general list, for phones without the first) */
    private fun pageIntents(ctx: Context): List<Intent> {
        val service = ComponentName(ctx, ScreenAgentService::class.java).flattenToString()
        return listOf(
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, service),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
    }

    /** opens the service's page in Settings (or the general accessibility page); true when one opened */
    fun openPage(ctx: Context): Boolean {
        for (i in pageIntents(ctx)) {
            try {
                if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    /** Opens the page and, in the background, switches the shortcut off there. Call it from a screen the person is looking at. */
    fun start(ctx: Context): Start {
        val svc = ScreenAgentService.instance ?: return Start.NEED_SERVICE
        if (running) return Start.BUSY
        running = true
        result = ""
        val opened = openPage(ctx)
        val app = ctx.applicationContext
        Bg.io {
            try {
                if (!opened) finish(app, svc, "fail") else run(app, svc)
            } finally {
                running = false
            }
        }
        return Start.OK
    }

    private fun isSettings(pkg: String): Boolean = pkg == "com.android.settings" || pkg.contains("settings", ignoreCase = true)

    private fun run(app: Context, svc: ScreenAgentService) {
        val name = app.applicationInfo.loadLabel(app.packageManager).toString()
        val started = SystemClock.uptimeMillis()
        val end = started + MAX_MS
        var seen = false                 // the Settings app has been in front at least once
        var awaySince = started
        var lastKey = ""
        var same = 0
        var presses = 0
        var acted = false                // something on the shortcut itself was pressed (not only the list entry)
        var lost = 0
        var scrolls = 0
        var rowPressedAt = 0L
        while (SystemClock.uptimeMillis() < end && ScreenAgentService.instance === svc) {
            Thread.sleep(POLL_MS)
            val now = SystemClock.uptimeMillis()
            val snap = ScreenReader.read(svc)
            if (snap == null || !isSettings(snap.screen.pkg)) {
                snap?.release()
                if (now - awaySince > AWAY_MS) {
                    // the person left Settings (or it never opened): stop quietly after a while
                    if (!seen) finish(app, svc, "fail") else AppLog.d("hider", "left settings, stopped")
                    return
                }
                continue
            }
            seen = true
            awaySince = now
            var wait = 0L
            try {
                val scr = snap.screen
                val inList = rowPressedAt > 0 && now - rowPressedAt < 15_000
                val plan = (if (inList) ShortcutRows.planDialog(scr) else null) ?: ShortcutRows.plan(scr, name)
                when (plan) {
                    is ShortcutRows.Plan.Done -> {
                        finish(app, svc, if (acted) "ok" else "already")
                        return
                    }
                    is ShortcutRows.Plan.Press -> {
                        val key = "${plan.kind}|${plan.node.top}|${plan.node.label}"
                        same = if (key == lastKey) same + 1 else 0
                        lastKey = key
                        if (same >= 3 || presses >= 14) { finish(app, svc, "fail"); return }
                        AppLog.d("hider", "press ${plan.kind} \"${plan.node.label.take(30)}\" try ${same + 1}")
                        press(svc, snap, plan.node, same)
                        presses++
                        lost = 0
                        when (plan.kind) {
                            ShortcutRows.Kind.LIST_ENTRY -> wait = 900
                            ShortcutRows.Kind.ROW -> { acted = true; rowPressedAt = now }
                            ShortcutRows.Kind.SAVE -> { acted = true; rowPressedAt = 0 }
                            else -> acted = true
                        }
                    }
                    is ShortcutRows.Plan.Scroll -> {
                        val list = ScrollLogic.listWithMoreBelow(scr)
                        if (list == null || scrolls >= 10) { finish(app, svc, "fail"); return }
                        scrolls++
                        snap.node(list.id)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    }
                    is ShortcutRows.Plan.Lost -> {
                        lost++
                        AppLog.d("hider", "nothing recognised ($lost)")
                        if (lost >= 10) { finish(app, svc, "fail"); return }
                    }
                }
            } finally {
                snap.release()
            }
            if (wait > 0) Thread.sleep(wait)
        }
        if (ScreenAgentService.instance === svc) finish(app, svc, "fail")
    }

    /** Presses [n]: the first time as a click on it (or on the nearest thing around it that takes clicks), then as a real finger tap. */
    private fun press(svc: AccessibilityService, snap: Snapshot, n: UiNode, attempt: Int): Boolean {
        if (attempt == 0) {
            var node: AccessibilityNodeInfo? = snap.node(n.id)
            var hops = 0
            while (node != null && !node.isClickable && hops < 5) { node = node.parent; hops++ }
            if (node != null && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return Gestures.tap(svc, n.centerX, n.centerY)
    }

    private fun finish(app: Context, svc: AccessibilityService, how: String) {
        result = how
        AppLog.d("hider", "finished: $how")
        when (how) {
            "ok" -> Notice.toast(app, "اتقفل الاختصار ✓ — الأيقونة العايمة هتختفي. باقي الميزات شغّالة زي ما هي")
            "already" -> Notice.toast(app, "الاختصار مقفول أصلًا ✓")
            else -> Notice.toast(app, "مقدرتش أقفله لوحدي. اقفل «الاختصار» (Shortcut) من الصفحة المفتوحة بإيدك")
        }
        if (how != "fail") backToApp(app, svc)
    }

    /** is the Settings app the one in front? (what the screen reader sees; our own app and the system bars are not listed by it) */
    private fun settingsInFront(svc: AccessibilityService): Boolean {
        val snap = ScreenReader.read(svc) ?: return false
        return try { isSettings(snap.screen.pkg) } finally { snap.release() }
    }

    /** from the Settings page back to the app, with the Back button, while Settings is still in front (a few times at most) */
    private fun backToApp(app: Context, svc: AccessibilityService) {
        repeat(4) { n ->
            Thread.sleep(if (n == 0) 1200L else 900L)
            val still = settingsInFront(svc)
            AppLog.d("hider", "back $n: settings in front = $still")
            if (!still) return
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }
}
