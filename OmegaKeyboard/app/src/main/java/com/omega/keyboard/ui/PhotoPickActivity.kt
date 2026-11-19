package com.omega.keyboard.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.omega.keyboard.Prefs
import com.omega.keyboard.ime.Photos

/**
 * A see-through screen the keyboard opens so the user can pick a picture from the gallery for a "صورتي" look. The keyboard itself
 * cannot show a picker; this takes the choice, keeps a shrunk copy inside the app (in the place the keyboard asked for), switches that
 * look on and closes.
 */
class PhotoPickActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try {
            val pick = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
            startActivityForResult(Intent.createChooser(pick, "اختار صورة للكيبورد"), REQ)
        } catch (_: Throwable) {
            Toast.makeText(this, "مفيش تطبيق معرض يفتح هنا", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ || resultCode != RESULT_OK || uri == null) { finish(); return }
        val app = applicationContext
        val slot = intent?.getIntExtra(EXTRA_SLOT, 0)?.coerceIn(0, Photos.SLOTS - 1) ?: 0
        Thread {
            val ok = Photos.save(app, uri, slot)
            runOnUiThread {
                if (ok) {
                    val p = Prefs.get(app)
                    p.bgPhotoRev = p.bgPhotoRev + 1
                    p.theme = Photos.idOf(slot)
                    Toast.makeText(this, "تمام، الصورة بقت خلفية الكيبورد", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "مقدرتش أقرا الصورة دي، جرّب غيرها", Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }.start()
    }

    companion object {
        private const val REQ = 41

        /** which of the places for the user's own pictures the new picture goes into (0..5) */
        const val EXTRA_SLOT = "slot"
    }
}
