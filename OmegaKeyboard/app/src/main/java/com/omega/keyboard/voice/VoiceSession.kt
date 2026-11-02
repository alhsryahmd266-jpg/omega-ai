package com.omega.keyboard.voice

import android.Manifest
import android.content.Context
import com.omega.keyboard.AppLog
import com.omega.keyboard.Prefs
import com.omega.keyboard.agent.AutoPilot
import com.omega.keyboard.agent.Notice
import com.omega.keyboard.agent.ScreenTranslateController
import com.omega.keyboard.core.CommandParser
import com.omega.keyboard.core.VoiceCommand

/**
 * Listens once, then decides what was said: text to type, or a command (call someone, open an app, translate the
 * screen, start / stop autonomous mode) or, while autonomous mode runs, an instruction for it.
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

    /** Tap on the keyboard's microphone: starts listening, or (when already listening) finishes and uses what was said. */
    fun listenForKeyboard(ctx: Context, sink: Sink, langHint: String) {
        if (active) { stopListening(); return }
        this.sink = sink
        begin(ctx, Source.KEYBOARD, langHint)
    }

    /** Microphone button on the autonomous-mode control bar. */
    fun toggleForAutopilot(ctx: Context) {
        if (active) { stopListening(); return }
        begin(ctx, Source.AUTOPILOT, "ar")
    }

    /** The user finished speaking: deliver what was heard. */
    fun stopListening() { controller?.stop() }

    /** Abort without using anything. */
    fun cancel() {
        controller?.cancel()
        controller = null
        if (active) {
            active = false
            report("", false, false)
        }
    }

    fun detach(s: Sink) { if (sink === s) { sink = null } }

    private fun resolveLang(pref: String, hint: String): String = when {
        pref != "auto" && pref.isNotBlank() -> pref
        hint.startsWith("ar") -> "ar-EG"
        hint == "en" -> "en-US"
        else -> hint
    }

    private fun report(text: String, warn: Boolean, listening: Boolean) {
        when (source) {
            Source.KEYBOARD -> sink?.onVoiceState(listening, text, warn)
            Source.AUTOPILOT -> {
                AutoPilot.setListening(listening)      // the bar's microphone button shows when we are listening
                if (text.isNotBlank() && AutoPilot.running) AutoPilot.note(text, warn)
            }
        }
    }

    private fun begin(ctx: Context, src: Source, langHint: String) {
        val app = ctx.applicationContext
        val prefs = Prefs.get(app)
        source = src
        val c = VoiceController(app)
        controller = c
        active = true
        report("بسمعك...", false, true)
        c.start(object : VoiceController.Callback {
            override fun onReady() { if (active) report("اتكلم دلوقتي...", false, true) }
            override fun onPartial(text: String) { if (active) report(text, false, true) }
            override fun onResult(text: String) {
                active = false
                controller = null
                AppLog.d("voice", "heard: $text")
                route(app, text)
            }
            override fun onError(message: String, needsPermission: Boolean) {
                active = false
                controller = null
                report(message, true, false)
                if (needsPermission) ActionActivity.requestPermissions(app, arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        }, resolveLang(prefs.voiceLang, langHint), prefs.voiceOffline)
    }

    private fun route(app: Context, spoken: String) {
        val prefs = Prefs.get(app)
        val cmd = CommandParser.parse(spoken, AutoPilot.running, prefs.voiceCommands)
        val say: (String, Boolean) -> Unit = { m, warn -> report(m, warn, false) }
        when (cmd) {
            is VoiceCommand.Text -> {
                if (cmd.text.isBlank()) { say("ما سمعتش كلام", true); return }
                val s = sink
                if (source == Source.KEYBOARD && s != null) s.onDictation(cmd.text)
                else say("سمعت: ${cmd.text} (مفيش خانة كتابة أكتب فيها)", true)
            }
            is VoiceCommand.Call -> Actions.call(app, cmd.name, cmd.alt, say)
            is VoiceCommand.OpenApp -> Actions.openApp(app, cmd.name, say)
            VoiceCommand.TranslateScreen -> {
                say("هترجم الشاشة", false)
                ScreenTranslateController.start(app)
            }
            VoiceCommand.StartAutopilot -> {
                say("هشغّل الوضع الذاتي", false)
                AutoPilot.start(app)
            }
            VoiceCommand.StopAutopilot -> {
                if (AutoPilot.running) AutoPilot.stop("بأمر صوتي") else say("الوضع الذاتي مش شغال", false)
            }
            VoiceCommand.ClearInstructions -> {
                AutoPilot.clearInstructions()
                say("مسحت التعليمات", false)
            }
            is VoiceCommand.Instruction -> {
                if (AutoPilot.running) {
                    AutoPilot.setListening(false)
                    AutoPilot.addInstruction(cmd.text)       // shows "تمام، هبعت: ..." on the bar and wakes the loop
                } else {
                    Notice.toast(app, "سمعت: ${cmd.text}")
                    say("سمعت: ${cmd.text}", false)
                }
            }
        }
    }
}
