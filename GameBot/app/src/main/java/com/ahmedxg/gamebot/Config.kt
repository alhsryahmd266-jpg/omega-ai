package com.ahmedxg.gamebot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Mode { MONKEY, AI }
enum class Backend { AUTO, SHIZUKU, ACCESSIBILITY }

/** Difficulty profile of the bot (QA bots usually need several levels). */
enum class Skill(
    val label: String,
    val reactionMs: Int,
    val aimGain: Float,
    val burstOnMs: Int,
    val burstOffMs: Int,
    val strafe: Boolean
) {
    EASY("Easy", 450, 0.30f, 120, 600, false),
    NORMAL("Normal", 250, 0.50f, 160, 350, true),
    PRO("Pro", 120, 0.70f, 220, 180, true),
    MAX("Max", 0, 0.92f, 450, 80, true);

    companion object {
        fun from(name: String?): Skill = values().firstOrNull { it.name == name } ?: NORMAL
    }
}

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
        points["stick"] = floatArrayOf(0.17f, 0.68f)
        points["look"] = floatArrayOf(0.72f, 0.50f)
        points["fire"] = floatArrayOf(0.88f, 0.62f)
        points["jump"] = floatArrayOf(0.93f, 0.80f)
        points["crouch"] = floatArrayOf(0.82f, 0.86f)
        points["reload"] = floatArrayOf(0.70f, 0.84f)
        points["ads"] = floatArrayOf(0.94f, 0.42f)
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
    var mode = Mode.MONKEY
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
    var maxRunMinutes = 30
    var startDelaySec = 5
    var recordFrames = false
    var recordEveryMs = 500
    var aimScale = 1.0f
    var crossX = 0.5f
    var crossY = 0.5f
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
        o.put("layout", layout.toJson())
        return o
    }

    fun fromJson(o: JSONObject) {
        gamePackage = o.optString("gamePackage", gamePackage)
        mode = try { Mode.valueOf(o.optString("mode", mode.name)) } catch (e: Exception) { Mode.MONKEY }
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
