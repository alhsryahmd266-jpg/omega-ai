package expo.modules.terminal

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * A small floating button over every app. Tap it to pause or resume the agent.
 * While paused, every screen tool refuses to act. Needs "Display over other apps".
 */
class GvrBubbleService : Service() {

    private var wm: WindowManager? = null
    private var bubble: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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

    override fun onDestroy() {
        bubble?.let { wm?.removeView(it) }
        bubble = null
        super.onDestroy()
    }
}
