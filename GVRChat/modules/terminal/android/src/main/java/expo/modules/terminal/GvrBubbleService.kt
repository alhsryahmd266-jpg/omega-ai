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
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating buttons over every app.
 *  - GVR button: tap pauses or resumes the agent, long-press closes everything.
 *  - Access button: shows whether screen control is connected, and opens the
 *    Accessibility settings page in one tap. Android does not let an app switch
 *    the accessibility service on or off by itself, so the user flips the switch there.
 */
class GvrBubbleService : Service() {

    private var wm: WindowManager? = null
    private var column: LinearLayout? = null
    private var accessBtn: TextView? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateAccessLabel()
            handler.postDelayed(this, 2000)
        }
    }

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

    private fun accessOn(): Boolean = GvrAccessibilityService.instance != null

    private fun updateAccessLabel() {
        accessBtn?.text = if (accessOn()) "♿ مفعّلة" else "♿ مقفولة"
        accessBtn?.setBackgroundColor(if (accessOn()) 0xFF1B7A3EL.toInt() else 0xFF7A1B1BL.toInt())
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager

        val gvr = TextView(this)
        gvr.text = if (GvrAccessibilityService.paused) "GVR ▶" else "GVR ⏹"
        gvr.setTextColor(0xFFFFFFFFL.toInt())
        gvr.setBackgroundColor(0xFF5B3FD6L.toInt())
        gvr.setPadding(28, 18, 28, 18)
        gvr.setOnClickListener {
            GvrAccessibilityService.paused = !GvrAccessibilityService.paused
            gvr.text = if (GvrAccessibilityService.paused) "GVR ▶" else "GVR ⏹"
        }
        gvr.setOnLongClickListener {
            stop(this)
            true
        }

        val access = TextView(this)
        access.setTextColor(0xFFFFFFFFL.toInt())
        access.setPadding(28, 14, 28, 14)
        access.setOnClickListener {
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
        accessBtn = access
        updateAccessLabel()

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(gvr)
        col.addView(access)
        column = col

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
        manager.addView(col, params)
        handler.post(refresh)
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "GVR Agent", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notif: Notification = builder
            .setContentTitle("GVR Agent")
            .setContentText("الأزرار شغالة فوق الشاشة. اضغط مطولاً على GVR لقفلها.")
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
        handler.removeCallbacks(refresh)
        column?.let { wm?.removeView(it) }
        column = null
        accessBtn = null
        super.onDestroy()
    }
}
