/**
 * localLLM.ts — on-device model management + inference (llama.rn)
 * =================================================================
 * Everything App.tsx / gvrEngine.ts / attachments.ts need from the model layer:
 *   listLocalModels, importModelFromDevice, loadModel, unloadModel, deleteModel,
 *   autoLoadLastModel, isModelLoaded, isVisionReady, analyzeImage, ...
 *
 * Models live in <documentDirectory>/models/. A model is any *.gguf file.
 * A file whose name contains "mmproj" is a vision projector: it is not loaded
 * by itself, it is paired automatically with the text model on load.
 */
import { initLlama, type LlamaContext } from 'llama.rn';
import * as FileSystem from 'expo-file-system';
import * as DocumentPicker from 'expo-document-picker';
import AsyncStorage from '@react-native-async-storage/async-storage';

const MODELS_DIR = `${FileSystem.documentDirectory}models/`;
const LAST_MODEL_KEY = 'gvr_last_model_uri';
const LOADING_FLAG_KEY = 'gvr_model_loading_flag';
const GGUF_MAGIC_B64 = 'R0dVRg=='; // base64 of the ASCII bytes "GGUF"

/* ── TYPES ─────────────────────────────────────────────────────────────── */
export interface ModelInfo {
  uri: string;
  name: string;
  sizeBytes: number;
  sizeGB: string;
  isProjector: boolean;
}

export interface LoadOptions {
  /** Context size in tokens. Default: tries 8192, then 4096, then 2048. */
  nCtx?: number;
  nThreads?: number;
  gpuLayers?: number;
  /** Force a specific mmproj file instead of auto-pairing. */
  mmprojUri?: string;
}

export interface ModelProfile {
  name: string;
  paramsB: number;
  hasChatTemplate: boolean;
  /** Looks like a raw (non-instruction-tuned) model: it will not follow instructions or use tools. */
  looksBase: boolean;
  tier: 'tiny' | 'small' | 'ok';
  /** Warnings in Arabic, ready to show to the user. */
  warnings: string[];
}

/* ── STATE ─────────────────────────────────────────────────────────────── */
let ctx: LlamaContext | null = null;
let loadedModel: ModelInfo | null = null;
let loadedCtxSize = 0;
let visionOn = false;
let visionNote = '';
let profile: ModelProfile | null = null;
let activeDownload: FileSystem.DownloadResumable | null = null;

export const isModelLoaded = (): boolean => ctx !== null;
export const isVisionReady = (): boolean => ctx !== null && visionOn;
export const getLoadedModel = (): ModelInfo | null => loadedModel;
export const getLoadedContextSize = (): number => loadedCtxSize;
export const getVisionNote = (): string => visionNote;
export const getModelProfile = (): ModelProfile | null => profile;

/** Decides, from the GGUF metadata + file name, how capable this model is. */
function analyzeModel(c: LlamaContext, fileName: string): ModelProfile {
  const info: any = c.model || {};
  const meta: Record<string, unknown> = info.metadata || {};
  const label = [meta['general.name'], meta['general.basename'], meta['general.finetune'], meta['general.size_label'], fileName]
    .filter(Boolean).join(' ');
  const paramsB = (Number(info.nParams) || 0) / 1e9;
  const templates = info.chatTemplates || {};
  const hasChatTemplate = !!(meta['tokenizer.chat_template'] || templates.llamaChat || templates.jinja?.default);
  const instructHint = /(instruct|chat|[-_. ]it\b|assistant|hermes|coder|thinking)/i.test(label);
  const baseWord = /base/i.test(label) && !instructHint;
  const looksBase = !hasChatTemplate || baseWord;
  const tier: ModelProfile['tier'] = paramsB > 0 && paramsB < 1.5 ? 'tiny' : paramsB > 0 && paramsB < 3.5 ? 'small' : 'ok';

  const warnings: string[] = [];
  if (looksBase) {
    warnings.push(
      'ده نموذج Base (مش Instruct): مبيفهمش الأوامر، بيكرر كلامه، ومش هينفّذ أدوات. ' +
      'حمّل نموذج Instruct (مثلاً Qwen2.5-3B-Instruct) من قائمة النماذج المقترحة.',
    );
  }
  if (tier === 'tiny') {
    warnings.push(
      `حجم النموذج صغير جداً (~${paramsB.toFixed(1)}B). الأدوات هتشتغل تلقائياً من التطبيق، لكن جودة الردود هتكون ضعيفة. ` +
      'للأدوات والكود استخدم 3B أو أكبر.',
    );
  }
  return { name: String(meta['general.name'] || fileName), paramsB, hasChatTemplate, looksBase, tier, warnings };
}

/** Sampling that stops repetition loops (tiny / base models need it most). */
export function samplingFor(p: ModelProfile | null) {
  const weak = !p || p.tier === 'tiny' || p.looksBase;
  return {
    temperature: weak ? 0.45 : 0.6,
    top_k: 40,
    top_p: 0.9,
    min_p: 0.05,
    penalty_repeat: weak ? 1.18 : 1.08,
    penalty_last_n: 256,
    dry_multiplier: 0.8,
    dry_base: 1.75,
    dry_allowed_length: 2,
    dry_penalty_last_n: 512,
    dry_sequence_breakers: ['\n', ':', '"', '*'],
  };
}

/** End-of-turn markers of the common chat formats (so generation always stops). */
export const STOP_WORDS = ['<|im_end|>', '<|endoftext|>', '<|im_start|>', '<|eot_id|>', '<end_of_turn>', '</s>', '<|user|>', '<|end|>'];

/** True when the tail of the text is the same chunk repeated 3+ times. */
export function detectLoop(text: string): boolean {
  if (text.length < 150) return false;
  const tail = text.slice(-700);
  for (const len of [20, 40, 80]) {
    if (tail.length < len * 3) continue;
    const unit = tail.slice(-len);
    let count = 0;
    let idx = tail.indexOf(unit);
    while (idx !== -1) { count++; idx = tail.indexOf(unit, idx + len); }
    if (count >= 3) return true;
  }
  return false;
}

/** Cuts a looping text back to a single copy of the repeated chunk. */
export function collapseLoop(text: string): string {
  const unit = text.slice(-40);
  const first = text.indexOf(unit);
  if (first === -1 || first + unit.length >= text.length - 5) return text;
  return text.slice(0, first + unit.length).trim();
}

/* ── RECOMMENDED MODELS (downloaded straight from Hugging Face) ────────── */
export interface RecommendedModel {
  id: string;
  title: string;
  file: string;
  url: string;
  sizeGB: number;
  note: string;
}

export const RECOMMENDED_MODELS: RecommendedModel[] = [
  {
    id: 'qwen25-3b-instruct',
    title: 'Qwen2.5-3B-Instruct (Q4_K_M)',
    file: 'Qwen2.5-3B-Instruct-Q4_K_M.gguf',
    url: 'https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf',
    sizeGB: 1.93,
    note: 'سريع، بيفهم الأوامر وبينفّذ الأدوات — بداية موصى بيها',
  },
  {
    id: 'qwen25-7b-instruct',
    title: 'Qwen2.5-7B-Instruct (Q4_K_M)',
    file: 'Qwen2.5-7B-Instruct-Q4_K_M.gguf',
    url: 'https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf',
    sizeGB: 4.68,
    note: 'أذكى بكتير وأحسن في الأدوات والعربي، أبطأ وبياخد ~6GB رام',
  },
];

export function getContext(): LlamaContext {
  if (!ctx) throw new Error('مفيش نموذج متحمّل — حمّل نموذج من الإعدادات الأول.');
  return ctx;
}

/* ── GENERATION LOCK (one completion at a time per context) ────────────── */
let lockChain: Promise<unknown> = Promise.resolve();
export function withGenerationLock<T>(fn: () => Promise<T>): Promise<T> {
  const run = lockChain.then(fn, fn);
  lockChain = run.then(() => undefined, () => undefined);
  return run;
}

export async function stopGeneration(): Promise<void> {
  try {
    if (ctx) await ctx.stopCompletion();
  } catch {
    /* nothing running */
  }
}

/** Removes <think>…</think> reasoning blocks some models emit. */
export function stripThinking(text: string): string {
  return text
    .replace(/<think>[\s\S]*?<\/think>/g, '')
    .replace(/^[\s\S]*?<\/think>/, '')
    .trim();
}

/* ── FILE HELPERS ──────────────────────────────────────────────────────── */
async function ensureModelsDir(): Promise<void> {
  const info = await FileSystem.getInfoAsync(MODELS_DIR);
  if (!info.exists) {
    await FileSystem.makeDirectoryAsync(MODELS_DIR, { intermediates: true });
  }
}

const isProjectorName = (name: string): boolean => /mmproj/i.test(name);

function toModelInfo(name: string, sizeBytes: number): ModelInfo {
  return {
    uri: MODELS_DIR + name,
    name,
    sizeBytes,
    sizeGB: (sizeBytes / 1e9).toFixed(2),
    isProjector: isProjectorName(name),
  };
}

function safeFileName(name: string): string {
  return name.replace(/[\\/:*?"<>|\s]+/g, '_');
}

/* ── LIST / IMPORT / DELETE ────────────────────────────────────────────── */
export async function listLocalModels(): Promise<ModelInfo[]> {
  await ensureModelsDir();
  const names = await FileSystem.readDirectoryAsync(MODELS_DIR);
  const out: ModelInfo[] = [];
  for (const name of names) {
    if (!/\.gguf$/i.test(name)) continue;
    const info = await FileSystem.getInfoAsync(MODELS_DIR + name, { size: true });
    if (info.exists && !info.isDirectory) out.push(toModelInfo(name, info.size));
  }
  out.sort(
    (a, b) => Number(a.isProjector) - Number(b.isProjector) || a.name.localeCompare(b.name),
  );
  return out;
}

/**
 * Lets the user pick a .gguf file anywhere on the device and copies it into
 * the app's private models folder. Validates the GGUF header after copying so
 * a truncated / wrong file is reported clearly instead of crashing at load.
 */
export async function importModelFromDevice(): Promise<ModelInfo | null> {
  const res = await DocumentPicker.getDocumentAsync({
    type: '*/*',
    copyToCacheDirectory: false, // avoid a second multi-GB copy in the cache
    multiple: false,
  });
  if (res.canceled || !res.assets?.[0]) return null;

  const asset = res.assets[0];
  const name = safeFileName(asset.name || 'model.gguf');
  if (!/\.gguf$/i.test(name)) {
    throw new Error(`الملف "${asset.name}" مش بصيغة .gguf — اختار ملف نموذج GGUF.`);
  }

  await ensureModelsDir();
  const dest = MODELS_DIR + name;
  const wantBytes = asset.size ?? 0;

  const existing = await FileSystem.getInfoAsync(dest, { size: true });
  const alreadyThere = existing.exists && wantBytes > 0 && existing.size === wantBytes;

  if (!alreadyThere) {
    if (existing.exists) await FileSystem.deleteAsync(dest, { idempotent: true });

    if (wantBytes > 0) {
      const free = await FileSystem.getFreeDiskStorageAsync();
      const need = wantBytes + 300 * 1024 * 1024; // file + 300 MB headroom
      if (free < need) {
        throw new Error(
          `المساحة الفاضية مش كفاية. محتاج ~${(need / 1e9).toFixed(1)} GB وعندك ${(free / 1e9).toFixed(1)} GB.`,
        );
      }
    }

    try {
      await FileSystem.copyAsync({ from: asset.uri, to: dest });
    } catch (e: any) {
      await FileSystem.deleteAsync(dest, { idempotent: true }).catch(() => {});
      throw new Error(`فشل نسخ الملف: ${e?.message || e}`);
    }
  }

  const head = await FileSystem.readAsStringAsync(dest, {
    encoding: FileSystem.EncodingType.Base64,
    position: 0,
    length: 4,
  });
  if (head !== GGUF_MAGIC_B64) {
    await FileSystem.deleteAsync(dest, { idempotent: true }).catch(() => {});
    throw new Error('الملف مش GGUF صالح (الـ header غلط). ممكن يكون ناقص أو تالف — حمّله تاني.');
  }

  const info = await FileSystem.getInfoAsync(dest, { size: true });
  return toModelInfo(name, info.exists ? info.size : wantBytes);
}

export async function deleteModel(model: ModelInfo): Promise<void> {
  if (loadedModel && loadedModel.uri === model.uri) await unloadModel();
  await FileSystem.deleteAsync(model.uri, { idempotent: true });
  const last = await AsyncStorage.getItem(LAST_MODEL_KEY);
  if (last === model.uri) await AsyncStorage.removeItem(LAST_MODEL_KEY);
}

/* ── VISION PROJECTOR PAIRING ──────────────────────────────────────────── */
function stem(name: string): string {
  return name
    .toLowerCase()
    .replace(/\.gguf$/, '')
    .replace(/mmproj/g, '')
    .replace(/[-_.](f16|f32|bf16|q\d\w*)/g, '')
    .replace(/[^a-z0-9]/g, '');
}

function commonPrefixLen(a: string, b: string): number {
  let i = 0;
  while (i < a.length && i < b.length && a[i] === b[i]) i++;
  return i;
}

function findProjector(model: ModelInfo, all: ModelInfo[]): ModelInfo | null {
  const projectors = all.filter(m => m.isProjector);
  if (projectors.length === 0) return null;

  const base = stem(model.name);
  let best: ModelInfo | null = null;
  let bestScore = 0;
  for (const p of projectors) {
    const score = commonPrefixLen(base, stem(p.name));
    if (score > bestScore) { best = p; bestScore = score; }
  }
  if (best && bestScore >= 4) return best;

  // Single projector + single text model: assume they belong together.
  const textModels = all.filter(m => !m.isProjector);
  if (projectors.length === 1 && textModels.length === 1) return projectors[0];
  return null;
}

/* ── LOAD / UNLOAD ─────────────────────────────────────────────────────── */
export async function unloadModel(): Promise<void> {
  if (ctx) {
    try { await ctx.release(); } catch { /* already gone */ }
  }
  ctx = null;
  loadedModel = null;
  loadedCtxSize = 0;
  visionOn = false;
  visionNote = '';
  profile = null;
}

const MEMORY_ERR = /context|memory|alloc|kv|out of|oom/i;

/**
 * Loads a text model (and its vision projector if one is paired).
 * onProgress receives 0..1.
 */
export async function loadModel(
  model: ModelInfo,
  opts: LoadOptions = {},
  onProgress?: (pct: number) => void,
): Promise<void> {
  if (model.isProjector) {
    throw new Error('ده ملف mmproj (للرؤية) مش نموذج. اختار النموذج الأساسي — الـ mmproj بيتربط بيه تلقائياً.');
  }

  await unloadModel();
  await AsyncStorage.setItem(LOADING_FLAG_KEY, model.uri);

  const sizes = opts.nCtx ? [opts.nCtx] : [8192, 4096, 2048];
  let lastError: any = null;

  for (const nCtx of sizes) {
    try {
      ctx = await initLlama(
        {
          model: model.uri,
          n_ctx: nCtx,
          n_threads: opts.nThreads ?? 4,
          n_gpu_layers: opts.gpuLayers ?? 0,
          use_mlock: false,
        },
        (p: number) => onProgress?.(Math.max(0, Math.min(1, p / 100))),
      );
      loadedModel = model;
      loadedCtxSize = nCtx;
      lastError = null;
      break;
    } catch (e: any) {
      lastError = e;
      ctx = null;
      // Only a memory-related failure can be fixed with a smaller context.
      if (!MEMORY_ERR.test(String(e?.message || e))) break;
    }
  }

  if (!ctx || !loadedModel) {
    await AsyncStorage.removeItem(LOADING_FLAG_KEY);
    const reason = String(lastError?.message || lastError || 'سبب غير معروف');
    throw new Error(
      `فشل تحميل "${model.name}": ${reason}\n` +
      'تأكد إن الملف GGUF كامل، وإن النموذج مدعوم (موديلات حديثة ممكن تحتاج إصدار أحدث من llama.rn).',
    );
  }

  try { profile = analyzeModel(ctx, model.name); } catch { profile = null; }

  // Vision (optional)
  try {
    const all = await listLocalModels();
    const proj = opts.mmprojUri
      ? all.find(m => m.uri === opts.mmprojUri) ?? null
      : findProjector(model, all);
    if (proj) {
      const ok = await ctx.initMultimodal({ path: proj.uri, use_gpu: false });
      if (ok) {
        const support = await ctx.getMultimodalSupport();
        visionOn = !!support.vision;
        visionNote = visionOn ? `رؤية: ${proj.name}` : 'ملف mmproj اتحمّل بس بدون دعم صور.';
      } else {
        visionNote = `فشل تحميل ملف الرؤية ${proj.name} (ممكن مش مطابق للنموذج).`;
      }
    } else {
      visionNote = '';
    }
  } catch (e: any) {
    visionOn = false;
    visionNote = `فشل تفعيل الرؤية: ${e?.message || e}`;
  }

  await AsyncStorage.setItem(LAST_MODEL_KEY, model.uri);
  await AsyncStorage.removeItem(LOADING_FLAG_KEY);
}

export type AutoLoadStatus = 'loaded' | 'none' | 'skipped_after_crash';

/**
 * Re-loads the last used model on startup. If the previous attempt never
 * finished (the app was killed mid-load, e.g. out of memory), it is skipped
 * once instead of crash-looping.
 */
export async function autoLoadLastModel(
  onProgress?: (pct: number) => void,
): Promise<{ status: AutoLoadStatus; model?: ModelInfo }> {
  const flag = await AsyncStorage.getItem(LOADING_FLAG_KEY);
  if (flag) {
    await AsyncStorage.removeItem(LOADING_FLAG_KEY);
    await AsyncStorage.removeItem(LAST_MODEL_KEY);
    return { status: 'skipped_after_crash' };
  }
  const last = await AsyncStorage.getItem(LAST_MODEL_KEY);
  if (!last) return { status: 'none' };

  const models = await listLocalModels();
  const m = models.find(x => x.uri === last && !x.isProjector);
  if (!m) return { status: 'none' };

  await loadModel(m, {}, onProgress);
  return { status: 'loaded', model: m };
}

/* ── DOWNLOAD A RECOMMENDED MODEL ──────────────────────────────────────── */
export async function downloadRecommendedModel(
  rec: RecommendedModel,
  onProgress: (fraction: number, writtenBytes: number, totalBytes: number) => void,
): Promise<ModelInfo> {
  await ensureModelsDir();
  const dest = MODELS_DIR + rec.file;
  const part = `${dest}.part`;

  const needBytes = rec.sizeGB * 1e9 * 1.05 + 300 * 1024 * 1024;
  const free = await FileSystem.getFreeDiskStorageAsync();
  if (free < needBytes) {
    throw new Error(`المساحة الفاضية مش كفاية. محتاج ~${(needBytes / 1e9).toFixed(1)} GB وعندك ${(free / 1e9).toFixed(1)} GB.`);
  }
  await FileSystem.deleteAsync(part, { idempotent: true });

  const dl = FileSystem.createDownloadResumable(rec.url, part, {}, (p) => {
    const total = p.totalBytesExpectedToWrite || rec.sizeGB * 1e9;
    onProgress(Math.min(1, p.totalBytesWritten / total), p.totalBytesWritten, total);
  });
  activeDownload = dl;
  let res;
  try {
    res = await dl.downloadAsync();
  } finally {
    activeDownload = null;
  }
  if (!res || res.status !== 200) {
    await FileSystem.deleteAsync(part, { idempotent: true });
    throw new Error(`فشل التنزيل (HTTP ${res?.status ?? 'cancelled'}).`);
  }
  await FileSystem.deleteAsync(dest, { idempotent: true });
  await FileSystem.moveAsync({ from: part, to: dest });

  const head = await FileSystem.readAsStringAsync(dest, {
    encoding: FileSystem.EncodingType.Base64, position: 0, length: 4,
  });
  if (head !== GGUF_MAGIC_B64) {
    await FileSystem.deleteAsync(dest, { idempotent: true });
    throw new Error('الملف اللي اتنزّل مش GGUF صالح. جرّب تاني.');
  }
  const info = await FileSystem.getInfoAsync(dest, { size: true });
  return toModelInfo(rec.file, info.exists ? info.size : 0);
}

export async function cancelDownload(): Promise<void> {
  try { await activeDownload?.pauseAsync(); } catch { /* already finished */ }
  activeDownload = null;
}

/* ── VISION HELPER (used by attachments.ts for video frames) ───────────── */
export async function analyzeImage(
  imageUri: string,
  prompt: string,
  maxTokens = 400,
): Promise<string> {
  const c = getContext();
  if (!visionOn) throw new Error('مفيش نموذج رؤية (mmproj) متحمّل.');
  const url = imageUri.startsWith('/') ? `file://${imageUri}` : imageUri;

  return withGenerationLock(async () => {
    const r = await c.completion({
      messages: [
        {
          role: 'user',
          content: [
            { type: 'text', text: prompt },
            { type: 'image_url', image_url: { url } },
          ],
        },
      ],
      n_predict: maxTokens,
      temperature: 0.2,
    });
    return stripThinking(r.text || '');
  });
}
