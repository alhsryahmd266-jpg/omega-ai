package expo.modules.videoprocessor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import java.io.FileOutputStream

/**
 * VideoProcessorModule — real frame extraction via Android's own
 * MediaMetadataRetriever, no external native library required.
 *
 * Honest scope: this replaces an earlier FFmpegKit-based design.
 * FFmpegKit (arthenica/ffmpeg-kit) was archived in 2023 and its Maven
 * Central artifacts were later pulled entirely (confirmed: 404 on
 * ffmpeg-kit-full, ffmpeg-kit, and ffmpeg-kit-min as of this build) —
 * so it could not be used as a dependency at all, not just outdated.
 *
 * MediaMetadataRetriever is a standard Android SDK class, ships with
 * every device, and needs zero extra dependencies. Its trade-off:
 *   - Frame extraction: real, reliable (getFrameAtTime).
 *   - Audio track extraction: NOT implemented in this version — would
 *     require MediaExtractor + MediaCodec, real work, not done yet.
 *     Callers should not assume speech-to-text input is available.
 *   - Video compression/downscaling: NOT implemented — would require a
 *     real MediaCodec encoder pipeline. Long/huge videos are rejected
 *     by duration cap (enforced in TS) rather than silently compressed.
 */
class VideoProcessorModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("VideoProcessor")

    AsyncFunction("getDurationSeconds") { videoPath: String, promise: expo.modules.kotlin.Promise ->
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(videoPath)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val seconds = (durationMs?.toLongOrNull() ?: -1L) / 1000.0
        promise.resolve(seconds)
      } catch (e: Exception) {
        promise.reject("RETRIEVER_ERROR", "Could not read video duration: ${e.message}", e)
      } finally {
        retriever.release()
      }
    }

    // Extract frames at a fixed interval (real getFrameAtTime calls),
    // write each as a JPEG, return the real file paths written.
    AsyncFunction("extractFrames") { videoPath: String, outDir: String, fps: Double, maxFrames: Int, promise: expo.modules.kotlin.Promise ->
      val dir = File(outDir)
      if (!dir.exists()) dir.mkdirs()

      val retriever = MediaMetadataRetriever()
      val written = mutableListOf<String>()

      try {
        retriever.setDataSource(videoPath)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
          ?.toLongOrNull() ?: 0L

        val intervalMs = if (fps > 0) (1000.0 / fps).toLong() else 2000L
        var timeMs = 0L
        var frameIndex = 0

        while (timeMs < durationMs && frameIndex < maxFrames) {
          val bitmap: Bitmap? = retriever.getFrameAtTime(
            timeMs * 1000, // microseconds
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
          )
          if (bitmap != null) {
            val framePath = "$outDir/frame_${String.format("%04d", frameIndex)}.jpg"
            FileOutputStream(framePath).use { out ->
              bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
            written.add(framePath)
            frameIndex++
          }
          timeMs += intervalMs
        }

        promise.resolve(written)
      } catch (e: Exception) {
        promise.reject("RETRIEVER_ERROR", "Frame extraction failed: ${e.message}", e)
      } finally {
        retriever.release()
      }
    }

    // Not implemented in this version — see class doc. Returns a clear
    // error instead of a fake/empty success, so callers don't silently
    // proceed as if audio was extracted when it wasn't.
    AsyncFunction("extractAudio") { videoPath: String, outPath: String, promise: expo.modules.kotlin.Promise ->
      promise.reject(
        "NOT_IMPLEMENTED",
        "Audio extraction is not implemented in this build (requires MediaExtractor/MediaCodec, not yet built). Frame-based analysis only.",
        null
      )
    }

    // Not implemented — see class doc. Duration cap (enforced in TS)
    // is used instead of compression to keep processing bounded.
    AsyncFunction("compressVideo") { videoPath: String, outPath: String, maxWidth: Int, promise: expo.modules.kotlin.Promise ->
      promise.reject(
        "NOT_IMPLEMENTED",
        "Video compression is not implemented in this build. Long videos are rejected by duration cap instead.",
        null
      )
    }
  }
}
