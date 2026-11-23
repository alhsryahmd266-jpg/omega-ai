package com.ahmedxg.gamebot

import org.tensorflow.lite.Interpreter
import java.io.File

/** Runs the tactical-brain network (input [1,24] -> output [1,4]) with TensorFlow Lite on the CPU. */
class TfliteBrain private constructor(private val ip: Interpreter) : BrainModel {
    private val inp = Array(1) { FloatArray(BrainSpec.IN) }
    private val outp = Array(1) { FloatArray(BrainSpec.OUT) }

    override fun run(x: FloatArray): FloatArray {
        System.arraycopy(x, 0, inp[0], 0, BrainSpec.IN)
        ip.run(inp, outp)
        return outp[0].copyOf()
    }

    fun close() = ip.close()

    companion object {
        /** A brain model has a 2-D input [1,24]; the detector has a 4-D image input. */
        fun isBrainFile(f: File): Boolean = try {
            val i = Interpreter(f)
            try {
                val s = i.getInputTensor(0).shape()
                s.size == 2 && s[1] == BrainSpec.IN
            } finally { i.close() }
        } catch (t: Throwable) { false }

        /** @return the brain, or null when the file is missing or has the wrong shape. */
        fun load(f: File): TfliteBrain? {
            if (!f.exists()) return null
            return try {
                val o = Interpreter.Options()
                o.setNumThreads(1)
                val i = Interpreter(f, o)
                val si = i.getInputTensor(0).shape()
                val so = i.getOutputTensor(0).shape()
                if (si.size != 2 || si[1] != BrainSpec.IN || so.size != 2 || so[1] != BrainSpec.OUT) { i.close(); null } else TfliteBrain(i)
            } catch (t: Throwable) { null }
        }
    }
}
