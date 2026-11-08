package com.ahmedxg.gamebot

import android.content.Context
import java.io.File

/**
 * "Model built into the app": if you put your own trained detector at
 * GameBot/app/src/main/assets/detector.tflite (and optionally labels.txt) the CI build embeds it and the
 * app uses it automatically - no import step. (No generic model is shipped: it has to be trained on YOUR game.)
 */
object AssetModel {
    fun ensure(ctx: Context, cfg: Config): Boolean {
        if (cfg.modelFile.isNotEmpty() && File(cfg.modelFile).exists()) return false
        return try {
            val names = ctx.assets.list("") ?: emptyArray()
            if (!names.contains("detector.tflite")) return false
            val dir = File(ctx.filesDir, "models")
            dir.mkdirs()
            val f = File(dir, "detector.tflite")
            ctx.assets.open("detector.tflite").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            cfg.modelFile = f.absolutePath
            if (names.contains("labels.txt")) {
                val lf = File(dir, "labels.txt")
                ctx.assets.open("labels.txt").use { i -> lf.outputStream().use { o -> i.copyTo(o) } }
                cfg.labelsFile = lf.absolutePath
            }
            cfg.save(ctx)
            Logger.i("model loaded from the app's built-in assets")
            true
        } catch (t: Throwable) {
            false
        }
    }
}
