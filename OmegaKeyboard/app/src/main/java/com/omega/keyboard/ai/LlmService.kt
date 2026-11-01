package com.omega.keyboard.ai

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File

/**
 * Runs the local Gemma model (.litertlm) inside its own process (":llm").
 *
 * The keyboard and the screen agent live in the main process. If the model ever crashes natively or the phone kills
 * this process because memory ran out, only this process dies; the keyboard keeps working and [Llm] simply starts a
 * fresh process on the next request.
 *
 * Protocol (Messenger): client sends OP_* with arg1 = request id and replyTo set; we answer with R_* and the same id.
 */
class LlmService : Service() {

    companion object {
        const val OP_LOAD = 1
        const val OP_GEN = 2
        const val OP_CANCEL = 3
        const val OP_UNLOAD = 4

        const val R_OK = 101
        const val R_RESULT = 102
        const val R_ERROR = 103

        const val K_PATH = "path"
        const val K_BACKEND = "backend"
        const val K_CACHE = "cache"
        const val K_SYSTEM = "system"
        const val K_USER = "user"
        const val K_MAXTOK = "maxtok"
        const val K_TEMP = "temp"
        const val K_TOPK = "topk"
        const val K_TOPP = "topp"
        const val K_TEXT = "text"
        const val K_ERR = "err"
        const val K_INFO = "info"
    }

    private lateinit var worker: Handler
    private val ui = Handler(Looper.getMainLooper()) { m -> dispatch(m); true }
    private val messenger = Messenger(ui)

    @Volatile private var engine: Engine? = null
    @Volatile private var loadedKey = ""
    @Volatile private var usedBackend = ""
    @Volatile private var current: Conversation? = null

    override fun onCreate() {
        super.onCreate()
        val t = HandlerThread("llm-worker")
        t.start()
        worker = Handler(t.looper)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        closeEngine()
        super.onDestroy()
    }

    private fun dispatch(m: Message) {
        when (m.what) {
            // cancelling must not wait behind the (blocked) worker thread
            OP_CANCEL -> try { current?.cancelProcess() } catch (_: Throwable) {}
            OP_LOAD, OP_GEN, OP_UNLOAD -> {
                val copy = Message.obtain(m)
                worker.post {
                    try { run(copy) } finally { copy.recycle() }
                }
            }
        }
    }

    private fun run(m: Message) {
        when (m.what) {
            OP_LOAD -> load(m)
            OP_GEN -> generate(m)
            OP_UNLOAD -> {
                closeEngine()
                reply(m, R_OK)
                // hand every byte of native memory back to the phone
                ui.postDelayed({ Process.killProcess(Process.myPid()) }, 250)
            }
        }
    }

    private fun reply(req: Message, what: Int, fill: (Bundle.() -> Unit)? = null) {
        val to = req.replyTo ?: return
        val r = Message.obtain(null, what, req.arg1, 0)
        r.data = Bundle().also { b -> fill?.invoke(b) }
        try { to.send(r) } catch (_: RemoteException) {}
    }

    private fun fail(req: Message, t: Throwable) {
        val text = describe(t)
        reply(req, R_ERROR) { putString(K_ERR, text) }
    }

    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        val msg = (root.message ?: t.message ?: "").take(300)
        return when (root) {
            is UnsatisfiedLinkError -> "مكتبة النموذج مش شغالة على الهاتف ده ($msg)"
            is OutOfMemoryError -> "الذاكرة مش كفاية لتحميل النموذج"
            else -> "${root.javaClass.simpleName}: $msg"
        }
    }

    private fun closeEngine() {
        try { current?.close() } catch (_: Throwable) {}
        current = null
        val e = engine
        engine = null
        loadedKey = ""
        usedBackend = ""
        if (e != null) try { e.close() } catch (_: Throwable) {}
    }

    private fun load(m: Message) {
        val d = m.data
        val path = d.getString(K_PATH).orEmpty()
        val want = d.getString(K_BACKEND) ?: "cpu"
        val cache = d.getString(K_CACHE).orEmpty()
        try {
            val key = "$path|$want"
            if (engine != null && loadedKey == key) {
                reply(m, R_OK) { putString(K_INFO, usedBackend) }
                return
            }
            closeEngine()
            val f = File(path)
            if (!f.isFile || !f.canRead()) throw IllegalStateException("ملف النموذج مش موجود أو مش مسموح بقراءته: $path")
            val order = if (want == "gpu") listOf("gpu", "cpu") else listOf("cpu")
            var last: Throwable? = null
            for (b in order) {
                try {
                    val cfg = EngineConfig(
                        modelPath = path,
                        backend = if (b == "gpu") Backend.GPU() else Backend.CPU(),
                        cacheDir = cache.ifEmpty { null },
                    )
                    val e = Engine(cfg)
                    e.initialize()
                    engine = e
                    loadedKey = key
                    usedBackend = b
                    reply(m, R_OK) { putString(K_INFO, b) }
                    return
                } catch (t: Throwable) {
                    last = t
                    engine = null
                }
            }
            throw last ?: IllegalStateException("فشل تحميل النموذج")
        } catch (t: Throwable) {
            fail(m, t)
        }
    }

    private fun generate(m: Message) {
        val e = engine
        if (e == null) {
            reply(m, R_ERROR) { putString(K_ERR, "النموذج مش متحمّل") }
            return
        }
        val d = m.data
        var conv: Conversation? = null
        try {
            val sampler = SamplerConfig(
                topK = d.getInt(K_TOPK, 40).coerceAtLeast(1),
                topP = d.getFloat(K_TOPP, 0.95f).toDouble().coerceIn(0.0, 1.0),
                temperature = d.getFloat(K_TEMP, 0.7f).toDouble().coerceAtLeast(0.0),
            )
            val system = d.getString(K_SYSTEM).orEmpty()
            val maxTok = d.getInt(K_MAXTOK, 256).coerceAtLeast(8)
            conv = newConversation(e, system, sampler, maxTok)
            current = conv
            val out = conv.sendMessage(d.getString(K_USER).orEmpty()).toString()
            reply(m, R_RESULT) { putString(K_TEXT, out) }
        } catch (t: Throwable) {
            fail(m, t)
        } finally {
            current = null
            try { conv?.close() } catch (_: Throwable) {}
        }
    }

    private fun newConversation(e: Engine, system: String, sampler: SamplerConfig, maxTok: Int): Conversation {
        val sys = if (system.isBlank()) null else Contents.of(system)
        return try {
            // thinking off: we want the answer, not the model's scratchpad (and it is much faster)
            e.createConversation(
                ConversationConfig(
                    systemInstruction = sys,
                    samplerConfig = sampler,
                    maxOutputToken = maxTok,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                )
            )
        } catch (_: Throwable) {
            e.createConversation(ConversationConfig(systemInstruction = sys, samplerConfig = sampler))
        }
    }
}
