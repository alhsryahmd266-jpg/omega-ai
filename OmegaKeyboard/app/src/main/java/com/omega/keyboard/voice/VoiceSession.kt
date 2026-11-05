package com.omega.keyboard.voice

import android.Manifest
import android.content.Context
import com.omega.keyboard.AppLog
import com.omega.keyboard.Prefs

/**
 * Dictation through the phone's own recogniser: the keyboard's microphone listens once and types what was said.
 * (There are no spoken orders any more; autonomous mode is a single button.) Main thread only.
 */
object VoiceSession {

    interface Sink {
        /** [active]: the microphone is open. [text]: what to show (hint, partial speech, result or error). */
        fun onVoiceState(active: Boolean, text: String, warn: Boolean)
        fun onDictation(text: String)
    }

    @Volatile var active = false
        private set
    private var controller: VoiceController? = null
    private var sink: Sink? = null

    /** Tap on the keyboard's microphone: starts listening, or (when already listening) finishes and uses what was said. */
    fun listenForKeyboard(ctx: Context, sink: Sink, langHint: String) {
        if (active) { stopListening(); return }
        this.sink = sink
        begin(ctx, langHint)
    }

    /** The user finished speaking: deliver what was heard. */
    fun stopListening() { controller?.stop() }

    /** Abort without using anything. */
    fun cancel() {
        controller?.cancel()
        controller = null
        if (active) {
            active = false
            sink?.onVoiceState(false, "", false)
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
                val said = text.trim()
                val s = sink
                when {
                    said.isEmpty() -> s?.onVoiceState(false, "ما سمعتش كلام", true)
                    s != null -> s.onDictation(said)
                    else -> {}
                }
            }
            override fun onError(message: String, needsPermission: Boolean, code: Int) {
                active = false
                controller = null
                sink?.onVoiceState(false, message, true)
                if (needsPermission) ActionActivity.requestPermissions(app, arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        }, resolveLang(prefs.voiceLang, langHint), prefs.voiceOffline)
    }
}
