/**
 * MISSION (acceptance) tests: real tasks run through the REAL engine + tools with a REAL bash and a REAL disk.
 * The only thing that is scripted is the model's decisions (its tool calls) — exactly what the on-device LLM does.
 * So these prove the TOOLS, the pipeline and the glue; they cannot judge how well a small phone model plans.
 *
 *   GVR_TEST_REAL=1 npx tsx tests/missions.test.ts [M1 M2 …]      (needs network: GitHub, npm, PyPI)
 */
if (process.env.GVR_TEST_REAL !== '1') throw new Error('Run with GVR_TEST_REAL=1 (real bash + real disk).');
import './stubs/install';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import AsyncStorage from './stubs/async-storage';
import * as FS from './stubs/expo-file-system-disk';
import * as Sharing from './stubs/expo-sharing';
import * as Intent from './stubs/expo-intent-launcher';
import Terminal, { __reset as resetTerm, calls as termCalls } from './stubs/terminal-real';
import { __reset as resetModel, queue, script, seen } from './stubs/localLLM';
import { requestStop, resetConversation, runWithAttachment, type StepEvent } from '../src/gvrEngine';
import { safeFileName } from '../src/agentLogic';
import { dispatchTool, refreshLinuxState, resetTerminalState, resetToolApprovals, setAutonomy, setToolConfirmHandler, toolOutcome } from '../src/tools';

export const ROOT = FS.ROOT;
export const W = path.join(ROOT, 'files', 'workspace');           // what the phone calls /workspace
export const OUT = path.join(ROOT, 'sdcard', 'Download', 'GVR');   // where deliver_file puts results
export const call = (tool: string, arg: string): string => `<tool name="${tool}">${arg}</tool>`;

/** Full (unclipped) result of every tool call of the current mission, in order. */
export const full: string[] = [];
export const toolsUsed: string[] = [];
export const lastFull = (): string => full[full.length - 1] ?? '';
const onEvent = (e: StepEvent): void => {
  if (e.type === 'tool_call') toolsUsed.push(e.tool);
  if (e.type === 'tool_result') full.push(e.result);
};

export async function resetMission(): Promise<void> {
  full.length = 0;
  toolsUsed.length = 0;
  resetModel();
  resetTerm();
  FS.__reset();
  Sharing.calls.length = 0;
  Intent.calls.length = 0;
  await AsyncStorage.clear();
  await refreshLinuxState();
  resetConversation();
  await new Promise(r => setTimeout(r, 10));
  resetToolApprovals();
  resetTerminalState();
  setAutonomy({ mode: 'auto', guard: true });
  setToolConfirmHandler(null);
}

/** One step of a scripted "model": text, or a function of (prompt, remaining plan) that may insert fallback steps. */
export type Step = string | ((p: any, rest: Step[]) => string);

/**
 * Runs one user request through the whole app. The model's replies come from `steps`, in order — a step can look at
 * the real results so far (`full`) and insert a different next step (`rest.unshift(...)`): that is how a model
 * switches to an alternative when something fails.
 */
export async function ask(userText: string, steps: Step[], attachment: any = null) {
  const rest = [...steps];
  for (let i = 0; i < 80; i++) {
    queue((p: any) => {
      const next = rest.shift();
      if (next === undefined) throw new Error('the mission plan ran out of steps');
      return typeof next === 'string' ? next : next(p, rest);
    });
  }
  const before = seen.length;
  try {
    const res = await runWithAttachment(userText, attachment, onEvent);
    return { res, modelCalls: seen.length - before };
  } finally {
    script.length = 0;                       // unused scripted replies must not leak into the next turn
  }
}

export const sh = (cmd: string, cwd = W): string => execFileSync('bash', ['-c', cmd], { cwd, encoding: 'utf8', maxBuffer: 1 << 26 }).trim();
export const attachmentFor = (name: string, kind = 'file') => {
  const safe = safeFileName(name);                          // the very function the app stages uploads with
  return { kind, name, uri: `file:///cache/${name}`, extractedContent: `${kind} summary`, warnings: [],
    workPath: `/data/user/0/com.ahmedxg.gvrchat/files/workspace/uploads/${safe}`, linuxPath: `/workspace/uploads/${safe}` };
};

/* ── tiny runner ─────────────────────────────────────────────────────── */
type Mission = { id: string; title: string; run: () => Promise<void> };
const missions: Mission[] = [];
export const mission = (id: string, title: string, run: () => Promise<void>): void => { missions.push({ id, title, run }); };

(async () => {
  await import('./missions/m6-terminal');
  await import('./missions/m1-research');
  await import('./missions/m4-images');
  await import('./missions/m5-video');
  await import('./missions/m2-build-app');
  await import('./missions/m3-apk');
  await import('./missions/m8-browse');
  const want = process.argv.slice(2).map(s => s.toUpperCase());
  let failed = 0;
  for (const m of missions) {
    if (want.length && !want.includes(m.id)) continue;
    const t0 = Date.now();
    try { await m.run(); console.log(`✔ ${m.id} ${m.title}  (${Math.round((Date.now() - t0) / 1000)}s)`); }
    catch (e) {
      failed++;
      console.error(`✗ ${m.id} ${m.title}\n    ${(e as Error).stack?.split('\n').slice(0, 8).join('\n    ')}`);
      console.error(`    tools so far: ${toolsUsed.join(' → ')}`);
      full.slice(-3).forEach((r, i) => console.error(`    last result ${i - 2}: ${r.replace(/\s+/g, ' ').slice(0, 240)}`));
    }
  }
  console.log(failed ? `\n${failed} mission(s) FAILED` : '\nall selected missions passed');
  process.exit(failed ? 1 : 0);
})();

export { assert, fs, path, Terminal, termCalls, requestStop, dispatchTool, toolOutcome, Sharing, Intent, seen };
