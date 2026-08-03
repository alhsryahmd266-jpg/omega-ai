/**
 * gvrEngine.ts — On-device reasoning engine
 * ============================================
 * Runs entirely against the local model (localLLM.ts) + real device tools
 * (tools.ts). No server, no network required except the search tool.
 *
 * Speedup techniques used here — and what they actually are:
 *
 *  1. System-prompt session caching (real, best-effort — see localLLM.ts).
 *     Saves the KV state once after the agent's system prompt is warmed up,
 *     reloads it before each step instead of re-processing those tokens.
 *
 *  2. Tree-of-Thoughts with early pruning (real). Instead of generating N
 *     full branches (expensive on a phone CPU) we generate a short PREVIEW
 *     per branch, score the previews cheaply, and only fully expand the
 *     single best branch. This is a genuine reduction in total tokens
 *     generated — not a parallel-decode claim, because llama.rn does not
 *     safely support concurrent completions on one context.
 *
 * What is NOT claimed: no fake "parallel branches", no fake terminal.
 */
import {
  generate,
  generateStream,
  trySaveSession,
  tryLoadSession,
} from './localLLM';
import { dispatchTool, AVAILABLE_TOOLS } from './tools';

export type StepEvent =
  | { type: 'tool_call'; tool: string; arg: string }
  | { type: 'tool_result'; tool: string; result: string }
  | { type: 'thought'; text: string }
  | { type: 'branch_score'; branch: number; score: number; preview: string };

export interface RunResult {
  answer: string;
  steps: { tool: string; arg: string; obs: string }[];
  score?: number;
  elapsed: number;
}

/* ── VERIFY (honest signals — no code execution on-device) ──────────────── */
function verify(answer: string, secondSample?: string): number {
  const signals: number[] = [];

  const words = answer.trim().split(/\s+/).length;
  signals.push(Math.min(words / 50, 1));

  if (secondSample) {
    const a = new Set(answer.toLowerCase().split(/\s+/));
    const b = new Set(secondSample.toLowerCase().split(/\s+/));
    const inter = [...a].filter(x => b.has(x)).length;
    const union = new Set([...a, ...b]).size;
    if (union > 0) signals.push(inter / union);
  }

  const bad = ['i cannot', "i can't", "i'm unable", 'as an ai language model'];
  const hits = bad.filter(b => answer.toLowerCase().includes(b)).length;
  signals.push(Math.max(0, 1 - hits * 0.35));

  return signals.reduce((a, b) => a + b, 0) / signals.length;
}

/* ── SYSTEM PROMPTS ───────────────────────────────────────────────────────── */
const AGENT_SYS = `You are GVR-Agent, running entirely on-device (no internet except web search).
You have these real tools: ${AVAILABLE_TOOLS.join(', ')}.

To call a tool, output EXACTLY ONE LINE:
TOOL: search <query>
TOOL: device_info
TOOL: javascript <code>
TOOL: mem_save <key>|<value>
TOOL: mem_get <key>
TOOL: mem_list

Note: there is no terminal/shell and no Python execution available on this
device build — only the tools listed above are real. Do not claim to run
shell commands.

Otherwise, write your final answer directly. Be concise and precise.`;

const AGENT_SESSION_NAME = 'agent_system_v1';

/* ── AGENT (ReAct loop with tool use) ────────────────────────────────────── */
export async function runAgent(
  task: string,
  onEvent?: (e: StepEvent) => void,
  maxSteps = 5
): Promise<RunResult> {
  const t0 = Date.now();
  let history = `Task: ${task}\n`;
  const steps: RunResult['steps'] = [];

  await tryLoadSession(AGENT_SESSION_NAME);

  for (let step = 0; step < maxSteps; step++) {
    const prompt = `${AGENT_SYS}\n\n${history}\nYour next action:`;
    const res = await generate(prompt, { temperature: 0.25, maxTokens: 220 });
    const response = res.text;

    if (step === 0) trySaveSession(AGENT_SESSION_NAME);

    const m = response.match(/TOOL:\s*(\w+)\s*(.*)/s);
    if (!m) {
      return { answer: response, steps, elapsed: (Date.now() - t0) / 1000 };
    }

    const tool = m[1];
    const arg = (m[2] || '').trim();
    onEvent?.({ type: 'tool_call', tool, arg });

    const obs = await dispatchTool(tool, arg);
    onEvent?.({ type: 'tool_result', tool, result: obs });

    steps.push({ tool, arg, obs });
    history += `\nAction: TOOL: ${tool} ${arg}\nObservation: ${obs}\n`;
  }

  const final = await generate(
    `${AGENT_SYS}\n\n${history}\nGive your final answer now (no more tools):`,
    { temperature: 0.3, maxTokens: 400 }
  );
  return { answer: final.text, steps, elapsed: (Date.now() - t0) / 1000 };
}

/* ── GVR LOOP (Generate → Verify → Refine) ───────────────────────────────── */
export async function runGVR(
  question: string,
  onEvent?: (e: StepEvent) => void,
  maxIterations = 3
): Promise<RunResult> {
  const t0 = Date.now();
  let best = '';
  let bestScore = -1;
  let refinePrefix = '';

  for (let it = 0; it < maxIterations; it++) {
    const prompt = refinePrefix + question;
    const res = await generate(prompt, { temperature: 0.5 + it * 0.1, maxTokens: 600 });

    const second = it === 0
      ? (await generate(question, { temperature: 0.9, maxTokens: 150 })).text
      : undefined;

    const score = verify(res.text, second);
    onEvent?.({ type: 'thought', text: `Iteration ${it + 1}: score ${score.toFixed(2)}` });

    if (score > bestScore) { bestScore = score; best = res.text; }
    if (score >= 0.7) break;

    refinePrefix =
      'Your previous answer needs improvement — be more complete and precise.\n\n';
  }

  return { answer: best, steps: [], score: bestScore, elapsed: (Date.now() - t0) / 1000 };
}

/* ── TREE OF THOUGHTS (real pruning, not fake parallelism) ───────────────── */
export async function runTreeOfThoughts(
  question: string,
  onEvent?: (e: StepEvent) => void,
  branches = 3
): Promise<RunResult> {
  const t0 = Date.now();

  const previews: { text: string; score: number; temp: number }[] = [];
  for (let i = 0; i < branches; i++) {
    const temp = 0.55 + i * 0.15;
    const res = await generate(
      `(Approach ${i + 1}/${branches}) Briefly outline your reasoning strategy for:\n${question}`,
      { temperature: temp, maxTokens: 60 }
    );
    const score = verify(res.text);
    previews.push({ text: res.text, score, temp });
    onEvent?.({ type: 'branch_score', branch: i + 1, score, preview: res.text });
  }

  const bestPreview = previews.reduce((a, b) => (b.score > a.score ? b : a));
  onEvent?.({ type: 'thought', text: `Expanding branch with score ${bestPreview.score.toFixed(2)}` });

  const full = await generate(
    `Continue this reasoning to a complete, final answer:\n${bestPreview.text}\n\nQuestion: ${question}\n\nFull answer:`,
    { temperature: bestPreview.temp, maxTokens: 600 }
  );

  return {
    answer: full.text,
    steps: [],
    score: verify(full.text),
    elapsed: (Date.now() - t0) / 1000,
  };
}

/* ── SELF-CONSISTENCY (real, sequential samples) ─────────────────────────── */
export async function runSelfConsistency(
  question: string,
  onEvent?: (e: StepEvent) => void,
  n = 3
): Promise<RunResult> {
  const t0 = Date.now();
  const samples: string[] = [];

  for (let i = 0; i < n; i++) {
    const res = await generate(question, { temperature: 0.5 + i * 0.15, maxTokens: 500 });
    samples.push(res.text);
    onEvent?.({ type: 'thought', text: `Sample ${i + 1}/${n} generated` });
  }

  const scores = samples.map((s, i) => {
    const wa = new Set(s.toLowerCase().split(/\s+/));
    const sims = samples
      .filter((_, j) => j !== i)
      .map(b => {
        const wb = new Set(b.toLowerCase().split(/\s+/));
        const inter = [...wa].filter(x => wb.has(x)).length;
        const union = new Set([...wa, ...wb]).size;
        return union > 0 ? inter / union : 0;
      });
    return sims.reduce((a, b) => a + b, 0) / Math.max(sims.length, 1);
  });

  const bestIdx = scores.indexOf(Math.max(...scores));
  return {
    answer: samples[bestIdx],
    steps: [],
    score: scores[bestIdx],
    elapsed: (Date.now() - t0) / 1000,
  };
}

/* ── STREAMING CHAT (fastest path — no tools, no verify) ─────────────────── */
export async function runChat(
  message: string,
  onToken: (piece: string) => void
): Promise<RunResult> {
  const t0 = Date.now();
  const res = await generateStream(message, onToken, { temperature: 0.7, maxTokens: 800 });
  return { answer: res.text, steps: [], elapsed: (Date.now() - t0) / 1000 };
}
