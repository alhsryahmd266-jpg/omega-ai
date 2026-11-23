package com.ahmedxg.gamebot

import android.content.Context
import java.io.File

/**
 * Models built into the app: the CI build puts the newest detector (and tactical brain) into assets/, and the app uses
 * them automatically - no file picker, no import step. When a newer APK ships a different model set (assets/models_stamp.txt
 * changes) the new models replace the old ones on first launch.
 */
object AssetModel {
    private fun copy(ctx: Context, asset: String, dst: File) {
        ctx.assets.open(asset).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
    }

    /** @return true if models were (re)copied from the APK this time. */
    fun ensure(ctx: Context, cfg: Config): Boolean {
        return try {
            val names = ctx.assets.list("") ?: emptyArray()
            if (!names.contains("detector.tflite")) return false
            val stamp = if (names.contains("models_stamp.txt")) ctx.assets.open("models_stamp.txt").bufferedReader().use { it.readText().trim() } else "x"
            val dir = File(ctx.filesDir, "models")
            dir.mkdirs()
            val det = File(dir, "detector.tflite")
            val brain = File(dir, "brain.tflite")
            val hasBrain = names.contains("brain.tflite")
            val upToDate = cfg.assetStamp == stamp && det.exists() && cfg.modelFile == det.absolutePath &&
                (!hasBrain || (brain.exists() && cfg.brainFile == brain.absolutePath))
            if (upToDate) return false
            copy(ctx, "detector.tflite", det)
            cfg.modelFile = det.absolutePath
            if (names.contains("labels.txt")) {
                val lf = File(dir, "labels.txt")
                copy(ctx, "labels.txt", lf)
                cfg.labelsFile = lf.absolutePath
            }
            if (hasBrain) {
                copy(ctx, "brain.tflite", brain)
                cfg.brainFile = brain.absolutePath
            }
            cfg.assetStamp = stamp
            cfg.save(ctx)
            Logger.i("models loaded from the app's built-in assets (stamp $stamp)")
            true
        } catch (t: Throwable) {
            false
        }
    }
}
