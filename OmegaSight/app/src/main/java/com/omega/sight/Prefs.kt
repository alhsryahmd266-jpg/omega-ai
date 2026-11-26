package com.omega.sight

import android.content.Context
import android.content.SharedPreferences
import com.omega.sight.core.Tuning

/** Everything the app remembers. Plain SharedPreferences inside the app's own folder; nothing leaves the phone. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences("sight", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun put(k: String, v: Float) = sp.edit().putFloat(k, v).apply()

    /** the package of the game the owner picked */
    var gamePkg: String
        get() = str("game", "")
        set(v) = put("game", v)

    /** he said this is his own offline game */
    var ownConfirmed: Boolean
        get() = sp.getBoolean("own", false)
        set(v) = put("own", v)

    /** the small status dot over the game */
    var pill: Boolean
        get() = sp.getBoolean("pill", true)
        set(v) = put("pill", v)

    /** long side of the captured picture, pixels */
    var captureLong: Int
        get() = sp.getInt("capLong", 1280)
        set(v) = put("capLong", v)

    var visionOn: Boolean
        get() = sp.getBoolean("visionOn", true)
        set(v) = put("visionOn", v)

    var brainOn: Boolean
        get() = sp.getBoolean("brainOn", false)
        set(v) = put("brainOn", v)

    /** 0 = the classical aim alone, 1 = the brain's aim */
    var brainMix: Float
        get() = sp.getFloat("brainMix", 0.5f)
        set(v) = put("brainMix", v)

    /** try the phone's GPU for the model files */
    var gpu: Boolean
        get() = sp.getBoolean("gpu", true)
        set(v) = put("gpu", v)

    /** where the aiming finger works: 0 = found by itself, 1 = right, 2 = left, 3 = middle */
    var zoneMode: Int
        get() = sp.getInt("zoneMode", 0)
        set(v) = put("zoneMode", v)

    /** crosshair position as a share of the screen */
    var crossX: Float
        get() = sp.getFloat("crossX", 0.5f)
        set(v) = put("crossX", v)
    var crossY: Float
        get() = sp.getFloat("crossY", 0.5f)
        set(v) = put("crossY", v)

    var tuning: Tuning
        get() {
            val d = Tuning()
            return Tuning(
                strength = sp.getFloat("t_strength", d.strength),
                fov = sp.getFloat("t_fov", d.fov),
                smooth = sp.getFloat("t_smooth", d.smooth),
                maxSpeed = sp.getFloat("t_maxSpeed", d.maxSpeed),
                headBias = sp.getFloat("t_headBias", d.headBias),
                lead = sp.getFloat("t_lead", d.lead),
                motionSensitivity = sp.getFloat("t_motionSens", d.motionSensitivity),
                minBlob = sp.getFloat("t_minBlob", d.minBlob),
                motionTargets = sp.getBoolean("t_motionTargets", d.motionTargets),
                farMode = sp.getBoolean("t_farMode", d.farMode),
                modelScore = sp.getFloat("t_modelScore", d.modelScore),
                glow = sp.getBoolean("t_glow", d.glow),
                glowSens = sp.getFloat("t_glowSens", d.glowSens),
                idleEvery = sp.getInt("t_idleEvery", d.idleEvery),
            )
        }
        set(t) {
            sp.edit()
                .putFloat("t_strength", t.strength)
                .putFloat("t_fov", t.fov)
                .putFloat("t_smooth", t.smooth)
                .putFloat("t_maxSpeed", t.maxSpeed)
                .putFloat("t_headBias", t.headBias)
                .putFloat("t_lead", t.lead)
                .putFloat("t_motionSens", t.motionSensitivity)
                .putFloat("t_minBlob", t.minBlob)
                .putBoolean("t_motionTargets", t.motionTargets)
                .putBoolean("t_farMode", t.farMode)
                .putFloat("t_modelScore", t.modelScore)
                .putBoolean("t_glow", t.glow)
                .putFloat("t_glowSens", t.glowSens)
                .putInt("t_idleEvery", t.idleEvery)
                .apply()
        }

    /** what the aim loop learnt about one game's camera: relX, relY, lag, confidence */
    class Calib(val relX: Float, val relY: Float, val lag: Float, val confidence: Float)

    fun calib(pkg: String): Calib? {
        val s = sp.getString("cal_$pkg", null) ?: return null
        val p = s.split(',')
        if (p.size != 4) return null
        return try {
            Calib(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun saveCalib(pkg: String, c: Calib) {
        put("cal_$pkg", "${c.relX},${c.relY},${c.lag},${c.confidence}")
    }

    fun forgetCalib(pkg: String) {
        sp.edit().remove("cal_$pkg").apply()
    }
}
