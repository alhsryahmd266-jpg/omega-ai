package com.ahmedxg.gamebot

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/** Which app is in the foreground? Used so the bot never injects touches outside the configured game. */
object ForegroundGuard {
    fun foreground(ctx: Context): String? {
        val svc = BotAccessibilityService.instance
        if (svc != null && BotAccessibilityService.lastPackage.isNotEmpty()) {
            return BotAccessibilityService.lastPackage
        }
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val ev = usm.queryEvents(end - 3L * 60 * 60 * 1000, end)
            val e = UsageEvents.Event()
            var cur: String? = null
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    cur = e.packageName
                } else if (e.eventType == UsageEvents.Event.ACTIVITY_PAUSED && cur == e.packageName) {
                    cur = null
                }
            }
            cur
        } catch (t: Throwable) {
            null
        }
    }
}
