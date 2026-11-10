package com.ahmedxg.gamebot

import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.io.File

/** One-tap "what is wrong" report: states of every permission/backend plus the recent log. Safe to share. */
object Diagnostics {
    private fun mark(b: Boolean) = if (b) "OK" else "NO"

    fun collect(ctx: Context): String {
        val sb = StringBuilder()
        val cfg = Config.load(ctx)
        sb.append("GameBot diagnostics\n")
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" | Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            sb.append("app: ").append(pi.versionName).append(" (").append(pi.longVersionCode).append(")\n")
        } catch (e: Throwable) {
        }
        sb.append("\n-- permissions --\n")
        sb.append("overlay: ").append(mark(Settings.canDrawOverlays(ctx))).append('\n')
        sb.append("accessibility service connected: ").append(mark(BotAccessibilityService.instance != null)).append('\n')
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        sb.append("battery optimization ignored: ").append(mark(pm.isIgnoringBatteryOptimizations(ctx.packageName))).append('\n')
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        sb.append("notifications allowed: ").append(mark(nm.areNotificationsEnabled())).append('\n')
        sb.append("shizuku: ").append(try {
            if (!rikka.shizuku.Shizuku.pingBinder()) "not running" else "running"
        } catch (t: Throwable) {
            "unavailable"
        }).append('\n')

        sb.append("\n-- built-in shell (wireless debugging) --\n")
        sb.append("paired (saved flag): ").append(mark(ShellState.paired(ctx))).append('\n')
        try {
            sb.append("developer options on: ").append(mark(Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1)).append('\n')
            sb.append("wireless debugging on: ").append(mark(Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1)).append('\n')
        } catch (t: Throwable) {
            sb.append("settings read failed: ").append(t.javaClass.simpleName).append('\n')
        }
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            sb.append("connected to Wi-Fi: ").append(mark(caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))).append('\n')
        } catch (t: Throwable) {
        }

        sb.append("\n-- input backends (real test) --\n")
        for (b in listOf(Backend.ACCESSIBILITY, Backend.SHIZUKU, Backend.SHELL)) {
            val r = try {
                val pair = Backends.create(ctx, b)
                val ok = pair.first != null
                try { pair.first?.close() } catch (e: Throwable) { }
                if (ok) "READY" else pair.second
            } catch (t: Throwable) {
                "error " + t.javaClass.simpleName + " " + t.message
            }
            sb.append(b.name).append(": ").append(r).append('\n')
        }

        sb.append("\n-- model --\n")
        val mf = if (cfg.modelFile.isNotEmpty()) File(cfg.modelFile) else null
        sb.append("model file: ").append(if (mf != null && mf.exists()) mf.name + " (" + mf.length() / 1024 + " KB)" else "none -> the bot can only explore (Monkey)").append('\n')

        sb.append("\n-- settings --\n")
        sb.append("game: ").append(cfg.gamePackage.ifEmpty { "(not selected)" }).append('\n')
        sb.append("mode=").append(cfg.mode).append(" skill=").append(cfg.skill).append(" style=").append(cfg.style)
            .append(" backend=").append(cfg.backend).append(" autoStart=").append(cfg.autoStart)
            .append(" calibrate=").append(cfg.autoCalibrate).append('\n')
        sb.append("bot running: ").append(BotService.running).append(" | status: ").append(BotService.status).append('\n')

        val rep = File(ctx.filesDir, "last_report.txt")
        if (rep.exists()) sb.append("\n-- last report --\n").append(rep.readText().take(1200)).append('\n')
        sb.append("\n-- recent log --\n").append(Logger.tail(45)).append('\n')
        return sb.toString()
    }
}
