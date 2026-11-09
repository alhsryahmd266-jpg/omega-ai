package com.ahmedxg.gamebot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Mode { MONKEY, AI }
enum class Backend { AUTO, SHIZUKU, ACCESSIBILITY, SHELL }

/** Positions of the game's on-screen controls, normalized 0..1 of the display. */
class ControlLayout {
    val points = LinkedHashMap<String, FloatArray>()
    var stickRadius = 0.10f   // fraction of the short display side
    var lookRadius = 0.14f

    init {
        reset()
    }

    fun reset() {
        points.clear()
        points["stick"] = floatArrayOf(0.16f, 0.72f)
        points["look"] = floatArrayOf(0.72f, 0.50f)
        points["fire"] = floatArrayOf(0.86f, 0.70f)
        points["jump"] = floatArrayOf(0.93f, 0.80f)
        points["crouch"] = floatArrayOf(0.82f, 0.86f)
        points["reload"] = floatArrayOf(0.70f, 0.84f)
        points["ads"] = floatArrayOf(0.94f, 0.42f)
        // menu / "play again" taps used by the soak-test macro
        points["m1"] = floatArrayOf(0.50f, 0.82f)
        points["m2"] = floatArrayOf(0.50f, 0.68f)
        points["m3"] = floatArrayOf(0.80f, 0.88f)
        points["m4"] = floatArrayOf(0.50f, 0.50f)
        points["heal"] = floatArrayOf(0.62f, 0.88f)
        // HUD health bar rectangle corners
        points["hpTL"] = floatArrayOf(0.30f, 0.93f)
        points["hpBR"] = floatArrayOf(0.50f, 0.96f)
        // minimap rectangle corners (for zone navigation)
        points["mapTL"] = floatArrayOf(0.845f, 0.012f)
        points["mapBR"] = floatArrayOf(0.995f, 0.27f)
        stickRadius = 0.10f
        lookRadius = 0.14f
    }

    fun x(name: String): Float = points[name]?.get(0) ?: 0.5f
    fun y(name: String): Float = points[name]?.get(1) ?: 0.5f

    fun toJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in points) o.put(k, JSONArray().put(v[0].toDouble()).put(v[1].toDouble()))
        o.put("stickRadius", stickRadius.toDouble())
        o.put("lookRadius", lookRadius.toDouble())
        return o
    }

    fun load(o: JSONObject?) {
        if (o == null) return
        for (k in points.keys.toList()) {
            val a = o.optJSONArray(k) ?: continue
            if (a.length() >= 2) {
                points[k] = floatArrayOf(a.optDouble(0, 0.5).toFloat(), a.optDouble(1, 0.5).toFloat())
            }
        }
        stickRadius = o.optDouble("stickRadius", stickRadius.toDouble()).toFloat()
        lookRadius = o.optDouble("lookRadius", lookRadius.toDouble()).toFloat()
    }
}

class Config {
    var gamePackage = ""
    var mode = Mode.AI
    var skill = Skill.NORMAL
    var backend = Backend.AUTO
    var modelFile = ""
    var labelsFile = ""
    var labels = "enemy,enemy_head"
    var conf = 0.35f
    var iou = 0.5f
    var threads = 4
    var useGpu = false
    var targetFps = 30
    var captureShortSide = 480
    var guardEnabled = true
    var maxRunMinutes = 0          // 0 = play until you press STOP
    var startDelaySec = 5
    var recordFrames = false
    var recordEveryMs = 500
    var aimScale = 1.0f
    var crossX = 0.5f
    var crossY = 0.5f
    var autoStart = true           // true: starts by itself after the countdown; false: wait for START on the HUD
    var soakEnabled = true         // supervisor: frozen-screen detection + restart macro
    var relaunchOnExit = true      // re-open the game if it closed / crashed
    var frozenSeconds = 15
    var macroCount = 3             // how many menu points (m1..m4) to tap
    var macroDelayMs = 1500
    var zoneEnabled = false
    var zoneColor = "FFFFFF"       // RRGGBB of the safe-zone ring on the minimap
    var zoneTol = 40               // max per-channel color difference
    var zoneHeadingUp = true       // minimap rotates with the player (player icon at the center, facing up)
    var tacticSneak = true         // stalk / flank an enemy before striking
    var tacticAmbush = true        // hold the angle for a moment after losing an enemy at close range
    var tacticDodge = true         // evasive strafe + jump when taking damage
    var tacticJump = true          // jump-peeks while strafing
    var tacticCrouch = false       // use the crouch button (toggle style) while stalking / ambushing
    var style = Style.BALANCED     // match plan: balanced / aggressive / stealth / survivor
    var healthEnabled = false      // read the HUD health bar (retreat / heal when low)
    var healthColor = "00FF00"     // RRGGBB of the health bar fill
    var healthTol = 50
    var allyMapEnabled = false     // read teammate markers from the minimap (numbered circles or green dots)
    var enemyMapEnabled = false    // read red enemy dots from the minimap
    var enemyMapColor = "FF2D2D"
    var enemyMapTol = 55
    var allyColor = "00A2FF"       // RRGGBB of the teammate markers
    var allyTol = 45
    var squadFollow = false
    var fovDeg = 80
    var autoCalibrate = true       // find the stick / fire buttons by itself (needs the camera to move)
    var calibrateEveryRun = true   // redo it on every start, so moved buttons are found again
    var layoutCalibrated = false
    var hitSensitivity = 25        // red-edge flash threshold used to detect "I got hit"
    val layout = ControlLayout()

    fun labelList(): List<String> {
        if (labelsFile.isNotEmpty()) {
            val f = File(labelsFile)
            if (f.exists()) {
                val l = f.readLines().map { it.trim() }.filter { it.isNotEmpty() }
                if (l.isNotEmpty()) return l
            }
        }
        return labels.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("gamePackage", gamePackage)
        o.put("mode", mode.name)
        o.put("skill", skill.name)
        o.put("backend", backend.name)
        o.put("modelFile", modelFile)
        o.put("labelsFile", labelsFile)
        o.put("labels", labels)
        o.put("conf", conf.toDouble())
        o.put("iou", iou.toDouble())
        o.put("threads", threads)
        o.put("useGpu", useGpu)
        o.put("targetFps", targetFps)
        o.put("captureShortSide", captureShortSide)
        o.put("guardEnabled", guardEnabled)
        o.put("maxRunMinutes", maxRunMinutes)
        o.put("startDelaySec", startDelaySec)
        o.put("recordFrames", recordFrames)
        o.put("recordEveryMs", recordEveryMs)
        o.put("aimScale", aimScale.toDouble())
        o.put("crossX", crossX.toDouble())
        o.put("crossY", crossY.toDouble())
        o.put("autoStart", autoStart)
        o.put("soakEnabled", soakEnabled)
        o.put("relaunchOnExit", relaunchOnExit)
        o.put("frozenSeconds", frozenSeconds)
        o.put("macroCount", macroCount)
        o.put("macroDelayMs", macroDelayMs)
        o.put("zoneEnabled", zoneEnabled)
        o.put("zoneColor", zoneColor)
        o.put("zoneTol", zoneTol)
        o.put("zoneHeadingUp", zoneHeadingUp)
        o.put("tacticSneak", tacticSneak)
        o.put("tacticAmbush", tacticAmbush)
        o.put("tacticDodge", tacticDodge)
        o.put("tacticJump", tacticJump)
        o.put("tacticCrouch", tacticCrouch)
        o.put("style", style.name)
        o.put("healthEnabled", healthEnabled)
        o.put("healthColor", healthColor)
        o.put("healthTol", healthTol)
        o.put("allyMapEnabled", allyMapEnabled)
        o.put("enemyMapEnabled", enemyMapEnabled)
        o.put("enemyMapColor", enemyMapColor)
        o.put("enemyMapTol", enemyMapTol)
        o.put("allyColor", allyColor)
        o.put("allyTol", allyTol)
        o.put("squadFollow", squadFollow)
        o.put("fovDeg", fovDeg)
        o.put("autoCalibrate", autoCalibrate)
        o.put("calibrateEveryRun", calibrateEveryRun)
        o.put("layoutCalibrated", layoutCalibrated)
        o.put("hitSensitivity", hitSensitivity)
        o.put("layout", layout.toJson())
        return o
    }

    fun fromJson(o: JSONObject) {
        gamePackage = o.optString("gamePackage", gamePackage)
        mode = try { Mode.valueOf(o.optString("mode", mode.name)) } catch (e: Exception) { Mode.AI }
        skill = Skill.from(o.optString("skill", skill.name))
        backend = try { Backend.valueOf(o.optString("backend", backend.name)) } catch (e: Exception) { Backend.AUTO }
        modelFile = o.optString("modelFile", modelFile)
        labelsFile = o.optString("labelsFile", labelsFile)
        labels = o.optString("labels", labels)
        conf = o.optDouble("conf", conf.toDouble()).toFloat()
        iou = o.optDouble("iou", iou.toDouble()).toFloat()
        threads = o.optInt("threads", threads)
        useGpu = o.optBoolean("useGpu", useGpu)
        targetFps = o.optInt("targetFps", targetFps)
        captureShortSide = o.optInt("captureShortSide", captureShortSide)
        guardEnabled = o.optBoolean("guardEnabled", guardEnabled)
        maxRunMinutes = o.optInt("maxRunMinutes", maxRunMinutes)
        startDelaySec = o.optInt("startDelaySec", startDelaySec)
        recordFrames = o.optBoolean("recordFrames", recordFrames)
        recordEveryMs = o.optInt("recordEveryMs", recordEveryMs)
        aimScale = o.optDouble("aimScale", aimScale.toDouble()).toFloat()
        crossX = o.optDouble("crossX", crossX.toDouble()).toFloat()
        crossY = o.optDouble("crossY", crossY.toDouble()).toFloat()
        autoStart = o.optBoolean("autoStart", autoStart)
        soakEnabled = o.optBoolean("soakEnabled", soakEnabled)
        relaunchOnExit = o.optBoolean("relaunchOnExit", relaunchOnExit)
        frozenSeconds = o.optInt("frozenSeconds", frozenSeconds)
        macroCount = o.optInt("macroCount", macroCount)
        macroDelayMs = o.optInt("macroDelayMs", macroDelayMs)
        zoneEnabled = o.optBoolean("zoneEnabled", zoneEnabled)
        zoneColor = o.optString("zoneColor", zoneColor)
        zoneTol = o.optInt("zoneTol", zoneTol)
        zoneHeadingUp = o.optBoolean("zoneHeadingUp", zoneHeadingUp)
        tacticSneak = o.optBoolean("tacticSneak", tacticSneak)
        tacticAmbush = o.optBoolean("tacticAmbush", tacticAmbush)
        tacticDodge = o.optBoolean("tacticDodge", tacticDodge)
        tacticJump = o.optBoolean("tacticJump", tacticJump)
        tacticCrouch = o.optBoolean("tacticCrouch", tacticCrouch)
        style = Style.from(o.optString("style", style.name))
        healthEnabled = o.optBoolean("healthEnabled", healthEnabled)
        healthColor = o.optString("healthColor", healthColor)
        healthTol = o.optInt("healthTol", healthTol)
        allyMapEnabled = o.optBoolean("allyMapEnabled", allyMapEnabled)
        enemyMapEnabled = o.optBoolean("enemyMapEnabled", enemyMapEnabled)
        enemyMapColor = o.optString("enemyMapColor", enemyMapColor)
        enemyMapTol = o.optInt("enemyMapTol", enemyMapTol)
        allyColor = o.optString("allyColor", allyColor)
        allyTol = o.optInt("allyTol", allyTol)
        squadFollow = o.optBoolean("squadFollow", squadFollow)
        fovDeg = o.optInt("fovDeg", fovDeg)
        autoCalibrate = o.optBoolean("autoCalibrate", autoCalibrate)
        calibrateEveryRun = o.optBoolean("calibrateEveryRun", calibrateEveryRun)
        layoutCalibrated = o.optBoolean("layoutCalibrated", layoutCalibrated)
        hitSensitivity = o.optInt("hitSensitivity", hitSensitivity)
        layout.load(o.optJSONObject("layout"))
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("gamebot", Context.MODE_PRIVATE)
            .edit().putString("cfg", toJson().toString()).apply()
    }

    companion object {
        fun load(ctx: Context): Config {
            val c = Config()
            val s = ctx.getSharedPreferences("gamebot", Context.MODE_PRIVATE).getString("cfg", null)
            if (s != null) {
                try { c.fromJson(JSONObject(s)) } catch (e: Exception) { Logger.i("config load failed: ${e.message}") }
            }
            return c
        }
    }
}
