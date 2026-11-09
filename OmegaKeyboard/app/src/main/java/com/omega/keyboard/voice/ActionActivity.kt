package com.omega.keyboard.voice

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Toast
import com.omega.keyboard.AppLog
import com.omega.keyboard.ui.BaseActivity

/**
 * A see-through screen the keyboard can open for the things a keyboard cannot do by itself:
 * ask for a runtime permission (the microphone, for dictation), and show the system's own voice dialog when none of the
 * phone's recognisers could be used quietly.
 */
class ActionActivity : BaseActivity() {

    companion object {
        private const val EXTRA_PERMS = "perms"
        private const val EXTRA_SPEECH = "speech_lang"
        private const val REQ_PERMS = 41
        private const val REQ_SPEECH = 42

        private fun launch(ctx: Context, i: Intent) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (t: Throwable) {
                AppLog.e("action", "could not open the helper screen", t)
            }
        }

        fun requestPermissions(ctx: Context, perms: Array<String>) {
            launch(ctx, Intent(ctx, ActionActivity::class.java).putExtra(EXTRA_PERMS, perms))
        }

        /** Opens the system's voice dialog; the answer arrives in [VoiceSession.onDialogResult]. */
        fun recognize(ctx: Context, lang: String) {
            launch(ctx, Intent(ctx, ActionActivity::class.java).putExtra(EXTRA_SPEECH, lang))
        }
    }

    private var askedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this))
        val speech = intent?.getStringExtra(EXTRA_SPEECH)
        if (speech != null) {
            if (savedInstanceState == null) openSpeechDialog(speech)
            return
        }
        val perms = intent?.getStringArrayExtra(EXTRA_PERMS) ?: emptyArray()
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { finish(); return }
        askedAt = SystemClock.uptimeMillis()
        requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    private fun openSpeechDialog(lang: String) {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "اتكلم دلوقتي")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (lang.isNotBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            }
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SPEECH)
        } catch (_: ActivityNotFoundException) {
            // this phone has no voice dialog either
            VoiceSession.onDialogResult(null, opened = false)
            finish()
        } catch (t: Throwable) {
            AppLog.e("action", "voice dialog failed", t)
            VoiceSession.onDialogResult(null, opened = false)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH) {
            val heard = if (resultCode == Activity.RESULT_OK) {
                data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull { it.isNotBlank() }
            } else null
            // the user closing the dialog without speaking is not an error worth a message
            VoiceSession.onDialogResult(heard, opened = true)
            finish()
        }
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
