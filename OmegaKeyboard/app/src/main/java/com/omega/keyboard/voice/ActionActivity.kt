package com.omega.keyboard.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Toast
import com.omega.keyboard.ui.BaseActivity

/**
 * A see-through screen the keyboard can open for the one thing a keyboard cannot do by itself:
 * ask for a runtime permission (the microphone, for dictation).
 */
class ActionActivity : BaseActivity() {

    companion object {
        private const val EXTRA_PERMS = "perms"
        private const val REQ_PERMS = 41

        private fun launch(ctx: Context, i: Intent) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (_: Throwable) {
            }
        }

        fun requestPermissions(ctx: Context, perms: Array<String>) {
            launch(ctx, Intent(ctx, ActionActivity::class.java).putExtra(EXTRA_PERMS, perms))
        }
    }

    private var askedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = intent?.getStringArrayExtra(EXTRA_PERMS) ?: emptyArray()
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { finish(); return }
        setContentView(View(this))
        askedAt = SystemClock.uptimeMillis()
        requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val denied = permissions.indices.filter { grantResults.getOrNull(it) != PackageManager.PERMISSION_GRANTED }
            if (denied.isEmpty()) {
                Toast.makeText(this, "تمام، الإذن اتفعّل. جرّب تاني.", Toast.LENGTH_SHORT).show()
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
        }
    }
}
