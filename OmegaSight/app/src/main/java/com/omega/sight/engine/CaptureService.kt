package com.omega.sight.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.omega.sight.Prefs
import com.omega.sight.R
import com.omega.sight.core.ScreenMath
import com.omega.sight.ui.MainActivity

/**
 * Holds the screen-capture permission the owner gave and feeds every new picture to the [Engine]. It runs as a foreground service (the
 * system requires that for screen capture) with a notification that has a stop button. Nothing is saved or sent anywhere: the pictures
 * only live in memory for the moment the engine looks at them.
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var engine: Engine? = null
    private var dm: DisplayManager? = null
    private var listener: DisplayManager.DisplayListener? = null
    private var capW = 0
    private var capH = 0

    /** the size of the captured picture that the system itself reported (Android 14 and later), 0 = not told */
    private var hintW = 0
    private var hintH = 0
    private var lastTry = 0L
    private var wrongSince = 0L
    private var retries = 0
    private val main = Handler(Looper.getMainLooper())
    private val debug by lazy { (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }

    /** looks every half second that the picture still has the shape of the screen: the phone may have turned without telling us */
    private val watch = object : Runnable {
        override fun run() {
            if (engine == null) return
            try {
                check()
            } catch (_: Throwable) {
            }
            main.postDelayed(this, WATCH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val n = notification()
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                else startForeground(NOTE_ID, n)
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
                if (data == null || !begin(code, data)) {
                    shutdown()
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                shutdown()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun begin(code: Int, data: Intent): Boolean {
        if (projection != null) return true
        return try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data) ?: return false
            projection = mp
            // the system tells us when the owner stops the capture from its own status-bar chip
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post {
                        shutdown()
                        stopSelf()
                    }
                }

                /** Android 14 and later: the system says how big the captured screen is, now and every time it changes (the phone turned) */
                override fun onCapturedContentResize(width: Int, height: Int) {
                    main.post {
                        if (width > 0 && height > 0) {
                            hintW = width
                            hintH = height
                        }
                        note("content resize ${width}x$height")
                        turned("content")
                    }
                }
            }, main)
            val eng = Engine(applicationContext)
            engine = eng
            Hub.engine = eng
            makeDisplay(eng)
            val d = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm = d
            val l = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == 0) turned("display")
                }
            }
            listener = l
            d.registerDisplayListener(l, main)
            main.postDelayed(watch, WATCH_MS)
            true
        } catch (t: Throwable) {
            Hub.stats.error = "التقاط الشاشة: ${t.message ?: t.javaClass.simpleName}"
            false
        }
    }

    /** the size the picture should have for the screen as it is now: [width, height, dpi] */
    private fun target(): IntArray {
        val s = ScreenInfo.read(this)
        var sw = s[0]
        var sh = s[1]
        // Android 14 and later tell the capture itself how big the screen is: that is the most direct word there is
        if (hintW > 0 && hintH > 0) {
            sw = hintW
            sh = hintH
        }
        val c = ScreenMath.captureSize(sw, sh, Prefs(this).captureLong)
        return intArrayOf(c[0], c[1], s[2])
    }

    private fun note(what: String) {
        if (debug) Log.i("SightLive", "capture: $what")
    }

    private fun makeDisplay(eng: Engine) {
        val t = target()
        capW = t[0]
        capH = t[1]
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd -> eng.onImage(rd) }, eng.handler)
        reader = r
        display = projection!!.createVirtualDisplay(
            "omega-sight", capW, capH, t[2],
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
        )
        Hub.stats.capW = capW
        Hub.stats.capH = capH
        note("started ${capW}x$capH")
    }

    /** the phone turned (or the screen changed its size): the picture has to follow, or it would show the screen as a small strip */
    private fun turned(why: String) {
        val eng = engine ?: return
        val t = target()
        if (t[0] == capW && t[1] == capH) return
        note("$why: ${capW}x$capH -> ${t[0]}x${t[1]}")
        applySize(eng, t)
    }

    private fun applySize(eng: Engine, t: IntArray): Boolean {
        val vd = display ?: return false
        var r: ImageReader? = null
        return try {
            r = ImageReader.newInstance(t[0], t[1], PixelFormat.RGBA_8888, 3)
            r.setOnImageAvailableListener({ rd -> eng.onImage(rd) }, eng.handler)
            vd.resize(t[0], t[1], t[2])
            vd.surface = r.surface
            val old = reader
            reader = r
            capW = t[0]
            capH = t[1]
            Hub.stats.capW = capW
            Hub.stats.capH = capH
            // the engine thread may be reading a picture of the old reader right now: it is closed on that same thread
            if (old != null) eng.dispose(old)
            note("resized to ${capW}x$capH")
            true
        } catch (e: Throwable) {
            note("resize failed: ${e.javaClass.simpleName} ${e.message}")
            try {
                r?.close()
            } catch (_: Throwable) {
            }
            Hub.stats.error = "تعديل حجم الالتقاط: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    private fun check() {
        val eng = engine ?: return
        val now = SystemClock.uptimeMillis()
        val t = target()
        if (t[0] != capW || t[1] != capH) {
            // a size is wanted that is not there yet (an event may have been missed, or the last try failed)
            if (now - lastTry >= 700L) {
                lastTry = now
                note("watch: ${capW}x$capH -> ${t[0]}x${t[1]}")
                applySize(eng, t)
            }
            return
        }
        // the size was set; the pictures that arrive should have it too. Nothing arrives while the screen stays still, so this only asks again, twice at most
        val st = Hub.stats
        if (st.running && st.w > 0 && (st.w != capW || st.h != capH)) {
            if (wrongSince == 0L) wrongSince = now
            else if (now - wrongSince > 2500L && retries < 2) {
                retries++
                wrongSince = now
                note("pictures still ${st.w}x${st.h}: asking again (${retries})")
                applySize(eng, intArrayOf(capW, capH, t[2]))
            }
        } else {
            wrongSince = 0L
            retries = 0
        }
    }

    private fun shutdown() {
        main.removeCallbacks(watch)
        try {
            listener?.let { dm?.unregisterDisplayListener(it) }
        } catch (_: Throwable) {
        }
        listener = null
        Hub.testAim = false
        val eng = engine
        val rd = reader
        if (Hub.engine === eng) Hub.engine = null
        engine = null
        try {
            display?.release()
        } catch (_: Throwable) {
        }
        display = null
        reader = null
        // the engine thread may still be reading a picture: it closes the reader itself, after its last picture
        if (eng != null) eng.stop(rd)
        else try {
            rd?.close()
        } catch (_: Throwable) {
        }
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        hintW = 0
        hintH = 0
        retries = 0
        wrongSince = 0L
        Hub.stats.running = false
        try {
            stopForeground(true)
        } catch (_: Throwable) {
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Omega Sight", NotificationManager.IMPORTANCE_LOW)
            ch.description = "يظهر طول ما التقاط الشاشة شغّال"
            nm.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("Omega Sight شغّال")
            .setContentText("بيحلل الشاشة على جهازك بس — مفيش حاجة بتتحفظ أو تتبعت")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "إيقاف", stop).build())
        return b.build()
    }

    companion object {
        const val ACTION_START = "com.omega.sight.START"
        const val ACTION_STOP = "com.omega.sight.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "sight_capture"
        private const val NOTE_ID = 7
        private const val WATCH_MS = 500L

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java).setAction(ACTION_START).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
