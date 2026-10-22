/**
 * agentMemory.ts — the agent's memory for LONG jobs (hours), kept on the phone
 * ===========================================================================
 *   mission journal  what was asked + every tool step so far. Saved after each step, so the job survives
 *                    the app being killed and a "continue" picks up exactly where it stopped.
 *   conversation     the model-facing history, restored after a restart (the chat screen already was).
 *   episodes         a one-line summary of each finished job (ep:* memories), found again by keywords.
 *   recall           the memories that match the NEW request, handed to the model at the END of the prompt.
 *
 * Why "at the end": llama.cpp reuses the already-processed start of the prompt (system prompt + history).
 * Anything that changes every turn must therefore go last, or the whole prompt is re-read from scratch.
 *
 * The logic (what to keep, how to compress, how to rank) is pure and tested in agentLogic.ts; this file only
 * stores and loads.
 */
import AsyncStorage from '@react-native-async-storage/async-storage';
import { journalDigest, newMission, oneLine, rankMemories, recordStep, tokenize, type Mission } from './agentLogic';
import { loadMemoryItems, toolMemoryDelete, toolMemorySave } from './tools';

const MISSION_KEY = 'gvr_mission_v1';
const HISTORY_KEY = 'gvr_history_v1';
/** A job older than this is not offered for resuming any more. */
const RESUME_TTL_MS = 12 * 3600 * 1000;
const EPISODE_LIMIT = 120;

export interface ChatTurn { role: 'user' | 'assistant'; content: string }

let mission: Mission | null = null;
let loaded = false;
let saveTimer: ReturnType<typeof setTimeout> | null = null;

export async function loadMission(): Promise<Mission | null> {
  if (!loaded) {
    try {
      const raw = await AsyncStorage.getItem(MISSION_KEY);
      mission = raw ? (JSON.parse(raw) as Mission) : null;
    } catch { mission = null; }
    loaded = true;
  }
  return mission;
}

export const currentMission = (): Mission | null => mission;

async function writeMission(): Promise<void> {
  try {
    if (mission) await AsyncStorage.setItem(MISSION_KEY, JSON.stringify(mission));
    else await AsyncStorage.removeItem(MISSION_KEY);
  } catch { /* the journal is best-effort: a full disk must not stop the agent */ }
}

function saveSoon(): void {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => { saveTimer = null; void writeMission(); }, 400);
}

export async function flushMission(): Promise<void> {
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
  await writeMission();
}

function sharesWords(a: string, b: string): boolean {
  const wa = new Set(tokenize(a).filter(w => w.length > 3));
  return tokenize(b).some(w => w.length > 3 && wa.has(w));
}

// A plain boolean on purpose: as a type guard, its `false` branch narrowed the mission to `never`.
const resumable = (m: Mission | null): boolean =>
  !!m && (m.status === 'paused' || m.status === 'running') && Date.now() - m.updatedAt < RESUME_TTL_MS;

/** The job the app was in the middle of when it was closed/killed (for the "continue?" banner). */
export async function interruptedMission(): Promise<Mission | null> {
  const m = await loadMission();
  return m && m.status === 'running' && Date.now() - m.updatedAt < RESUME_TTL_MS ? m : null;
}

/** Text of the journal for the start of a turn: only for an unfinished job, or when the user says "continue". */
export async function digestForPrompt(budgetChars: number, continuing: boolean, question = ''): Promise<string> {
  const m = await loadMission();
  if (!m || budgetChars < 200 || m.steps.length === 0) return '';
  // An unrelated question must not get a stale job log: continue, or share words with the job (goal / files).
  const related = continuing || sharesWords(question, `${m.goal} ${m.files.join(' ')}`);
  if (!(related && (resumable(m) || continuing))) return '';
  return journalDigest(m, budgetChars);
}

/** A new request starts a new job, a "continue" resumes the unfinished one. */
export async function beginMission(goal: string, continuing: boolean): Promise<Mission> {
  const m = await loadMission();
  if (continuing && m && (resumable(m) || m.steps.length > 0)) mission = { ...m, status: 'running', updatedAt: Date.now() };
  else mission = newMission(goal);
  saveSoon();
  return mission;
}

export function noteStep(tool: string, arg: string, result: string): void {
  if (!mission) return;
  mission = recordStep(mission, tool, arg, result);
  saveSoon();
}

/** Finishes the job. 'done' with real work also leaves a one-line episode behind for later recall. */
export async function endMission(status: 'done' | 'paused' | 'failed', summary = ''): Promise<void> {
  if (!mission) return;
  mission = { ...mission, status, updatedAt: Date.now() };
  await flushMission();
  if (status === 'done' && mission.steps.length >= 2) await saveEpisode(mission, summary);
}

async function saveEpisode(m: Mission, summary: string): Promise<void> {
  try {
    const ok = m.steps.filter(s => s.outcome === 'ok').length;
    const files = m.files.slice(-3).join(', ');
    const key = `ep:${new Date(m.startedAt).toISOString().slice(0, 16).replace(/[-:T]/g, '')}`;
    const outcome = summary ? oneLine(summary, 160) : `${m.steps.length} steps (${ok} ok)`;
    await toolMemorySave(key, oneLine(`${m.goal} → ${outcome}${files ? ` · files: ${files}` : ''}`, 360));
    const eps = (await loadMemoryItems()).filter(i => i.key.startsWith('ep:')).sort((a, b) => a.key.localeCompare(b.key));
    for (const old of eps.slice(0, Math.max(0, eps.length - EPISODE_LIMIT))) await toolMemoryDelete(old.key);
  } catch { /* memory is best-effort */ }
}

/** Memories (facts the user asked to remember + past jobs) that match this request. */
export async function recallFor(query: string, maxChars: number): Promise<string> {
  if (maxChars < 120 || query.trim().length < 3) return '';
  try {
    const hits = rankMemories(query, await loadMemoryItems(), 3, 1.0);
    let out = '';
    for (const h of hits) {
      const line = `- ${h.key}: ${oneLine(h.value, 220)}\n`;
      if (out.length + line.length > maxChars) break;
      out += line;
    }
    return out ? `[Relevant memory]\n${out.trim()}` : '';
  } catch { return ''; }
}

/* ── conversation (what the model sees) ───────────────────────────────── */
export async function saveHistory(history: ChatTurn[]): Promise<void> {
  try { await AsyncStorage.setItem(HISTORY_KEY, JSON.stringify(history.slice(-24))); } catch { /* best-effort */ }
}

export async function loadHistory(): Promise<ChatTurn[]> {
  try {
    const a = JSON.parse((await AsyncStorage.getItem(HISTORY_KEY)) || '[]');
    return Array.isArray(a)
      ? a.filter((t): t is ChatTurn => t && (t.role === 'user' || t.role === 'assistant') && typeof t.content === 'string')
      : [];
  } catch { return []; }
}

/** "New chat": forget the conversation and the job (saved memories and episodes stay). */
export async function clearConversationMemory(): Promise<void> {
  mission = null;
  loaded = true;
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
  try { await AsyncStorage.multiRemove([MISSION_KEY, HISTORY_KEY]); } catch { /* nothing to remove */ }
}
