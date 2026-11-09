package com.tacticai.bot.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * General-purpose vision: "what is in the frame" (80 COCO classes — person, car, etc.), via the
 * EfficientDet-Lite0 model bundled in assets/ (fetched by CI from Google's official MediaPipe model
 * hosting at build time — see build_tacticai.yml — Apache-2.0, not the AGPL-licensed YOLO family).
 *
 * Honest limit: COCO's 80 classes do not include "enemy soldier" or any game-specific character model, so
 * this will not reliably spot a stylised in-game enemy the way a model trained on your game would. It is
 * useful today for the generic "person" class (many 3rd/1st-person character rigs still read as a person
 * silhouette) and as the architecture to swap in a model fine-tuned on your own gameplay footage later —
 * see [com.tacticai.bot.detect.ButtonFinder] / README for why that fine-tuning step needs real screenshots.
 */
class ObjectDetectorWrapper private constructor(private val detector: ObjectDetector) {

    fun detect(frame: Bitmap, timestampMs: Long): List<WorldDetection> = try {
        val mpImage = BitmapImageBuilder(frame).build()
        val result = detector.detectForVideo(mpImage, timestampMs)
        result.detections().mapNotNull { d ->
            val top = d.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
            val bb = d.boundingBox()
            WorldDetection(
                box = Rect(bb.left, bb.top, bb.right, bb.bottom),
                label = top.categoryName() ?: "object",
                score = top.score(),
            )
        }
    } catch (e: Throwable) {
        Log.w(TAG, "detect() failed: ${e.message}")
        emptyList()
    }

    fun close() = runCatching { detector.close() }

    companion object {
        private const val TAG = "ObjectDetectorWrapper"
        private const val MODEL_ASSET = "efficientdet_lite0.tflite"

        /** Null if the bundled model/asset is missing or neither GPU nor CPU delegate could initialise. */
        fun create(context: Context, scoreThreshold: Float = 0.4f, maxResults: Int = 10): ObjectDetectorWrapper? {
            for (delegate in listOf(Delegate.GPU, Delegate.CPU)) {
                try {
                    val base = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(delegate).build()
                    val options = ObjectDetector.ObjectDetectorOptions.builder()
                        .setBaseOptions(base)
                        .setRunningMode(RunningMode.VIDEO)
                        .setScoreThreshold(scoreThreshold)
                        .setMaxResults(maxResults)
                        .build()
                    val det = ObjectDetector.createFromOptions(context, options)
                    Log.i(TAG, "ObjectDetector ready on ${delegate.name}")
                    return ObjectDetectorWrapper(det)
                } catch (e: Throwable) {
                    Log.w(TAG, "ObjectDetector init failed on ${delegate.name}: ${e.message}")
                }
            }
            return null
        }
    }
}
