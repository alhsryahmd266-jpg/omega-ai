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
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message as LmMessage
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

        /** load: also set the picture reader up. reply: true when the model can read pictures */
        const val K_VISION = "vision"
        /** generate: absolute path of a picture to read together with the text */
        const val K_IMAGE = "image"
        /** generate: how many "visual tokens" the picture may use (more = reads small print better, slower); 0 = library default */
        const val K_VTOK = "vtok"
        /** generate: 0 library default, 1 speculative decoding on, 2 off */
        const val K_SPEC = "spec"

        /** generate: the conversation before the new message, as two arrays of the same length: the texts and "written by me" flags */
        const val K_HTEXT = "htext"
        const val K_HMINE = "hmine"
        /** generate: the model may think first, up to this many tokens (0 = answer straight away) */
        const val K_THINK = "think"
    }

    private lateinit var worker: Handler
    private val ui = Handler(Looper.getMainLooper()) { m -> dispatch(m); true }
    private val messenger = Messenger(ui)

    @Volatile private var engine: Engine? = null
    @Volatile private var loadedKey = ""
    @Volatile private var usedBackend = ""
    @Volatile private var current: Conversation? = null
    @Volatile private var cancelled = false

    // the picture reader: on = the engine was opened with one, tried = we already looked for one for this model (do not loop)
    @Volatile private var visionOn = false
    @Volatile private var visionTried = false
    @Volatile private var visionName = ""
    @Volatile private var openPath = ""
    @Volatile private var openMain = ""
    @Volatile private var openCache = ""
    // speculative decoding turned out not to work with this model file: stop asking for it
    @Volatile private var specBroken = false

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
            OP_CANCEL -> {
                cancelled = true
                try { current?.cancelProcess() } catch (_: Throwable) {}
            }
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
        visionOn = false
        visionTried = false
        visionName = ""
        if (e != null) try { e.close() } catch (_: Throwable) {}
    }

    /** Opens the model on [mainName] ("gpu" / "cpu"), with a picture reader on [visionName] ("gpu" / "cpu") or none (""). */
    private fun open(path: String, mainName: String, visionName: String, cache: String): Engine {
        val cfg = EngineConfig(
            modelPath = path,
            backend = if (mainName == "gpu") Backend.GPU() else Backend.CPU(),
            visionBackend = when (visionName) {
                "gpu" -> Backend.GPU()
                "cpu" -> Backend.CPU()
                else -> null
            },
            cacheDir = cache.ifEmpty { null },
        )
        val e = Engine(cfg)
        try {
            e.initialize()
        } catch (t: Throwable) {
            try { e.close() } catch (_: Throwable) {}
            throw t
        }
        return e
    }

    private fun load(m: Message) {
        val d = m.data
        val path = d.getString(K_PATH).orEmpty()
        val want = d.getString(K_BACKEND) ?: "cpu"
        val cache = d.getString(K_CACHE).orEmpty()
        val wantVision = d.getBoolean(K_VISION, false)
        try {
            val key = "$path|$want"
            if (engine != null && loadedKey == key && (!wantVision || visionOn || visionTried)) {
                reply(m, R_OK) {
                    putString(K_INFO, usedBackend)
                    putBoolean(K_VISION, visionOn)
                }
                return
            }
            closeEngine()
            val f = File(path)
            if (!f.isFile || !f.canRead()) throw IllegalStateException("ملف النموذج مش موجود أو مش مسموح بقراءته: $path")
            val mains = if (want == "gpu") listOf("gpu", "cpu") else listOf("cpu")
            // the picture reader is tried on the graphics chip first, then on the processor; when neither opens, the model
            // still loads for text and the client is told it cannot read pictures
            val combos = ArrayList<Pair<String, String>>()
            if (wantVision) for (b in mains) for (v in listOf("gpu", "cpu")) combos.add(b to v)
            for (b in mains) combos.add(b to "")
            var last: Throwable? = null
            for ((b, v) in combos) {
                try {
                    val e = open(path, b, v, cache)
                    engine = e
                    loadedKey = key
                    usedBackend = b
                    visionName = v
                    visionOn = v.isNotEmpty()
                    visionTried = wantVision
                    openPath = path
                    openMain = b
                    openCache = cache
                    reply(m, R_OK) {
                        putString(K_INFO, b)
                        putBoolean(K_VISION, visionOn)
                    }
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
        val d = m.data
        val image = d.getString(K_IMAGE).orEmpty()
        if (engine == null) {
            reply(m, R_ERROR) { putString(K_ERR, "النموذج مش متحمّل") }
            return
        }
        if (image.isNotEmpty() && !visionOn) {
            reply(m, R_ERROR) { putString(K_ERR, "النموذج ده أو الجهاز ده مش بيقرا صور") }
            return
        }
        cancelled = false
        var spec = if (specBroken) 0 else d.getInt(K_SPEC, 0)
        var specOffRetried = false
        var visionRetried = false
        while (true) {
            val e = engine
            if (e == null) {
                reply(m, R_ERROR) { putString(K_ERR, "النموذج مش متحمّل") }
                return
            }
            var conv: Conversation? = null
            try {
                val sampler = SamplerConfig(
                    topK = d.getInt(K_TOPK, 40).coerceAtLeast(1),
                    topP = d.getFloat(K_TOPP, 0.95f).toDouble().coerceIn(0.0, 1.0),
                    temperature = d.getFloat(K_TEMP, 0.7f).toDouble().coerceAtLeast(0.0),
                )
                val system = d.getString(K_SYSTEM).orEmpty()
                val maxTok = d.getInt(K_MAXTOK, 256).coerceAtLeast(8)
                conv = newConversation(e, system, sampler, maxTok, spec, history(d), d.getInt(K_THINK, 0))
                current = conv
                val user = d.getString(K_USER).orEmpty()
                val out = if (image.isEmpty()) {
                    conv.sendMessage(user).toString()
                } else {
                    setVisualBudget(d.getInt(K_VTOK, 0))
                    conv.sendMessage(Contents.of(Content.ImageFile(image), Content.Text(user))).toString()
                }
                if (spec == 1 && specOffRetried) specBroken = true
                reply(m, R_RESULT) { putString(K_TEXT, out) }
                return
            } catch (t: Throwable) {
                if (!cancelled && spec == 1 && !specOffRetried) {
                    // this model file has no (working) drafter: answer without the speed-up
                    specOffRetried = true
                    spec = 0
                    continue
                }
                if (!cancelled && image.isNotEmpty() && visionName == "gpu" && !visionRetried) {
                    // the picture reader would not start on the graphics chip: reopen it on the processor and try once more
                    visionRetried = true
                    try { conv?.close() } catch (_: Throwable) {}
                    conv = null
                    current = null
                    if (reopenVisionOnCpu()) continue
                }
                fail(m, t)
                return
            } finally {
                current = null
                try { conv?.close() } catch (_: Throwable) {}
            }
        }
    }

    private fun reopenVisionOnCpu(): Boolean {
        val old = engine
        engine = null
        if (old != null) try { old.close() } catch (_: Throwable) {}
        return try {
            val e = open(openPath, openMain, "cpu", openCache)
            engine = e
            visionName = "cpu"
            visionOn = true
            true
        } catch (_: Throwable) {
            visionOn = false
            visionName = ""
            // keep the text side alive for the next request
            try {
                engine = open(openPath, openMain, "", openCache)
            } catch (_: Throwable) {
                engine = null
                loadedKey = ""
            }
            false
        }
    }

    /** The library keeps this as a global setting; set by name so a library without it still works. */
    private fun setVisualBudget(n: Int) {
        try {
            val c = Class.forName("com.google.ai.edge.litertlm.ExperimentalFlags")
            val inst = c.getField("INSTANCE").get(null)
            val v: Any? = if (n > 0) Integer.valueOf(n) else null
            c.getMethod("setVisualTokenBudget", Integer::class.java).invoke(inst, *arrayOf<Any?>(v))
        } catch (_: Throwable) {
        }
    }

    /** The turns that came before the new message (the other side's turn = "user", mine = "model"), or null for a one-message request. */
    private fun history(d: Bundle): List<LmMessage>? {
        val texts = d.getStringArray(K_HTEXT) ?: return null
        val mine = d.getBooleanArray(K_HMINE) ?: return null
        if (texts.isEmpty() || texts.size != mine.size) return null
        return texts.indices.map { i -> if (mine[i]) LmMessage.model(texts[i]) else LmMessage.user(texts[i]) }
    }

    private fun newConversation(
        e: Engine, system: String, sampler: SamplerConfig, maxTok: Int, spec: Int, history: List<LmMessage>?, think: Int
    ): Conversation {
        val sys = if (system.isBlank()) null else Contents.of(system)
        val past = history ?: emptyList()
        // thinking is off unless asked for: we want the answer, not the model's scratchpad (and it is much faster)
        val thinking = if (think > 0) ThinkingConfig(enableThinking = true, thinkingTokenBudget = think) else ThinkingConfig(enableThinking = false)
        if (spec == 1) {
            // speculative decoding (the model drafts several words ahead and checks them in one step): same answer, faster
            return e.createConversation(
                ConversationConfig(
                    systemInstruction = sys,
                    initialMessages = past,
                    samplerConfig = sampler,
                    maxOutputToken = maxTok,
                    thinkingConfig = thinking,
                    enableSpeculativeDecoding = true,
                )
            )
        }
        return try {
            e.createConversation(
                ConversationConfig(
                    systemInstruction = sys,
                    initialMessages = past,
                    samplerConfig = sampler,
                    maxOutputToken = maxTok,
                    thinkingConfig = thinking,
                )
            )
        } catch (t: Throwable) {
            // a conversation with turns that the model refuses is reported (the caller asks again with one text)
            if (past.isNotEmpty()) throw t
            e.createConversation(ConversationConfig(systemInstruction = sys, samplerConfig = sampler))
        }
    }
}
