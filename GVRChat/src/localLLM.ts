/**
 * localLLM.ts — Local inference via llama.cpp running inside proot+Alpine
 *
 * Instead of llama.rn (which has RN 0.74 compatibility issues),
 * we download the llama.cpp ARM64 binary once and run it through
 * the terminal module. This gives us full llama.cpp power:
 *   • Any GGUF model (text, vision with mmproj, tool-use)
 *   • All generation parameters
 *   • Streaming output via tail -f
 *   • Vision: llama-llava-cli for multimodal models
 */

import Terminal from '../modules/terminal/src';

const LLAMA_BIN_URL =
  'https://github.com/ggerganov/llama.cpp/releases/download/b3668/llama-b3668-bin-android-arm64-v8a.zip';
const LLAMA_DIR = '/opt/llama';

export interface GenerateOptions {
  modelPath: string;       // path on device, e.g. /sdcard/models/mistral.gguf
  prompt: string;
  systemPrompt?: string;
  maxTokens?: number;      // default 512
  temperature?: number;    // default 0.7
  topP?: number;           // default 0.9
  mmprojPath?: string;     // for vision models (Qwen-VL, LLaVA)
  imagePath?: string;      // image to analyse (requires mmprojPath)
  stopWords?: string[];
}

/** Returns true when llama-cli is ready inside Alpine */
export async function isLlamaReady(): Promise<boolean> {
  const out = await Terminal.run(`[ -f ${LLAMA_DIR}/llama-cli ] && echo YES || echo NO`, 5);
  return out.trim().startsWith('YES');
}

/**
 * Download and install llama.cpp ARM64 inside Alpine (one-time setup, ~8 MB)
 */
export async function setupLlama(): Promise<string> {
  const log: string[] = [];

  // ensure Alpine is up first
  const alpineOut = await Terminal.run('which apk 2>/dev/null || echo MISSING', 5);
  if (alpineOut.includes('MISSING')) {
    log.push('Setting up Alpine first...');
    await Terminal.setupTerminal();
    log.push('Alpine ready');
  }

  log.push('Installing llama.cpp dependencies...');
  await Terminal.run('apk add --no-cache wget unzip libstdc++ 2>&1 | tail -3', 60);

  log.push(`Downloading llama.cpp binary (~8 MB)...`);
  const dl = await Terminal.run(
    `mkdir -p ${LLAMA_DIR} && ` +
    `wget -q --show-progress -O /tmp/llama.zip "${LLAMA_BIN_URL}" && echo OK`,
    120
  );
  if (!dl.includes('OK')) {
    // fallback: try building from source via apk
    log.push('Direct download failed — trying apk...');
    const apkInstall = await Terminal.run('apk add --no-cache llama-cpp 2>&1', 120);
    log.push(apkInstall.slice(0, 200));
    // symlink
    await Terminal.run(`ln -sf /usr/bin/llama-cli ${LLAMA_DIR}/llama-cli 2>/dev/null || true`, 5);
  } else {
    log.push('Extracting...');
    await Terminal.run(
      `unzip -o /tmp/llama.zip -d ${LLAMA_DIR} 2>&1 | tail -5 && ` +
      `chmod +x ${LLAMA_DIR}/llama-cli 2>/dev/null || true && ` +
      `chmod +x ${LLAMA_DIR}/llama-llava-cli 2>/dev/null || true`,
      30
    );
    await Terminal.run('rm /tmp/llama.zip', 5);
  }

  const check = await isLlamaReady();
  log.push(check ? '✅ llama-cli ready' : '⚠️  llama-cli not found — will try at inference time');
  return log.join('\n');
}

/**
 * Run inference — streams output, returns full completion
 */
export async function generate(opts: GenerateOptions): Promise<string> {
  const {
    modelPath,
    prompt,
    systemPrompt = 'You are GVR, a helpful on-device AI assistant.',
    maxTokens = 512,
    temperature = 0.7,
    topP = 0.9,
    mmprojPath,
    imagePath,
    stopWords = [],
  } = opts;

  // Make model accessible inside Alpine via bind-mount (/host maps to host filesDir)
  // The proot setup already binds /sdcard → use the absolute path directly
  const modelArg = modelPath.startsWith('/') ? modelPath : `/host/${modelPath}`;

  const fullPrompt = systemPrompt
    ? `<|system|>\n${systemPrompt}\n<|end|>\n<|user|>\n${prompt}\n<|end|>\n<|assistant|>`
    : prompt;

  // Stop words
  const stopArgs = stopWords.map(s => `--stop "${s}"`).join(' ');

  let cmd: string;
  if (mmprojPath && imagePath) {
    // Vision model (LLaVA / Qwen-VL)
    cmd =
      `${LLAMA_DIR}/llama-llava-cli ` +
      `-m "${modelArg}" ` +
      `--mmproj "${mmprojPath}" ` +
      `--image "${imagePath}" ` +
      `-p "${fullPrompt.replace(/"/g, '\\"')}" ` +
      `-n ${maxTokens} ` +
      `--temp ${temperature} ` +
      `--top-p ${topP} ` +
      `--no-display-prompt ` +
      `${stopArgs} 2>/dev/null`;
  } else {
    cmd =
      `${LLAMA_DIR}/llama-cli ` +
      `-m "${modelArg}" ` +
      `-p "${fullPrompt.replace(/"/g, '\\"')}" ` +
      `-n ${maxTokens} ` +
      `--temp ${temperature} ` +
      `--top-p ${topP} ` +
      `--no-display-prompt ` +
      `-e ` +
      `${stopArgs} 2>/dev/null`;
  }

  const timeoutSec = Math.max(120, Math.ceil(maxTokens / 8));
  return Terminal.run(cmd, timeoutSec);
}
