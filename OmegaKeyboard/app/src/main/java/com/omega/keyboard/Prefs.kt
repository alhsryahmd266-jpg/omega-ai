package com.omega.keyboard

import android.content.Context
import android.content.SharedPreferences
import com.omega.keyboard.core.Align
import com.omega.keyboard.core.LlmPrompts
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** All user settings in one place. Read live by the keyboard, the agent service and the settings screens. */
class Prefs private constructor(ctx: Context) {
    val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("omega", Context.MODE_PRIVATE)

    private inner class S(val k: String, val d: String) : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = sp.getString(k, d) ?: d
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { sp.edit().putString(k, value).apply() }
    }

    private inner class B(val k: String, val d: Boolean) : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = sp.getBoolean(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { sp.edit().putBoolean(k, value).apply() }
    }

    private inner class I(val k: String, val d: Int) : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = sp.getInt(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { sp.edit().putInt(k, value).apply() }
    }

    // ---- keyboard look & feel
    var theme by S("theme", "auto")
    var heightPct by I("height_pct", 100)
    var numberRow by B("number_row", false)
    var indicDigits by B("indic_digits", false)
    var haptic by B("haptic", true)
    var sound by B("sound", false)
    var keyPreview by B("key_preview", true)
    var autoCap by B("auto_cap", true)
    var suggestions by B("suggestions", true)
    var doubleSpacePeriod by B("double_space", true)
    var autoCorrect by B("auto_correct", true)
    var langs by S("langs", "ar,en")
    var lastLang by S("last_lang", "ar")
    var toolbarOpen by B("toolbar_open", true)

    // ---- translation
    var screenTarget by S("screen_target", "ar")
    var typedTarget by S("typed_target", "en")
    var engineMode by S("engine_mode", "auto")        // auto | mlkit | gemma
    var translateOnSend by B("translate_on_send", false)

    // ---- autonomous mode
    var apPersona by S("ap_persona", LlmPrompts.DEFAULT_PERSONA)
    var apDelayMinSec by I("ap_delay_min", 2)
    var apDelayMaxSec by I("ap_delay_max", 6)
    var apMaxReplies by I("ap_max_replies", 100)
    var apAutoSend by B("ap_auto_send", true)
    var apSide by S("ap_side", "auto")                 // auto | left | right: which edge my chat bubbles hug

    // ---- voice
    var voiceLang by S("voice_lang", "auto")
    var voiceCommands by B("voice_commands", true)
    var voiceOffline by B("voice_offline", false)

    // ---- local model
    var modelPath by S("model_path", "")
    var backend by S("backend", "cpu")                // cpu | gpu
    var idleMinutes by I("idle_minutes", 10)           // 0 = keep loaded
    var llmNoCache by B("llm_nocache", false)         // saves ~GBs of storage, loads slower

    var clipboardHistory by S("clip_history", "")
    var recentEmoji by S("recent_emoji", "")
    var userWordsAr by S("user_words_ar", "")
    var userWordsEn by S("user_words_en", "")
    var lastLog by S("last_log", "")

    fun langList(): List<String> = langs.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("ar", "en") }

    /** which edge MY chat bubbles stick to in [pkg] (learned after the first message we send) */
    fun align(pkg: String): Align = if (sp.getString("align_$pkg", "R") == "L") Align.LEFT else Align.RIGHT
    fun setAlign(pkg: String, a: Align) { sp.edit().putString("align_$pkg", if (a == Align.LEFT) "L" else "R").apply() }

    companion object {
        @Volatile private var inst: Prefs? = null
        fun get(ctx: Context): Prefs = inst ?: synchronized(this) { inst ?: Prefs(ctx).also { inst = it } }
    }
}
