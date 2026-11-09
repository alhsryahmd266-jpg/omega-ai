package com.tacticai.bot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * A small circular button drawn over whatever app is in front, draggable anywhere on screen. Tapping it
 * (not dragging it) toggles the bot on/off — the only way the bot ever starts or stops.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: ImageView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification(running = false))
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        CaptureAccessibilityService.instance?.stopEngine()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    private fun addBubble() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val view = ImageView(this).apply {
            setImageResource(R.drawable.overlay_button_off)
            layoutParams = android.view.ViewGroup.LayoutParams(size, size)
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size * 2
            y = resources.displayMetrics.heightPixels / 3
        }
        params = lp
        attachDragAndTap(view, lp)
        wm.addView(view, lp)
        bubble = view
    }

    private fun attachDragAndTap(view: View, lp: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var downAt = 0L
        var dragged = false

        view.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y
                    downAt = System.currentTimeMillis()
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (abs(dx) > TAP_SLOP || abs(dy) > TAP_SLOP) dragged = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    runCatching { wm.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged && System.currentTimeMillis() - downAt < TAP_MAX_MS) toggle()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggle() {
        val svc = CaptureAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, "فعّل خدمة الوصول الأول من الإعدادات", Toast.LENGTH_LONG).show()
            return
        }
        val nowRunning = !(StatusBus.state.value.running)
        if (nowRunning) svc.startEngine() else svc.stopEngine()
        bubble?.setImageResource(if (nowRunning) R.drawable.overlay_button_on else R.drawable.overlay_button_off)
        updateNotification(nowRunning)
    }

    private fun updateNotification(running: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(running))
    }

    private fun buildNotification(running: Boolean) = NotificationCompat.Builder(this, channel())
        .setSmallIcon(android.R.drawable.presence_online)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(if (running) getString(R.string.notif_running) else getString(R.string.overlay_off))
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun channel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
        return CHANNEL_ID
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "tacticai_status"
        private const val TAP_SLOP = 18
        private const val TAP_MAX_MS = 250
    }
}
