package com.omega.sight.engine

import com.omega.sight.Bidi
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.omega.sight.Prefs
import com.omega.sight.core.Det
import com.omega.sight.ml.TfliteRunner
import com.omega.sight.ml.VisionModel
import com.omega.sight.model.ModelStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the owner's vision file on its own thread, so a slow model never slows down the motion and colour detection. The engine hands over
 * a copy of the newest picture and takes the answer a few frames later; [Result.cumTx] / [Result.cumTy] say how far the picture moved
 * since, so the boxes can be laid onto the current picture.
 */
class ModelWorker(private val ctx: Context, private val prefs: Prefs) {
    class Result(val dets: List<Det>, val cumTx: Float, val cumTy: Float, val pass: Int, val ms: Float)

    private val thread = HandlerThread("sight-model", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val handler = Handler(thread.looper)
    private val busy = AtomicBoolean(false)
    private var copy = ByteArray(0)

    @Volatile
    var pending: Result? = null

    @Volatile
    var status = ""
        private set

    @Volatile
    var usingGpu = false
        private set

    @Volatile
    var lastMs = 0f
        private set

    @Volatile
    var loaded = false
        private set

    // only touched on the model thread
    private var model: VisionModel? = null
    private var stamp = -1L
    private var wasGpu = false
    private var failedStamp = -1L

    fun isBusy(): Boolean = busy.get()

    /**
     * Hands over the picture for one run; false = the worker is still busy with the last one.
     * @param pass 0 = the whole picture, 1 = a crop around (cropX, cropY) at full resolution (for far targets)
     */
    fun submit(rgba: ByteArray, w: Int, h: Int, stride: Int, pass: Int, cropX: Int, cropY: Int, thr: Float, cumTx: Float, cumTy: Float): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        if (copy.size != rgba.size) copy = ByteArray(rgba.size)
        System.arraycopy(rgba, 0, copy, 0, rgba.size)
        handler.post {
            try {
                ensureModel()
                val m = model
                if (m != null) {
                    var rx = 0
                    var ry = 0
                    var rw = w
                    var rh = h
                    if (pass == 1) {
                        rw = minOf(w, m.spec.inW)
                        rh = minOf(h, m.spec.inH)
                        rx = (cropX - rw / 2).coerceIn(0, w - rw)
                        ry = (cropY - rh / 2).coerceIn(0, h - rh)
                    }
                    val dets = m.detect(copy, w, h, stride, rx, ry, rw, rh, thr)
                    lastMs = m.lastMs
                    pending = Result(dets, cumTx, cumTy, pass, m.lastMs)
                    if (m.runner.lastError.isNotEmpty()) status = "فشل التشغيل: ${m.runner.lastError}"
                }
            } catch (t: Throwable) {
                status = "خطأ: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    /** opens the file again when the owner replaced it or changed the GPU switch */
    private fun ensureModel() {
        val f = ModelStore.file(ctx, ModelStore.Slot.VISION)
        val st = if (f.isFile) f.lastModified() + f.length() else 0L
        val gpu = prefs.gpu
        if (st == stamp && gpu == wasGpu) return
        model?.close()
        model = null
        loaded = false
        usingGpu = false
        stamp = st
        wasGpu = gpu
        if (st == 0L) {
            status = "مفيش ملف رؤية"
            return
        }
        if (TfliteRunner.libraryName() == null) {
            status = "مكتبة LiteRT مش موجودة"
            return
        }
        val o = VisionModel.open(f, ModelStore.names(ctx), 4, gpu)
        val m = o.model
        if (m == null) {
            status = o.error
            failedStamp = st
            return
        }
        model = m
        loaded = true
        usingGpu = m.usingGpu
        status = "شغّال ${Bidi.dim(m.spec.inW, m.spec.inH)}" + if (m.usingGpu) " (كرت الشاشة)" else " (المعالج)"
    }

    fun stop() {
        handler.post {
            model?.close()
            model = null
        }
        thread.quitSafely()
    }
}
