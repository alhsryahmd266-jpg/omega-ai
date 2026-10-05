/**
 * gvrEngine.ts — GVR agent loop
 * ==============================
 * The model asks for a tool by writing exactly one tag and stopping:
 *
 *     <tool name="terminal">ls -la</tool>
 *
 * The engine runs the tool, feeds the result back as the next message, and
 * lets the model continue until it answers without a tool call (or the round
 * limit is reached). The conversation is kept in memory between calls so the
 * model sees previous turns; resetConversation() clears it.
 */
import type { RNLlamaOAICompatibleMessage } from 'llama.rn';
import {
  getContext, getLoadedContextSize, isModelLoaded, isVisionReady,
  stripThinking, withGenerationLock,
} from './localLLM';
import { dispatchTool, memoryDigest, normalizeToolName, toolPromptLines } from './tools';
import type { PreparedAttachment } from './attachments';

/* ── TYPES (App.tsx depends on these exact shapes) ─────────────────────── */
export type StepEvent =
  | { type: 'thought'; text: string }
  | { type: 'tool_call'; tool: string; arg: string }
  | { type: 'tool_result'; tool: string; result: string }
  | { type: 'branch_score'; branch: string | number; score: number };

export interface AgentStep {
  tool: string;
  arg: string;
  result: string;
}

export interface AgentResult {
  answer: string;
  steps: AgentStep[];
  elapsed: number;
  score?: number;
  attachmentWarnings: string[];
}

/* ── CONSTANTS ─────────────────────────────────────────────────────────── */
const N_PREDICT = 1024;
const TOOL_RESULT_CHARS = 3000;
const HISTORY_MAX_MESSAGES = 12;
const CHARS_PER_TOKEN = 2.8; // conservative (Arabic + code tokenise poorly)

const TOOL_TAG_RE = /<tool\s+name\s*=\s*["']?([a-zA-Z_]+)["']?\s*>([\s\S]*?)(?:<\/tool>|$)/;
// Same shape, global: used to remove tool tags that must never reach the user.
const STRAY_TOOL_RE = /<tool\s+name\s*=\s*["']?[a-zA-Z_]+["']?\s*>[\s\S]*?(?:<\/tool>|$)/g;

/* ── CONVERSATION STATE ────────────────────────────────────────────────── */
let history: RNLlamaOAICompatibleMessage[] = [];

export function resetConversation(): void {
  history = [];
}

/* ── SYSTEM PROMPT ─────────────────────────────────────────────────────── */
async function buildSystemPrompt(): Promise<string> {
  const mem = await memoryDigest();
  const now = new Date().toString();
  return [
    'You are GVR, an AI assistant running entirely on the user\'s Android phone, with tools.',
    '',
    'To use a tool, write exactly ONE tool call and then stop and wait for the result:',
    '<tool name="TOOL_NAME">argument</tool>',
    'The result comes back in the next message. Then continue, or answer the user.',
    '',
    'TOOLS:',
    toolPromptLines(),
    '',
    'RULES:',
    '- Use a tool only when it is really needed. Simple questions: just answer.',
    '- Never invent tool output. If a tool fails, say so and try another way or explain.',
    '- One tool call per message. Do not write anything after the closing tag.',
    '- Before destructive actions (deleting, overwriting), make sure the user asked for it.',
    '- Reply in the same language the user writes in (Arabic dialects are fine). Be concise.',
    '',
    `Current time: ${now}`,
    mem ? `Saved memory: ${mem}` : '',
  ].filter(Boolean).join('\n');
}

/* ── CONTEXT BUDGET ────────────────────────────────────────────────────── */
function contentChars(m: RNLlamaOAICompatibleMessage): number {
  if (typeof m.content === 'string') return m.content.length;
  if (Array.isArray(m.content)) {
    return m.content.reduce((n, p) => n + (p.text ? p.text.length : 300), 0);
  }
  return 0;
}

function totalChars(msgs: RNLlamaOAICompatibleMessage[]): number {
  return msgs.reduce((n, m) => n + contentChars(m), 0);
}

function charBudget(): number {
  const nCtx = getLoadedContextSize() || 4096;
  return Math.floor(Math.max(512, nCtx - N_PREDICT - 200) * CHARS_PER_TOKEN);
}

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
  const warnings: string[] = attachment ? [...attachment.warnings] : [];
  const steps: AgentStep[] = [];
  const answerParts: string[] = [];

  const system = await buildSystemPrompt();

  /* ── build the user message (text + attachment) ──────────────────────── */
  const budget = charBudget();
  const sysChars = system.length;
  let histCopy = [...history];
  // Drop the oldest turns until the history fits in ~40% of the budget.
  while (histCopy.length > 0 && totalChars(histCopy) > budget * 0.4) histCopy = histCopy.slice(2);

  let attachmentText = '';
  if (attachment && attachment.extractedContent) {
    const room = Math.max(
      500,
      budget - sysChars - totalChars(histCopy) - userText.length - 400,
    );
    attachmentText = attachment.extractedContent;
    if (attachmentText.length > room) {
      attachmentText = attachmentText.slice(0, room);
      warnings.push(`المرفق اتقصّ لأول ${room} حرف عشان يناسب حجم الـ context.`);
    }
  }

  const header = attachment
    ? `[Attached ${attachment.kind}: ${attachment.name}]\n${attachmentText ? attachmentText + '\n[End of attachment]\n\n' : ''}`
    : '';
  const question = userText.trim();

  let userMsg: RNLlamaOAICompatibleMessage;
  if (attachment?.kind === 'image' && isVisionReady()) {
    const url = attachment.uri.startsWith('/') ? `file://${attachment.uri}` : attachment.uri;
    userMsg = {
      role: 'user',
      content: [
        { type: 'text', text: `${header}${question}` },
        { type: 'image_url', image_url: { url } },
      ],
    };
  } else {
    if (attachment?.kind === 'image' && !isVisionReady()) {
      warnings.push('الصورة متحلّلتش: محتاج نموذج رؤية + ملف mmproj.');
    }
    userMsg = { role: 'user', content: `${header}${question}` };
  }

  const turn: RNLlamaOAICompatibleMessage[] = [userMsg];
  let finalText = '';

  /* ── tool loop ───────────────────────────────────────────────────────── */
  for (let round = 0; round <= maxToolRounds; round++) {
    const lastRound = round === maxToolRounds;
    onEvent({ type: 'thought', text: round === 0 ? 'يفكر...' : 'بيراجع النتيجة...' });

    const messages: RNLlamaOAICompatibleMessage[] = [
      { role: 'system', content: system },
      ...histCopy,
      ...turn,
    ];
    if (lastRound) {
      messages.push({
        role: 'user',
        content: 'Do not call any more tools. Answer the user now using what you already have.',
      });
    }

    let tokens = 0;
    let result;
    try {
      result = await ctx.completion(
        {
          messages,
          n_predict: N_PREDICT,
          temperature: 0.6,
          top_p: 0.9,
          enable_thinking: false,
          stop: lastRound ? [] : ['</tool>'],
        },
        () => {
          tokens++;
          if (tokens % 24 === 0) onEvent({ type: 'thought', text: `يكتب الرد... (${tokens})` });
        },
      );
    } catch (e: any) {
      throw new Error(`فشل التوليد: ${e?.message || e}`);
    }

    const raw = stripThinking(result.text || '');
    const match = lastRound ? null : TOOL_TAG_RE.exec(raw);

    if (!match) {
      // On the last round the model may still emit a tool tag; never show it raw.
      finalText = raw.replace(STRAY_TOOL_RE, '').trim();
      if (result.context_full) warnings.push('الـ context امتلى — الرد ممكن يكون ناقص.');
      break;
    }

    // Narrative before the tag is part of the answer the user sees.
    const before = raw.slice(0, match.index).trim();
    if (before) answerParts.push(before);

    const tool = normalizeToolName(match[1]);
    const arg = (match[2] || '').trim();

    onEvent({ type: 'tool_call', tool, arg });
    const toolResult = await dispatchTool(tool, arg);
    onEvent({ type: 'tool_result', tool, result: toolResult });
    steps.push({ tool, arg, result: toolResult });

    turn.push({ role: 'assistant', content: `${before ? before + '\n' : ''}<tool name="${tool}">${arg}</tool>` });
    turn.push({
      role: 'user',
      content: `<tool_result name="${tool}">\n${toolResult.slice(0, TOOL_RESULT_CHARS)}\n</tool_result>`,
    });

    // Keep the loop from overflowing the context: drop the oldest tool pair.
    while (turn.length > 3 && totalChars(turn) > budget * 0.7) turn.splice(1, 2);
  }

  if (finalText) answerParts.push(finalText);
  let answer = answerParts.join('\n\n').trim();
  if (!answer) {
    const last = steps[steps.length - 1];
    answer = last
      ? `(النموذج ما ردّش بنص بعد الأداة.) آخر نتيجة من ${last.tool}:\n${last.result.slice(0, 800)}`
      : '(النموذج ما رجّعش رد. جرّب تعيد الصياغة أو تجرب نموذج تاني.)';
  }

  // Remember this exchange (attachment bodies are not kept to save context).
  history.push({
    role: 'user',
    content: attachment ? `[Attached ${attachment.kind}: ${attachment.name}] ${question}` : question,
  });
  history.push({ role: 'assistant', content: answer });
  if (history.length > HISTORY_MAX_MESSAGES) history = history.slice(-HISTORY_MAX_MESSAGES);

  return {
    answer,
    steps,
    elapsed: Math.round((Date.now() - t0) / 100) / 10,
    attachmentWarnings: warnings,
  };
}
