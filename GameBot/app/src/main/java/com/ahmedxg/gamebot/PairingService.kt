package com.ahmedxg.gamebot

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.ahmedxg.gamebot.adb.GbAdbManager
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils

/**
 * One-time activation of the built-in shell. While the system's "Pair device with pairing code" screen is
 * open (Developer options > Wireless debugging), the code is typed into the notification of this service
 * (the system screen closes if you leave it, so the notification is the way to type without leaving).
 */
class PairingService : Service() {
    companion object {
        const val ACTION_START = "com.ahmedxg.gamebot.PAIR_START"
        const val ACTION_REPLY = "com.ahmedxg.gamebot.PAIR_REPLY"
        const val ACTION_STOP = "com.ahmedxg.gamebot.PAIR_STOP"
        const val KEY_CODE = "pairing_code"
        const val NID = 9

        @Volatile
        var status = ""
    }

    private var mdns: AdbMdns? = null

    @Volatile
    private var port = -1

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.init(this)
        when (intent?.action) {
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.trim() ?: ""
                doPair(code)
            }
            ACTION_STOP -> {
                stopMdns()
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> begin()
        }
        return START_NOT_STICKY
    }

    private fun notif(text: String, withInput: Boolean): Notification {
        Util.ensureChannel(this)
        val b = Notification.Builder(this, Util.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("GameBot: تفعيل الشل")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(withInput)
        if (withInput) {
            val ri = RemoteInput.Builder(KEY_CODE).setLabel("كود الاقتران (6 أرقام)").build()
            val i = Intent(this, PairingService::class.java)
            i.action = ACTION_REPLY
            val pi = PendingIntent.getService(this, 3, i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_send, "اكتب الكود", pi).addRemoteInput(ri).build())
            val si = Intent(this, PairingService::class.java)
            si.action = ACTION_STOP
            val sp = PendingIntent.getService(this, 4, si, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "إلغاء", sp).build())
        }
        return b.build()
    }

    private fun show(text: String, withInput: Boolean) {
        status = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NID, notif(text, withInput))
    }

    private fun begin() {
        startForeground(NID, notif("1) Developer options ← Wireless debugging (شغّله) ← Pair device with pairing code\\n2) سيب الشاشة دي مفتوحة واكتب الكود هنا في الإشعار", true))
        status = "بدأ الاقتران"
        try {
            val m = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING, AdbMdns.OnAdbDaemonDiscoveredListener { _, p ->
                port = p
                Logger.i("pairing port found: $p")
            })
            m.start()
            mdns = m
        } catch (t: Throwable) {
            Logger.i("mdns failed: ${t.message}")
        }
    }

    private fun stopMdns() {
        try { mdns?.stop() } catch (t: Throwable) { }
        mdns = null
    }

    private fun doPair(code: String) {
        if (code.length < 4) {
            show("الكود مش واضح، اكتبه تاني", true)
            return
        }
        Thread {
            try {
                if (port <= 0) {
                    show("لسه مالقيتش بورت الاقتران. افتح شاشة Pair device with pairing code واكتب الكود تاني", true)
                    return@Thread
                }
                val m = GbAdbManager.getInstance(this)
                val ok = m.pair(AndroidUtils.getHostIpAddress(this), port, code)
                if (!ok) {
                    show("الاقتران فشل (الكود غلط أو الشاشة اتقفلت). افتحها واكتب الكود تاني", true)
                    return@Thread
                }
                try {
                    m.autoConnect(this, 6000L)
                } catch (t: Throwable) {
                    Logger.i("autoConnect after pairing: ${t.message}")
                }
                ShellState.setPaired(this, true)
                Logger.i("shell paired")
                show("تم تفعيل الشل ✔  تقدر تقفل الإعدادات. (لو فصلت الـ Wi-Fi أو أعدت التشغيل شغّل Wireless debugging تاني)", false)
                stopMdns()
                stopForeground(Service.STOP_FOREGROUND_DETACH)
                stopSelf()
            } catch (t: Throwable) {
                show("فشل: ${t.javaClass.simpleName} ${t.message}", true)
            }
        }.start()
    }

    override fun onDestroy() {
        stopMdns()
        super.onDestroy()
    }
}
