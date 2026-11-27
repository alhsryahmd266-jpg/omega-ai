package com.omega.sight.engine

import com.omega.sight.core.Safety

/** What the engine, the capture service, the gesture service and the screens share. Plain fields: the screens only read them. */
object Hub {
    @Volatile
    var engine: Engine? = null

    /** the hands-off aiming test is switched on: the app moves the camera by itself, so nobody may touch the screen */
    @Volatile
    var testAim = false

    /** screen rectangle [l, t, r, b] in pixels that the status dot covers; it is kept out of the analysis */
    @Volatile
    var ignoreRect: IntArray? = null

    /** screen rectangle [l, t, r, b] in pixels where the aiming finger works; the window guard checks that nothing else is over it */
    @Volatile
    var zone: IntArray? = null

    val stats = Stats()
}

/** Numbers for the diagnostics screen and the status dot. Written by the engine thread, read by the screens. */
class Stats {
    @Volatile var running = false
    @Volatile var w = 0
    @Volatile var h = 0

    /** the screen as the system says it is now (pixels, turned the way the phone is turned) and the size the capture was asked for */
    @Volatile var screenW = 0
    @Volatile var screenH = 0
    @Volatile var capW = 0
    @Volatile var capH = 0

    /** the picture and the screen have the same direction (portrait/landscape); without that nothing is aimed */
    @Volatile var sizeOk = true

    /** the owner's game itself is the app in front right now (our own screen does not count): the only place anything is aimed */
    @Volatile var inGame = false

    /** the package of the window that lies over the aim zone ("?" = a window that did not say who it is), "" = nothing covers it */
    @Volatile var coveredBy = ""
    @Volatile var fps = 0f
    @Volatile var frameMs = 0f
    @Volatile var motionMs = 0f
    @Volatile var farMs = 0f
    @Volatile var glowMs = 0f

    /** detections found in the last frame by each part */
    @Volatile var nMotion = 0
    @Volatile var nFar = 0
    @Volatile var nGlow = 0
    @Volatile var nModel = 0
    @Volatile var nTracks = 0
    @Volatile var picked = ""
    @Volatile var egoSpeed = 0f
    @Volatile var hudPairs = 0
    @Volatile var hudReady = false
    @Volatile var verdict: Safety.Verdict = Safety.Verdict.NO_GAME

    @Volatile var visionStatus = ""
    @Volatile var visionMs = 0f
    @Volatile var visionGpu = false
    @Volatile var brainStatus = ""

    @Volatile var calibrated = false
    @Volatile var relX = 0f
    @Volatile var relY = 0f
    @Volatile var lag = 0f
    @Volatile var confidence = 0f

    /** gesture counters: sent, finished, cancelled by the system (a real finger touched the screen) */
    @Volatile var strokesSent = 0
    @Volatile var strokesDone = 0
    @Volatile var strokesCancelled = 0
    @Volatile var strokesFailed = 0

    @Volatile var error = ""

    /** one line with every number, for the debug log of the emulator run */
    fun logLine(): String = String.format(
        java.util.Locale.US,
        "%dx%d screen=%dx%d cap=%dx%d sizeOk=%b inGame=%b covered=%s fps=%.1f frameMs=%.1f motionMs=%.1f farMs=%.1f glowMs=%.1f | motion=%d far=%d glow=%d model=%d tracks=%d picked=%s ego=%.2f hud=%d/%b | verdict=%s | " +
            "calibrated=%b rel=(%.2f,%.2f) lag=%.1f conf=%.2f | strokes sent=%d done=%d cancelled=%d failed=%d | vision=%s %.1fms gpu=%b brain=%s | err=%s",
        w, h, screenW, screenH, capW, capH, sizeOk, inGame, coveredBy.ifEmpty { "-" }, fps, frameMs, motionMs, farMs, glowMs, nMotion, nFar, nGlow, nModel, nTracks, picked, egoSpeed, hudPairs, hudReady, verdict.name,
        calibrated, relX, relY, lag, confidence, strokesSent, strokesDone, strokesCancelled, strokesFailed, visionStatus, visionMs, visionGpu, brainStatus, error,
    )

    fun clear() {
        running = false
        inGame = false
        coveredBy = ""
        fps = 0f
        frameMs = 0f
        nTracks = 0
        picked = ""
        error = ""
    }
}
