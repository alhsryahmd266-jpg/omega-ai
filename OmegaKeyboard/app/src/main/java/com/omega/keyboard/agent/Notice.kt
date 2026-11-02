package com.omega.keyboard.agent

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.omega.keyboard.Bg
import com.omega.keyboard.ui.MainActivity

/** Short messages shown from anywhere (keyboard, services). */
object Notice {
    fun toast(ctx: Context, msg: String, long: Boolean = true) {
        val app = ctx.applicationContext
        Bg.ui { Toast.makeText(app, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }

    /** The accessibility service is off: tell the user and open the setup screen that switches it on. */
    fun needAccessibility(ctx: Context) {
        toast(ctx, "فعّل خدمة \"Omega Keyboard\" من إعدادات إمكانية الوصول عشان الميزة دي تشتغل")
        openApp(ctx)
    }

    fun needModel(ctx: Context) {
        toast(ctx, "ضيف ملف النموذج المحلي الأول (من شاشة التطبيق)")
        openApp(ctx, "model")
    }

    fun openApp(ctx: Context, page: String? = null) {
        try {
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (page != null) i.putExtra("page", page)
            ctx.startActivity(i)
        } catch (_: Throwable) {
        }
    }
}
