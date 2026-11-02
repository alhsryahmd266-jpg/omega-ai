package com.omega.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.omega.keyboard.ui.BaseActivity
import com.omega.keyboard.ui.UiKit
import com.omega.keyboard.ui.dp

/**
 * A see-through screen the keyboard / the voice layer can open for the two things a keyboard cannot do by itself:
 * ask for a runtime permission, and show "calling X..." with a few seconds to cancel before the call starts.
 */
class ActionActivity : BaseActivity() {

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_PERMS = "perms"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_NUMBER = "number"
        private const val REQ_PERMS = 41
        private const val REQ_CALL = 42

        private fun launch(ctx: Context, i: Intent) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (_: Throwable) {
            }
        }

        fun requestPermissions(ctx: Context, perms: Array<String>) {
            launch(ctx, Intent(ctx, ActionActivity::class.java).putExtra(EXTRA_MODE, "perm").putExtra(EXTRA_PERMS, perms))
        }

        fun confirmCall(ctx: Context, name: String, number: String) {
            launch(
                ctx,
                Intent(ctx, ActionActivity::class.java).putExtra(EXTRA_MODE, "call").putExtra(EXTRA_NAME, name).putExtra(EXTRA_NUMBER, number)
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var askedAt = 0L
    private var number = ""
    private var countdown: Runnable? = null
    private var placed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.getStringExtra(EXTRA_MODE)) {
            "perm" -> {
                val perms = intent.getStringArrayExtra(EXTRA_PERMS) ?: emptyArray()
                val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) { finish(); return }
                setContentView(View(this))
                askedAt = SystemClock.uptimeMillis()
                requestPermissions(missing.toTypedArray(), REQ_PERMS)
            }
            "call" -> buildCallCard(intent.getStringExtra(EXTRA_NAME) ?: "", intent.getStringExtra(EXTRA_NUMBER) ?: "")
            else -> finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val denied = permissions.indices.filter { grantResults.getOrNull(it) != PackageManager.PERMISSION_GRANTED }
            if (denied.isEmpty()) {
                Toast.makeText(this, "تمام، الإذن اتفعّل. جرّب الأمر تاني.", Toast.LENGTH_SHORT).show()
            } else if (SystemClock.uptimeMillis() - askedAt < 350) {
                // the system did not even show its dialog: the permission was refused for good, only Settings can undo it
                Toast.makeText(this, "الإذن مقفول. فعّله من إعدادات التطبيق ← الأذونات.", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (_: Throwable) {
                }
            } else {
                Toast.makeText(this, "من غير الإذن ده الميزة مش هتشتغل.", Toast.LENGTH_SHORT).show()
            }
            finish()
        } else if (requestCode == REQ_CALL) {
            dial(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
        }
    }

    // ------------------------------------------------------------------ call confirmation

    private fun buildCallCard(name: String, num: String) {
        number = num
        val p = UiKit.pal(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.55f)

        val root = FrameLayout(this)
        root.setOnClickListener { cancel() }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = UiKit.shape(this@ActionActivity, p.card, 28f, p.divider, 1f)
            setPadding(dp(24), dp(24), dp(24), dp(20))
            isClickable = true
            elevation = dp(12).toFloat()
        }
        val ic = UiKit.icon(this, "call", 34f, p.ok)
        card.addView(ic)
        card.addView(UiKit.label(this, "هكلّم", 14f, p.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(UiKit.label(this, name, 24f, p.text, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(UiKit.label(this, num, 15f, p.sub).apply { gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val timer = UiKit.label(this, "", 13.5f, p.sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        card.addView(timer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val no = UiKit.button(this, p, "إلغاء", filled = false) { cancel() }
        val yes = UiKit.button(this, p, "اتصل دلوقتي", filled = true) { dial(null) }
        buttons.addView(no, LinearLayout.LayoutParams(0, dp(44), 1f).also { it.marginEnd = dp(6); it.topMargin = dp(14) })
        buttons.addView(yes, LinearLayout.LayoutParams(0, dp(44), 1f).also { it.marginStart = dp(6); it.topMargin = dp(14) })
        card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).also {
            it.setMargins(dp(28), 0, dp(28), 0)
        })
        setContentView(root)

        var left = 4
        val tick = object : Runnable {
            override fun run() {
                if (placed) return
                if (left <= 0) { dial(null); return }
                timer.text = "هتتصل تلقائي خلال $left ثانية"
                left--
                handler.postDelayed(this, 1000)
            }
        }
        countdown = tick
        handler.post(tick)
    }

    private fun cancel() {
        placed = true
        countdown?.let { handler.removeCallbacks(it) }
        finish()
    }

    /** [granted]: null = check / ask for the call permission first */
    private fun dial(granted: Boolean?) {
        if (placed && granted == null) return
        countdown?.let { handler.removeCallbacks(it) }
        val has = granted ?: (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
        if (granted == null && !has) {
            placed = true
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL)
            return
        }
        placed = true
        val uri = Uri.parse("tel:" + Uri.encode(number))
        try {
            if (has) startActivity(Intent(Intent.ACTION_CALL, uri)) else startActivity(Intent(Intent.ACTION_DIAL, uri))
        } catch (_: Throwable) {
            try { startActivity(Intent(Intent.ACTION_DIAL, uri)) } catch (_: Throwable) {
                Toast.makeText(this, "مقدرتش أفتح الاتصال", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    override fun onDestroy() {
        countdown?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }
}
