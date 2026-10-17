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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Floating buttons over every app. Both can be dragged anywhere on the screen.
 *  - GVR button: tap pauses or resumes the agent; long-press closes both buttons.
 *  - Access button: shows whether screen control is connected, and opens the
 *    Accessibility settings page in one tap.
 */
class GvrBubbleService : Service() {

    private var wm: WindowManager? = null
    private var column: LinearLayout? = null
    private var accessBtn: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
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

    /** Drag the whole column by any button. A short tap still runs onTap. */
    private fun makeDraggable(v: View, onTap: () -> Unit, onLong: (() -> Unit)?) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val longPress = Runnable {
            if (!moved && onLong != null) onLong.invoke()
        }
        v.setOnTouchListener { _, e ->
            val params = lp ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    if (onLong != null) handler.postDelayed(longPress, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (!moved && (abs(dx) > 12 || abs(dy) > 12)) {
                        moved = true
                        handler.removeCallbacks(longPress)
                    }
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        column?.let { wm?.updateViewLayout(it, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (!moved) onTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }
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

        val access = TextView(this)
        access.setTextColor(0xFFFFFFFFL.toInt())
        access.setPadding(28, 14, 28, 14)
        accessBtn = access
        updateAccessLabel()

        val closeBtn = TextView(this)
        closeBtn.text = "✕ إغلاق"
        closeBtn.setTextColor(0xFFFFFFFFL.toInt())
        closeBtn.setBackgroundColor(0xFF333333L.toInt())
        closeBtn.setPadding(28, 14, 28, 14)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(gvr)
        col.addView(access)
        col.addView(closeBtn)
        column = col

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = resources.displayMetrics.widthPixels - 300
        params.y = 300
        lp = params

        makeDraggable(gvr, onTap = {
            GvrAccessibilityService.paused = !GvrAccessibilityService.paused
            gvr.text = if (GvrAccessibilityService.paused) "GVR ▶" else "GVR ⏹"
        }, onLong = { stop(this) })

        makeDraggable(access, onTap = {
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }, onLong = null)

        makeDraggable(closeBtn, onTap = { stop(this) }, onLong = null)
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
        lp = null
        super.onDestroy()
    }
}
