package com.omega.sight.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.util.DisplayMetrics
import android.view.WindowManager
import com.omega.sight.Prefs
import com.omega.sight.R
import com.omega.sight.ui.MainActivity
import kotlin.math.max
import kotlin.math.min

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
    private var lastRot = -1
    private val main = Handler(Looper.getMainLooper())

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
                    if (displayId == 0) turned()
                }
            }
            listener = l
            d.registerDisplayListener(l, main)
            true
        } catch (t: Throwable) {
            Hub.stats.error = "التقاط الشاشة: ${t.message ?: t.javaClass.simpleName}"
            false
        }
    }

    private fun screen(): IntArray {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        lastRot = @Suppress("DEPRECATION") (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        return intArrayOf(m.widthPixels, m.heightPixels, m.densityDpi)
    }

    private fun even(v: Float): Int = max(64, (Math.round(v) / 2) * 2)

    private fun makeDisplay(eng: Engine) {
        val s = screen()
        val long = Prefs(this).captureLong
        val k = min(1f, long / max(s[0], s[1]).toFloat())
        capW = even(s[0] * k)
        capH = even(s[1] * k)
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd -> eng.onImage(rd) }, eng.handler)
        reader = r
        display = projection!!.createVirtualDisplay(
            "omega-sight", capW, capH, s[2],
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
        )
    }

    /** the phone turned: the picture has to turn with it */
    private fun turned() {
        val eng = engine ?: return
        val s = screen()
        val long = Prefs(this).captureLong
        val k = min(1f, long / max(s[0], s[1]).toFloat())
        val nw = even(s[0] * k)
        val nh = even(s[1] * k)
        if (nw == capW && nh == capH) return
        capW = nw
        capH = nh
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd -> eng.onImage(rd) }, eng.handler)
        val oldReader = reader
        reader = r
        try {
            display?.resize(capW, capH, s[2])
            display?.surface = r.surface
        } catch (_: Throwable) {
        }
        try {
            oldReader?.close()
        } catch (_: Throwable) {
        }
    }

    private fun shutdown() {
        try {
            listener?.let { dm?.unregisterDisplayListener(it) }
        } catch (_: Throwable) {
        }
        listener = null
        Hub.testAim = false
        engine?.stop()
        if (Hub.engine === engine) Hub.engine = null
        engine = null
        try {
            display?.release()
        } catch (_: Throwable) {
        }
        display = null
        try {
            reader?.close()
        } catch (_: Throwable) {
        }
        reader = null
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
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

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java).setAction(ACTION_START).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
