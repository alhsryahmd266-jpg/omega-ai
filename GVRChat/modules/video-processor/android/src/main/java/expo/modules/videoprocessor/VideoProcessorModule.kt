package expo.modules.videoprocessor

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * VideoProcessorModule — real FFmpeg-backed video/audio extraction.
 *
 * Honest scope: this does NOT make the model "watch" video directly — no
 * local model can. What it actually does, for real:
 *   1. extractFrames(): pulls JPEG frames at a fixed interval via ffmpeg
 *      -vf fps=... — each frame is then sent to the Vision model separately
 *      as a still image, and the agent stitches the descriptions together.
 *   2. extractAudio(): pulls a mono 16kHz WAV track via ffmpeg, suitable for
 *      a speech-to-text model (e.g. whisper.cpp) if/when one is wired up.
 *   3. getDuration(): real ffprobe-style duration query, used to enforce a
 *      sane processing cap on very long videos (a hard practical limit —
 *      analyzing a 2-hour video frame-by-frame on a phone CPU is not
 *      realistic, so callers should check this before proceeding).
 */
class VideoProcessorModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("VideoProcessor")

    // Real duration probe via ffmpeg's own metadata reader.
    AsyncFunction("getDurationSeconds") { videoPath: String, promise: expo.modules.kotlin.Promise ->
      val session = com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(videoPath)
      val info = session.mediaInformation
      val duration = info?.duration?.toDoubleOrNull() ?: -1.0
      promise.resolve(duration)
    }

    // Extract frames at `fps` (e.g. 0.5 = one frame every 2 seconds).
    // Returns the list of written JPEG file paths.
    AsyncFunction("extractFrames") { videoPath: String, outDir: String, fps: Double, maxFrames: Int, promise: expo.modules.kotlin.Promise ->
      val dir = File(outDir)
      if (!dir.exists()) dir.mkdirs()

      val pattern = "$outDir/frame_%04d.jpg"
      val cmd = "-y -i \"$videoPath\" -vf fps=$fps -frames:v $maxFrames -q:v 4 \"$pattern\""

      val session = FFmpegKit.execute(cmd)
      if (ReturnCode.isSuccess(session.returnCode)) {
        val frames = dir.listFiles { f -> f.name.startsWith("frame_") && f.name.endsWith(".jpg") }
          ?.sortedBy { it.name }
          ?.map { it.absolutePath }
          ?: emptyList()
        promise.resolve(frames)
      } else {
        promise.reject("FFMPEG_ERROR", "Frame extraction failed: ${session.failStackTrace}", null)
      }
    }

    // Extract mono 16kHz WAV audio track (whisper.cpp-compatible format).
    AsyncFunction("extractAudio") { videoPath: String, outPath: String, promise: expo.modules.kotlin.Promise ->
      val cmd = "-y -i \"$videoPath\" -vn -ac 1 -ar 16000 -f wav \"$outPath\""
      val session = FFmpegKit.execute(cmd)
      if (ReturnCode.isSuccess(session.returnCode)) {
        promise.resolve(outPath)
      } else {
        promise.reject("FFMPEG_ERROR", "Audio extraction failed: ${session.failStackTrace}", null)
      }
    }

    // Downscale + compress an oversized video before processing, so we
    // never try to hold a huge file in memory. Real, bounded operation.
    AsyncFunction("compressVideo") { videoPath: String, outPath: String, maxWidth: Int, promise: expo.modules.kotlin.Promise ->
      val cmd = "-y -i \"$videoPath\" -vf scale='min($maxWidth,iw)':-2 -c:v mpeg4 -q:v 5 \"$outPath\""
      val session = FFmpegKit.execute(cmd)
      if (ReturnCode.isSuccess(session.returnCode)) {
        promise.resolve(outPath)
      } else {
        promise.reject("FFMPEG_ERROR", "Compression failed: ${session.failStackTrace}", null)
      }
    }
  }
}
