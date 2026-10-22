/**
 * accel.ts — which hardware should run the model? (pure logic, no React Native imports → unit-tested in Node)
 * =========================================================================================================
 * llama.rn 0.12.9 ships these backends in the APK: CPU variants (dotprod / i8mm …), OpenCL for Qualcomm Adreno
 * GPUs, and Hexagon HTP for the Snapdragon NPU. It does NOT ship Vulkan (that would need a custom native build).
 * The app used to ask for none of them (n_gpu_layers 0): everything ran on 4 CPU threads.
 *
 *  - 'auto' (default): GPU (OpenCL) only when the phone has it AND the model is a quantisation the OpenCL backend
 *    accelerates (Q4_0 / Q6_K). Everything else stays on the CPU, exactly as before — no risk of a slow or
 *    broken GPU path for models that would not benefit.
 *  - 'gpu' / 'npu': the user forces it in Settings (the NPU is experimental, never automatic).
 *  - A mode that crashed or failed once is remembered as "bad" and skipped (see localLLM.ts).
 */

export type AccelMode = 'auto' | 'cpu' | 'gpu' | 'npu';
export type AccelUsed = 'cpu' | 'gpu' | 'npu';

export interface BackendDevice { backend: string; type: string; deviceName: string; maxMemorySize?: number }

export interface AccelPlan {
  mode: AccelUsed;
  nGpuLayers: number;
  /** explicit backend device names, or null = llama.rn's default devices */
  devices: string[] | null;
  /** CPU only, and do not even initialise GPU devices */
  noGpuDevices: boolean;
  flashAttn: 'off' | null;
  /** short text for the status line */
  label: string;
}

/** 'q4_k_m' from "Qwen2.5-7B-Instruct-Q4_K_M.gguf" ('' when not recognisable). */
export function quantOf(fileName: string): string {
  const m = /[-_.](iq\d(?:_[a-z0-9]+)*|q\d(?:_[a-z0-9]+)*|f16|bf16|f32)(?=[-_.]|$)/i.exec(fileName.replace(/\.gguf$/i, ''));
  return m ? m[1].toLowerCase() : '';
}

/** The OpenCL (Adreno) backend accelerates these quantisations. */
export const openclFriendly = (fileName: string): boolean => /^(?:q4_0|q6_k)$/.test(quantOf(fileName));

export function planAcceleration(a: { mode: AccelMode; fileName: string; devices: BackendDevice[]; bad: AccelUsed[] }): AccelPlan {
  const gpu = a.devices.find(d => /opencl/i.test(`${d.backend} ${d.deviceName}`) && /gpu/i.test(`${d.type} ${d.deviceName}`))
    ?? a.devices.find(d => /opencl/i.test(d.backend));
  const npu = a.devices.find(d => /^HTP/i.test(d.deviceName));
  const cpu = (label: string, noGpu = false): AccelPlan => ({ mode: 'cpu', nGpuLayers: 0, devices: null, noGpuDevices: noGpu, flashAttn: null, label });

  if (a.mode === 'cpu') return cpu('CPU', true);

  if (a.mode === 'npu') {
    if (npu && !a.bad.includes('npu')) {
      return { mode: 'npu', nGpuLayers: 99, devices: [npu.deviceName], noGpuDevices: false, flashAttn: null, label: `NPU Hexagon (${npu.deviceName})` };
    }
    return cpu(npu ? 'CPU (الـ NPU فشل قبل كده)' : 'CPU (مفيش NPU في الجهاز ده)');
  }

  const gpuPlan = (): AccelPlan => ({
    mode: 'gpu', nGpuLayers: 99, devices: gpu ? [gpu.deviceName] : null, noGpuDevices: false, flashAttn: 'off',
    label: `GPU OpenCL${gpu ? ` (${gpu.deviceName})` : ''}`,
  });

  if (a.mode === 'gpu') {
    if (gpu && !a.bad.includes('gpu')) return gpuPlan();
    return cpu(gpu ? 'CPU (الـ GPU فشل قبل كده)' : 'CPU (مفيش GPU OpenCL في الجهاز ده)');
  }

  // auto
  if (gpu && !a.bad.includes('gpu') && openclFriendly(a.fileName)) return gpuPlan();
  return cpu('CPU');
}

/** "cpu0 3000000\ncpu1 …" (what the probe command prints) → max frequency per core, in index order. */
export function parseFreqs(output: string): number[] {
  const byIdx = new Map<number, number>();
  for (const line of output.split('\n')) {
    const m = /^cpu(\d+)\s+(\d+)\s*$/.exec(line.trim());
    if (m) byIdx.set(Number(m[1]), Number(m[2]));
  }
  if (!byIdx.size) return [];
  const n = Math.max(...byIdx.keys()) + 1;
  return Array.from({ length: n }, (_v, i) => byIdx.get(i) ?? 0);
}

export interface ThreadPlan { nThreads: number; big: number; total: number; note: string }

/**
 * How many threads? One per "big" core (within 80 % of the fastest core). More threads than that put slow
 * cores into every synchronisation barrier and make the model SLOWER. llama.rn itself pins the threads to the
 * best cores on Android (set_best_cores), so only the number matters. Unknown topology → the old default, 4.
 */
export function planThreads(freqs: number[]): ThreadPlan {
  const total = freqs.length;
  const known = freqs.filter(f => f > 0);
  if (total < 4 || known.length < total) return { nThreads: 4, big: 0, total, note: 'threads 4' };
  const max = Math.max(...known);
  const big = freqs.filter(f => f >= max * 0.8).length;
  const nThreads = Math.min(8, Math.max(2, big));
  return { nThreads, big, total, note: `threads ${nThreads} (${big} big of ${total} cores)` };
}

/** GGUF metadata of a model with Multi-Token-Prediction layers (speculative decoding without a second model). */
export function hasMtp(meta: Record<string, unknown> | null | undefined): boolean {
  if (!meta) return false;
  return Object.entries(meta).some(([k, v]) => /(?:^|\.)nextn_predict_layers$/.test(k) && Number(v) > 0);
}

/** One honest line about speed for the settings screen. */
export function speedHint(fileName: string, plan: AccelPlan): string {
  const q = quantOf(fileName);
  if (plan.mode !== 'cpu') return '';
  if (q && q !== 'q4_0') return 'نصيحة للسرعة: نسخة Q4_0 من نفس النموذج أسرع على معالج الموبايل (وبتشغّل الـ GPU على Adreno).';
  return '';
}
