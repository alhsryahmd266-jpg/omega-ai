package expo.modules.terminal

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A chat window that floats over every app, so the user can talk to the agent
 * without leaving WhatsApp or any other app. Messages go to JS through onSend.
 */
object GvrChatOverlay {

    @Volatile var onSend: ((String) -> Unit)? = null

    private var root: LinearLayout? = null
    private var logView: TextView? = null
    private var scroll: ScrollView? = null
    private var wm: WindowManager? = null

    fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun isShown(): Boolean = root != null

    fun show(ctx: Context) {
        if (root != null) return
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val density = ctx.resources.displayMetrics.density

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF015121FL.toInt())
            setPadding(24, 24, 24, 24)
        }
        val header = TextView(ctx).apply {
            text = "GVR Agent"
            setTextColor(0xFFFFFFFFL.toInt())
            textSize = 16f
        }
        val log = TextView(ctx).apply {
            setTextColor(0xFFDDDDDDL.toInt())
            textSize = 14f
        }
        val sc = ScrollView(ctx).apply { addView(log) }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val edit = EditText(ctx).apply {
            hint = "اكتب هنا..."
            setTextColor(0xFFFFFFFFL.toInt())
            setHintTextColor(0xFF888888L.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val send = Button(ctx).apply {
            text = "إرسال"
            setOnClickListener {
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) {
                    appendLine("أنت: $t")
                    edit.setText("")
                    onSend?.invoke(t)
                }
            }
        }
        val pause = Button(ctx).apply {
            text = "⏹"
            setOnClickListener {
                GvrAccessibilityService.paused = !GvrAccessibilityService.paused
                text = if (GvrAccessibilityService.paused) "▶" else "⏹"
            }
        }
        val close = Button(ctx).apply {
            text = "✕"
            setOnClickListener { hide() }
        }
        row.addView(edit)
        row.addView(send)
        row.addView(pause)
        row.addView(close)
        col.addView(header)
        col.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(row)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (420 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        manager.addView(col, params)
        root = col
        logView = log
        scroll = sc
        appendLine("GVR: أنا هنا. اكتب أي طلب.")
    }

    fun appendLine(s: String) {
        val view = root ?: return
        view.post {
            logView?.append("\n" + s)
            scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    fun hide() {
        val view = root ?: return
        try { wm?.removeView(view) } catch (_: Exception) {}
        root = null
        logView = null
        scroll = null
    }
}
