package com.omega.sight.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import com.omega.sight.engine.CaptureService

/** Asks the system for the screen-capture permission (the owner sees the system's own dialog), then hands the answer to the service. */
class ProjectionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            @Suppress("DEPRECATION")
            // Android 14 and later let the owner pick "one app" in the dialog: this asks for the whole screen, which is what the app needs
            val ask = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else mpm.createScreenCaptureIntent()
            startActivityForResult(ask, REQ)
        } catch (_: Throwable) {
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) CaptureService.start(applicationContext, resultCode, data)
        finish()
    }

    companion object {
        private const val REQ = 41
    }
}
