/**
 * LocalLLM.ts — On-device inference engine
 * ===========================================
 * Wraps llama.rn to load ANY .gguf model directly on the phone.
 * No server, no Termux, no network for inference.
 *
 * Honest limits:
 * - CPU inference on Android (GPU/OpenCL is experimental, off by default)
 * - Model must fit in device RAM (14B Q4 ≈ 8-9GB context included)
 * - First load of a new model takes 10-30s depending on size
 */
import { initLlama, LlamaContext, releaseAllLlama } from 'llama.rn';
import * as FileSystem from 'expo-file-system';
import * as DocumentPicker from 'expo-document-picker';
import AsyncStorage from '@react-native-async-storage/async-storage';

export interface ModelInfo {
  uri: string;
  name: string;
  sizeBytes: number;
  sizeGB: number;
  addedAt: string;
}

const MODELS_DIR = FileSystem.documentDirectory + 'models/';
const MODELS_LIST_KEY = 'gvr_local_models';

let currentContext: LlamaContext | null = null;
let currentModelUri: string | null = null;

/* ── MODEL MANAGEMENT ─────────────────────────────────────────────────────── */

async function ensureModelsDir() {
  const info = await FileSystem.getInfoAsync(MODELS_DIR);
  if (!info.exists) {
    await FileSystem.makeDirectoryAsync(MODELS_DIR, { intermediates: true });
  }
}

export async function listLocalModels(): Promise<ModelInfo[]> {
  try {
    const raw = await AsyncStorage.getItem(MODELS_LIST_KEY);
    const models: ModelInfo[] = raw ? JSON.parse(raw) : [];
    // filter out any that no longer exist on disk
    const valid: ModelInfo[] = [];
    for (const m of models) {
      const info = await FileSystem.getInfoAsync(m.uri);
      if (info.exists) valid.push(m);
    }
    if (valid.length !== models.length) {
      await AsyncStorage.setItem(MODELS_LIST_KEY, JSON.stringify(valid));
    }
    return valid;
  } catch {
    return [];
  }
}

async function saveModelToList(model: ModelInfo) {
  const models = await listLocalModels();
  const filtered = models.filter(m => m.uri !== model.uri);
  filtered.push(model);
  await AsyncStorage.setItem(MODELS_LIST_KEY, JSON.stringify(filtered));
}

export async function removeLocalModel(uri: string): Promise<void> {
  try {
    await FileSystem.deleteAsync(uri, { idempotent: true });
  } catch {}
  const models = await listLocalModels();
  const filtered = models.filter(m => m.uri !== uri);
  await AsyncStorage.setItem(MODELS_LIST_KEY, JSON.stringify(filtered));
  if (currentModelUri === uri) {
    await unloadModel();
  }
}

/**
 * Import a .gguf file from device storage (any file manager / Downloads)
 * into the app's private models folder. Works for ANY GGUF model —
 * DeepSeek, Qwen, Llama, Phi, Gemma, Mistral, etc. — format is what matters,
 * not the model family.
 */
export async function importModelFromDevice(
  onProgress?: (pct: number) => void
): Promise<ModelInfo | null> {
  const result = await DocumentPicker.getDocumentAsync({
    type: ['*/*'],
    copyToCacheDirectory: false,
  });
  if (result.canceled || !result.assets?.[0]) return null;

  const asset = result.assets[0];
  if (!asset.name.toLowerCase().endsWith('.gguf')) {
    throw new Error('Only .gguf files are supported. Pick a GGUF-format model file.');
  }

  await ensureModelsDir();
  const dest = MODELS_DIR + asset.name;

  await FileSystem.copyAsync({ from: asset.uri, to: dest });
  const info = await FileSystem.getInfoAsync(dest, { size: true });
  const sizeBytes = (info as any).size || asset.size || 0;

  const model: ModelInfo = {
    uri: dest,
    name: asset.name,
    sizeBytes,
    sizeGB: Math.round((sizeBytes / 1e9) * 10) / 10,
    addedAt: new Date().toISOString(),
  };
  await saveModelToList(model);
  return model;
}

/**
 * Download a .gguf directly from a URL (e.g. a HuggingFace resolve link)
 * straight into the app's model folder, with progress callback.
 */
export async function downloadModelFromUrl(
  url: string,
  filename: string,
  onProgress: (pct: number) => void
): Promise<ModelInfo> {
  await ensureModelsDir();
  const dest = MODELS_DIR + filename;

  const downloadResumable = FileSystem.createDownloadResumable(
    url,
    dest,
    {},
    (p) => {
      const pct = p.totalBytesExpectedToWrite > 0
        ? p.totalBytesWritten / p.totalBytesExpectedToWrite
        : 0;
      onProgress(pct);
    }
  );

  const res = await downloadResumable.downloadAsync();
  if (!res) throw new Error('Download failed or was interrupted');

  const info = await FileSystem.getInfoAsync(dest, { size: true });
  const sizeBytes = (info as any).size || 0;

  const model: ModelInfo = {
    uri: dest,
    name: filename,
    sizeBytes,
    sizeGB: Math.round((sizeBytes / 1e9) * 10) / 10,
    addedAt: new Date().toISOString(),
  };
  await saveModelToList(model);
  return model;
}

/* ── MODEL LOADING / INFERENCE ────────────────────────────────────────────── */

export async function unloadModel() {
  if (currentContext) {
    try { await currentContext.release(); } catch {}
  }
  currentContext = null;
  currentModelUri = null;
}

export interface LoadOptions {
  contextSize?: number;   // n_ctx, default 4096
  threads?: number;       // default: half of available cores
  gpuLayers?: number;     // 0 = CPU only (default, safest)
}

export async function loadModel(
  model: ModelInfo,
  opts: LoadOptions = {},
  onProgress?: (pct: number) => void
): Promise<void> {
  if (currentModelUri === model.uri && currentContext) {
    return; // already loaded
  }
  await unloadModel();

  const ctx = await initLlama(
    {
      model: model.uri,
      n_ctx: opts.contextSize ?? 4096,
      n_threads: opts.threads ?? 4,
      n_gpu_layers: opts.gpuLayers ?? 0, // CPU by default — stable on all Android devices
    },
    (progress) => {
      onProgress?.(progress / 100);
    }
  );

  currentContext = ctx;
  currentModelUri = model.uri;
}

export function isModelLoaded(): boolean {
  return currentContext !== null;
}

export function getCurrentModelUri(): string | null {
  return currentModelUri;
}

/* ── GENERATION ────────────────────────────────────────────────────────────── */

export interface GenResult {
  text: string;
  tokensPerSecond?: number;
  tokensGenerated?: number;
}

export async function generate(
  prompt: string,
  opts: { temperature?: number; maxTokens?: number; system?: string } = {}
): Promise<GenResult> {
  if (!currentContext) throw new Error('No model loaded');

  const messages: { role: string; content: string }[] = [];
  if (opts.system) messages.push({ role: 'system', content: opts.system });
  messages.push({ role: 'user', content: prompt });

  const t0 = Date.now();
  const result = await currentContext.completion({
    messages,
    temperature: opts.temperature ?? 0.7,
    n_predict: opts.maxTokens ?? 512,
  });
  const elapsed = (Date.now() - t0) / 1000;

  const text = result.text?.trim() ?? '';
  const tokensGenerated = result.tokens_predicted ?? 0;

  return {
    text,
    tokensGenerated,
    tokensPerSecond: elapsed > 0 ? Math.round((tokensGenerated / elapsed) * 10) / 10 : undefined,
  };
}

/**
 * Streaming generation — calls onToken for each new piece of text.
 * This is what makes the UI feel alive on slow phone CPUs.
 */
export async function generateStream(
  prompt: string,
  onToken: (piece: string) => void,
  opts: { temperature?: number; maxTokens?: number; system?: string } = {}
): Promise<GenResult> {
  if (!currentContext) throw new Error('No model loaded');

  const messages: { role: string; content: string }[] = [];
  if (opts.system) messages.push({ role: 'system', content: opts.system });
  messages.push({ role: 'user', content: prompt });

  const t0 = Date.now();
  let full = '';

  const result = await currentContext.completion(
    {
      messages,
      temperature: opts.temperature ?? 0.7,
      n_predict: opts.maxTokens ?? 512,
    },
    (data) => {
      const piece = data.token ?? '';
      full += piece;
      onToken(piece);
    }
  );

  const elapsed = (Date.now() - t0) / 1000;
  const tokensGenerated = result.tokens_predicted ?? 0;

  return {
    text: full.trim() || result.text?.trim() || '',
    tokensGenerated,
    tokensPerSecond: elapsed > 0 ? Math.round((tokensGenerated / elapsed) * 10) / 10 : undefined,
  };
}

export async function stopGeneration() {
  if (currentContext) {
    try { await currentContext.stopCompletion(); } catch {}
  }
}

/* ── SESSION CACHING (real speedup, with safe fallback) ──────────────────────
 * llama.rn exposes context.saveSession()/loadSession() to persist KV-cache
 * state to disk, avoiding re-processing a long system prompt on every call.
 *
 * Honest caveat: there is a known upstream issue where session save/load
 * can throw or silently no-op in certain build configs (see mybigday/llama.rn
 * issue #321 — "parallel mode" completion state). We treat this as
 * best-effort: every call is wrapped so a failure here NEVER breaks the
 * actual chat flow, it just falls back to normal (slower) prefill.
 * ────────────────────────────────────────────────────────────────────────── */

const SESSION_DIR = FileSystem.documentDirectory + 'sessions/';

async function ensureSessionDir() {
  const info = await FileSystem.getInfoAsync(SESSION_DIR);
  if (!info.exists) await FileSystem.makeDirectoryAsync(SESSION_DIR, { intermediates: true });
}

/** Best-effort save. Returns true only if it actually succeeded. */
export async function trySaveSession(name: string): Promise<boolean> {
  if (!currentContext) return false;
  try {
    await ensureSessionDir();
    const path = SESSION_DIR + name + '.bin';
    await (currentContext as any).saveSession(path);
    return true;
  } catch {
    return false; // known upstream instability — fail silently, caller continues normally
  }
}

/** Best-effort load. Returns true only if a cached session was actually restored. */
export async function tryLoadSession(name: string): Promise<boolean> {
  if (!currentContext) return false;
  try {
    const path = SESSION_DIR + name + '.bin';
    const info = await FileSystem.getInfoAsync(path);
    if (!info.exists) return false;
    await (currentContext as any).loadSession(path);
    return true;
  } catch {
    return false;
  }
}
