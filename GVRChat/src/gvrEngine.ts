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
  CORE_TOOLS, dispatchTool, isLinuxReady, memoryDigest, NATIVE_TOOLS_LEAN, nativeArgToString, nativeToolSpecs,
  normalizeToolName, toolNeedsConfirm, toolPromptLines,
} from './tools';
import { autoRoute, cleanQuery, isInfoQuestion, type RouteHit } from './router';
import { formatNowArabic, looksUncertain } from './instant';
import { PLAYBOOK } from './skills';
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
// A ≥8B model on a phone CPU produces only a few tokens/s: keep prompts and answers short or every reply takes minutes.
const BIG_MODEL_B = 8;
const N_PREDICT_BIG = 480;
const ROUTED_RESULT_CHARS_BIG = 1800;
const MAX_COMPLETION_MS = 300_000;
const MAX_COMPLETION_MS_BIG = 600_000;

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

/**
 * Date only. The system prompt (+ tool list) must stay byte-identical between messages so llama.cpp can
 * reuse its KV cache: a clock with seconds inside it forced the whole prompt to be re-read on EVERY message,
 * which on a big model on a phone meant minutes of "thinking" before the first word.
 */
function today(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

async function buildSystemPrompt(mode: PromptMode, native = false): Promise<string> {
  if (mode === 'minimal') {
    return 'You are a helpful assistant. Answer briefly and clearly in the same language as the user. Do not repeat yourself.';
  }
  const mem = await memoryDigest();
  const linux = isLinuxReady()
    ? '- A real Linux (Alpine) is available through the terminal: python3, apk, git, node, curl, ffmpeg…'
    : '';
  const date = `Today: ${today()} (for the exact time use the datetime tool or the \`date\` command)`;
  if (native) {
    // Tool definitions are injected by the chat template (native `tools`), so no tag instructions here.
    return [
      'You are GVR, an AI assistant running on the user\'s Android phone, with tools.',
      '',
      'RULES:',
      '- You do NOT reliably know current or real-world facts (news, prices, who holds an office, numbers, versions, sports, weather, "how many", "who is", "latest"). For any such question, or whenever you are not sure, call `search` FIRST and answer from the results, naming the source. Never answer those from memory and never say you cannot look things up.',
      '- Linux commands, files, scripts → `terminal` / `python`. Math → `calc`. Chit-chat, opinions, creative writing and code you can write yourself need no tool.',
      '- Missing a program? Call `install_tool` with its name: it installs it and returns how to run it (executables + --help). Then run it with `terminal`. Never give up just because something is not installed.',
      '- One tool call at a time; wait for the result. Never invent tool output. If a tool fails, try another way.',
      '- If the message already contains results the app obtained, use them; do not repeat those tools.',
      '- Ask before destructive actions (deleting, overwriting).',
      ...PLAYBOOK.map(r => '- ' + r),
      '- Reply in the user\'s language (Arabic dialects are fine). Be concise: short answers unless detail is requested. Never repeat sentences.',
      linux,
      '',
      date,
      mem ? `Saved memory: ${mem}` : '',
    ].filter(Boolean).join('\n');
  }
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
    '- If you are not sure about a fact (news, prices, people, numbers, versions), use search first instead of guessing. Chit-chat: just answer, briefly.',
    '- Never invent tool output. If a tool fails, say so and try another way or explain.',
    '- One tool call per message. Nothing after the closing tag.',
    '- If the message already contains tool results, use them to answer; do not call the same tool again.',
    '- Before destructive actions (deleting, overwriting), make sure the user asked for it.',
    '- Reply in the same language the user writes in (Arabic dialects are fine). Be concise. Never repeat sentences.',
    mode === 'full' && isLinuxReady() ? '- Missing a program? <tool name="install_tool">ffmpeg</tool> installs it and shows how to run it.' : '',
    linux,
    '',
    date,
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
  const big = !weak && (profile?.paramsB ?? 0) >= BIG_MODEL_B;
  const nPredict = weak ? N_PREDICT_WEAK : big ? N_PREDICT_BIG : N_PREDICT_FULL;
  const routedCap = big ? ROUTED_RESULT_CHARS_BIG : ROUTED_RESULT_CHARS;
  const completionLimitMs = big ? MAX_COMPLETION_MS_BIG : MAX_COMPLETION_MS;
  const warnings: string[] = attachment ? [...attachment.warnings] : [];
  const steps: AgentStep[] = [];
  let stepSeq = 0;
  const nextId = () => `s${++stepSeq}`;
  const answerParts: string[] = [];
  const question = userText.trim();

  // Capable models use native tool calling (llama.rn `tools`: Jinja template + grammar-constrained
  // tool-call JSON). Tiny/base models keep the tag protocol + the app-side router.
  let useNative = false;
  try { useNative = !weak && mode === 'full' && (profile?.supportsTools ?? true) && ctx.isJinjaSupported(); } catch { useNative = false; }
  const nativeTools = useNative ? nativeToolSpecs(big ? NATIVE_TOOLS_LEAN : undefined) : [];
  if (!nativeTools.length) useNative = false;
  let system = await buildSystemPrompt(mode, useNative);
  const toolsChars = useNative ? JSON.stringify(nativeTools).length : 0;
  const budget = Math.max(1500, charBudget(nPredict) - toolsChars);

  /* ── 1) tools the app runs on its own ────────────────────────────────── */
  const routed: RouteHit[] = autoRoute(question, { hasAttachment: !!attachment, broad: weak });
  const routedBlocks: string[] = [];
  for (const hit of routed) {
    const id = nextId();
    onEvent({ type: 'tool_call', id, tool: hit.tool, arg: hit.arg });
    if (toolNeedsConfirm(hit.tool)) onEvent({ type: 'thought', text: `⏳ بانتظار موافقتك على ${hit.tool}...` });
    const result = await dispatchTool(hit.tool, hit.arg);
    onEvent({ type: 'tool_result', id, tool: hit.tool, result });
    steps.push({ id, tool: hit.tool, arg: hit.arg, result, auto: true });
    routedBlocks.push(`[${hit.tool}${hit.arg ? `: ${clip(hit.arg, 120)}` : ''}]\n${clip(result, routedCap)}`);
  }

  /* ── 1b) answers the app can give by itself ──────────────────────────── */
  // A request a tool fully answers (the time, a sum) must never depend on a model — least of all a small or slow one.
  let finalText = '';
  let skipModel = false;
  let showRaw = weak;
  if (!attachment && routed.length === 1 && question.length <= 80) {
    const only = routed[0];
    const got = steps[steps.length - 1];
    if (only.tool === 'datetime') {
      finalText = formatNowArabic();
      skipModel = true;
      showRaw = false;
    } else if (only.tool === 'calc' && got && !/error|invalid|خطأ/i.test(got.result)) {
      finalText = `🧮 ${got.result.includes('=') ? got.result : `${only.arg} = ${got.result}`}`;
      skipModel = true;
      showRaw = false;
    }
  }
  if (!skipModel && weak && !attachment && routed.length > 0) {
    // A tiny/base model only garbles tool output: show the results directly.
    finalText = 'النموذج المحمّل صغير، فبعرض لك نتيجة الأدوات مباشرة بدل ما يلخّصها غلط. حمّل نموذج Instruct أكبر (4B+) عشان يلخّص ويفكّر.';
    skipModel = true;
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

  // Two encodings of the same conversation so we can fall back from native tools at any round.
  const turnLegacy: RNLlamaOAICompatibleMessage[] = [userMsg];
  const turnNative: RNLlamaOAICompatibleMessage[] = [userMsg];
  const activeTurn = () => (useNative ? turnNative : turnLegacy);
  const sampling = samplingFor(profile);
  const stopBase = [...STOP_WORDS];
  let slowestTps: number | null = null;
  let autoSearched = false;
  // Auto-routed results already count as tool use for the model.
  const maxRounds = routed.length ? Math.max(1, maxToolRounds - 2) : maxToolRounds;

  /* ── 3) model loop (the model may request more tools) ────────────────── */
  for (let round = 0; !skipModel && round <= maxRounds; round++) {
    const lastRound = round === maxRounds;
    const nativeRound = useNative && !lastRound;
    onEvent({ type: 'thought', text: round === 0 ? 'يفكر...' : 'بيراجع النتيجة...' });

    const messages: RNLlamaOAICompatibleMessage[] = [
      { role: 'system', content: system },
      ...histCopy,
      ...activeTurn(),
    ];
    if (lastRound && round > 0) {
      messages.push({ role: 'user', content: 'Do not call any more tools. Answer the user now using what you already have.' });
    }

    let acc = '';
    let tokens = 0;
    let looped = false;
    let timedOut = false;
    let firstTokenAt = 0;
    const roundStart = Date.now();
    // Without feedback a slow model looks frozen: show prefill progress, then a live tail with tokens/s.
    const ticker = setInterval(() => {
      if (!firstTokenAt) {
        const secs = Math.round((Date.now() - roundStart) / 1000);
        onEvent({ type: 'thought', text: `بيقرا السؤال والأدوات... ${secs}ث${big ? ' — النموذج كبير، أول رد ممكن ياخد دقايق' : ''}` });
      }
    }, 3000);
    // Never hang forever: if the model is this slow, cut it and say why.
    const watchdog = setTimeout(() => { timedOut = true; ctx.stopCompletion().catch(() => {}); }, completionLimitMs);
    let result: Awaited<ReturnType<typeof ctx.completion>>;
    try {
      result = await ctx.completion(
        {
          messages,
          n_predict: nPredict,
          // repetition penalties must not bend tool-call JSON (paths/commands legitimately repeat)
          ...(nativeRound ? { ...sampling, temperature: 0.35, penalty_repeat: 1.04, dry_multiplier: 0 } : sampling),
          enable_thinking: false,
          ...(nativeRound ? { jinja: true, tools: nativeTools, tool_choice: 'auto' } : {}),
          stop: nativeRound || lastRound || mode === 'minimal' ? stopBase : [...stopBase, '</tool>', '</tool_call>'],
        },
        (data) => {
          if (!firstTokenAt) firstTokenAt = Date.now();
          acc += data.token;
          tokens++;
          if (tokens % 10 === 0 && !looped && detectLoop(acc)) {
            looped = true;
            ctx.stopCompletion().catch(() => {});
          }
          if (tokens % 6 === 0) {
            const rate = tokens / Math.max(1, (Date.now() - firstTokenAt) / 1000);
            const tail = acc.replace(/\s+/g, ' ').slice(-60);
            onEvent({ type: 'thought', text: `✍️ …${tail}  (${tokens} توكن · ${rate.toFixed(1)}/ث)` });
          }
        },
      );
    } catch (e: any) {
      clearInterval(ticker);
      clearTimeout(watchdog);
      if (nativeRound) {
        // This model/template can't do native tools: fall back to the tag protocol and redo this round.
        useNative = false;
        system = await buildSystemPrompt(mode, false);
        warnings.push('الأدوات المهيكلة مش شغّالة مع النموذج ده — رجعت للطريقة التقليدية.');
        round -= 1;
        continue;
      }
      throw new Error(`فشل التوليد: ${e?.message || e}`);
    }

    clearInterval(ticker);
    clearTimeout(watchdog);
    if (timedOut) warnings.push('الرد اتقطع لأن النموذج بطيء جداً على الجهاز — جرّب نموذج أصغر (4–9B).');
    const tps = result.timings?.predicted_per_second;
    if (typeof tps === 'number' && tps > 0 && (result.tokens_predicted ?? 0) >= 24) {
      slowestTps = slowestTps === null ? tps : Math.min(slowestTps, tps);
    }

    const nativeCall = nativeRound && Array.isArray(result.tool_calls) && result.tool_calls.length > 0
      ? result.tool_calls[0]
      : null;
    const textOut = nativeRound
      ? ((result.content || '').trim() ? result.content : nativeCall ? '' : result.text || '')
      : result.text || '';
    let raw = stripThinking(textOut);
    if (looped) {
      raw = collapseLoop(raw);
      warnings.push('النموذج دخل في تكرار فاتوقّفته. جرّب نموذج Instruct أكبر لنتيجة أحسن.');
    }

    let call: ParsedCall | null = null;
    let callId = '';
    if (nativeCall) {
      let parsedArgs: unknown = nativeCall.function.arguments;
      try { parsedArgs = JSON.parse(nativeCall.function.arguments || '{}'); } catch { /* keep the raw string */ }
      const toolName = normalizeToolName(nativeCall.function.name);
      call = { before: stripToolMarkup(raw).trim(), tool: toolName, arg: nativeArgToString(toolName, parsedArgs).trim() };
      callId = nativeCall.id || `call_${round}`;
    } else if (!(lastRound || mode === 'minimal')) {
      call = parseToolCall(raw); // tag / Hermes text form (also catches models that ignore native tools)
    }
    if (!call && !weak && !lastRound && !autoSearched && !attachment && steps.length === 0
        && isInfoQuestion(question) && looksUncertain(raw)) {
      // "Sorry, I don't know…" is not an answer: go and look it up on the web instead.
      autoSearched = true;
      call = { before: '', tool: 'search', arg: cleanQuery(question) || question };
    }
    if (!call) {
      finalText = stripToolMarkup(raw);
      if (result.context_full) warnings.push('الـ context امتلى — الرد ممكن يكون ناقص.');
      break;
    }

    if (call.before) answerParts.push(call.before);
    const id = nextId();
    onEvent({ type: 'tool_call', id, tool: call.tool, arg: call.arg });
    if (toolNeedsConfirm(call.tool)) onEvent({ type: 'thought', text: `⏳ بانتظار موافقتك على تشغيل ${call.tool}...` });
    const toolResult = await dispatchTool(call.tool, call.arg);
    onEvent({ type: 'tool_result', id, tool: call.tool, result: toolResult });
    steps.push({ id, tool: call.tool, arg: call.arg, result: toolResult });

    turnLegacy.push({ role: 'assistant', content: `${call.before ? `${call.before}\n` : ''}<tool name="${call.tool}">${call.arg}</tool>` });
    turnLegacy.push({ role: 'user', content: `<tool_result name="${call.tool}">\n${toolResult.slice(0, TOOL_RESULT_CHARS)}\n</tool_result>` });
    if (nativeCall) {
      turnNative.push({
        role: 'assistant',
        content: call.before || '',
        tool_calls: [{ type: 'function', id: callId, function: { name: nativeCall.function.name, arguments: nativeCall.function.arguments } }],
      } as unknown as RNLlamaOAICompatibleMessage);
      turnNative.push({
        role: 'tool',
        tool_call_id: callId,
        name: nativeCall.function.name,
        content: toolResult.slice(0, TOOL_RESULT_CHARS),
      } as unknown as RNLlamaOAICompatibleMessage);
    } else {
      turnNative.push(turnLegacy[turnLegacy.length - 2], turnLegacy[turnLegacy.length - 1]);
    }
    for (const t of [turnLegacy, turnNative]) {
      while (t.length > 3 && totalChars(t) > budget * 0.7) t.splice(1, 2);
    }
  }

  if (slowestTps !== null && slowestTps < 5) {
    warnings.push(`⏱ سرعة التوليد ~${slowestTps.toFixed(1)} توكن/ث. لو بطيء عليك جرّب نموذج أصغر (4–9B) للـ agent.`);
  }

  /* ── 4) assemble the answer ──────────────────────────────────────────── */
  if (finalText) answerParts.push(finalText);
  let answer = answerParts.join('\n\n').trim();

  // Weak / base models often can't summarise tool output: always show the raw results too.
  if (showRaw && steps.length) {
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
