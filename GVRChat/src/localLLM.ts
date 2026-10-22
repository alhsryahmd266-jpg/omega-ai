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
import { getBackendDevicesInfo, initLlama, loadLlamaModelInfo, type LlamaContext } from 'llama.rn';
import * as FileSystem from 'expo-file-system';
import * as DocumentPicker from 'expo-document-picker';
import AsyncStorage from '@react-native-async-storage/async-storage';
import Terminal from '../modules/terminal/src';
import {
  hasMtp, parseFreqs, planAcceleration, planThreads, speedHint,
  type AccelMode, type AccelPlan, type AccelUsed, type BackendDevice,
} from './accel';

export type { AccelMode } from './accel';

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
  /** Manual override. Default: one thread per big CPU core (see accel.ts planThreads). */
  nThreads?: number;
  /** Manual override. Default: decided by the acceleration plan. */
  gpuLayers?: number;
  /** Hardware choice for this load. Default: the saved setting ('auto'). */
  accel?: AccelMode;
  /** Force a specific mmproj file instead of auto-pairing. */
  mmprojUri?: string;
}

export interface ModelProfile {
  name: string;
  paramsB: number;
  hasChatTemplate: boolean;
  /** The chat template knows about tools (native tool calling is safe). Otherwise llama.cpp would force every reply into a generic JSON envelope. */
  supportsTools: boolean;
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
  const templateText = [meta['tokenizer.chat_template'], meta['tokenizer.chat_template.tool_use']]
    .filter((x): x is string => typeof x === 'string').join('\n');
  const supportsTools = !!templates.jinja?.toolUse || /\btools?\b|tool_call|tool_use|TOOL_CALLS|tool▁call/i.test(templateText);
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
  if (paramsB >= 8) {
    warnings.push(
      `النموذج ده كبير (~${paramsB.toFixed(0)}B): على معالج الموبايل التوليد بيبقى بطيء (تقدير: 2–3 توكن/ث) وأول رد ممكن ياخد دقايق. ` +
      'التطبيق هيعرض التقدم، وبيقصّر الردود وقائمة الأدوات تلقائياً. للـ agent السريع جرّب نموذج 4–8B.',
    );
  }
  return { name: String(meta['general.name'] || fileName), paramsB, hasChatTemplate, supportsTools, looksBase, tier, warnings };
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
  accel = null;
}

const MEMORY_ERR = /context|memory|alloc|kv|out of|oom/i;

/* ── ACCELERATION: GPU (OpenCL) / NPU (Hexagon) / CPU threads / MTP ─────────────────────────────────────
 * The decisions are pure functions in accel.ts (unit-tested). This part only talks to the phone.
 * Safety net: an accelerated load writes ACCEL_TRY_KEY first. If the app dies during that load (a native
 * crash JavaScript cannot catch) the next launch finds the key, marks that mode as "bad" and loads on the CPU.
 */
const ACCEL_MODE_KEY = 'gvr_accel_mode';
const ACCEL_BAD_KEY = 'gvr_accel_bad';
const ACCEL_TRY_KEY = 'gvr_accel_try';
const CPU_FREQ_KEY = 'gvr_cpu_freqs';

let accel: { plan: AccelPlan; threads: string; mtp: boolean; hint: string } | null = null;

export async function getAccelMode(): Promise<AccelMode> {
  const v = await AsyncStorage.getItem(ACCEL_MODE_KEY);
  return v === 'cpu' || v === 'gpu' || v === 'npu' ? v : 'auto';
}

/** Choosing 'gpu' or 'npu' by hand also forgives an earlier failure of that mode. */
export async function setAccelMode(mode: AccelMode): Promise<void> {
  await AsyncStorage.setItem(ACCEL_MODE_KEY, mode);
  if (mode === 'gpu' || mode === 'npu') {
    const bad = (await getBadAccel()).filter(b => b !== mode);
    await AsyncStorage.setItem(ACCEL_BAD_KEY, JSON.stringify(bad));
  }
}

async function getBadAccel(): Promise<AccelUsed[]> {
  try {
    const a = JSON.parse((await AsyncStorage.getItem(ACCEL_BAD_KEY)) || '[]');
    return Array.isArray(a) ? a.filter((x): x is AccelUsed => x === 'gpu' || x === 'npu') : [];
  } catch { return []; }
}

async function markAccelBad(mode: AccelUsed): Promise<void> {
  if (mode === 'cpu') return;
  const bad = new Set(await getBadAccel());
  bad.add(mode);
  await AsyncStorage.setItem(ACCEL_BAD_KEY, JSON.stringify([...bad]));
}

/** Short status for the UI, e.g. "CPU · threads 4 (4 big of 8 cores)" or "GPU OpenCL (GPUOpenCL)". */
export function getAccelStatus(): string {
  if (!accel) return '';
  const parts = [accel.plan.label];
  if (accel.plan.mode === 'cpu' && accel.threads) parts.push(accel.threads);
  if (accel.mtp) parts.push('MTP');
  return parts.join(' · ');
}
export const getSpeedHint = (): string => accel?.hint ?? '';
export const isMtpActive = (): boolean => !!accel?.mtp;

async function backendDevices(): Promise<BackendDevice[]> {
  try { return ((await getBackendDevicesInfo()) as BackendDevice[]) ?? []; } catch { return []; }
}

/** Max frequency of every CPU core (read once, cached). [] when the phone does not let us read it. */
async function cpuFreqs(): Promise<number[]> {
  try {
    const cached = await AsyncStorage.getItem(CPU_FREQ_KEY);
    if (cached) {
      const a = JSON.parse(cached);
      if (Array.isArray(a) && a.length >= 4) return a;
    }
    const out = await Terminal.runAndroid('for f in /sys/devices/system/cpu/cpu[0-9]*; do echo "$(basename $f) $(cat $f/cpufreq/cpuinfo_max_freq 2>/dev/null)"; done', 10);
    const freqs = parseFreqs(out);
    if (freqs.length >= 4) await AsyncStorage.setItem(CPU_FREQ_KEY, JSON.stringify(freqs));
    return freqs;
  } catch { return []; }
}

/** MTP (multi-token prediction) = speculative decoding with no second model. Only some GGUFs carry the layers. */
async function detectMtp(uri: string): Promise<boolean> {
  try { return hasMtp((await loadLlamaModelInfo(uri)) as Record<string, unknown>); } catch { return false; }
}

type InitParams = Parameters<typeof initLlama>[0];

/** Typed on purpose (no ternary spreads): the compiler checks every option against llama.rn's own types. */
function buildInitParams(model: ModelInfo, nCtx: number, threads: number, plan: AccelPlan, opts: LoadOptions, mtp: boolean): InitParams {
  const p: InitParams = {
    model: model.uri,
    n_ctx: nCtx,
    n_threads: opts.nThreads ?? threads,
    n_gpu_layers: opts.gpuLayers ?? plan.nGpuLayers,
    use_mlock: false,
  };
  if (plan.devices) p.devices = plan.devices;
  if (plan.noGpuDevices) p.no_gpu_devices = true;
  if (plan.flashAttn) p.flash_attn_type = plan.flashAttn;
  if (mtp) p.speculative = { type: 'draft-mtp', n_max: 3 };
  return p;
}

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

  const mode = opts.accel ?? (await getAccelMode());
  const [devices, bad, freqs, mtp] = await Promise.all([backendDevices(), getBadAccel(), cpuFreqs(), detectMtp(model.uri)]);
  const threads = planThreads(freqs);
  const firstPlan = planAcceleration({ mode, fileName: model.name, devices, bad });
  // An accelerated plan gets one try. If it fails, the CPU plan runs and the failure is remembered.
  const cpuFallback: AccelPlan = {
    ...planAcceleration({ mode: 'cpu', fileName: model.name, devices, bad }),
    noGpuDevices: false,
    label: `CPU (بعد فشل ${firstPlan.mode === 'npu' ? 'الـ NPU' : 'الـ GPU'})`,
  };
  const plans: AccelPlan[] = firstPlan.mode === 'cpu' ? [firstPlan] : [firstPlan, cpuFallback];

  const sizes = opts.nCtx ? [opts.nCtx] : [8192, 4096, 2048];
  let lastError: any = null;
  let usedPlan: AccelPlan | null = null;

  plansLoop: for (const plan of plans) {
    if (plan.mode !== 'cpu') await AsyncStorage.setItem(ACCEL_TRY_KEY, plan.mode);
    for (const nCtx of sizes) {
      try {
        ctx = await initLlama(
          buildInitParams(model, nCtx, threads.nThreads, plan, opts, mtp),
          (p: number) => onProgress?.(Math.max(0, Math.min(1, p / 100))),
        );
        // The first GPU/NPU run compiles kernels: do it now so a broken backend shows up here, not mid-chat.
        if (plan.mode !== 'cpu') await ctx.completion({ prompt: 'Hi', n_predict: 2, temperature: 0 });
        loadedModel = model;
        loadedCtxSize = nCtx;
        usedPlan = plan;
        lastError = null;
        await AsyncStorage.removeItem(ACCEL_TRY_KEY);
        break plansLoop;
      } catch (e: any) {
        lastError = e;
        if (ctx) { try { await ctx.release(); } catch { /* already gone */ } }
        ctx = null;
        // Only a memory-related failure can be fixed with a smaller context.
        if (!MEMORY_ERR.test(String(e?.message || e))) break;
      }
    }
    if (plan.mode !== 'cpu') {
      await markAccelBad(plan.mode);
      await AsyncStorage.removeItem(ACCEL_TRY_KEY);
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
  const finalPlan: AccelPlan = usedPlan ?? plans[plans.length - 1];
  accel = { plan: finalPlan, threads: finalPlan.mode === 'cpu' ? threads.note : '', mtp, hint: speedHint(model.name, finalPlan) };

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
  const tried = await AsyncStorage.getItem(ACCEL_TRY_KEY);
  if (tried === 'gpu' || tried === 'npu') {
    // The app died while the GPU/NPU was being initialised: that backend is the suspect, not the model.
    // Remember it as bad and load the same model on the CPU instead of skipping it.
    await markAccelBad(tried);
    await AsyncStorage.removeItem(ACCEL_TRY_KEY);
    await AsyncStorage.removeItem(LOADING_FLAG_KEY);
  } else if (flag) {
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
