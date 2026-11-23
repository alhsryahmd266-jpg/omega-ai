package com.ahmedxg.gamebot

import android.app.Application
import android.content.Context
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** The built-in ADB client uses the platform's Conscrypt (TLS 1.3 for wireless-debugging pairing). */
class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
            } catch (t: Throwable) {
            }
        }
    }
}
