package com.ahmedxg.gamebot

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * The bot loop: screen capture (MediaProjection) -> detector (TFLite) -> policy -> controller (touches).
 * Everything stays on the phone. A HUD with a STOP button is always shown while it runs.
 */
class BotService : Service() {
    companion object {
        const val ACTION_START = "com.ahmedxg.gamebot.START"
        const val ACTION_STOP = "com.ahmedxg.gamebot.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        @Volatile
        var running = false

        @Volatile
        var status = "stopped"
    }

    private var cfg = Config()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var detector: Detector? = null
    private var backend: InputBackend? = null
    private var controller: Controller? = null
    private var policy: Policy? = null
    private var hud: Hud? = null
    private var backendName = ""
    private var stopping = false

    private var startedAt = 0L
    private var activeFrom = 0L
    private var scrW = 0
    private var scrH = 0
    private var capW = 0
    private var capH = 0
    private var reuse: Bitmap? = null
    private var lastProc = 0L
    private var minInterval = 33L
    private var lastRecord = 0L

    private var fps = 0
    private var fpsCount = 0
    private var fpsT = 0L
    private var emaDet = 0f
    private var emaLoop = 0f
    private var emaInj = 0f
    private var nDets = 0
    private var state = ""
    private var thermal = 0
    private var guardOk = true
    private var guardFg = ""
    private var guardBadSince = 0L
    private var lastGuardCheck = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val h = handler
            if (h != null) h.post { stopBot("اتوقف من المستخدم") } else stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        Logger.init(this)
        cfg = Config.load(this)
        startForeground(
            7,
            Util.notification(this, "البوت شغّال", BotService::class.java, ACTION_STOP),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) {
            fail("مفيش إذن تسجيل الشاشة")
            return START_NOT_STICKY
        }
        running = true
        stopping = false
        status = "starting"
        val t = HandlerThread("gamebot-loop")
        t.start()
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post { startup(code, data) }
        return START_NOT_STICKY
    }

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun fail(msg: String) {
        Logger.i("FAIL: $msg")
        status = "failed: $msg"
        toast(msg)
        running = false
        try { stopForeground(Service.STOP_FOREGROUND_REMOVE) } catch (e: Throwable) { }
        stopSelf()
    }

    private fun startup(code: Int, data: Intent) {
        if (cfg.guardEnabled && cfg.gamePackage.isEmpty()) {
            stopBot("اختار اللعبة أو عطّل الحماية")
            return
        }
        val pair = Backends.create(this, cfg.backend)
        val b = pair.first
        if (b == null) {
            toast("مفيش وسيلة تحكم: " + pair.second)
            stopBot("مفيش وسيلة تحكم: " + pair.second)
            return
        }
        backend = b
        backendName = pair.second

        if (cfg.mode == Mode.AI) {
            if (cfg.modelFile.isNotEmpty()) {
                val d = Detector(cfg)
                val err = d.load()
                if (err == null) {
                    detector = d
                } else {
                    Logger.i("AI model: $err -> falling back to Monkey")
                    toast("النموذج: $err — هيشتغل Monkey")
                }
            } else {
                toast("مفيش نموذج — هيشتغل Monkey")
            }
        }
        policy = if (detector != null) {
            TacticalPolicy(cfg.skill, cfg.aimScale, cfg.crossX, cfg.crossY)
        } else {
            MonkeyPolicy()
        }
        controller = Controller(b, cfg.layout, handler ?: Handler(Looper.getMainLooper()))

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            mpm.getMediaProjection(code, data)
        } catch (t: Throwable) {
            null
        }
        if (mp == null) {
            stopBot("فشل إذن تسجيل الشاشة")
            return
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler?.post { stopBot("تسجيل الشاشة اتوقف") }
            }
        }, handler)

        try {
            setupCapture()
        } catch (t: Throwable) {
            stopBot("فشل التقاط الشاشة: ${t.javaClass.simpleName} ${t.message}")
            return
        }

        hud = Hud(this) { handler?.post { stopBot("STOP من الـ HUD") } }
        hud?.show()
        startedAt = SystemClock.uptimeMillis()
        activeFrom = startedAt + cfg.startDelaySec * 1000L
        minInterval = 1000L / cfg.targetFps.coerceIn(5, 60)
        fpsT = startedAt
        status = "running"
        Logger.i("bot started: mode=${if (detector != null) "AI" else "MONKEY"} skill=${cfg.skill} backend=$backendName capture=${capW}x$capH screen=${scrW}x$scrH")
        qaBroadcast(true)
        handler?.postDelayed(watchdog, 500L)
    }

    private fun setupCapture() {
        val p: Point = Util.realSize(this)
        scrW = p.x
        scrH = p.y
        val s = cfg.captureShortSide.toFloat() / min(scrW, scrH)
        capW = max(64, ((scrW * s).toInt() / 2) * 2)
        capH = max(64, ((scrH * s).toInt() / 2) * 2)
        val dpi = resources.displayMetrics.densityDpi
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, handler)
        reader = r
        vd = projection?.createVirtualDisplay(
            "GameBotCapture", capW, capH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
    }

    private fun resizeCapture(p: Point) {
        scrW = p.x
        scrH = p.y
        val s = cfg.captureShortSide.toFloat() / min(scrW, scrH)
        capW = max(64, ((scrW * s).toInt() / 2) * 2)
        capH = max(64, ((scrH * s).toInt() / 2) * 2)
        val old = reader
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, handler)
        vd?.resize(capW, capH, resources.displayMetrics.densityDpi)
        vd?.surface = r.surface
        reader = r
        reuse = null
        try { old?.close() } catch (e: Throwable) { }
        controller?.releaseAll()
        Logger.i("display changed -> ${scrW}x$scrH, capture ${capW}x$capH")
    }

    private fun onFrame(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        val now = SystemClock.uptimeMillis()
        var bmp: Bitmap? = null
        var w = 0
        var h = 0
        try {
            if (!running || now - lastProc < minInterval) return
            val pl = img.planes[0]
            w = img.width
            h = img.height
            val bw = pl.rowStride / pl.pixelStride
            val cur = reuse
            val b: Bitmap = if (cur != null && cur.width == bw && cur.height == h) {
                cur
            } else {
                val nb = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888)
                reuse = nb
                nb
            }
            val buf = pl.buffer
            buf.rewind()
            b.copyPixelsFromBuffer(buf)
            bmp = b
        } catch (t: Throwable) {
            Logger.i("frame copy failed: ${t.javaClass.simpleName} ${t.message}")
            return
        } finally {
            img.close()
        }
        val frame = bmp ?: return
        lastProc = now
        try {
            processFrame(frame, w, h, now)
        } catch (t: Throwable) {
            Logger.i("processFrame error: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun checkGuard(now: Long): Boolean {
        if (!cfg.guardEnabled) return true
        if (now - lastGuardCheck > 400L) {
            lastGuardCheck = now
            val fg = ForegroundGuard.foreground(this)
            guardFg = fg ?: "?"
            guardOk = fg != null && fg == cfg.gamePackage
            if (guardOk) guardBadSince = 0L else if (guardBadSince == 0L) guardBadSince = now
        }
        return guardOk
    }

    private fun processFrame(bmp: Bitmap, w: Int, h: Int, now: Long) {
        val t0 = System.nanoTime()
        val active = now >= activeFrom
        val inGame = checkGuard(now)

        var dets: List<Det> = emptyList()
        var detMs = 0f
        val d = detector
        if (d != null && active && inGame) {
            dets = d.detect(bmp, w, h)
            detMs = d.lastMs
        }
        nDets = dets.size

        val act: Action = if (active && inGame) {
            policy?.decide(Perception(now, dets, scrW, scrH), now) ?: Action.idle()
        } else {
            Action.idle()
        }
        state = if (!active) {
            "COUNTDOWN " + ((activeFrom - now) / 1000 + 1)
        } else if (!inGame) {
            "PAUSED"
        } else {
            act.state
        }

        val t1 = System.nanoTime()
        controller?.apply(act, now, scrW, scrH)
        val t2 = System.nanoTime()

        if (cfg.recordFrames && now - lastRecord >= cfg.recordEveryMs && inGame) {
            lastRecord = now
            saveFrame(bmp, w, h)
        }

        emaDet = emaDet * 0.9f + detMs * 0.1f
        emaLoop = emaLoop * 0.9f + ((t2 - t0) / 1e6f) * 0.1f
        emaInj = emaInj * 0.9f + ((t2 - t1) / 1e6f) * 0.1f
        fpsCount++
        if (now - fpsT >= 1000L) {
            fps = fpsCount
            fpsCount = 0
            fpsT = now
        }
    }

    private fun saveFrame(bmp: Bitmap, w: Int, h: Int) {
        try {
            val dir = getExternalFilesDir("frames") ?: return
            dir.mkdirs()
            val crop = Bitmap.createBitmap(bmp, 0, 0, w, h)
            FileOutputStream(File(dir, "f_" + System.currentTimeMillis() + ".jpg")).use { o ->
                crop.compress(Bitmap.CompressFormat.JPEG, 90, o)
            }
            crop.recycle()
        } catch (t: Throwable) {
            Logger.i("saveFrame failed: ${t.message}")
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            // frames stalled -> never leave fingers pressed
            if (now - lastProc > 700L) controller?.releaseAll()

            // rotation / resolution change
            val p = Util.realSize(this@BotService)
            if (p.x != scrW || p.y != scrH) {
                try { resizeCapture(p) } catch (t: Throwable) { Logger.i("resize failed: ${t.message}") }
            }

            // thermal protection
            thermal = try {
                (getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
            } catch (t: Throwable) {
                0
            }
            val base = 1000L / cfg.targetFps.coerceIn(5, 60)
            minInterval = if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) base * 2 else base
            if (thermal >= PowerManager.THERMAL_STATUS_CRITICAL) {
                stopBot("الجهاز سخن جدًا")
                return
            }
            if (now - startedAt > cfg.maxRunMinutes * 60_000L) {
                stopBot("خلص الوقت الأقصى (${cfg.maxRunMinutes} دقيقة)")
                return
            }
            if (!guardOk && guardBadSince != 0L && now - guardBadSince > 60_000L) {
                stopBot("اللعبة مش على الشاشة بقالها دقيقة")
                return
            }
            hud?.update(
                "BOT ${if (detector != null) "AI" else "MONKEY"} ${cfg.skill.label} | $backendName\n" +
                    "FPS $fps  det ${emaDet.toInt()}ms  loop ${emaLoop.toInt()}ms  inj ${emaInj.toInt()}ms\n" +
                    "$state | dets $nDets | ${if (guardOk) "guard OK" else "PAUSED ($guardFg)"} | T$thermal"
            )
            handler?.postDelayed(this, 500L)
        }
    }

    private fun qaBroadcast(started: Boolean) {
        if (cfg.gamePackage.isEmpty()) return
        try {
            val i = Intent(if (started) "com.ahmedxg.gamebot.action.BOT_STARTED" else "com.ahmedxg.gamebot.action.BOT_STOPPED")
            i.setPackage(cfg.gamePackage)
            i.putExtra("mode", if (detector != null) "AI" else "MONKEY")
            i.putExtra("skill", cfg.skill.name)
            sendBroadcast(i)
        } catch (t: Throwable) {
        }
    }

    @Synchronized
    private fun stopBot(reason: String) {
        if (stopping) return
        stopping = true
        Logger.i("stop: $reason")
        val wasRunning = running
        running = false
        status = "stopped: $reason"
        if (wasRunning) qaBroadcast(false)
        handler?.removeCallbacksAndMessages(null)
        try { controller?.releaseAll() } catch (e: Throwable) { }
        try { backend?.close() } catch (e: Throwable) { }
        try { vd?.release() } catch (e: Throwable) { }
        try { reader?.close() } catch (e: Throwable) { }
        try { projection?.stop() } catch (e: Throwable) { }
        try { detector?.close() } catch (e: Throwable) { }
        hud?.hide()
        thread?.quitSafely()
        try { stopForeground(Service.STOP_FOREGROUND_REMOVE) } catch (e: Throwable) { }
        stopSelf()
    }

    override fun onDestroy() {
        if (running) {
            running = false
            try { controller?.releaseAll() } catch (e: Throwable) { }
            hud?.hide()
        }
        super.onDestroy()
    }
}
