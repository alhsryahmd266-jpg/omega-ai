import assert from 'node:assert/strict';
import { hasMtp, openclFriendly, parseFreqs, planAcceleration, planThreads, quantOf, speedHint, type BackendDevice } from '../src/accel';

let pass = 0;
let fail = 0;
function t(name: string, fn: () => void): void {
  try { fn(); pass++; } catch (e) { fail++; console.error(`✗ ${name}\n    ${(e as Error).message.split('\n').join('\n    ')}`); }
}

const ADRENO: BackendDevice[] = [
  { backend: 'opencl', type: 'gpu', deviceName: 'GPUOpenCL', maxMemorySize: 8e9 },
  { backend: 'hexagon', type: 'accel', deviceName: 'HTP0', maxMemorySize: 2e9 },
  { backend: 'cpu', type: 'cpu', deviceName: 'CPU' },
];
const CPU_ONLY: BackendDevice[] = [{ backend: 'cpu', type: 'cpu', deviceName: 'CPU' }];

t('quantOf', () => {
  assert.equal(quantOf('Qwen2.5-7B-Instruct-Q4_K_M.gguf'), 'q4_k_m');
  assert.equal(quantOf('llama-3.2-3b-instruct-q4_0.gguf'), 'q4_0');
  assert.equal(quantOf('model.Q6_K.gguf'), 'q6_k');
  assert.equal(quantOf('gemma-2-2b-it-IQ4_XS.gguf'), 'iq4_xs');
  assert.equal(quantOf('mystery.gguf'), '');
  assert.ok(openclFriendly('x-Q4_0.gguf') && openclFriendly('x-Q6_K.gguf') && !openclFriendly('x-Q4_K_M.gguf'));
});

t('auto: GPU only for Q4_0/Q6_K on a phone that has OpenCL', () => {
  assert.equal(planAcceleration({ mode: 'auto', fileName: 'm-Q4_0.gguf', devices: ADRENO, bad: [] }).mode, 'gpu');
  assert.equal(planAcceleration({ mode: 'auto', fileName: 'm-Q4_K_M.gguf', devices: ADRENO, bad: [] }).mode, 'cpu');
  assert.equal(planAcceleration({ mode: 'auto', fileName: 'm-Q4_0.gguf', devices: CPU_ONLY, bad: [] }).mode, 'cpu');
  assert.equal(planAcceleration({ mode: 'auto', fileName: 'm-Q4_0.gguf', devices: ADRENO, bad: ['gpu'] }).mode, 'cpu');
});
t('auto never picks the experimental NPU', () => {
  assert.notEqual(planAcceleration({ mode: 'auto', fileName: 'm-Q4_0.gguf', devices: ADRENO, bad: ['gpu'] }).mode, 'npu');
});
t('gpu plan details', () => {
  const p = planAcceleration({ mode: 'gpu', fileName: 'm-Q4_K_M.gguf', devices: ADRENO, bad: [] });
  assert.equal(p.mode, 'gpu');
  assert.equal(p.nGpuLayers, 99);
  assert.deepEqual(p.devices, ['GPUOpenCL']);
  assert.equal(p.flashAttn, 'off');
  assert.equal(p.noGpuDevices, false);
});
t('npu: only when forced and present; falls back honestly', () => {
  const p = planAcceleration({ mode: 'npu', fileName: 'm-Q4_0.gguf', devices: ADRENO, bad: [] });
  assert.equal(p.mode, 'npu');
  assert.deepEqual(p.devices, ['HTP0']);
  assert.equal(planAcceleration({ mode: 'npu', fileName: 'm.gguf', devices: CPU_ONLY, bad: [] }).mode, 'cpu');
  assert.equal(planAcceleration({ mode: 'npu', fileName: 'm.gguf', devices: ADRENO, bad: ['npu'] }).mode, 'cpu');
});
t('cpu mode skips GPU devices entirely; auto-CPU keeps the old behaviour', () => {
  assert.equal(planAcceleration({ mode: 'cpu', fileName: 'm.gguf', devices: ADRENO, bad: [] }).noGpuDevices, true);
  const auto = planAcceleration({ mode: 'auto', fileName: 'm-Q4_K_M.gguf', devices: ADRENO, bad: [] });
  assert.equal(auto.noGpuDevices, false);
  assert.equal(auto.nGpuLayers, 0);
  assert.equal(auto.devices, null);
});

t('parseFreqs + planThreads', () => {
  const out = 'cpu0 1800000\ncpu1 1800000\ncpu2 1800000\ncpu3 1800000\ncpu4 2500000\ncpu5 2500000\ncpu6 2500000\ncpu7 3000000\n';
  const f = parseFreqs(out);
  assert.equal(f.length, 8);
  const p = planThreads(f);
  assert.equal(p.nThreads, 4);                       // 1+3 big cores of a Snapdragon 8 Gen 1
  assert.equal(p.big, 4);
  // 2 big + 6 little (A78 + A55): the little cores must NOT be counted
  assert.equal(planThreads([1800000, 1800000, 1800000, 1800000, 1800000, 1800000, 2400000, 2400000]).nThreads, 2);
  // 8 identical cores (new flagships) → 8
  assert.equal(planThreads(new Array(8).fill(3500000)).nThreads, 8);
  // unknown topology → the old default
  assert.equal(planThreads([]).nThreads, 4);
  assert.equal(planThreads([0, 0, 0, 0, 0, 0, 0, 0]).nThreads, 4);
  assert.equal(parseFreqs('garbage').length, 0);
});

t('hasMtp reads the GGUF metadata', () => {
  assert.equal(hasMtp({ 'qwen3next.nextn_predict_layers': 1 }), true);
  assert.equal(hasMtp({ 'glm4moe.nextn_predict_layers': '2' }), true);
  assert.equal(hasMtp({ 'llama.block_count': 32 }), false);
  assert.equal(hasMtp({ 'x.nextn_predict_layers': 0 }), false);
  assert.equal(hasMtp(null), false);
});

t('speedHint only nags CPU users with a non-Q4_0 model', () => {
  const cpu = planAcceleration({ mode: 'auto', fileName: 'm-Q4_K_M.gguf', devices: CPU_ONLY, bad: [] });
  assert.match(speedHint('m-Q4_K_M.gguf', cpu), /Q4_0/);
  assert.equal(speedHint('m-Q4_0.gguf', cpu), '');
});

console.log(`accel: ${pass} passed, ${fail} failed`);
if (fail) process.exit(1);
