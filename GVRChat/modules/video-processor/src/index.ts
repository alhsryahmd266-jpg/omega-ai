import { requireNativeModule } from 'expo-modules-core';

interface VideoProcessorNative {
  getDurationSeconds(videoPath: string): Promise<number>;
  extractFrames(videoPath: string, outDir: string, fps: number, maxFrames: number): Promise<string[]>;
}

const Native = requireNativeModule<VideoProcessorNative>('VideoProcessor');

/**
 * Hard practical limits — not arbitrary. Processing every frame of a long
 * video on a phone CPU through a Vision model is not realistic; this caps
 * total work to something that finishes in a reasonable time.
 *
 * Implementation note: frame extraction uses Android's built-in
 * MediaMetadataRetriever (no external native library — FFmpegKit's Maven
 * artifacts were found to be pulled entirely, 404 across the board, so it
 * could not be used). This means, honestly:
 *   - No audio track extraction in this version (no speech-to-text input).
 *   - No pre-compression of oversized videos — the duration cap below is
 *     what keeps this bounded instead.
 */
export const VIDEO_LIMITS = {
  maxDurationSeconds: 180,     // videos longer than 3 min are rejected outright
  frameIntervalSeconds: 2,     // one frame every 2 seconds
  maxFrames: 20,                // hard cap regardless of duration
};

export interface VideoAnalysisPrep {
  framePaths: string[];
  audioPath: null;              // always null in this version — see note above
  durationSeconds: number;
  wasCompressed: false;         // always false in this version — see note above
}

/**
 * Prepares a video for on-device analysis: checks duration, extracts
 * frames at a fixed interval via MediaMetadataRetriever. Returns real
 * file paths — no simulation.
 */
export async function prepareVideoForAnalysis(
  videoUri: string,
  workDir: string
): Promise<VideoAnalysisPrep> {
  const duration = await Native.getDurationSeconds(videoUri);

  if (duration < 0) {
    throw new Error('Could not read video — file may be corrupt or unsupported format.');
  }
  if (duration > VIDEO_LIMITS.maxDurationSeconds) {
    throw new Error(
      `Video is ${Math.round(duration)}s long. On-device analysis is capped at ` +
      `${VIDEO_LIMITS.maxDurationSeconds}s — please trim it first.`
    );
  }

  const fps = 1 / VIDEO_LIMITS.frameIntervalSeconds;
  const framePaths = await Native.extractFrames(
    videoUri, workDir, fps, VIDEO_LIMITS.maxFrames
  );

  return { framePaths, audioPath: null, durationSeconds: duration, wasCompressed: false };
}
