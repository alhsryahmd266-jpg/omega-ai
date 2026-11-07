package com.ahmedxg.gamebot

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The bot loop: screen capture (MediaProjection) -> detector (TFLite) / zone reader -> policy ->
 * controller (touches). Everything stays on the phone. A HUD with START/PAUSE and STOP is always shown.
 * Soak-test supervisor: re-opens the game if it exits, detects a frozen/static screen and runs a
 * restart macro (menu taps), and writes a report when it stops.
 */
class BotService : Service() {
    companion object {
        const val ACTION_START = "com.ahmedxg.gamebot.START"
        const val ACTION_STOP = "com.ahmedxg.gamebot.STOP"
        const val ACTION_TOGGLE = "com.ahmedxg.gamebot.TOGGLE"
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
    private var zoneNav: ZoneNav? = null
    private var backend: InputBackend? = null
    private var controller: Controller? = null
    private var policy: Policy? = null
    private var hud: Hud? = null
    private var backendName = ""
    private var stopping = false

    @Volatile
    private var lastZone: ZoneInfo? = null

    private var armed = false
    private var startedAt = 0L
    private var activeFrom = Long.MAX_VALUE
    private var scrW = 0
    private var scrH = 0
    private var capW = 0
    private var capH = 0
    private var reuse: Bitmap? = null
    private var lastProc = 0L
    private var minInterval = 33L
    private var lastRecord = 0L

    // live stats
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

    // soak supervisor
    private var prevSig: IntArray? = null
    private var lastSigAt = 0L
    private var staticSince = 0L
    private var consecutiveStatic = 0
    private var macroUntil = 0L
    private var pendingMacroAt = 0L

    // report counters
    private var framesTotal = 0L
    private var armedMs = 0L
    private var lastWatch = 0L
    private var maxThermal = 0
    private var fpsSum = 0L
    private var fpsSamples = 0
    private var relaunches = 0
    private var staticEvents = 0
    private var macroRuns = 0
    private var zoneSeen = 0L
    private var zoneValid = 0L
    private var zoneOutside = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val act = intent?.action
        if (act == ACTION_STOP) {
            val h = handler
            if (h != null) h.post { stopBot("اتوقف من المستخدم") } else stopSelf()
            return START_NOT_STICKY
        }
        if (act == ACTION_TOGGLE) {
            handler?.post { toggleArmed() }
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        Logger.init(this)
        cfg = Config.load(this)
        startForeground(
            7,
            Util.notification(this, "GameBot شغّال", BotService::class.java, ACTION_STOP, ACTION_TOGGLE),
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
        if (Safety.isBlocked(cfg.gamePackage)) {
            toast(Safety.MESSAGE)
            stopBot(Safety.MESSAGE)
            return
        }
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
        val base: Policy = if (detector != null) {
            TacticalPolicy(cfg.skill, cfg.aimScale, cfg.crossX, cfg.crossY)
        } else {
            MonkeyPolicy()
        }
        if (cfg.zoneEnabled) {
            zoneNav = ZoneNav(cfg)
            policy = ZonePolicy(base, { lastZone }, cfg.aimScale, cfg.zoneHeadingUp)
        } else {
            policy = base
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

        hud = Hud(this, { handler?.post { toggleArmed() } }, { handler?.post { stopBot("STOP من الـ HUD") } })
        hud?.show()
        startedAt = SystemClock.uptimeMillis()
        lastWatch = startedAt
        minInterval = 1000L / cfg.targetFps.coerceIn(5, 60)
        fpsT = startedAt
        armed = cfg.autoStart
        activeFrom = if (armed) startedAt + cfg.startDelaySec * 1000L else Long.MAX_VALUE
        hud?.setArmed(armed)
        status = "running"
        Logger.i(
            "bot ready: mode=${if (detector != null) "AI" else "MONKEY"} skill=${cfg.skill} backend=$backendName " +
                "capture=${capW}x$capH screen=${scrW}x$scrH zone=${cfg.zoneEnabled} soak=${cfg.soakEnabled} autoStart=${cfg.autoStart}"
        )
        qaBroadcast(true)
        handler?.postDelayed(watchdog, 500L)
    }

    private fun toggleArmed() {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        if (!armed) {
            armed = true
            activeFrom = now + cfg.startDelaySec * 1000L
            policy?.reset()
            prevSig = null
            staticSince = 0L
            consecutiveStatic = 0
            Logger.i("START pressed (countdown ${cfg.startDelaySec}s)")
        } else {
            armed = false
            activeFrom = Long.MAX_VALUE
            controller?.releaseAll()
            Logger.i("PAUSE pressed")
        }
        hud?.setArmed(armed)
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
        prevSig = null
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

    /** 24x14 luma thumbnail, used to tell "the screen is frozen / static (menu, game over)" from live gameplay. */
    private fun sampleSig(bmp: Bitmap, w: Int, h: Int): IntArray {
        val gx = 24
        val gy = 14
        val sig = IntArray(gx * gy)
        var k = 0
        for (j in 0 until gy) {
            val y = ((j + 0.5f) * h / gy).toInt().coerceIn(0, h - 1)
            for (i in 0 until gx) {
                val x = ((i + 0.5f) * w / gx).toInt().coerceIn(0, bmp.width - 1)
                val p = bmp.getPixel(x, y)
                sig[k++] = (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10
            }
        }
        return sig
    }

    /** Menu / "play again" taps (m1..m4 in the layout). */
    private fun runMacro(now: Long) {
        val n = cfg.macroCount.coerceIn(0, 4)
        if (n == 0) return
        macroRuns++
        val gap = cfg.macroDelayMs.coerceIn(300, 10000).toLong()
        macroUntil = now + n * gap + 1500L
        controller?.releaseAll()
        for (i in 0 until n) {
            val name = "m" + (i + 1)
            handler?.postDelayed({
                if (running) controller?.tapNow(cfg.layout.x(name), cfg.layout.y(name), scrW, scrH)
            }, 400L + i * gap)
        }
        Logger.i("macro #$macroRuns: $n taps")
    }

    private fun processFrame(bmp: Bitmap, w: Int, h: Int, now: Long) {
        val t0 = System.nanoTime()
        val active = armed && now >= activeFrom
        val inGame = checkGuard(now)
        val inMacro = now < macroUntil
        framesTotal++

        // soak: frozen / static screen -> restart macro
        if (cfg.soakEnabled && active && inGame && !inMacro && now - lastSigAt >= 400L) {
            lastSigAt = now
            val sig = sampleSig(bmp, w, h)
            val prev = prevSig
            prevSig = sig
            if (prev != null) {
                var sum = 0
                for (i in sig.indices) sum += abs(sig[i] - prev[i])
                val mean = sum.toFloat() / sig.size
                if (mean < 1.2f) {
                    if (staticSince == 0L) {
                        staticSince = now
                    } else if (now - staticSince >= cfg.frozenSeconds.coerceIn(5, 300) * 1000L) {
                        staticEvents++
                        consecutiveStatic++
                        staticSince = now
                        Logger.i("static screen (event #$staticEvents)")
                        if (consecutiveStatic >= 6) {
                            stopBot("الشاشة ثابتة والماكرو مش بيفيد")
                            return
                        }
                        runMacro(now)
                    }
                } else {
                    staticSince = 0L
                    consecutiveStatic = 0
                }
            }
        }

        var dets: List<Det> = emptyList()
        var detMs = 0f
        val d = detector
        if (d != null && active && inGame && !inMacro) {
            dets = d.detect(bmp, w, h)
            detMs = d.lastMs
        }
        nDets = dets.size

        val zn = zoneNav
        if (zn != null && active && inGame && !inMacro) {
            val z = zn.detect(bmp, w, h)
            lastZone = z
            zoneSeen++
            if (z.valid) {
                zoneValid++
                if (z.outside) zoneOutside++
            }
        } else {
            lastZone = null
        }

        val act: Action = if (active && inGame && !inMacro) {
            policy?.decide(Perception(now, dets, scrW, scrH), now) ?: Action.idle()
        } else {
            Action.idle()
        }
        state = if (!armed) {
            "READY - press START"
        } else if (now < activeFrom) {
            "COUNTDOWN " + ((activeFrom - now) / 1000 + 1)
        } else if (!inGame) {
            "PAUSED (game not in front)"
        } else if (inMacro) {
            "MACRO"
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
            if (active) {
                fpsSum += fps
                fpsSamples++
            }
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

    private fun relaunchGame(now: Long) {
        val i = packageManager.getLaunchIntentForPackage(cfg.gamePackage)
        if (i == null) {
            stopBot("مش لاقي اللعبة عشان أفتحها")
            return
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (t: Throwable) {
            Logger.i("relaunch failed: ${t.message}")
        }
        relaunches++
        guardBadSince = now
        pendingMacroAt = now + 20_000L
        prevSig = null
        staticSince = 0L
        controller?.releaseAll()
        Logger.i("game was not in front -> relaunched (#$relaunches)")
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            if (armed && now >= activeFrom) armedMs += now - lastWatch
            lastWatch = now

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
            if (thermal > maxThermal) maxThermal = thermal
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

            // soak supervisor: the game left the foreground (crash / exit)
            if (armed && cfg.guardEnabled && !guardOk && guardBadSince != 0L) {
                val away = now - guardBadSince
                if (cfg.soakEnabled && cfg.relaunchOnExit) {
                    if (away > 8_000L) {
                        if (relaunches >= 20) {
                            stopBot("اللعبة بتقفل كتير (20 مرة)")
                            return
                        }
                        relaunchGame(now)
                    }
                } else if (away > 60_000L) {
                    stopBot("اللعبة مش على الشاشة بقالها دقيقة")
                    return
                }
            }
            if (pendingMacroAt != 0L && now >= pendingMacroAt && guardOk) {
                pendingMacroAt = 0L
                runMacro(now)
            }

            val z = lastZone
            val zoneTxt = if (!cfg.zoneEnabled) "" else if (z != null && z.valid) {
                " | zone ${z.angleDeg.toInt()}deg ${if (z.outside) "OUT" else "in"}"
            } else {
                " | zone ?"
            }
            hud?.update(
                "BOT ${if (detector != null) "AI" else "MONKEY"} ${cfg.skill.label} | $backendName\n" +
                    "FPS $fps  det ${emaDet.toInt()}ms  loop ${emaLoop.toInt()}ms  inj ${emaInj.toInt()}ms\n" +
                    "$state | dets $nDets | ${if (guardOk) "guard OK" else "away ($guardFg)"} | T$thermal$zoneTxt\n" +
                    "exits $relaunches  static $staticEvents  macros $macroRuns"
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

    private fun writeReport(reason: String) {
        try {
            val dur = (SystemClock.uptimeMillis() - startedAt) / 1000
            val avgFps = if (fpsSamples > 0) fpsSum.toFloat() / fpsSamples else 0f
            val zonePct = if (zoneSeen > 0) 100f * zoneValid / zoneSeen else 0f
            val outPct = if (zoneValid > 0) 100f * zoneOutside / zoneValid else 0f
            val sb = StringBuilder()
            sb.append("GameBot report\n")
            sb.append("reason: ").append(reason).append('\n')
            sb.append("game: ").append(cfg.gamePackage).append('\n')
            sb.append("duration: ").append(dur / 60).append("m ").append(dur % 60).append("s, active ").append(armedMs / 60000).append("m ").append((armedMs / 1000) % 60).append("s\n")
            sb.append("frames: ").append(framesTotal).append(", avg FPS while active: ").append("%.1f".format(avgFps)).append('\n')
            sb.append("game exits / relaunches: ").append(relaunches).append('\n')
            sb.append("static-screen events: ").append(staticEvents).append(", macro runs: ").append(macroRuns).append('\n')
            sb.append("max thermal status: ").append(maxThermal).append('\n')
            if (cfg.zoneEnabled) {
                sb.append("zone reading valid: ").append("%.0f".format(zonePct)).append("%, outside zone: ").append("%.0f".format(outPct)).append("% of valid\n")
            }
            sb.append("mode: ").append(if (detector != null) "AI" else "MONKEY").append(", skill ").append(cfg.skill.label).append(", backend ").append(backendName).append('\n')
            File(filesDir, "last_report.txt").writeText(sb.toString())
            Logger.i(sb.toString().replace("\n", " | "))
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
        if (wasRunning && startedAt != 0L) {
            writeReport(reason)
            qaBroadcast(false)
        }
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
