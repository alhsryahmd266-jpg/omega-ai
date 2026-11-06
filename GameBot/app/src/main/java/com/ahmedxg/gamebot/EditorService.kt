package com.ahmedxg.gamebot

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Floating "layout" pill over the game. Tap it while you are inside a match to open the editor,
 * drag the markers onto your game's controls, then Save.
 */
class EditorService : Service() {
    companion object {
        const val ACTION_STOP = "com.ahmedxg.gamebot.EDITOR_STOP"
    }

    private var wm: WindowManager? = null
    private var pill: View? = null
    private var editor: FrameLayout? = null
    private var cfg: Config? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }
        Logger.init(this)
        startForeground(8, Util.notification(this, "تعديل أماكن التحكم", EditorService::class.java, ACTION_STOP))
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل صلاحية الظهور فوق التطبيقات الأول", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        cfg = Config.load(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showPill()
        return START_NOT_STICKY
    }

    private fun overlayParams(full: Boolean): WindowManager.LayoutParams {
        val flags = if (full) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        val lp = WindowManager.LayoutParams(
            if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        if (full) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        } else {
            lp.gravity = Gravity.TOP or Gravity.END
            lp.x = 8
            lp.y = 8
            lp.alpha = 0.7f
        }
        return lp
    }

    private fun showPill() {
        main.post {
            if (pill != null) return@post
            val dp = resources.displayMetrics.density
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setBackgroundColor(0xCC000000.toInt())
            row.setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            val edit = TextView(this)
            edit.text = "⚙ Layout"
            edit.setTextColor(Color.GREEN)
            edit.textSize = 12f
            edit.setPadding(0, 0, (14 * dp).toInt(), 0)
            edit.setOnClickListener { showEditor() }
            val x = TextView(this)
            x.text = "✕"
            x.setTextColor(Color.RED)
            x.textSize = 14f
            x.setOnClickListener { cleanup(); stopSelf() }
            row.addView(edit)
            row.addView(x)
            try {
                wm?.addView(row, overlayParams(false))
                pill = row
            } catch (e: Throwable) {
                Logger.i("pill failed: ${e.message}")
            }
        }
    }

    private fun hidePill() {
        val p = pill
        if (p != null) {
            try { wm?.removeView(p) } catch (e: Throwable) { }
        }
        pill = null
    }

    private fun bar(label: String, f: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 10f
        b.setOnClickListener { f() }
        return b
    }

    private fun showEditor() {
        main.post {
            if (editor != null) return@post
            hidePill()
            val c = cfg ?: Config.load(this)
            cfg = c
            val size = Util.realSize(this)
            val root = FrameLayout(this)
            root.setBackgroundColor(0x55000000)
            val view = LayoutEditorView(this, c.layout, size.x, size.y)
            root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val hint = TextView(this)
            hint.text = "اسحب كل دايرة فوق زرارها في اللعبة، وبعدين اضغط حفظ"
            hint.setTextColor(Color.WHITE)
            hint.setBackgroundColor(0xAA000000.toInt())
            hint.textSize = 12f
            root.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setBackgroundColor(0xCC000000.toInt())
            row.addView(bar("حفظ") {
                c.save(this)
                Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
                hideEditor()
            })
            row.addView(bar("إلغاء") {
                cfg = Config.load(this)
                hideEditor()
            })
            row.addView(bar("إعادة") { c.layout.reset(); view.invalidate() })
            row.addView(bar("عصا +") { c.layout.stickRadius = (c.layout.stickRadius + 0.01f).coerceAtMost(0.3f); view.invalidate() })
            row.addView(bar("عصا −") { c.layout.stickRadius = (c.layout.stickRadius - 0.01f).coerceAtLeast(0.03f); view.invalidate() })
            row.addView(bar("نظر +") { c.layout.lookRadius = (c.layout.lookRadius + 0.01f).coerceAtMost(0.4f); view.invalidate() })
            row.addView(bar("نظر −") { c.layout.lookRadius = (c.layout.lookRadius - 0.01f).coerceAtLeast(0.03f); view.invalidate() })
            root.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            try {
                wm?.addView(root, overlayParams(true))
                editor = root
            } catch (e: Throwable) {
                Logger.i("editor failed: ${e.message}")
                showPill()
            }
        }
    }

    private fun hideEditor() {
        val e = editor
        if (e != null) {
            try { wm?.removeView(e) } catch (t: Throwable) { }
        }
        editor = null
        showPill()
    }

    private fun cleanup() {
        val e = editor
        if (e != null) { try { wm?.removeView(e) } catch (t: Throwable) { } }
        editor = null
        hidePill()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
