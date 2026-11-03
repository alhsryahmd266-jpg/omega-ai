package com.omega.keyboard.voice

import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.omega.keyboard.AppLog
import com.omega.keyboard.Prefs
import com.omega.keyboard.agent.AutoPilot
import com.omega.keyboard.agent.ScreenTranslateController
import com.omega.keyboard.core.CommandParser
import com.omega.keyboard.core.VoiceCommand

/**
 * Two ways of listening, both through the phone's own recogniser:
 *  - the keyboard's microphone: listens once, then types what was said or runs the command (call someone, open an app,
 *    translate the screen, start / stop autonomous mode);
 *  - autonomous mode: the microphone stays open (one short session after another) so the user can give orders while the
 *    chat is being answered. Only clear orders count there, see [com.omega.keyboard.core.OrderParser]; it is switched on
 *    and off with the button on the control bar.
 * Main thread only.
 */
object VoiceSession {

    interface Sink {
        /** [active]: the microphone is open. [text]: what to show (hint, partial speech, result or error). */
        fun onVoiceState(active: Boolean, text: String, warn: Boolean)
        fun onDictation(text: String)
    }

    private enum class Source { KEYBOARD, AUTOPILOT }

    @Volatile var active = false
        private set
    private var controller: VoiceController? = null
    private var sink: Sink? = null
    private var source = Source.KEYBOARD
    private val main = Handler(Looper.getMainLooper())

    // continuous listening for autonomous mode
    private var autoOn = false
    private var autoFails = 0
    private var autoTask: Runnable? = null
    private var askPermission = false

    /** The microphone is meant to stay open for autonomous-mode orders. */
    val autoListening: Boolean get() = autoOn

    // ------------------------------------------------------------------ the keyboard's microphone

    /** Tap on the keyboard's microphone: starts listening, or (when already listening) finishes and uses what was said. */
    fun listenForKeyboard(ctx: Context, sink: Sink, langHint: String) {
        if (autoOn) { sink.onVoiceState(false, "الميكروفون شغّال مع الوضع الذاتي، اقفله من الزرار الأول", true); return }
        if (active) { stopListening(); return }
        this.sink = sink
        begin(ctx, langHint)
    }

    /** The user finished speaking: deliver what was heard. */
    fun stopListening() { if (source == Source.KEYBOARD) controller?.stop() }

    /** Abort without using anything (also ends continuous listening). */
    fun cancel() {
        stopAuto()
        if (source == Source.KEYBOARD) {
            controller?.cancel()
            controller = null
            if (active) {
                active = false
                sink?.onVoiceState(false, "", false)
            }
        }
    }

    fun detach(s: Sink) { if (sink === s) { sink = null } }

    private fun resolveLang(pref: String, hint: String): String = when {
        pref != "auto" && pref.isNotBlank() -> pref
        hint.startsWith("ar") -> "ar-EG"
        hint == "en" -> "en-US"
        else -> hint
    }

    private fun begin(ctx: Context, langHint: String) {
        val app = ctx.applicationContext
        val prefs = Prefs.get(app)
        source = Source.KEYBOARD
        val c = VoiceController(app)
        controller = c
        active = true
        sink?.onVoiceState(true, "بسمعك...", false)
        c.start(object : VoiceController.Callback {
            override fun onReady() { if (active) sink?.onVoiceState(true, "اتكلم دلوقتي...", false) }
            override fun onPartial(text: String) { if (active) sink?.onVoiceState(true, text, false) }
            override fun onResult(text: String) {
                active = false
                controller = null
                AppLog.d("voice", "heard: $text")
                routeKeyboard(app, text)
            }
            override fun onError(message: String, needsPermission: Boolean, code: Int) {
                active = false
                controller = null
                sink?.onVoiceState(false, message, true)
                if (needsPermission) ActionActivity.requestPermissions(app, arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        }, resolveLang(prefs.voiceLang, langHint), prefs.voiceOffline)
    }

    private fun routeKeyboard(app: Context, spoken: String) {
        val prefs = Prefs.get(app)
        val cmd = CommandParser.parse(spoken, AutoPilot.running, prefs.voiceCommands)
        val say: (String, Boolean) -> Unit = { m, warn -> sink?.onVoiceState(false, m, warn) }
        if (cmd is VoiceCommand.Text) {
            val s = sink
            when {
                cmd.text.isBlank() -> say("ما سمعتش كلام", true)
                s != null -> s.onDictation(cmd.text)
                else -> say("سمعت: ${cmd.text} (مفيش خانة كتابة أكتب فيها)", true)
            }
            return
        }
        dispatch(app, cmd, say)
    }

    // ------------------------------------------------------------------ autonomous mode: the microphone stays open

    /**
     * Starts (or keeps) the open microphone for orders. Safe to call twice.
     * [explicit]: the user just pressed the voice button, so a missing microphone permission may be asked for now;
     * when the mode merely starts with voice on, a missing permission only switches the voice off with a note.
     */
    fun startAuto(ctx: Context, explicit: Boolean = false) {
        if (autoOn) return
        if (source == Source.KEYBOARD && active) { controller?.cancel(); controller = null; active = false; sink?.onVoiceState(false, "", false) }
        autoOn = true
        autoFails = 0
        askPermission = explicit
        cycleAuto(ctx.applicationContext)
    }

    fun stopAuto() {
        autoOn = false
        autoTask?.let { main.removeCallbacks(it) }
        autoTask = null
        if (source == Source.AUTOPILOT) {
            controller?.cancel()
            controller = null
            active = false
        }
    }

    private fun again(app: Context, delayMs: Long) {
        if (!autoOn) return
        autoTask?.let { main.removeCallbacks(it) }
        val r = Runnable { autoTask = null; cycleAuto(app) }
        autoTask = r
        main.postDelayed(r, delayMs)
    }

    /** One short listening session; when it ends the next one starts, until autonomous mode or the switch ends it. */
    private fun cycleAuto(app: Context) {
        if (!autoOn) return
        if (!AutoPilot.running) { autoOn = false; return }
        val prefs = Prefs.get(app)
        source = Source.AUTOPILOT
        val c = VoiceController(app)
        controller = c
        active = true
        c.start(object : VoiceController.Callback {
            override fun onReady() {}
            override fun onPartial(text: String) { if (autoOn && controller === c) AutoPilot.heard(text) }
            override fun onResult(text: String) {
                if (controller !== c) return
                active = false
                controller = null
                autoFails = 0
                AppLog.d("voice", "heard (auto): $text")
                routeAuto(app, text)
                again(app, 250)
            }
            override fun onError(message: String, needsPermission: Boolean, code: Int) {
                if (controller !== c) return
                active = false
                controller = null
                when {
                    needsPermission -> {
                        autoOn = false
                        if (askPermission) {
                            AutoPilot.voiceStopped("الميكروفون محتاج إذن. اسمح بيه وبعدين دوس زرار الصوت تاني", warn = true)
                            ActionActivity.requestPermissions(app, arrayOf(Manifest.permission.RECORD_AUDIO))
                        } else {
                            AutoPilot.voiceStopped("الميكروفون مش مسموح له. دوس زرار الصوت عشان تديله الإذن", warn = true)
                        }
                    }
                    VoiceController.isQuiet(code) -> again(app, 200)          // nobody spoke: just listen again
                    else -> {
                        autoFails++
                        AppLog.d("voice", "auto listening error $code ($autoFails): $message")
                        if (autoFails >= 6) {
                            autoOn = false
                            AutoPilot.voiceStopped("الصوت وقف: $message", warn = true)
                        } else {
                            again(app, minOf(1000L shl (autoFails - 1), 8000L))      // 1, 2, 4, 8, 8 seconds
                        }
                    }
                }
            }
        }, resolveLang(prefs.voiceLang, "ar"), prefs.voiceOffline)
    }

    private fun routeAuto(app: Context, spoken: String) {
        val prefs = Prefs.get(app)
        val cmd = CommandParser.parse(spoken, AutoPilot.running, prefs.voiceCommands, always = true)
        dispatch(app, cmd) { m, warn -> AutoPilot.note(m, warn) }
    }

    // ------------------------------------------------------------------ what a sentence does

    private fun dispatch(app: Context, cmd: VoiceCommand, say: (String, Boolean) -> Unit) {
        when (cmd) {
            is VoiceCommand.Text -> {}                      // dictation is handled by the keyboard path
            is VoiceCommand.Call -> Actions.call(app, cmd.name, cmd.alt, say)
            is VoiceCommand.OpenApp -> Actions.openApp(app, cmd.name, say)
            VoiceCommand.TranslateScreen -> {
                say("هترجم الشاشة", false)
                ScreenTranslateController.start(app)
            }
            VoiceCommand.StartAutopilot -> {
                if (AutoPilot.running) say("الوضع الذاتي شغال بالفعل", false)
                else {
                    say("هشغّل الوضع الذاتي", false)
                    AutoPilot.start(app)
                }
            }
            VoiceCommand.StopAutopilot -> {
                if (AutoPilot.running) AutoPilot.stop("بأمر صوتي") else say("الوضع الذاتي مش شغال", false)
            }
            VoiceCommand.ClearInstructions -> {
                AutoPilot.clearInstructions()
                say("مسحت التعليمات", false)
            }
            is VoiceCommand.Auto -> if (AutoPilot.running) AutoPilot.applyOrder(cmd.order) else say("الوضع الذاتي مش شغال", true)
        }
    }
}
