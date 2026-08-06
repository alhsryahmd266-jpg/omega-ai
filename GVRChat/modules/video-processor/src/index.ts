import { requireNativeModule } from 'expo-modules-core';

interface VideoProcessorNative {
  getDurationSeconds(videoPath: string): Promise<number>;
  extractFrames(videoPath: string, outDir: string, fps: number, maxFrames: number): Promise<string[]>;
  extractAudio(videoPath: string, outPath: string): Promise<string>;
  compressVideo(videoPath: string, outPath: string, maxWidth: number): Promise<string>;
}

const Native = requireNativeModule<VideoProcessorNative>('VideoProcessor');

/**
 * Hard practical limits — not arbitrary. Processing every frame of a long
 * video on a phone CPU through a Vision model is not realistic; this caps
 * total work to something that finishes in a reasonable time.
 */
export const VIDEO_LIMITS = {
  maxDurationSeconds: 180,     // videos longer than 3 min are rejected outright
  frameIntervalSeconds: 2,     // one frame every 2 seconds
  maxFrames: 20,                // hard cap regardless of duration
  maxInputWidthPx: 1280,       // videos wider than this get compressed first
};

export interface VideoAnalysisPrep {
  framePaths: string[];
  audioPath: string | null;
  durationSeconds: number;
  wasCompressed: boolean;
}

/**
 * Prepares a video for on-device analysis: checks duration, compresses if
 * oversized, extracts frames at a fixed interval, extracts audio for
 * optional speech-to-text. Returns real file paths — no simulation.
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

  let sourcePath = videoUri;
  let wasCompressed = false;

  // Compress large videos before frame extraction to avoid memory pressure
  const compressedPath = `${workDir}/compressed.mp4`;
  try {
    await Native.compressVideo(videoUri, compressedPath, VIDEO_LIMITS.maxInputWidthPx);
    sourcePath = compressedPath;
    wasCompressed = true;
  } catch {
    // fall back to original if compression fails — not fatal
  }

  const fps = 1 / VIDEO_LIMITS.frameIntervalSeconds;
  const framePaths = await Native.extractFrames(
    sourcePath, workDir, fps, VIDEO_LIMITS.maxFrames
  );

  let audioPath: string | null = null;
  try {
    audioPath = await Native.extractAudio(sourcePath, `${workDir}/audio.wav`);
  } catch {
    audioPath = null; // audio extraction is optional, not fatal
  }

  return { framePaths, audioPath, durationSeconds: duration, wasCompressed };
}
