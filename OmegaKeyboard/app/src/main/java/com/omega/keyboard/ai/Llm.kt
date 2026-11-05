package com.omega.keyboard.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import com.omega.keyboard.Prefs
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Where the local model file is. */
object ModelStore {
    const val MIN_BYTES = 50L * 1024 * 1024

    /** The configured model path if the file is really there and readable, else null. */
    fun path(ctx: Context): String? {
        val p = Prefs.get(ctx).modelPath
        if (p.isBlank()) return null
        val f = File(p)
        return if (f.isFile && f.canRead() && f.length() > MIN_BYTES) p else null
    }

    fun sizeMb(ctx: Context): Long = path(ctx)?.let { File(it).length() / (1024 * 1024) } ?: 0L
}

/**
 * Client side of the local model. Every call here blocks, so call it from a background thread only.
 * The model itself runs in [LlmService] (separate process); calls are serialized, one at a time.
 */
object Llm {

    class LlmException(message: String) : Exception(message)

    @Volatile var loaded = false
        private set
    @Volatile var loading = false
        private set
    @Volatile var backendUsed = ""
        private set
    @Volatile var lastError = ""
        private set
    @Volatile var lastLoadMs = 0L
        private set
    /** set while the autonomous mode runs: the model then stays in memory, however long nobody wrote to it */
    @Volatile var keepLoaded = false

    @Volatile private var lastUsed = 0L
    @Volatile private var loadedKey = ""
    @Volatile private var app: Context? = null

    private class Pending {
        val latch = CountDownLatch(1)
        @Volatile var text: String? = null
        @Volatile var error: String? = null
        @Volatile var info: String? = null
    }

    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val serial = Any()
    private val bindLock = Any()
    @Volatile private var service: Messenger? = null
    @Volatile private var bound = false
    @Volatile private var connected = CountDownLatch(1)
    private val main = Handler(Looper.getMainLooper())

    private val replyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(m: Message) { onReply(m) }
    }
    private val client = Messenger(replyHandler)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) { onLost() }
        override fun onBindingDied(name: ComponentName) { onLost() }
        override fun onNullBinding(name: ComponentName) { onLost() }
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            val a = app ?: return
            if (!loaded) return
            val minutes = Prefs.get(a).idleMinutes
            if (!keepLoaded && minutes > 0 && pending.isEmpty() && SystemClock.elapsedRealtime() - lastUsed > minutes * 60_000L) {
                Thread { unload() }.start()
            } else {
                main.postDelayed(this, 30_000)
            }
        }
    }

    private fun onReply(m: Message) {
        val p = pending[m.arg1] ?: return
        val b: Bundle? = m.data
        when (m.what) {
            LlmService.R_OK -> p.info = b?.getString(LlmService.K_INFO)
            LlmService.R_RESULT -> p.text = b?.getString(LlmService.K_TEXT).orEmpty()
            LlmService.R_ERROR -> p.error = b?.getString(LlmService.K_ERR) ?: "خطأ غير معروف"
        }
        p.latch.countDown()
    }

    private fun failAll(msg: String) {
        for ((_, p) in pending) {
            if (p.error == null && p.text == null) p.error = msg
            p.latch.countDown()
        }
    }

    private fun onLost() {
        service = null
        loaded = false
        loading = false
        backendUsed = ""
        loadedKey = ""
        failAll("النموذج وقف فجأة (غالبًا الذاكرة خلصت). هيتحمّل تاني في الطلب الجاي.")
        val a = app
        synchronized(bindLock) {
            if (a != null) try { a.unbindService(conn) } catch (_: Throwable) {}
            bound = false
        }
    }

    private fun ensureBound(a: Context): Messenger {
        service?.let { return it }
        synchronized(bindLock) {
            if (service == null && !bound) {
                connected = CountDownLatch(1)
                bound = a.bindService(Intent(a, LlmService::class.java), conn, Context.BIND_AUTO_CREATE)
                if (!bound) throw LlmException("مقدرتش أشغّل خدمة النموذج")
            }
        }
        connected.await(12, TimeUnit.SECONDS)
        return service ?: throw LlmException("خدمة النموذج مش بترد")
    }

    private fun request(a: Context, op: Int, fill: Bundle.() -> Unit, timeoutMs: Long): Pending {
        val id = ids.getAndIncrement()
        val p = Pending()
        pending[id] = p
        try {
            val msg = Message.obtain(null, op, id, 0)
            msg.replyTo = client
            msg.data = Bundle().also(fill)
            ensureBound(a).send(msg)
        } catch (e: RemoteException) {
            pending.remove(id)
            onLost()
            throw LlmException("خدمة النموذج مش متاحة، جرّب تاني")
        } catch (e: LlmException) {
            pending.remove(id)
            throw e
        }
        val ok = p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pending.remove(id)
        if (!ok) {
            if (op == LlmService.OP_GEN) cancel()
            throw LlmException(if (op == LlmService.OP_LOAD) "تحميل النموذج أخد وقت طويل قوي" else "النموذج اتأخر قوي، وقفته")
        }
        p.error?.let { throw LlmException(it) }
        return p
    }

    private fun checkBackground() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Llm must not be called on the main thread" }
    }

    fun isConfigured(ctx: Context): Boolean = ModelStore.path(ctx) != null

    /** Loads the model if needed. Returns the backend that ended up in use ("cpu" / "gpu"). */
    fun ensureLoaded(ctx: Context): String {
        checkBackground()
        val a = ctx.applicationContext
        app = a
        synchronized(serial) { return loadLocked(a) }
    }

    private fun loadLocked(a: Context): String {
        val prefs = Prefs.get(a)
        val path = ModelStore.path(a) ?: throw LlmException("لسه مفيش ملف نموذج. افتح التطبيق واختار الملف.")
        val key = path + "|" + prefs.backend
        if (loaded && loadedKey == key) return backendUsed
        loading = true
        val t0 = SystemClock.elapsedRealtime()
        try {
            val cache = if (prefs.llmNoCache) ":nocache" else File(a.cacheDir, "litert").apply { mkdirs() }.path
            val p = request(a, LlmService.OP_LOAD, {
                putString(LlmService.K_PATH, path)
                putString(LlmService.K_BACKEND, prefs.backend)
                putString(LlmService.K_CACHE, cache)
            }, 300_000)
            loaded = true
            loadedKey = key
            backendUsed = p.info ?: prefs.backend
            lastLoadMs = SystemClock.elapsedRealtime() - t0
            lastError = ""
            lastUsed = SystemClock.elapsedRealtime()
            main.removeCallbacks(idleCheck)
            main.postDelayed(idleCheck, 30_000)
            return backendUsed
        } catch (e: LlmException) {
            lastError = e.message ?: ""
            throw e
        } finally {
            loading = false
        }
    }

    /**
     * One prompt in, one answer out. A fresh conversation is used each time, so there is no memory between calls;
     * everything the model needs has to be inside [system] and [user].
     */
    fun generate(
        ctx: Context,
        system: String,
        user: String,
        maxTokens: Int = 256,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.95f,
        timeoutMs: Long = 180_000,
    ): String {
        checkBackground()
        val a = ctx.applicationContext
        app = a
        synchronized(serial) {
            loadLocked(a)
            try {
                val p = request(a, LlmService.OP_GEN, {
                    putString(LlmService.K_SYSTEM, system)
                    putString(LlmService.K_USER, user)
                    putInt(LlmService.K_MAXTOK, maxTokens)
                    putFloat(LlmService.K_TEMP, temperature)
                    putInt(LlmService.K_TOPK, topK)
                    putFloat(LlmService.K_TOPP, topP)
                }, timeoutMs)
                lastUsed = SystemClock.elapsedRealtime()
                lastError = ""
                return p.text.orEmpty()
            } catch (e: LlmException) {
                lastError = e.message ?: ""
                throw e
            }
        }
    }

    /** Stops the answer that is being produced right now (safe from any thread). */
    fun cancel() {
        val s = service ?: return
        try { s.send(Message.obtain(null, LlmService.OP_CANCEL, 0, 0)) } catch (_: RemoteException) {}
    }

    /** Frees the model's memory (the model process exits). The next [generate] loads it again. */
    fun unload() {
        val a = app ?: return
        val s = service ?: run { loaded = false; return }
        val id = ids.getAndIncrement()
        val p = Pending()
        pending[id] = p
        try {
            val msg = Message.obtain(null, LlmService.OP_UNLOAD, id, 0)
            msg.replyTo = client
            s.send(msg)
            p.latch.await(6, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        } finally {
            pending.remove(id)
        }
        loaded = false
        loadedKey = ""
        backendUsed = ""
        main.removeCallbacks(idleCheck)
        main.post {
            synchronized(bindLock) {
                try { a.unbindService(conn) } catch (_: Throwable) {}
                bound = false
                service = null
            }
        }
    }

    fun statusText(ctx: Context): String = when {
        !isConfigured(ctx) -> "مفيش نموذج لسه"
        loading -> "النموذج بيتحمّل..."
        loaded -> "النموذج جاهز (" + (if (backendUsed == "gpu") "كرت الشاشة" else "المعالج") + ")"
        lastError.isNotEmpty() -> "فيه مشكلة: $lastError"
        else -> "النموذج موجود وهيتحمّل أول ما تحتاجه"
    }
}
