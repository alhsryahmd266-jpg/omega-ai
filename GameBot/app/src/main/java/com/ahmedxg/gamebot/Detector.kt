package com.ahmedxg.gamebot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device object detector (TFLite). Expects a YOLOv8/YOLO11-style export (no built-in NMS):
 * output [1, 4+nc, N] or [1, N, 4+nc], input NHWC. Float32 / UINT8 / INT8 supported.
 * The model is NOT bundled: train it on your own game (see training/README_AR.md).
 */
class Detector(private val cfg: Config) {
    private var interp: Interpreter? = null
    private var gpu: GpuDelegate? = null
    var inW = 0
        private set
    var inH = 0
        private set
    var delegateName = "CPU"
        private set
    var lastMs = 0f
        private set

    private var inType = DataType.FLOAT32
    private var lut = ByteArray(256)
    private var outType = DataType.FLOAT32
    private var outScale = 1f
    private var outZero = 0
    private var outShape = IntArray(0)
    private var inBuf: ByteBuffer? = null
    private var outBuf: ByteBuffer? = null
    private var outFloats = FloatArray(0)
    private var work: Bitmap? = null
    private var canvas: Canvas? = null
    private var pixels = IntArray(0)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private var labels: List<String> = emptyList()

    private fun create(f: File, useGpu: Boolean): Interpreter {
        val o = Interpreter.Options()
        o.setNumThreads(cfg.threads.coerceIn(1, 8))
        delegateName = "CPU x" + cfg.threads.coerceIn(1, 8)
        if (useGpu) {
            val d = GpuDelegate()
            gpu = d
            o.addDelegate(d)
            delegateName = "GPU"
        }
        return Interpreter(f, o)
    }

    /** @return null on success, otherwise an error message. */
    fun load(): String? {
        close()
        val f = File(cfg.modelFile)
        if (cfg.modelFile.isEmpty() || !f.exists()) return "ملف النموذج مش موجود"
        labels = cfg.labelList()
        var ip: Interpreter? = null
        try {
            ip = create(f, cfg.useGpu)
        } catch (t: Throwable) {
            Logger.i("model load failed (gpu=${cfg.useGpu}): ${t.javaClass.simpleName} ${t.message}")
            try { gpu?.close() } catch (e: Throwable) { }
            gpu = null
            if (cfg.useGpu) {
                try {
                    ip = create(f, false)
                } catch (t2: Throwable) {
                    return "فشل تحميل النموذج: ${t2.javaClass.simpleName} ${t2.message}"
                }
            } else {
                return "فشل تحميل النموذج: ${t.javaClass.simpleName} ${t.message}"
            }
        }
        val i = ip ?: return "فشل تحميل النموذج"
        interp = i

        val inT = i.getInputTensor(0)
        val s = inT.shape()
        if (s.size != 4 || s[3] != 3) return "شكل الإدخال مش مدعوم (لازم NHWC ×3): " + s.joinToString("x")
        inH = s[1]
        inW = s[2]
        inType = inT.dataType()
        if (inType == DataType.FLOAT32) {
            // nothing to build
        } else if (inType == DataType.UINT8 || inType == DataType.INT8) {
            val q = inT.quantizationParams()
            val sc = if (q.scale == 0f) 1f / 255f else q.scale
            val zp = q.zeroPoint
            val lo = if (inType == DataType.UINT8) 0 else -128
            val hi = if (inType == DataType.UINT8) 255 else 127
            for (r in 0 until 256) {
                lut[r] = ((r / 255f) / sc + zp).roundToInt().coerceIn(lo, hi).toByte()
            }
        } else {
            return "نوع الإدخال مش مدعوم: $inType"
        }
        val bpc = if (inType == DataType.FLOAT32) 4 else 1
        inBuf = ByteBuffer.allocateDirect(inW * inH * 3 * bpc).order(ByteOrder.nativeOrder())

        val ot = i.getOutputTensor(0)
        outShape = ot.shape()
        outType = ot.dataType()
        if (outShape.size != 3) return "شكل الخرج مش مدعوم: " + outShape.joinToString("x")
        if (outType == DataType.UINT8 || outType == DataType.INT8) {
            val q = ot.quantizationParams()
            outScale = if (q.scale == 0f) 1f else q.scale
            outZero = q.zeroPoint
        } else if (outType != DataType.FLOAT32) {
            return "نوع الخرج مش مدعوم: $outType"
        }
        outBuf = ByteBuffer.allocateDirect(ot.numBytes()).order(ByteOrder.nativeOrder())
        outFloats = FloatArray(outShape[0] * outShape[1] * outShape[2])

        val wb = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888)
        work = wb
        canvas = Canvas(wb)
        pixels = IntArray(inW * inH)
        Logger.i("model loaded: ${inW}x${inH} in=$inType out=${outShape.joinToString("x")} $outType via $delegateName")
        return null
    }

    fun close() {
        try { interp?.close() } catch (e: Throwable) { }
        try { gpu?.close() } catch (e: Throwable) { }
        interp = null
        gpu = null
    }

    fun benchmark(runs: Int): String {
        val i = interp ?: return "النموذج مش محمّل"
        val ib = inBuf ?: return "النموذج مش محمّل"
        val ob = outBuf ?: return "النموذج مش محمّل"
        for (k in 0 until 5) {
            ib.rewind(); ob.rewind(); i.run(ib, ob)
        }
        val t = ArrayList<Float>()
        for (k in 0 until runs) {
            val a = System.nanoTime()
            ib.rewind(); ob.rewind(); i.run(ib, ob)
            t.add((System.nanoTime() - a) / 1e6f)
        }
        val avg = t.sum() / t.size
        return "$delegateName ${inW}x$inH: avg ${"%.1f".format(avg)} ms (min ${"%.1f".format(t.minOrNull() ?: 0f)}, max ${"%.1f".format(t.maxOrNull() ?: 0f)}) ≈ ${(1000f / avg).toInt()} FPS"
    }

    fun detect(src: Bitmap, srcW: Int, srcH: Int): List<Det> {
        val ip = interp ?: return emptyList()
        val ib = inBuf ?: return emptyList()
        val ob = outBuf ?: return emptyList()
        val c = canvas ?: return emptyList()
        val wb = work ?: return emptyList()
        val t0 = System.nanoTime()

        // letterbox (same preprocessing Ultralytics uses for training/inference)
        val scale = min(inW.toFloat() / srcW, inH.toFloat() / srcH)
        val nw = srcW * scale
        val nh = srcH * scale
        val padX = (inW - nw) / 2f
        val padY = (inH - nh) / 2f
        c.drawColor(Color.rgb(114, 114, 114))
        srcRect.set(0, 0, srcW, srcH)
        dstRect.set(padX, padY, padX + nw, padY + nh)
        c.drawBitmap(src, srcRect, dstRect, paint)
        wb.getPixels(pixels, 0, inW, 0, 0, inW, inH)

        ib.rewind()
        if (inType == DataType.FLOAT32) {
            for (p in pixels) {
                ib.putFloat(((p shr 16) and 255) / 255f)
                ib.putFloat(((p shr 8) and 255) / 255f)
                ib.putFloat((p and 255) / 255f)
            }
        } else {
            for (p in pixels) {
                ib.put(lut[(p shr 16) and 255])
                ib.put(lut[(p shr 8) and 255])
                ib.put(lut[p and 255])
            }
        }
        ib.rewind()
        ob.rewind()
        ip.run(ib, ob)
        ob.rewind()
        if (outType == DataType.FLOAT32) {
            ob.asFloatBuffer().get(outFloats)
        } else {
            for (k in outFloats.indices) {
                val b = if (outType == DataType.UINT8) (ob.get(k).toInt() and 255) else ob.get(k).toInt()
                outFloats[k] = (b - outZero) * outScale
            }
        }

        val res = YoloDecode.decode(outFloats, outShape, inW, inH, scale, padX, padY, srcW, srcH, labels, cfg.conf, cfg.iou)
        lastMs = (System.nanoTime() - t0) / 1e6f
        return res
    }
}
