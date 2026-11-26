package com.omega.sight.ml

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs one .tflite file with LiteRT (Google's new name for TensorFlow Lite), or with the older TensorFlow Lite classes when that is what the
 * build carries. Everything is reached by reflection: this file compiles without either library, and the app tells the owner in plain
 * words when neither one is there.
 *
 * Buffers are direct, native-order ByteBuffers, which both libraries accept. Float, uint8 and int8 tensors are understood.
 * Create and use a runner on ONE thread: the GPU delegate is tied to the thread that made it.
 */
class TfliteRunner private constructor(
    private val interp: Any,
    private val runMethod: Method,
    private val closeMethod: Method?,
    private val gpuDelegate: Any?,
    val library: String,
    val inputs: List<TensorInfo>,
    val outputs: List<TensorInfo>,
) {
    class TensorInfo(val shape: IntArray, val type: String, val bytes: Int, val scale: Float, val zero: Int) {
        val count: Int get() = shape.fold(1) { a, b -> a * b }
        val isFloat: Boolean get() = type == "FLOAT32"
        val isU8: Boolean get() = type == "UINT8"
        val isI8: Boolean get() = type == "INT8"
        fun describe(): String = "${shape.joinToString("×")} $type"
    }

    val usingGpu: Boolean get() = gpuDelegate != null

    private val inBufs = Array(inputs.size) { ByteBuffer.allocateDirect(inputs[it].bytes).order(ByteOrder.nativeOrder()) }
    private val outBufs = Array(outputs.size) { ByteBuffer.allocateDirect(outputs[it].bytes).order(ByteOrder.nativeOrder()) }
    private var closed = false

    var lastError: String = ""
        private set

    /** the buffer to fill with input number [i], emptied and ready */
    fun input(i: Int = 0): ByteBuffer {
        val b = inBufs[i]
        b.clear()
        return b
    }

    /** runs the model on what was written into [input]; false = it failed ([lastError] says why) */
    fun run(): Boolean {
        if (closed) return false
        return try {
            val ins = arrayOfNulls<Any>(inBufs.size)
            for (i in inBufs.indices) {
                inBufs[i].rewind()
                ins[i] = inBufs[i]
            }
            val outs = HashMap<Int, Any>()
            for (i in outBufs.indices) {
                outBufs[i].rewind()
                outs[i] = outBufs[i]
            }
            runMethod.invoke(interp, ins, outs)
            true
        } catch (t: Throwable) {
            lastError = rootMessage(t)
            false
        }
    }

    /** output number [i] as floats (8-bit outputs are turned back into real numbers with the tensor's own scale) */
    fun outputFloats(i: Int, reuse: FloatArray? = null): FloatArray {
        val info = outputs[i]
        val n = info.count
        val dst = if (reuse != null && reuse.size == n) reuse else FloatArray(n)
        val b = outBufs[i]
        b.rewind()
        when {
            info.isFloat -> b.asFloatBuffer().get(dst, 0, n)
            info.isU8 -> {
                val sc = if (info.scale > 0f) info.scale else 1f / 255f
                for (k in 0 until n) dst[k] = ((b.get(k).toInt() and 255) - info.zero) * sc
            }
            info.isI8 -> {
                val sc = if (info.scale > 0f) info.scale else 1f / 255f
                for (k in 0 until n) dst[k] = (b.get(k).toInt() - info.zero) * sc
            }
            else -> java.util.Arrays.fill(dst, 0f)
        }
        return dst
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            closeMethod?.invoke(interp)
        } catch (_: Throwable) {
        }
        try {
            gpuDelegate?.javaClass?.getMethod("close")?.invoke(gpuDelegate)
        } catch (_: Throwable) {
        }
    }

    class Opened(val runner: TfliteRunner?, val error: String)

    companion object {
        private val bases = listOf("com.google.ai.edge.litert", "org.tensorflow.lite")

        /** the library found in this build ("com.google.ai.edge.litert" or "org.tensorflow.lite"), null = none */
        fun libraryName(): String? {
            for (b in bases) {
                try {
                    Class.forName("$b.Interpreter")
                    return b
                } catch (_: Throwable) {
                }
            }
            return null
        }

        fun rootMessage(t: Throwable): String {
            var e: Throwable = t
            if (e is InvocationTargetException && e.targetException != null) e = e.targetException
            while (e.cause != null && e.cause !== e) e = e.cause!!
            val m = e.message
            return (e.javaClass.simpleName + (if (m.isNullOrBlank()) "" else ": $m")).take(300)
        }

        /** opens [file]; with [gpu] the phone's GPU is tried first and the CPU used when that fails */
        fun open(file: File, threads: Int, gpu: Boolean): Opened {
            val base = libraryName() ?: return Opened(null, "مكتبة LiteRT مش موجودة في النسخة دي")
            if (gpu) {
                val g = try {
                    makeGpu(base)
                } catch (_: Throwable) {
                    null
                }
                if (g != null) {
                    val r = tryOpen(base, file, threads, g)
                    if (r.runner != null) return r
                    try {
                        g.javaClass.getMethod("close").invoke(g)
                    } catch (_: Throwable) {
                    }
                }
            }
            return tryOpen(base, file, threads, null)
        }

        private fun makeGpu(base: String): Any? {
            try {
                val cl = Class.forName("$base.gpu.CompatibilityList").getConstructor().newInstance()
                val ok = cl.javaClass.getMethod("isDelegateSupportedOnThisDevice").invoke(cl) as Boolean
                try {
                    cl.javaClass.getMethod("close").invoke(cl)
                } catch (_: Throwable) {
                }
                if (!ok) return null
            } catch (_: ClassNotFoundException) {
                // the library cannot be asked: try anyway
            }
            return Class.forName("$base.gpu.GpuDelegate").getConstructor().newInstance()
        }

        private fun tryOpen(base: String, file: File, threads: Int, gpuDelegate: Any?): Opened {
            return try {
                val interpClass = Class.forName("$base.Interpreter")
                val optClass = Class.forName("$base.Interpreter\$Options")
                val opts = optClass.getConstructor().newInstance()
                optClass.getMethod("setNumThreads", Int::class.javaPrimitiveType).invoke(opts, threads)
                if (gpuDelegate != null) {
                    val delegateClass = Class.forName("$base.Delegate")
                    optClass.getMethod("addDelegate", delegateClass).invoke(opts, gpuDelegate)
                }
                val interp = interpClass.getConstructor(File::class.java, optClass).newInstance(file, opts)

                val inCount = interpClass.getMethod("getInputTensorCount").invoke(interp) as Int
                val outCount = interpClass.getMethod("getOutputTensorCount").invoke(interp) as Int
                val getIn = interpClass.getMethod("getInputTensor", Int::class.javaPrimitiveType)
                val getOut = interpClass.getMethod("getOutputTensor", Int::class.javaPrimitiveType)
                val ins = ArrayList<TensorInfo>()
                for (i in 0 until inCount) ins.add(info(base, getIn.invoke(interp, i)!!))
                val outs = ArrayList<TensorInfo>()
                for (i in 0 until outCount) outs.add(info(base, getOut.invoke(interp, i)!!))

                val run = interpClass.getMethod("runForMultipleInputsOutputs", Array<Any>::class.java, Map::class.java)
                val close = try {
                    interpClass.getMethod("close")
                } catch (_: Throwable) {
                    null
                }
                Opened(TfliteRunner(interp, run, close, gpuDelegate, base, ins, outs), "")
            } catch (t: Throwable) {
                Opened(null, rootMessage(t))
            }
        }

        /**
         * The tensor object the library hands back is an instance of a package-private implementation class, and reflection refuses to call
         * even public methods through such a class. So every call goes through the library's PUBLIC Tensor type.
         */
        private fun info(base: String, tensor: Any): TensorInfo {
            val c = Class.forName("$base.Tensor")
            val shape = c.getMethod("shape").invoke(tensor) as IntArray
            val type = c.getMethod("dataType").invoke(tensor).toString()
            val bytes = c.getMethod("numBytes").invoke(tensor) as Int
            var scale = 0f
            var zero = 0
            try {
                val q = c.getMethod("quantizationParams").invoke(tensor)
                if (q != null) {
                    val qc = Class.forName("$base.Tensor\$QuantizationParams")
                    scale = qc.getMethod("getScale").invoke(q) as Float
                    zero = qc.getMethod("getZeroPoint").invoke(q) as Int
                }
            } catch (_: Throwable) {
            }
            return TensorInfo(shape, type, bytes, scale, zero)
        }
    }
}
