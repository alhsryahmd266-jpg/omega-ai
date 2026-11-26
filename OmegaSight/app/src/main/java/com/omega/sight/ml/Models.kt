package com.omega.sight.ml

import com.omega.sight.Bidi
import com.omega.sight.core.BrainSpec
import com.omega.sight.core.Det
import com.omega.sight.core.ModelSpec
import com.omega.sight.core.Preprocess
import com.omega.sight.core.YoloDecoder
import java.io.File

/** A model that was opened, or the reason it was not (in Arabic, for the owner). */
class Loaded<T>(val model: T?, val error: String)

/**
 * The owner's vision file. Looks at a region of the screenshot and returns the boxes of what it knows (bodies, heads). The layout of the
 * input and of the output is read from the file itself (see [YoloDecoder] for the output layouts that are understood).
 */
class VisionModel private constructor(val runner: TfliteRunner, val spec: ModelSpec) {
    private val inInfo = runner.inputs[0]
    private val rgb = ByteArray(spec.inW * spec.inH * 3)
    private val fbuf: FloatArray? = if (inInfo.isFloat) FloatArray(spec.inW * spec.inH * 3) else null
    private val qbuf: ByteArray? = if (inInfo.isFloat) null else ByteArray(spec.inW * spec.inH * 3)
    private var outArr: FloatArray? = null

    /** milliseconds the last call took */
    var lastMs = 0f
        private set

    val usingGpu: Boolean get() = runner.usingGpu

    /**
     * @param rgba RGBA_8888 screenshot, [rowStride] bytes per row
     * @param rx the region to look at, in screenshot pixels
     * @return boxes in the pixels of the whole screenshot
     */
    fun detect(rgba: ByteArray, w: Int, h: Int, rowStride: Int, rx: Int, ry: Int, rw: Int, rh: Int, scoreThr: Float): List<Det> {
        val t0 = System.nanoTime()
        val lb = Preprocess.letterboxRgb(rgba, w, h, rowStride, rx, ry, rw, rh, spec.inW, spec.inH, rgb)
        val buf = runner.input(0)
        if (fbuf != null) {
            Preprocess.toFloat(rgb, spec.inW, spec.inH, spec.nchw, fbuf)
            buf.asFloatBuffer().put(fbuf)
        } else if (qbuf != null) {
            val signed = inInfo.isI8
            val zero = if (inInfo.scale > 0f) inInfo.zero else if (signed) -128 else 0
            val scale = if (inInfo.scale > 0f) inInfo.scale else 1f / 255f
            Preprocess.toQuant(rgb, spec.inW, spec.inH, spec.nchw, scale, zero, signed, qbuf)
            buf.put(qbuf)
        }
        if (!runner.run()) {
            lastMs = (System.nanoTime() - t0) / 1e6f
            return emptyList()
        }
        val out = runner.outputFloats(0, outArr)
        outArr = out
        val dets = YoloDecoder.decode(out, spec.outShape, lb, spec.names, scoreThr)
        lastMs = (System.nanoTime() - t0) / 1e6f
        if (rx == 0 && ry == 0) return dets
        return dets.map { Det(it.box.shifted(rx.toFloat(), ry.toFloat()), it.score, it.kind) }
    }

    fun close() = runner.close()

    companion object {
        fun open(file: File, names: List<String>, threads: Int, gpu: Boolean): Loaded<VisionModel> {
            val o = TfliteRunner.open(file, threads, gpu)
            val r = o.runner ?: return Loaded(null, "الملف ماتفتحش: ${o.error}")
            if (r.inputs.size != 1) {
                r.close()
                return Loaded(null, "الملف فيه ${r.inputs.size} مداخل — المدعوم دخل واحد (صورة)")
            }
            if (r.outputs.size != 1) {
                r.close()
                return Loaded(null, "الملف فيه ${r.outputs.size} مخارج — المدعوم مخرج واحد (YOLO مصدَّر بمخرج واحد)")
            }
            val s = r.inputs[0].shape
            if (s.size != 4 || !(r.inputs[0].isFloat || r.inputs[0].isU8 || r.inputs[0].isI8)) {
                r.close()
                return Loaded(null, "شكل الدخل ${r.inputs[0].describe()} مش مدعوم — المطلوب [1, ارتفاع, عرض, 3] أرقام عشرية")
            }
            val nchw = s[1] == 3 && s[3] != 3
            val inH = if (nchw) s[2] else s[1]
            val inW = if (nchw) s[3] else s[2]
            val ch = if (nchw) s[1] else s[3]
            if (ch != 3 || inH < 32 || inW < 32 || inH > 2048 || inW > 2048) {
                r.close()
                return Loaded(null, "حجم الدخل ${r.inputs[0].describe()} غريب — المطلوب صورة RGB بقنوات 3")
            }
            return Loaded(VisionModel(r, ModelSpec(inW, inH, nchw, r.outputs[0].shape, names)), "")
        }
    }
}

/** The owner's "brain" file: [BrainSpec.IN] numbers in, 2 or 3 numbers out. */
class BrainModel private constructor(val runner: TfliteRunner) {
    private var outArr: FloatArray? = null

    var lastMs = 0f
        private set

    fun infer(state: FloatArray): FloatArray? {
        val t0 = System.nanoTime()
        val b = runner.input(0)
        b.asFloatBuffer().put(state, 0, BrainSpec.IN)
        if (!runner.run()) return null
        val o = runner.outputFloats(0, outArr)
        outArr = o
        lastMs = (System.nanoTime() - t0) / 1e6f
        return o
    }

    fun close() = runner.close()

    companion object {
        fun open(file: File, threads: Int, gpu: Boolean): Loaded<BrainModel> {
            val o = TfliteRunner.open(file, threads, gpu)
            val r = o.runner ?: return Loaded(null, "ملف العقل ماتفتحش: ${o.error}")
            if (r.inputs.size != 1 || r.outputs.size != 1) {
                r.close()
                return Loaded(null, "ملف العقل لازم يكون له دخل واحد ومخرج واحد")
            }
            if (!r.inputs[0].isFloat || r.inputs[0].count != BrainSpec.IN) {
                val d = r.inputs[0].describe()
                r.close()
                return Loaded(null, "دخل العقل ${Bidi.ltr(d)} — المطلوب ${Bidi.ltr("float32")} بعدد ${BrainSpec.IN} رقم")
            }
            val oc = r.outputs[0].count
            if (oc != 2 && oc != 3) {
                r.close()
                return Loaded(null, "مخرج العقل فيه $oc رقم — المطلوب 2 أو 3")
            }
            return Loaded(BrainModel(r), "")
        }
    }
}
