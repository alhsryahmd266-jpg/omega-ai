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

    init {
        // 1.0.7: the language button of the screen-translation bar used to save its choice, which could leave screen translation
        // on English or French for good. It is Arabic again, and the bar's button now only changes the current session.
        if (!sp.getBoolean("screen_target_reset_107", false)) {
            sp.edit().putString("screen_target", "ar").putBoolean("screen_target_reset_107", true).apply()
        }
    }

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

    private inner class L(val k: String, val d: Long) : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Long = sp.getLong(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) { sp.edit().putLong(k, value).apply() }
    }

    // ---- keyboard look & feel
    var theme by S("theme", "auto")
    var heightPct by I("height_pct", 100)                 // 60..170 (dragged by hand or set with the slider)

    // colours, size and effects. 0 / -1 mean "as the theme says"
    var textColor by I("text_color", 0xFF2979FF.toInt())  // colour of the text you type; 0 = the field's own colour
    var textRainbow by B("text_rainbow", false)           // every letter in the next colour of the rainbow
    var textHighlight by B("text_highlight", false)       // highlighter pen: the colour goes behind the text
    var colourOnSend by B("colour_on_send", true)         // the send key of a chat box sends the text as a coloured picture / colour codes
    var picAutoSend by B("pic_auto_send", true)           // presses the app's own "send" on the picture preview (needs the accessibility service)
    var picStyle by S("pic_style", "clean")               // clean | neon
    var colorCodes by S("color_codes", "none")            // how colour travels where pictures are refused (games): see ColorCodes.Format
    var keyColor by I("c_key", 0)
    var fnColor by I("c_fn", 0)
    var accentColor by I("c_accent", 0)
    var letterColor by I("c_letter", 0)
    var bgColor by I("c_bg", 0)
    var bgStyle by I("bg_style", -1)                      // -1 theme, 0 flat, 1 gradient, 2 aurora (moves when you type)
    var glassKeys by I("glass_keys", -1)                  // -1 theme, 0 solid keys, 1 see-through glass keys
    var neonLetters by I("neon_letters", -1)              // -1 theme, 0 off, 1 letters glow
    var fontPct by I("font_pct", 100)                     // size of the letters on the keys, 80..140
    var widthPct by I("width_pct", 100)                   // width of the keys area, 60..100 (one-handed typing)
    var kbAlign by I("kb_align", 0)                       // where a narrower keyboard sits: 0 centre, 1 left, 2 right (the older three-way setting)

    /** where a narrower keyboard sits, 0 = at the left edge .. 50 = centred .. 100 = at the right edge; dragging the edges by hand can leave it anywhere */
    var kbOffset: Int
        get() = if (sp.contains("kb_offset")) sp.getInt("kb_offset", 50) else when (kbAlign) { 1 -> 0; 2 -> 100; else -> 50 }
        set(v) {
            val o = v.coerceIn(0, 100)
            sp.edit().putInt("kb_offset", o).putInt("kb_align", if (o <= 8) 1 else if (o >= 92) 2 else 0).apply()
        }
    var fxGlow by B("fx_glow", true)                      // light that follows the finger across the keys
    var fxRipple by B("fx_ripple", true)                  // ring that spreads from a pressed key
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
    var apAutoSend by B("ap_auto_send", true)
    var apSide by S("ap_side", "auto")                 // auto | left | right: which edge my chat bubbles hug

    // ---- voice
    var voiceLang by S("voice_lang", "auto")
    var voiceOffline by B("voice_offline", false)
    var voiceGood by S("voice_good", "")                 // the recogniser + language that worked last time: tried first from now on

    // ---- local model
    var modelPath by S("model_path", "")
    var backend by S("backend", "cpu")                // cpu | gpu
    var idleMinutes by I("idle_minutes", 10)           // 0 = keep loaded
    var llmNoCache by B("llm_nocache", false)         // saves ~GBs of storage, loads slower
    var dlId by L("dl_id", -1L)                        // id of the model download in the phone's download manager, -1 = none
    var dlName by S("dl_name", "")                     // file name that download writes to

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
