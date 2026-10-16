/**
 * modelSelector.ts — picks the strongest model that fits this phone's RAM,
 * and the speed settings for it. Sizes are approximate GGUF file sizes (Q4_K_M)
 * and must be verified against the real files before downloads are enabled.
 */

export interface ModelSpec {
  id: string;
  family: string;
  params: string;
  fileGB: number;
  layers: number;
  kvHeads: number;
  headDim: number;
  coding: boolean;
  vision: boolean;
  url: string | null; // filled in only after the file is verified
}

export const CATALOG: ModelSpec[] = [
  { id: 'qwen2.5-coder-14b-q4', family: 'Qwen2.5-Coder', params: '14B', fileGB: 9.0, layers: 48, kvHeads: 8, headDim: 128, coding: true, vision: false, url: null },
  { id: 'qwen2.5-coder-7b-q4', family: 'Qwen2.5-Coder', params: '7B', fileGB: 4.7, layers: 28, kvHeads: 4, headDim: 128, coding: true, vision: false, url: null },
  { id: 'qwen2.5-coder-1.5b-q4', family: 'Qwen2.5-Coder', params: '1.5B', fileGB: 1.1, layers: 28, kvHeads: 2, headDim: 128, coding: true, vision: false, url: null },
];

const OS_RESERVE_GB = 4.0; // Android itself and background apps
const APP_GB = 0.8;        // this app's own memory

/** KV cache size in GB for a context length, with q8 cache (1 byte per value). */
export function kvCacheGB(m: ModelSpec, ctx: number): number {
  return (2 * m.layers * m.kvHeads * m.headDim * ctx * 1) / 1e9;
}

/** Total RAM this model needs at the given context length. */
export function neededGB(m: ModelSpec, ctx: number): number {
  return m.fileGB + kvCacheGB(m, ctx) + OS_RESERVE_GB + APP_GB;
}

/** Strongest coding model that fits. Returns null if nothing fits. */
export function pickModel(ramGB: number, ctx = 4096): ModelSpec | null {
  const fits = CATALOG.filter(m => m.coding && neededGB(m, ctx) <= ramGB);
  fits.sort((a, b) => b.fileGB - a.fileGB);
  return fits[0] || null;
}

/** Speed settings for llama.rn 0.12.9 (no MTP needed). */
export function speedProfile(ramGB: number) {
  if (ramGB >= 12) {
    return { n_ctx: 4096, n_batch: 1024, n_ubatch: 512, flash_attn_type: 'auto', cache_type_k: 'q8_0', cache_type_v: 'q8_0', n_threads: 4 };
  }
  return { n_ctx: 2048, n_batch: 512, n_ubatch: 256, flash_attn_type: 'auto', cache_type_k: 'q4_0', cache_type_v: 'q4_0', n_threads: 4 };
}

export function describePick(ramGB: number): string {
  const m = pickModel(ramGB);
  if (!m) return `With ${ramGB} GB there is no coding model that fits safely. Use a smaller phone profile or close other apps.`;
  const need = neededGB(m, 4096).toFixed(1);
  const p = speedProfile(ramGB);
  return `Recommended: ${m.family} ${m.params} (${m.fileGB} GB file, needs about ${need} GB). Context ${p.n_ctx}, cache ${p.cache_type_k}, batch ${p.n_batch}, threads ${p.n_threads}. ${m.url ? '' : 'Download link not verified yet.'}`;
}
