package com.ahmedxg.gamebot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display

object Util {
    const val CHANNEL = "gamebot"

    fun realSize(ctx: Context): Point {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point()
        d.getRealSize(p)
        return p
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "GameBot", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun notification(ctx: Context, text: String, stopService: Class<*>, stopAction: String, toggleAction: String? = null): Notification {
        ensureChannel(ctx)
        val i = Intent(ctx, stopService)
        i.action = stopAction
        val pi = PendingIntent.getService(ctx, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("GameBot")
            .setContentText(text)
            .setOngoing(true)
        if (toggleAction != null) {
            val ti = Intent(ctx, stopService)
            ti.action = toggleAction
            val tp = PendingIntent.getService(ctx, 2, ti, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "START/PAUSE", tp).build())
        }
        b.addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "STOP", pi).build())
        return b.build()
    }
}
