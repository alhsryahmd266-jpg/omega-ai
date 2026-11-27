package com.omega.sight.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.omega.sight.Prefs
import com.omega.sight.core.AimCmd
import com.omega.sight.core.AimController
import com.omega.sight.core.Box
import com.omega.sight.core.Brain
import com.omega.sight.core.BrainSpec
import com.omega.sight.core.Cover
import com.omega.sight.core.Det
import com.omega.sight.core.Ego
import com.omega.sight.core.FarMotion
import com.omega.sight.core.GlobalMotion
import com.omega.sight.core.GlowDetector
import com.omega.sight.core.Gray
import com.omega.sight.core.HudAnalyzer
import com.omega.sight.core.Kind
import com.omega.sight.core.MotionDetector
import com.omega.sight.core.Pick
import com.omega.sight.core.Resample
import com.omega.sight.core.Safety
import com.omega.sight.core.ScreenMath
import com.omega.sight.core.TargetPicker
import com.omega.sight.core.Tracker
import com.omega.sight.core.Tuning
import com.omega.sight.core.Vec2
import com.omega.sight.input.SightService
import com.omega.sight.ml.BrainModel
import com.omega.sight.ml.TfliteRunner
import com.omega.sight.model.ModelStore
import kotlin.math.max
import kotlin.math.min

/**
 * The whole pipeline, one frame at a time, on its own thread:
 *
 *   screen picture -> small luma picture -> camera movement (GlobalMotion) -> moving things (MotionDetector, FarMotion)
 *                  -> red lights (GlowDetector) -> the owner's vision file (ModelWorker, a few frames late)
 *   -> Tracker (what is followed) -> TargetPicker (which one) -> AimController (how far the finger has to move) -> gesture service.
 *
 * The follow-up never waits for a button: targets are tracked all the time, so the correction is already known when the aiming starts.
 * Gestures are only sent while the hands-off test is on, the picked game is in front, and nobody is touching the screen.
 */
class Engine(private val app: Context) {
    private val prefs = Prefs(app)
    private val thread = HandlerThread("sight-engine", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }

    /** the capture service delivers its pictures on this handler */
    val handler = Handler(thread.looper)
    private val worker = ModelWorker(app, prefs)

    // ---- sizes ----
    private var w = 0
    private var h = 0
    private var stride = 0
    private var screenW = 0
    private var screenH = 0

    /** the picture and the screen are turned the same way; while they are not (the phone just turned) nothing is aimed */
    private var sizeOk = true

    /** the screen's density and the band along its edges that the aim keeps out of (the system's edge gestures live there) */
    private var screenDpi = 420
    private var margin = 100

    /** nothing is aimed before this moment (uptime ms): a short pause after the screen or the picture changed its size */
    private var settleUntil = 0L
    private var rgba = ByteArray(0)
    private val cw = 192
    private var ch = 0

    /** capture pixels in one coarse pixel */
    private var scaleC = 1f
    private var fw = 0
    private var fh = 0

    /** fine pixels in one coarse pixel */
    private var kf = 1f

    // ---- analysis parts ----
    private var cur = Gray(1, 1)
    private var prev = Gray(1, 1)
    private var fine = Gray(1, 1)
    private var havePrev = false
    private var gm = GlobalMotion(8, 8)
    private var md = MotionDetector(8, 8)
    private var far = FarMotion(8, 8)
    private var glow = GlowDetector(8, 8)
    private var hud = HudAnalyzer(8, 8)
    private var maskC: ByteArray? = null
    private var maskF: ByteArray? = null
    private var maskFull: ByteArray? = null
    private var maskIgnore: IntArray? = null
    private val tracker = Tracker()
    private val picker = TargetPicker()
    private val aim = AimController()

    // ---- state ----
    private var tuning: Tuning = prefs.tuning
    private var crossX = 0.5f
    private var crossY = 0.5f
    private var frameNo = 0L
    private var lastNs = 0L
    private var fps = 0f
    private var cumTx = 0f
    private var cumTy = 0f
    private var pass = 0
    private var zoneCx = 0f
    private var zoneCy = 0f
    private var wasAiming = false
    private var aimSince = 0L
    private var epoch = 0
    private var lastCmdX = 0f
    private var lastCmdY = 0f
    private var wasAway = false
    private var brain: BrainModel? = null
    private var brainStamp = -1L
    private var brainTried = -1L
    private val brainIn = FloatArray(BrainSpec.IN)
    private var calibPkg = ""
    private var lastSaved = 0L

    /** a debug build (the emulator run on CI) writes the numbers to the log every two seconds or so; the release APK never does */
    private val debugLog = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var lastLogNs = 0L

    /** the capture service calls this for every new picture */
    fun onImage(reader: ImageReader) {
        val img = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return
        try {
            process(img)
        } catch (t: Throwable) {
            Hub.stats.error = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
        } finally {
            img.close()
        }
    }

    /** a reader the capture service does not need any more: closed on the engine thread, so no picture is being read from it at that moment */
    fun dispose(reader: ImageReader) {
        handler.post {
            try {
                reader.close()
            } catch (_: Throwable) {
            }
        }
    }

    fun stop(vararg readers: ImageReader?) {
        handler.removeCallbacksAndMessages(null)
        handler.post {
            saveCalibration(true)
            SightService.instance?.lift()
            brain?.close()
            brain = null
            worker.stop()
            Hub.zone = null
            Hub.stats.clear()
            for (r in readers) {
                try {
                    r?.close()
                } catch (_: Throwable) {
                }
            }
            thread.quitSafely()
        }
    }

    /** reads the screen as it is now; true when its size changed */
    private fun updateScreen(): Boolean {
        val s = ScreenInfo.read(app)
        val changed = s[0] != screenW || s[1] != screenH
        screenW = s[0]
        screenH = s[1]
        screenDpi = s[2]
        margin = ScreenMath.safeMargin(screenDpi, screenW, screenH)
        sizeOk = w <= 0 || (screenW >= screenH) == (w >= h)
        val st = Hub.stats
        st.screenW = screenW
        st.screenH = screenH
        st.sizeOk = sizeOk
        return changed
    }

    /** everything that is worked out from the screen's size */
    private fun screenChanged() {
        settleUntil = SystemClock.uptimeMillis() + SETTLE_MS
        if (cw > 0) aim.screenPerFrame = screenW / cw.toFloat()
        computeZone()
        rebuildMasks()
    }

    private fun configure(newW: Int, newH: Int, rowStride: Int) {
        w = newW
        h = newH
        stride = rowStride
        rgba = ByteArray(max(rowStride * newH, newW * newH * 4))
        updateScreen()
        ch = max(24, Math.round(cw * newH / newW.toFloat()))
        scaleC = newW / cw.toFloat()
        fw = min(newW, 640)
        fh = max(24, Math.round(fw * newH / newW.toFloat()))
        kf = fw / cw.toFloat()
        cur = Gray(cw, ch)
        prev = Gray(cw, ch)
        fine = Gray(fw, fh)
        gm = GlobalMotion(cw, ch)
        md = MotionDetector(cw, ch)
        far = FarMotion(fw, fh)
        glow = GlowDetector(newW, newH)
        hud = HudAnalyzer(cw, ch)
        maskC = null
        maskF = null
        maskFull = null
        maskIgnore = null
        havePrev = false
        tracker.reset()
        picker.reset()
        aim.screenPerFrame = screenW / cw.toFloat()
        aim.frameH = ch.toFloat()
        calibPkg = ""
        loadCalibration()
        computeZone()
        Hub.stats.w = newW
        Hub.stats.h = newH
    }

    private fun loadCalibration() {
        val pkg = prefs.gamePkg
        if (pkg == calibPkg) return
        calibPkg = pkg
        val c = if (pkg.isEmpty()) null else prefs.calib(pkg)
        if (c != null) aim.loadCalibration(c.relX, c.relY, c.lag, c.confidence)
    }

    private fun saveCalibration(force: Boolean) {
        val pkg = prefs.gamePkg
        if (pkg.isEmpty() || !aim.calibrated) return
        val now = System.nanoTime()
        if (!force && now - lastSaved < 8_000_000_000L) return
        lastSaved = now
        prefs.saveCalib(pkg, Prefs.Calib(aim.relX, aim.relY, aim.lag, aim.confidence))
    }

    /**
     * "" when nothing lies over the aim zone, else the package of the window that does ("?" = it did not say, or the window guard has not
     * answered for a while: something does not respond, so the finger stays away). Without the guard (the service is not there) nothing
     * can be aimed anyway.
     */
    private fun whoCovers(svc: SightService?): String {
        val g = svc?.guard ?: return ""
        if (SystemClock.uptimeMillis() - g.at > GUARD_FRESH_MS) return "?"
        return if (g.state == Cover.State.COVERED) g.by.ifEmpty { "?" } else ""
    }

    /** where the aiming finger works, in screen pixels */
    private fun computeZone() {
        val sw = screenW.toFloat()
        val sh = screenH.toFloat()
        var l = 0.55f * sw
        var t = 0.15f * sh
        var r = 0.95f * sw
        var b = 0.85f * sh
        when (prefs.zoneMode) {
            2 -> {
                l = 0.05f * sw
                r = 0.45f * sw
            }
            3 -> {
                l = 0.30f * sw
                r = 0.70f * sw
            }
            0 -> if (hud.ready) {
                val z = hud.suggestZone(hud.circles())
                val kx = sw / cw
                val ky = sh / ch
                l = z[0] * kx
                t = z[1] * ky
                r = z[2] * kx
                b = z[3] * ky
            }
        }
        // inside the band that the system's edge gestures leave alone
        val m = margin.toFloat()
        l = max(l, m)
        t = max(t, m)
        r = min(r, sw - m)
        b = min(b, sh - m)
        if (r - l < 0.12f * sw || b - t < 0.2f * sh) {
            l = max(m, 0.55f * sw)
            r = min(sw - m, 0.92f * sw)
            t = max(m, 0.15f * sh)
            b = min(sh - m, 0.85f * sh)
        }
        zoneCx = (l + r) / 2f
        zoneCy = (t + b) / 2f
        aim.zoneHalfW = 0.5f * (r - l)
        aim.zoneHalfH = 0.5f * (b - t)
        Hub.zone = intArrayOf(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }

    /** the screen furniture found so far, plus the status dot, as masks the detectors keep away from */
    private fun rebuildMasks() {
        val ig = Hub.ignoreRect
        maskIgnore = ig
        if (!hud.ready && ig == null) {
            maskC = null
            maskF = null
            maskFull = null
            return
        }
        val mc = if (hud.ready) hud.maskFor(cw, ch, 1) else ByteArray(cw * ch)
        val mf = if (hud.ready) hud.maskFor(fw, fh, 2) else ByteArray(fw * fh)
        val mu = if (hud.ready) hud.maskFor(w, h, 3) else ByteArray(w * h)
        if (ig != null) {
            fillRect(mc, cw, ch, ig)
            fillRect(mf, fw, fh, ig)
            fillRect(mu, w, h, ig)
        }
        maskC = mc
        maskF = mf
        maskFull = mu
    }

    private fun fillRect(m: ByteArray, mw: Int, mh: Int, r: IntArray) {
        val x0 = max(0, (r[0] * mw / screenW.toFloat()).toInt() - 1)
        val y0 = max(0, (r[1] * mh / screenH.toFloat()).toInt() - 1)
        val x1 = min(mw - 1, (r[2] * mw / screenW.toFloat()).toInt() + 1)
        val y1 = min(mh - 1, (r[3] * mh / screenH.toFloat()).toInt() + 1)
        for (y in y0..y1) for (x in x0..x1) m[y * mw + x] = 1
    }

    private fun ensureBrain() {
        val f = ModelStore.file(app, ModelStore.Slot.BRAIN)
        val st = if (f.isFile) f.lastModified() + f.length() else 0L
        if (st == brainStamp) return
        brain?.close()
        brain = null
        brainStamp = st
        if (st == 0L) {
            Hub.stats.brainStatus = ""
            return
        }
        if (TfliteRunner.libraryName() == null) {
            Hub.stats.brainStatus = "مكتبة LiteRT مش موجودة"
            return
        }
        val o = BrainModel.open(f, 2, false)
        brain = o.model
        Hub.stats.brainStatus = if (o.model != null) "شغّال" else o.error
    }

    private fun toCoarse(d: Det, div: Float): Det =
        Det(Box(d.box.l / div, d.box.t / div, d.box.r / div, d.box.b / div), d.score, d.kind)

    private fun process(img: Image) {
        val t0 = System.nanoTime()
        val plane = img.planes[0]
        if (img.width != w || img.height != h || plane.rowStride != stride) configure(img.width, img.height, plane.rowStride)
        val st = Hub.stats
        if (!st.running) SightService.instance?.guard?.poke()
        st.running = true
        frameNo++
        // the screen is looked at again every few pictures: the phone may have turned
        if (frameNo % 6L == 0L && updateScreen()) screenChanged()
        if (frameNo % 20L == 1L) {
            tuning = prefs.tuning
            crossX = prefs.crossX
            crossY = prefs.crossY
            loadCalibration()
        }

        val verdict = Safety.verdict(prefs.gamePkg, prefs.ownConfirmed, SightService.foreground, app.packageName)
        st.verdict = verdict
        // another app is in front: look only now and then, to spare the battery
        val away = SightService.foreground.isNotEmpty() && verdict == Safety.Verdict.WRONG_APP && !Hub.testAim
        if (away) {
            wasAway = true
            if (frameNo % 4L != 0L) return
        } else if (wasAway) {
            wasAway = false
            far.reset()
            tracker.reset()
            picker.reset()
            havePrev = false
        }

        val buf = plane.buffer
        buf.rewind()
        buf.get(rgba, 0, min(buf.remaining(), rgba.size))
        val now = System.nanoTime()
        var dt = if (lastNs == 0L) 1f / 30f else (now - lastNs) / 1e9f
        lastNs = now
        if (dt > 0.2f) {
            // a long gap: what was followed before is no use any more
            far.reset()
            tracker.reset()
            picker.reset()
            havePrev = false
        }
        dt = dt.coerceIn(0.005f, 0.25f)
        fps = if (fps == 0f) 1f / dt else 0.92f * fps + 0.08f / dt

        if (frameNo % 90L == 1L || maskIgnore !== Hub.ignoreRect) {
            rebuildMasks()
            if (frameNo % 90L == 1L) computeZone()
        }

        // ---- the small picture, the camera, the screen furniture ----
        Resample.lumaFromRgba(rgba, w, h, stride, 0, 0, w, h, cur)
        var ego = Ego.none(cw, ch)
        if (havePrev) {
            ego = gm.estimate(prev, cur, maskC)
            hud.update(prev, cur)
        }
        cumTx += ego.tx
        cumTy += ego.ty
        aim.observe(ego)
        st.egoSpeed = ego.speed
        st.hudPairs = hud.pairsSeen
        st.hudReady = hud.ready

        val dets = ArrayList<Det>(24)
        val cross = Vec2(crossX * cw, crossY * ch)

        // ---- moving things ----
        var tm = System.nanoTime()
        var nMotion = 0
        val motion = ArrayList<Det>()
        if (havePrev && tuning.motionTargets) {
            motion.addAll(md.detect(prev, cur, ego, maskC, tuning.motionSensitivity, tuning.minBlob))
            nMotion = motion.size
            dets.addAll(motion)
        }
        st.motionMs = (System.nanoTime() - tm) / 1e6f

        // ---- very small and slow things, at the sharp picture ----
        tm = System.nanoTime()
        var nFar = 0
        if (havePrev && tuning.farMode && tuning.motionTargets) {
            Resample.lumaFromRgba(rgba, w, h, stride, 0, 0, w, h, fine)
            val half = (tuning.fov.coerceIn(0.1f, 0.9f) * 1.3f * fh).toInt()
            val cxF = (crossX * fw).toInt()
            val cyF = (crossY * fh).toInt()
            val roi = intArrayOf(max(2, cxF - half), max(2, cyF - half), min(fw - 2, cxF + half), min(fh - 2, cyF + half))
            val minArea = if (tuning.minBlob <= 0.5f) 1 else 1 + ((tuning.minBlob - 0.5f) * 8f).toInt()
            val f = far.process(fine, ego, kf, maskF, roi, tuning.motionSensitivity, minArea)
            for (d in f) {
                val c = toCoarse(d, kf)
                // already inside a bigger moving shape: it is the same thing
                var dup = false
                for (m in motion) {
                    if (m.box.inflated(1.3f).contains(c.box.cx, c.box.cy)) {
                        dup = true
                        break
                    }
                }
                if (!dup) {
                    dets.add(c)
                    nFar++
                }
            }
        } else if (tuning.farMode && tuning.motionTargets) {
            // the first picture: only fills the history
            Resample.lumaFromRgba(rgba, w, h, stride, 0, 0, w, h, fine)
            far.process(fine, null, 1f, maskF, null, tuning.motionSensitivity, 1)
        }
        st.farMs = (System.nanoTime() - tm) / 1e6f

        // ---- the glowing red light, by colour ----
        tm = System.nanoTime()
        var nGlow = 0
        if (tuning.glow) {
            val g = glow.detect(rgba, stride, maskFull, tuning.glowSens, 2)
            for (d in g) dets.add(toCoarse(d, scaleC))
            nGlow = g.size
        }
        st.glowMs = (System.nanoTime() - tm) / 1e6f

        // ---- the vision file: take the answer that came back, hand over the next picture ----
        var nModel = 0
        val r = worker.pending
        if (r != null) {
            worker.pending = null
            val ox = cumTx - r.cumTx
            val oy = cumTy - r.cumTy
            for (d in r.dets) {
                val c = toCoarse(d, scaleC)
                dets.add(Det(c.box.shifted(ox, oy), c.score, c.kind))
                nModel++
            }
        }
        if (prefs.visionOn && !worker.isBusy()) {
            val hasTarget = tracker.tracks.isNotEmpty()
            if (hasTarget || Hub.testAim || frameNo % max(1, tuning.idleEvery).toLong() == 0L) {
                val usePass = if (tuning.farMode) pass else 0
                if (worker.submit(rgba, w, h, stride, usePass, (crossX * w).toInt(), (crossY * h).toInt(), tuning.modelScore, cumTx, cumTy)) pass = 1 - pass
            }
        }
        st.visionStatus = worker.status
        st.visionMs = worker.lastMs
        st.visionGpu = worker.usingGpu

        // ---- follow, choose, aim ----
        val tracks = tracker.update(dets, ego, dt, cw, ch, cross)
        var pick: Pick? = picker.pick(tracks, cross, ch.toFloat(), tuning, aim.latency(dt))

        if (frameNo % 60L == 1L) ensureBrain()
        val br = brain
        if (prefs.brainOn && br != null && pick != null) {
            val secs = if (aimSince == 0L) 0f else (now - aimSince) / 1e9f
            Brain.buildState(tracks, picker, cross, ch.toFloat(), ego, dt, secs, lastCmdX, lastCmdY, screenH.toFloat(), tuning.headBias, brainIn)
            val out = br.infer(brainIn)
            val bo = if (out != null) Brain.interpret(out, ch.toFloat()) else null
            if (bo != null) pick = Pick(pick.track, Brain.mix(pick.aim, cross, bo, prefs.brainMix), pick.dist)
        }

        val svc = SightService.instance
        if (svc != null && svc.strokeEpoch != epoch) {
            epoch = svc.strokeEpoch
            aim.endStroke()
        }
        // the aim only works inside the owner's own game: it has to be the app in front (our own screen does not count), nothing else may lie
        // over the place where the finger works (a system message, the shade, a keyboard, a floating button), the picture and the screen
        // have to agree, and a moment has to have passed since the phone turned
        val coveredBy = whoCovers(svc)
        st.coveredBy = coveredBy
        val inGame = verdict == Safety.Verdict.OK && SightService.foreground == prefs.gamePkg && coveredBy.isEmpty()
        st.inGame = inGame
        val allowed = inGame && svc != null && Hub.testAim && !svc.userTouching() && sizeOk && SystemClock.uptimeMillis() >= settleUntil
        if (allowed && pick != null && svc != null) {
            if (!wasAiming) aimSince = now
            val cmd: AimCmd = aim.step(pick, cross, tuning, dt)
            lastCmdX = cmd.dx
            lastCmdY = cmd.dy
            if (cmd.active) svc.aim(cmd.dx, cmd.dy, cmd.restart, zoneCx, zoneCy)
            wasAiming = true
        } else {
            aim.step(null, cross, tuning, dt)
            if (wasAiming) {
                svc?.lift()
                aim.endStroke()
                wasAiming = false
                aimSince = 0L
                lastCmdX = 0f
                lastCmdY = 0f
            }
        }

        if (frameNo % 150L == 0L) saveCalibration(false)

        // ---- numbers for the screens ----
        st.fps = fps
        st.nMotion = nMotion
        st.nFar = nFar
        st.nGlow = nGlow
        st.nModel = nModel
        st.nTracks = tracks.size
        st.picked = pick?.track?.kind?.name ?: ""
        st.calibrated = aim.calibrated
        st.relX = aim.relX
        st.relY = aim.relY
        st.lag = aim.lag
        st.confidence = aim.confidence
        val frameMs = (System.nanoTime() - t0) / 1e6f
        st.frameMs = if (st.frameMs == 0f) frameMs else 0.9f * st.frameMs + 0.1f * frameMs

        if (debugLog && t0 - lastLogNs > 2_000_000_000L) {
            lastLogNs = t0
            Log.i("SightLive", st.logLine())
        }

        val t = prev
        prev = cur
        cur = t
        havePrev = true
    }

    private companion object {
        /** how long nothing is aimed after the screen or the picture changed its size (ms) */
        const val SETTLE_MS = 1200L

        /** an answer of the window guard older than this (ms) is not trusted */
        const val GUARD_FRESH_MS = 2000L
    }
}
