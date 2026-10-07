/**
 * gvrEngine.ts — GVR agent loop
 * ==============================
 * Two ways a tool gets used:
 *  1. AUTO-ROUTED by the app (router.ts): obvious requests ("open the terminal",
 *     "search for X", a pasted URL, a shell command) run BEFORE the model, and the
 *     results are handed to it. This works even with tiny / non-instruct models.
 *  2. REQUESTED by the model, by writing one tag and stopping:
 *         <tool name="terminal">ls -la</tool>
 *     (the Hermes/Qwen form <tool_call>{"name":..,"arguments":{..}}</tool_call> is accepted too)
 *
 * Generation is protected against runaway repetition: sampling penalties + DRY,
 * end-of-turn stop words, a hard token cap, and a live loop detector that stops
 * the model the moment it starts repeating itself.
 */
import type { RNLlamaOAICompatibleMessage } from 'llama.rn';
import {
  collapseLoop, detectLoop, getContext, getLoadedContextSize, getModelProfile, isModelLoaded,
  isVisionReady, samplingFor, STOP_WORDS, stripThinking, withGenerationLock,
} from './localLLM';
import {
  CORE_TOOLS, dispatchTool, isLinuxReady, memoryDigest, normalizeToolName, toolPromptLines,
} from './tools';
import { autoRoute, type RouteHit } from './router';
import type { PreparedAttachment } from './attachments';

/* ── TYPES (App.tsx depends on these exact shapes) ─────────────────────── */
export type StepEvent =
  | { type: 'thought'; text: string }
  | { type: 'tool_call'; id: string; tool: string; arg: string }
  | { type: 'tool_result'; id: string; tool: string; result: string }
  | { type: 'branch_score'; branch: string | number; score: number };

export interface AgentStep {
  id: string;
  tool: string;
  arg: string;
  result: string;
  /** true when the app ran it automatically (router), not the model */
  auto?: boolean;
}

export interface AgentResult {
  answer: string;
  steps: AgentStep[];
  elapsed: number;
  score?: number;
  attachmentWarnings: string[];
}

/* ── CONSTANTS ─────────────────────────────────────────────────────────── */
const N_PREDICT_FULL = 900;
const N_PREDICT_WEAK = 450;
const TOOL_RESULT_CHARS = 3000;
const ROUTED_RESULT_CHARS = 3500;
const HISTORY_MAX_MESSAGES = 12;
const CHARS_PER_TOKEN = 2.8;

const TOOL_TAG_RE = /<tool\s+name\s*=\s*["']?([a-zA-Z_]+)["']?\s*>([\s\S]*?)(?:<\/tool>|$)/;
const STRAY_TOOL_RE = /<tool\s+name\s*=\s*["']?[a-zA-Z_]+["']?\s*>[\s\S]*?(?:<\/tool>|$)/g;
const HERMES_RE = /<tool_call>\s*(\{[\s\S]*?\})\s*(?:<\/tool_call>|$)/;
const STRAY_HERMES_RE = /<tool_call>[\s\S]*?(?:<\/tool_call>|$)/g;

/* ── CONVERSATION STATE ────────────────────────────────────────────────── */
let history: RNLlamaOAICompatibleMessage[] = [];

export function resetConversation(): void {
  history = [];
}

const clip = (s: string, n: number): string => (s.length > n ? `${s.slice(0, n)}\n…[truncated]` : s);

/* ── SYSTEM PROMPT ─────────────────────────────────────────────────────── */
type PromptMode = 'minimal' | 'compact' | 'full';

function promptMode(): PromptMode {
  const p = getModelProfile();
  if (!p) return 'full';
  if (p.looksBase) return 'minimal';
  if (p.tier === 'tiny') return 'compact';
  return 'full';
}

async function buildSystemPrompt(mode: PromptMode): Promise<string> {
  const now = new Date().toString();
  if (mode === 'minimal') {
    return 'You are a helpful assistant. Answer briefly and clearly in the same language as the user. Do not repeat yourself.';
  }
  const mem = await memoryDigest();
  const tools = toolPromptLines(mode === 'compact' ? CORE_TOOLS : undefined);
  return [
    'You are GVR, an AI assistant running entirely on the user\'s Android phone, with tools.',
    '',
    'To use a tool, write exactly ONE tool call and then stop and wait for the result:',
    '<tool name="TOOL_NAME">argument</tool>',
    'The result comes back in the next message. Then continue, or answer the user.',
    '',
    'Example:',
    'User: ابحث عن أحدث إصدار من أندرويد',
    'Assistant: <tool name="search">أحدث إصدار من أندرويد</tool>',
    '',
    'TOOLS:',
    tools,
    '',
    'RULES:',
    '- Use a tool only when it is really needed. Simple questions: just answer, briefly.',
    '- Never invent tool output. If a tool fails, say so and try another way or explain.',
    '- One tool call per message. Nothing after the closing tag.',
    '- If the message already contains tool results, use them to answer; do not call the same tool again.',
    '- Before destructive actions (deleting, overwriting), make sure the user asked for it.',
    '- Reply in the same language the user writes in (Arabic dialects are fine). Be concise. Never repeat sentences.',
    isLinuxReady() ? '- A real Linux (Alpine) is available: python3, apk, git, node can be used through the terminal.' : '',
    '',
    `Current time: ${now}`,
    mem ? `Saved memory: ${mem}` : '',
  ].filter(Boolean).join('\n');
}

/* ── CONTEXT BUDGET ────────────────────────────────────────────────────── */
function contentChars(m: RNLlamaOAICompatibleMessage): number {
  if (typeof m.content === 'string') return m.content.length;
  if (Array.isArray(m.content)) return m.content.reduce((n, p) => n + (p.text ? p.text.length : 300), 0);
  return 0;
}
const totalChars = (msgs: RNLlamaOAICompatibleMessage[]): number => msgs.reduce((n, m) => n + contentChars(m), 0);

function charBudget(nPredict: number): number {
  const nCtx = getLoadedContextSize() || 4096;
  return Math.floor(Math.max(512, nCtx - nPredict - 200) * CHARS_PER_TOKEN);
}

/* ── TOOL-CALL PARSING ─────────────────────────────────────────────────── */
interface ParsedCall { before: string; tool: string; arg: string }

function hermesArg(tool: string, args: unknown): string {
  if (typeof args === 'string') return args;
  if (!args || typeof args !== 'object') return '';
  const o = args as Record<string, unknown>;
  const pick = (...keys: string[]) => keys.map(k => o[k]).find(v => typeof v === 'string') as string | undefined;
  switch (normalizeToolName(tool)) {
    case 'terminal': return pick('command', 'cmd', 'script') ?? JSON.stringify(o);
    case 'search': return pick('query', 'q', 'text') ?? JSON.stringify(o);
    case 'fetch_url': return Object.keys(o).length === 1 && typeof o.url === 'string' ? o.url : JSON.stringify(o);
    case 'calc': return pick('expression', 'expr', 'query') ?? JSON.stringify(o);
    case 'python': return pick('code', 'script') ?? JSON.stringify(o);
    case 'javascript': return pick('code', 'script') ?? JSON.stringify(o);
    case 'read_file': case 'list_dir': case 'delete_file': case 'inspect_file':
      return pick('path', 'file', 'dir') ?? JSON.stringify(o);
    case 'write_file': {
      const path = pick('path', 'file');
      const content = pick('content', 'text', 'data');
      return path !== undefined ? `${path}\n${content ?? ''}` : JSON.stringify(o);
    }
    default: {
      const first = Object.values(o).find(v => typeof v === 'string') as string | undefined;
      return first ?? JSON.stringify(o);
    }
  }
}

export function parseToolCall(raw: string): ParsedCall | null {
  const m = TOOL_TAG_RE.exec(raw);
  if (m) return { before: raw.slice(0, m.index).trim(), tool: normalizeToolName(m[1]), arg: (m[2] || '').trim() };
  const h = HERMES_RE.exec(raw);
  if (h) {
    try {
      const j = JSON.parse(h[1]);
      const name = String(j.name || j.tool || '');
      if (name) {
        return {
          before: raw.slice(0, h.index).trim(),
          tool: normalizeToolName(name),
          arg: hermesArg(name, j.arguments ?? j.args ?? j.parameters).trim(),
        };
      }
    } catch { /* malformed JSON: ignore */ }
  }
  return null;
}

const stripToolMarkup = (s: string): string => s.replace(STRAY_TOOL_RE, '').replace(STRAY_HERMES_RE, '').trim();

/* ── MAIN ENTRY ────────────────────────────────────────────────────────── */
export async function runWithAttachment(
  userText: string,
  attachment: PreparedAttachment | null,
  onEvent: (e: StepEvent) => void,
  maxToolRounds = 5,
): Promise<AgentResult> {
  if (!isModelLoaded()) throw new Error('مفيش نموذج متحمّل — حمّل نموذج من الإعدادات الأول.');
  return withGenerationLock(() => runLocked(userText, attachment, onEvent, maxToolRounds));
}

async function runLocked(
  userText: string,
  attachment: PreparedAttachment | null,
  onEvent: (e: StepEvent) => void,
  maxToolRounds: number,
): Promise<AgentResult> {
  const t0 = Date.now();
  const ctx = getContext();
  const profile = getModelProfile();
  const weak = !!profile && (profile.tier === 'tiny' || profile.looksBase);
  const mode = promptMode();
  const nPredict = weak ? N_PREDICT_WEAK : N_PREDICT_FULL;
  const warnings: string[] = attachment ? [...attachment.warnings] : [];
  const steps: AgentStep[] = [];
  let stepSeq = 0;
  const nextId = () => `s${++stepSeq}`;
  const answerParts: string[] = [];
  const question = userText.trim();

  const system = await buildSystemPrompt(mode);
  const budget = charBudget(nPredict);

  /* ── 1) tools the app runs on its own ────────────────────────────────── */
  const routed: RouteHit[] = autoRoute(question, { hasAttachment: !!attachment });
  const routedBlocks: string[] = [];
  for (const hit of routed) {
    const id = nextId();
    onEvent({ type: 'tool_call', id, tool: hit.tool, arg: hit.arg });
    const result = await dispatchTool(hit.tool, hit.arg);
    onEvent({ type: 'tool_result', id, tool: hit.tool, result });
    steps.push({ id, tool: hit.tool, arg: hit.arg, result, auto: true });
    routedBlocks.push(`[${hit.tool}${hit.arg ? `: ${clip(hit.arg, 120)}` : ''}]\n${clip(result, ROUTED_RESULT_CHARS)}`);
  }

  /* ── 2) build the user message ───────────────────────────────────────── */
  let histCopy = [...history];
  while (histCopy.length > 0 && totalChars(histCopy) > budget * 0.4) histCopy = histCopy.slice(2);

  const routedText = routedBlocks.length
    ? `\n\n[Results the app already obtained for this request — use them to answer; do not call these tools again]\n${routedBlocks.join('\n\n')}`
    : '';

  let attachmentText = '';
  if (attachment && attachment.extractedContent) {
    const room = Math.max(
      500,
      budget - system.length - totalChars(histCopy) - question.length - routedText.length - 400,
    );
    attachmentText = attachment.extractedContent;
    if (attachmentText.length > room) {
      attachmentText = attachmentText.slice(0, room);
      warnings.push(`المرفق اتقصّ لأول ${room} حرف عشان يناسب حجم الـ context.`);
    }
  }
  const header = attachment
    ? `[Attached ${attachment.kind}: ${attachment.name}]\n${attachmentText ? `${attachmentText}\n[End of attachment]\n\n` : ''}`
    : '';

  let userMsg: RNLlamaOAICompatibleMessage;
  if (attachment?.kind === 'image' && isVisionReady()) {
    const url = attachment.uri.startsWith('/') ? `file://${attachment.uri}` : attachment.uri;
    userMsg = {
      role: 'user',
      content: [
        { type: 'text', text: `${header}${question}${routedText}` },
        { type: 'image_url', image_url: { url } },
      ],
    };
  } else {
    if (attachment?.kind === 'image' && !isVisionReady()) {
      warnings.push('الصورة متحلّلتش: محتاج نموذج رؤية + ملف mmproj.');
    }
    userMsg = { role: 'user', content: `${header}${question}${routedText}` };
  }

  const turn: RNLlamaOAICompatibleMessage[] = [userMsg];
  const sampling = samplingFor(profile);
  const stopBase = [...STOP_WORDS];
  let finalText = '';
  // Auto-routed results already count as tool use for the model.
  const maxRounds = routed.length ? Math.max(1, maxToolRounds - 2) : maxToolRounds;

  /* ── 3) model loop (the model may request more tools) ────────────────── */
  for (let round = 0; round <= maxRounds; round++) {
    const lastRound = round === maxRounds;
    onEvent({ type: 'thought', text: round === 0 ? 'يفكر...' : 'بيراجع النتيجة...' });

    const messages: RNLlamaOAICompatibleMessage[] = [
      { role: 'system', content: system },
      ...histCopy,
      ...turn,
    ];
    if (lastRound && round > 0) {
      messages.push({ role: 'user', content: 'Do not call any more tools. Answer the user now using what you already have.' });
    }

    let acc = '';
    let tokens = 0;
    let looped = false;
    let result;
    try {
      result = await ctx.completion(
        {
          messages,
          n_predict: nPredict,
          ...sampling,
          enable_thinking: false,
          stop: lastRound || mode === 'minimal' ? stopBase : [...stopBase, '</tool>', '</tool_call>'],
        },
        (data) => {
          acc += data.token;
          tokens++;
          if (tokens % 10 === 0) {
            if (!looped && detectLoop(acc)) {
              looped = true;
              ctx.stopCompletion().catch(() => {});
            }
            if (tokens % 30 === 0) onEvent({ type: 'thought', text: `يكتب الرد... (${tokens})` });
          }
        },
      );
    } catch (e: any) {
      throw new Error(`فشل التوليد: ${e?.message || e}`);
    }

    let raw = stripThinking(result.text || '');
    if (looped) {
      raw = collapseLoop(raw);
      warnings.push('النموذج دخل في تكرار فاتوقّفته. جرّب نموذج Instruct أكبر لنتيجة أحسن.');
    }

    const call = lastRound || mode === 'minimal' ? null : parseToolCall(raw);
    if (!call) {
      finalText = stripToolMarkup(raw);
      if (result.context_full) warnings.push('الـ context امتلى — الرد ممكن يكون ناقص.');
      break;
    }

    if (call.before) answerParts.push(call.before);
    const id = nextId();
    onEvent({ type: 'tool_call', id, tool: call.tool, arg: call.arg });
    const toolResult = await dispatchTool(call.tool, call.arg);
    onEvent({ type: 'tool_result', id, tool: call.tool, result: toolResult });
    steps.push({ id, tool: call.tool, arg: call.arg, result: toolResult });

    turn.push({ role: 'assistant', content: `${call.before ? `${call.before}\n` : ''}<tool name="${call.tool}">${call.arg}</tool>` });
    turn.push({ role: 'user', content: `<tool_result name="${call.tool}">\n${toolResult.slice(0, TOOL_RESULT_CHARS)}\n</tool_result>` });
    while (turn.length > 3 && totalChars(turn) > budget * 0.7) turn.splice(1, 2);
  }

  /* ── 4) assemble the answer ──────────────────────────────────────────── */
  if (finalText) answerParts.push(finalText);
  let answer = answerParts.join('\n\n').trim();

  // Weak / base models often can't summarise tool output: always show the raw results too.
  if (weak && steps.length) {
    const raws = steps.map(s => `▸ ${s.tool}${s.arg ? ` (${clip(s.arg, 60)})` : ''}:\n${clip(s.result, 900)}`).join('\n\n');
    answer = `${answer}${answer ? '\n\n' : ''}— نتائج الأدوات —\n${raws}`.trim();
  }
  if (!answer) {
    const last = steps[steps.length - 1];
    answer = last
      ? `(النموذج ما ردّش بنص بعد الأداة.) آخر نتيجة من ${last.tool}:\n${clip(last.result, 800)}`
      : '(النموذج ما رجّعش رد. جرّب تعيد الصياغة أو تجرب نموذج تاني.)';
  }

  history.push({
    role: 'user',
    content: attachment ? `[Attached ${attachment.kind}: ${attachment.name}] ${question}` : question,
  });
  history.push({ role: 'assistant', content: clip(answer, 2500) });
  if (history.length > HISTORY_MAX_MESSAGES) history = history.slice(-HISTORY_MAX_MESSAGES);

  return {
    answer,
    steps,
    elapsed: Math.round((Date.now() - t0) / 100) / 10,
    attachmentWarnings: warnings,
  };
}
