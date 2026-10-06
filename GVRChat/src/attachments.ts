/**
 * attachments.ts — Unified file handling for the single-screen chat
 * =====================================================================
 * The user attaches ANY file via one button. This module figures out
 * what it actually is and prepares it for the agent, honestly:
 *
 *   - text/*, .md, .json, .csv   → read directly, inserted into the prompt
 *   - application/pdf             → text extracted page-by-page (no OCR —
 *                                    scanned/image-only PDFs will yield
 *                                    little or no text, and we say so)
 *   - image/*                     → sent to the vision projector (if loaded)
 *   - video/*                     → frames extracted via VideoProcessor,
 *                                    each frame described by the vision
 *                                    model, descriptions stitched together
 *
 * Large files are capped deliberately (see LIMITS) — a phone has finite
 * RAM and a finite context window; pretending otherwise would just crash.
 */
import * as FileSystem from 'expo-file-system';
import { inspectFile } from './fileInspect';
import * as DocumentPicker from 'expo-document-picker';
import { analyzeImage, isVisionReady } from './localLLM';
import { prepareVideoForAnalysis, VIDEO_LIMITS } from '../modules/video-processor/src';

const B64_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

/** Pure-JS base64 -> binary string (does not rely on a global atob). */
function base64ToBinary(b64: string): string {
  const clean = b64.replace(/[^A-Za-z0-9+/]/g, '');
  const parts: string[] = [];
  let buf = 0;
  let bits = 0;
  for (let i = 0; i < clean.length; i++) {
    buf = (buf << 6) | B64_CHARS.indexOf(clean[i]);
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      parts.push(String.fromCharCode((buf >> bits) & 0xff));
      buf &= (1 << bits) - 1;
    }
  }
  return parts.join('');
}

export const LIMITS = {
  maxTextChars: 20000,        // ~5k tokens of raw text injected into prompt
  maxPdfPages: 30,
  maxImageDimPx: 1536,        // images larger than this get downscaled first
};

export type AttachmentKind = 'text' | 'pdf' | 'image' | 'video' | 'file' | 'unsupported';

export interface PreparedAttachment {
  kind: AttachmentKind;
  name: string;
  uri: string;
  sizeBytes: number;
  /** What actually gets fed to the model — always real, never a stub. */
  extractedContent: string;
  warnings: string[];
}

function detectKind(name: string, mime?: string | null): AttachmentKind {
  const ext = name.toLowerCase().split('.').pop() || '';
  if (mime?.startsWith('image/') || ['jpg','jpeg','png','webp','bmp','gif'].includes(ext)) return 'image';
  if (mime?.startsWith('video/') || ['mp4','mov','mkv','avi','webm','3gp'].includes(ext)) return 'video';
  if (mime === 'application/pdf' || ext === 'pdf') return 'pdf';
  if (mime?.startsWith('text/') || ['txt','md','json','csv','log','ts','js','py','java','c','cpp','html','css','xml','yaml','yml'].includes(ext)) return 'text';
  return 'file';   // APK, zip, office, binary ... handled by fileInspect.ts
}

/* ── PICK ANY FILE (the single attach button) ─────────────────────────── */
export async function pickAnyFile(): Promise<DocumentPicker.DocumentPickerAsset | null> {
  const result = await DocumentPicker.getDocumentAsync({
    type: '*/*',
    copyToCacheDirectory: true,
  });
  if (result.canceled || !result.assets?.[0]) return null;
  return result.assets[0];
}

/* ── TEXT ──────────────────────────────────────────────────────────────── */
async function prepareText(asset: DocumentPicker.DocumentPickerAsset): Promise<PreparedAttachment> {
  const warnings: string[] = [];
  let content = await FileSystem.readAsStringAsync(asset.uri, { length: LIMITS.maxTextChars * 4 });
  if (content.length > LIMITS.maxTextChars) {
    content = content.slice(0, LIMITS.maxTextChars);
    warnings.push(`File truncated to ${LIMITS.maxTextChars} characters (was longer).`);
  }
  return {
    kind: 'text', name: asset.name, uri: asset.uri,
    sizeBytes: asset.size || 0, extractedContent: content, warnings,
  };
}

/* ── PDF (Flate streams are decoded; no OCR — stated honestly) ───────── */
async function preparePdf(asset: DocumentPicker.DocumentPickerAsset): Promise<PreparedAttachment> {
  return prepareFile(asset, 'pdf');
}

/* ── ANY OTHER FILE: APK, ZIP, Office, binaries ... ───────────────────── */
async function prepareFile(
  asset: DocumentPicker.DocumentPickerAsset,
  kind: AttachmentKind = 'file',
): Promise<PreparedAttachment> {
  const warnings: string[] = [];
  let content = '';
  try {
    content = await inspectFile({ uri: asset.uri, name: asset.name, size: asset.size || 0, mime: asset.mimeType });
  } catch (e: any) {
    warnings.push(`Could not fully read this file: ${e?.message || e}`);
    content = `File: ${asset.name}\nsize: ${asset.size || 0} bytes\ntype: ${asset.mimeType || 'unknown'}`;
  }
  if (content.length > LIMITS.maxTextChars) {
    content = content.slice(0, LIMITS.maxTextChars);
    warnings.push(`Report truncated to ${LIMITS.maxTextChars} characters.`);
  }
  return {
    kind, name: asset.name, uri: asset.uri,
    sizeBytes: asset.size || 0, extractedContent: content, warnings,
  };
}

/* ── IMAGE ─────────────────────────────────────────────────────────────── */
// The image itself is handed to the vision model together with the user's
// question (see gvrEngine.runWithAttachment), so nothing is analysed here —
// attaching is instant and the answer is specific to what the user asks.
async function prepareImage(asset: DocumentPicker.DocumentPickerAsset): Promise<PreparedAttachment> {
  const warnings: string[] = [];
  if (!isVisionReady()) {
    warnings.push(
      'No vision model loaded — the image cannot be analysed. Load a vision-capable model + its mmproj file in settings first.'
    );
  }
  return {
    kind: 'image', name: asset.name, uri: asset.uri,
    sizeBytes: asset.size || 0,
    extractedContent: '',
    warnings,
  };
}

/* ── VIDEO ─────────────────────────────────────────────────────────────── */
async function prepareVideo(asset: DocumentPicker.DocumentPickerAsset): Promise<PreparedAttachment> {
  const warnings: string[] = [];
  const workDir = FileSystem.cacheDirectory + `video_${Date.now()}/`;
  await FileSystem.makeDirectoryAsync(workDir, { intermediates: true });

  try {
    const prep = await prepareVideoForAnalysis(asset.uri, workDir);
    // Frame extraction only in this version (MediaMetadataRetriever) —
    // no pre-compression step, see video-processor module notes.
    warnings.push(
      `Analyzed ${prep.framePaths.length} frames sampled every ` +
      `${VIDEO_LIMITS.frameIntervalSeconds}s (${Math.round(prep.durationSeconds)}s total). ` +
      `This is frame-by-frame image analysis, not true video understanding.`
    );

    if (!isVisionReady()) {
      warnings.push('No vision model loaded — frames were extracted but not analyzed.');
      return {
        kind: 'video', name: asset.name, uri: asset.uri,
        sizeBytes: asset.size || 0, extractedContent: '', warnings,
      };
    }

    const descriptions: string[] = [];
    for (let i = 0; i < prep.framePaths.length; i++) {
      const desc = await analyzeImage(
        prep.framePaths[i],
        `This is frame ${i + 1}/${prep.framePaths.length} from a video, taken at ` +
        `roughly ${Math.round(i * VIDEO_LIMITS.frameIntervalSeconds)}s. Describe what's happening.`
      );
      descriptions.push(`[${Math.round(i * VIDEO_LIMITS.frameIntervalSeconds)}s] ${desc}`);
    }

    return {
      kind: 'video', name: asset.name, uri: asset.uri,
      sizeBytes: asset.size || 0,
      extractedContent: descriptions.join('\n'),
      warnings,
    };
  } finally {
    FileSystem.deleteAsync(workDir, { idempotent: true }).catch(() => {});
  }
}

/* ── DISPATCH ─────────────────────────────────────────────────────────── */
export async function prepareAttachment(
  asset: DocumentPicker.DocumentPickerAsset
): Promise<PreparedAttachment> {
  const kind = detectKind(asset.name, asset.mimeType);
  switch (kind) {
    case 'text':  return prepareText(asset);
    case 'pdf':   return preparePdf(asset);
    case 'image': return prepareImage(asset);
    case 'video': return prepareVideo(asset);
    default:  return prepareFile(asset, 'file');
  }
}
