/**
 * gvrEngine.ts — GVR Agent Engine
 * ================================
 * The model sees a compact system prompt listing all available tools.
 * When it decides to use one, it emits a tag like:
 *   [TERMINAL: ls /home]
 *   [PYTHON: import math; print(math.pi)]
 *   [SEARCH: latest Android 15 features]
 * The engine intercepts these, runs the tool, feeds the result back,
 * and continues generation until no tool tag is present.
 */

import { LlamaContext } from 'llama.rn';
import { dispatchTool, AVAILABLE_TOOLS } from './tools';

/* ── SYSTEM PROMPT ─────────────────────────────────────────────────────────── */
export const AGENT_SYS = `You are GVR, an on-device AI assistant with access to real tools.

AVAILABLE TOOLS (use exact syntax):
[SEARCH: your query]           — search the web
[TERMINAL: shell command]      — run ANY Linux/shell command in Alpine Linux (proot)
[PYTHON: python code]          — run Python 3 code (multi-line ok)
[PKG_INSTALL: package name]    — install any Alpine package (git, ffmpeg, nodejs, etc.)
[MEM_SAVE: key | value]        — save something to persistent memory
[MEM_GET: key]                 — retrieve from memory
[MEM_LIST]                     — list all saved memories
[DEVICE_INFO]                  — battery level + storage info

RULES:
- Use TERMINAL for file ops, curl, git, bash scripts, compiling, etc.
- Use PYTHON for data processing, calculations, scripts
- You can chain tools: use TERMINAL to download something, PYTHON to process it
- PKG_INSTALL is persistent across sessions (Alpine stores packages)
- Always show the user what you're doing before running a tool
- After getting a tool result, explain what it means in plain language
- If a command fails, diagnose and try an alternative approach
- Respond in the same language the user writes in`;

/* ── TOOL TAG REGEX ────────────────────────────────────────────────────────── */
const TOOL_RE = /\[(SEARCH|TERMINAL|PYTHON|PKG_INSTALL|MEM_SAVE|MEM_GET|MEM_LIST|DEVICE_INFO)(?::[ \t]*([\s\S]*?))?\]/;

type StreamCallback = (token: string) => void;

/* ── MAIN INFERENCE ────────────────────────────────────────────────────────── */
export async function runAgent(
  ctx: LlamaContext,
  messages: Array<{ role: 'user' | 'assistant' | 'system'; content: string }>,
  onToken: StreamCallback,
  maxToolRounds = 6,
): Promise<string> {

  const history = [{ role: 'system' as const, content: AGENT_SYS }, ...messages];
  let fullReply = '';
  let rounds = 0;

  while (rounds < maxToolRounds) {
    rounds++;
    let chunk = '';

    // ── stream generation ────────────────────────────────────────────────────
    const result = await ctx.completion(
      {
        messages: history,
        n_predict: 1024,
        temperature: 0.7,
        top_p: 0.9,
        stop: ['<|end|>', '[/INST]', '</s>'],
      },
      (data: { token: string }) => {
        const t = data.token;
        chunk += t;
        // stream tokens to UI — hold back once we see '[' (might be a tool tag)
        if (!chunk.includes('[')) onToken(t);
      }
    );

    fullReply += chunk;
    history.push({ role: 'assistant', content: chunk });

    // ── check for tool call ─────────────────────────────────────────────────
    const match = TOOL_RE.exec(chunk);
    if (!match) {
      // flush any held-back tokens
      const heldBack = chunk.match(/\[.*/)?.[0] ?? '';
      if (heldBack) onToken(heldBack);
      break; // no tool, we're done
    }

    const [fullTag, toolRaw, argRaw = ''] = match;
    const tool = toolRaw.toLowerCase().replace(/_/g, '_');
    const arg  = argRaw.trim();

    // flush text before the tool tag
    const before = chunk.slice(0, match.index);
    if (before) onToken(before);

    // show "running…" indicator
    const indicator = `\n⚙️ [${toolRaw}${arg ? ': ' + arg.slice(0, 60) : ''}]\n`;
    onToken(indicator);

    // ── dispatch tool ────────────────────────────────────────────────────────
    let toolResult: string;
    try {
      // map tag names to dispatchTool keys
      const toolKey = tool === 'pkg_install' ? 'pkg_install'
                    : tool === 'device_info' ? 'device_info'
                    : tool === 'mem_list'    ? 'mem_list'
                    : tool;
      toolResult = await dispatchTool(toolKey, arg);
    } catch (e: any) {
      toolResult = `Tool error: ${e.message}`;
    }

    // show result to user
    const resultBlock = `\n\`\`\`\n${toolResult.slice(0, 2000)}\n\`\`\`\n`;
    onToken(resultBlock);

    // feed result back into context so model can continue
    history.push({
      role: 'user',
      content: `[Tool result for ${fullTag}]:\n${toolResult.slice(0, 3000)}`,
    });
  }

  return fullReply;
}

/* ── CONTEXT HELPERS ───────────────────────────────────────────────────────── */
export function formatMessages(
  msgs: Array<{ role: string; content: string }>,
) {
  return msgs;
}
