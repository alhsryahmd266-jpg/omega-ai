package com.omega.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import com.omega.keyboard.AppLog
import com.omega.keyboard.Bg
import com.omega.keyboard.core.ArabicText
import com.omega.keyboard.core.NameMatcher

/** What spoken commands actually do on the phone: call a contact, open an app. */
object Actions {
    private class Contact(val name: String, val number: String)
    private class AppEntry(val label: String, val pkg: String)

    /** [report] gets short Arabic status lines (text, isProblem). Safe to call from the main thread. */
    fun call(ctx: Context, spoken: String, alt: String, report: (String, Boolean) -> Unit) {
        val app = ctx.applicationContext

        // "كلم 01012345678": a number was spoken instead of a name
        val digits = ArabicText.toWesternDigits(spoken).filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 5 && spoken.none { it.isLetter() }) {
            report("هكلّم $digits", false)
            ActionActivity.confirmCall(app, digits, digits)
            return
        }
        if (app.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            report("محتاج إذن جهات الاتصال عشان أعرف أكلّم \"$spoken\"", true)
            ActionActivity.requestPermissions(app, arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE))
            return
        }
        Bg.io {
            val list = loadContacts(app)
            val c = NameMatcher.best(spoken, list, { it.name })
                ?: (if (alt != spoken) NameMatcher.best(alt, list, { it.name }) else null)
            AppLog.d("voice", "call '$spoken': ${list.size} contacts, match=${c?.name}")
            if (c == null) {
                Bg.ui { report(if (list.isEmpty()) "جهات الاتصال فاضية أو مش قادر أقراها" else "مش لاقي \"$spoken\" في جهات الاتصال", true) }
            } else {
                Bg.ui {
                    report("هكلّم ${c.name}", false)
                    ActionActivity.confirmCall(app, c.name, c.number)
                }
            }
        }
    }

    private fun loadContacts(app: Context): List<Contact> {
        val best = LinkedHashMap<String, Pair<Int, Contact>>()   // name -> (priority, contact)
        try {
            val cols = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
            )
            app.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null)?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 20000) {
                    n++
                    val name = c.getString(0)?.trim().orEmpty()
                    val num = c.getString(1)?.trim().orEmpty()
                    if (name.isEmpty() || num.isEmpty()) continue
                    val type = c.getInt(2)
                    val primary = c.getInt(3) != 0
                    val prio = (if (primary) 2 else 0) + (if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) 1 else 0)
                    val old = best[name]
                    if (old == null || prio > old.first) best[name] = prio to Contact(name, num)
                }
            }
        } catch (t: Throwable) {
            AppLog.e("voice", "contacts query failed", t)
        }
        return best.values.map { it.second }
    }

    fun openApp(ctx: Context, spoken: String, report: (String, Boolean) -> Unit) {
        val app = ctx.applicationContext
        Bg.io {
            val pm = app.packageManager
            val apps = try {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                    .filter { it.pkg != app.packageName }
            } catch (t: Throwable) {
                AppLog.e("voice", "app list failed", t)
                emptyList()
            }
            val best = NameMatcher.best(spoken, apps, { it.label })
            if (best == null) {
                Bg.ui { report("مش لاقي تطبيق اسمه \"$spoken\"", true) }
                return@io
            }
            val launch = pm.getLaunchIntentForPackage(best.pkg)
            if (launch == null) {
                Bg.ui { report("مقدرتش أفتح ${best.label}", true) }
                return@io
            }
            try {
                app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Bg.ui { report("فتحت ${best.label}", false) }
            } catch (t: Throwable) {
                AppLog.e("voice", "launch failed", t)
                Bg.ui { report("مقدرتش أفتح ${best.label}", true) }
            }
        }
    }
}
