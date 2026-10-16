package expo.modules.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * A floating button over every app, kept alive as a foreground service.
 * Tap pauses or resumes the agent. Long-press closes it.
 * It stays on until the user closes it, and comes back after a reboot if it was on.
 */
class GvrBubbleService : Service() {

    private var wm: WindowManager? = null
    private var bubble: TextView? = null

    companion object {
        private const val PREFS = "gvr_bubble"
        private const val KEY_ON = "on"
        private const val CHANNEL = "gvr_bubble"
        private const val NOTIF_ID = 4711

        fun start(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, true).apply()
            val i = Intent(ctx, GvrBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, false).apply()
            ctx.stopService(Intent(ctx, GvrBubbleService::class.java))
        }

        fun wasOn(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val tv = TextView(this)
        tv.text = if (GvrAccessibilityService.paused) "GVR ▶" else "GVR ⏹"
        tv.setTextColor(0xFFFFFFFFL.toInt())
        tv.setBackgroundColor(0xFF5B3FD6L.toInt())
        tv.setPadding(28, 18, 28, 18)
        tv.setOnClickListener {
            GvrAccessibilityService.paused = !GvrAccessibilityService.paused
            tv.text = if (GvrAccessibilityService.paused) "GVR ▶" else "GVR ⏹"
        }
        tv.setOnLongClickListener {
            stop(this)
            true
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 16
        params.y = 300
        manager.addView(tv, params)
        bubble = tv
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "GVR Agent", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notif: Notification = builder
            .setContentTitle("GVR Agent")
            .setContentText("زرار الإيقاف شغال فوق الشاشة. اضغط مطولاً عليه لقفله.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        bubble?.let { wm?.removeView(it) }
        bubble = null
        super.onDestroy()
    }
}
