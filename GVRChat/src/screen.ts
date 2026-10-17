/**
 * screen.ts — the agent's hands and eyes on the phone screen.
 * Needs the GVR Agent accessibility service turned on by the user, and only
 * acts inside apps the user put on the allowed list.
 */
import { requireNativeModule } from 'expo-modules-core';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as IntentLauncher from 'expo-intent-launcher';

const Native: any = requireNativeModule('Terminal');
const ALLOW_KEY = 'gvr_screen_allowed';
const OFF = 'The screen service is off. The user must turn on "GVR Agent" in Accessibility settings. Call screen_enable to open that page.';

async function getAllowed(): Promise<string[]> {
  try {
    return JSON.parse((await AsyncStorage.getItem(ALLOW_KEY)) || '[]');
  } catch {
    return [];
  }
}

async function sync(): Promise<boolean> {
  await Native.screenSetAllowed(await getAllowed());
  return !!(await Native.screenStatus());
}

export async function toolScreenRead(): Promise<string> {
  if (!(await sync())) return OFF;
  return String(await Native.screenDump());
}

export async function toolScreenTap(arg: string): Promise<string> {
  const n = parseInt(arg.trim(), 10);
  if (!n) return 'Give the element number from screen_read, e.g. 4';
  if (!(await sync())) return OFF;
  return String(await Native.screenTap(n));
}

export async function toolScreenType(arg: string): Promise<string> {
  const i = arg.indexOf('|');
  if (i < 0) return 'Give: number | text, e.g. 3 | hello';
  const n = parseInt(arg.slice(0, i).trim(), 10);
  const text = arg.slice(i + 1);
  if (!n) return 'Give the element number from screen_read first.';
  if (!(await sync())) return OFF;
  const blocked = await sessionCheck(text);
  if (blocked) return blocked;
  return String(await Native.screenType(n, text));
}

export async function toolScreenBack(): Promise<string> {
  if (!(await sync())) return OFF;
  return String(await Native.screenBack());
}

export async function toolScreenAllow(arg: string): Promise<string> {
  const pkgs = arg
    .split(/[\s,|]+/)
    .map(s => s.trim())
    .filter(s => /^[a-z0-9_]+(\.[a-z0-9_]+)+$/i.test(s));
  if (!pkgs.length) return 'Give package names, e.g. com.whatsapp';
  const next = Array.from(new Set([...(await getAllowed()), ...pkgs]));
  await AsyncStorage.setItem(ALLOW_KEY, JSON.stringify(next));
  return `Allowed apps: ${next.join(', ')}`;
}

export async function toolOpenApp(arg: string): Promise<string> {
  const pkg = arg.trim();
  if (!/^[a-z0-9_]+(\.[a-z0-9_]+)+$/i.test(pkg)) return 'Give the package name, e.g. com.whatsapp';
  const opened = String(await Native.openApp(pkg));
  if (!opened.startsWith('Opened')) return opened;
  await new Promise(r => setTimeout(r, 2500));
  if (!(await getAllowed()).includes(pkg)) {
    return `${opened} To read its screen, allow it first with screen_allow and ${pkg}; the user confirms.`;
  }
  if (!(await sync())) return `${opened} The screen service is off, so the screen cannot be read yet.`;
  return `${opened}\nCurrent screen:\n${String(await Native.screenDump())}`;
}

export async function toolScreenEnable(): Promise<string> {
  await IntentLauncher.startActivityAsync('android.settings.ACCESSIBILITY_SETTINGS');
  return 'Opened Accessibility settings. Ask the user to turn on GVR Agent there.';
}

/* ── CHAT SESSION: a bounded conversation the user approved and can stop ─── */
const SESSION_KEY = 'gvr_session';
type Session = { contact: string; goal: string; max: number; until: number; sent: number; disclosed: boolean; log: string[] };
const FORBIDDEN = /(كلم[هة]\s+السر|باسورد|password|otp|رمز\s+التحقق|فلوس|جنيه|تحويل|فيزا|visa|بطاق[هة]|رقم\s+الحساب|iban|حساب\s+بنكي)/i;

async function getSession(): Promise<Session | null> {
  try {
    return JSON.parse((await AsyncStorage.getItem(SESSION_KEY)) || 'null');
  } catch {
    return null;
  }
}

async function saveSession(s: Session | null): Promise<void> {
  if (!s) await AsyncStorage.removeItem(SESSION_KEY);
  else await AsyncStorage.setItem(SESSION_KEY, JSON.stringify(s));
}

async function sessionCheck(text: string): Promise<string | null> {
  const s = await getSession();
  if (!s) return 'Typing is only allowed inside a chat session. Start one with session_start after the user agrees.';
  if (Date.now() > s.until) {
    await saveSession(null);
    return 'The session time is over, so it was stopped.';
  }
  if (s.sent >= s.max) {
    await saveSession(null);
    return 'The message limit was reached, so the session was stopped.';
  }
  if (FORBIDDEN.test(text)) {
    return 'Blocked: this message touches money, passwords or bank data. Ask the user first.';
  }
  if (!s.disclosed) {
    if (!/ذكاء\s+صناعي|مساعد\s+(ذكي|آلي)|\bAI\b/i.test(text)) {
      return 'Your first message in a session must say you are an AI assistant writing for the user.';
    }
    s.disclosed = true;
  }
  s.sent += 1;
  s.log.push(new Date().toISOString().slice(11, 16) + ' ' + text.slice(0, 200));
  await saveSession(s);
  // a natural pause before typing, so the rhythm looks like a person
  await new Promise(r => setTimeout(r, 3000 + Math.random() * 9000));
  return null;
}

export async function toolSessionStart(arg: string): Promise<string> {
  const [contact = '', goal = '', maxStr = '', minStr = ''] = arg.split('|').map(s => s.trim());
  if (!contact || !goal) return 'Give: contact | goal | max messages | minutes';
  if (FORBIDDEN.test(goal)) return 'Blocked: the goal touches money, passwords or bank data.';
  const max = Math.min(Math.max(parseInt(maxStr, 10) || 20, 1), 50);
  const minutes = Math.min(Math.max(parseInt(minStr, 10) || 30, 1), 120);
  const bubble = String(await Native.bubbleShow());
  await saveSession({ contact, goal, max, until: Date.now() + minutes * 60000, sent: 0, disclosed: false, log: [] });
  return `Session started with ${contact}: goal "${goal}", up to ${max} messages for ${minutes} minutes. ${bubble}`;
}

export async function toolSessionStop(): Promise<string> {
  const s = await getSession();
  await saveSession(null);
  return s ? `Session with ${s.contact} stopped after ${s.sent} messages.` : 'No active session.';
}

export async function toolSessionStatus(): Promise<string> {
  const s = await getSession();
  if (!s) return 'No active session.';
  const left = Math.max(0, Math.round((s.until - Date.now()) / 60000));
  return `Session with ${s.contact}: ${s.sent}/${s.max} messages, about ${left} minutes left. Goal: ${s.goal}`;
}

const APP_ALIASES: Record<string, string> = {
  'واتس': 'whatsapp', 'واتساب': 'whatsapp', 'واتسب': 'whatsapp', 'وتس': 'whatsapp', 'وتساب': 'whatsapp',
  'فيسبوك': 'facebook', 'فيس بوك': 'facebook', 'يوتيوب': 'youtube', 'انستا': 'instagram',
  'انستجرام': 'instagram', 'انستغرام': 'instagram', 'جيت هب': 'github', 'جيثهب': 'github',
  'كروم': 'chrome', 'تيليجرام': 'telegram', 'تلغرام': 'telegram', 'سبوتيفاي': 'spotify',
  'خرايط': 'maps', 'جوجل ماب': 'maps', 'ماب': 'maps', 'جيميل': 'gmail', 'تويتر': 'twitter',
  'كاميرا': 'camera', 'ساعه': 'clock', 'حاسبه': 'calculator', 'ملاحظات': 'notes', 'المذكرات': 'notes',
};

const STOP_WORDS = new Set([
  'كدا', 'كده', 'وكلمني', 'وكلم', 'واكتب', 'وابعت', 'ثم', 'عشان', 'لو', 'و', 'واطمن', 'واتفاعل',
  'ودردش', 'مع', 'عليه', 'معاه', 'معه', 'شويه', 'شوية', 'واتكلم', 'وكلمه', 'عليها', 'معاها',
  'وشوف', 'واشوف', 'واعمل', 'واعرف', 'على', 'في', 'من', 'اللي', 'الي',
]);

/** Name variants: "الوتس" -> "وتس", "واتس" -> "اتس" etc. */
function variantsOf(w: string): string[] {
  const v = new Set([w]);
  if (w.startsWith('ال') && w.length > 3) v.add(w.slice(2));
  if (w.startsWith('و') && w.length > 2) v.add(w.slice(1));
  return [...v];
}

const GENERIC_WORDS = new Set(['برنامج', 'تطبيق', 'تطبيقات', 'البرنامج', 'التطبيق', 'app', 'apps']);

export async function toolFindApp(arg: string): Promise<string> {
  const q = arg.trim().replace(/^.*?(افتح|اشغل|شغل|اطلق|open|launch)\s*/i, '');
  const tokens: string[] = [];
  for (const w of q.split(/\s+/)) {
    if (GENERIC_WORDS.has(w) && tokens.length === 0) continue;
    if (!w || STOP_WORDS.has(w)) break;
    tokens.push(w);
  }
  if (!tokens.length) return 'Give the app name, e.g. افتح واتساب.';

  // candidate keys: longest phrase first, then the first word with its variants
  const keys: string[] = [];
  for (let n = Math.min(3, tokens.length); n >= 1; n--) {
    const phrase = tokens.slice(0, n).join(' ');
    keys.push(phrase);
    for (const v of variantsOf(tokens[0])) keys.push([v, ...tokens.slice(1, n)].join(' '));
  }

  const apps: string[] = await Native.listApps();
  const all = apps.map(s => {
    const i = s.lastIndexOf('|');
    return { label: s.slice(0, i), pkg: s.slice(i + 1) };
  });

  for (const key of keys) {
    const alias = APP_ALIASES[key];
    const needles = [alias, key].filter(Boolean).map(s => String(s).toLowerCase());
    const matches = all.filter(a => needles.some(nd => a.label.toLowerCase().includes(nd) || a.pkg.toLowerCase().includes(nd)));
    if (matches.length === 1) return toolOpenApp(matches[0].pkg);
    if (matches.length > 1) {
      return 'Several apps match: ' + matches.slice(0, 8).map(m => `${m.label} (${m.pkg})`).join(', ') + '. Ask the user which one.';
    }
  }
  return `No installed app matches "${tokens.join(' ')}" (apps scanned: ${all.length}). If apps scanned is 0, the phone did not list apps: say so and ask the user to run diagnose. Do not install packages to imitate the app.`;
}

export async function toolDiagnose(): Promise<string> {
  const inSettings = !!(await Native.accessibilityEnabledInSettings());
  const connected = !!(await Native.screenStatus());
  const overlay = !!(await Native.overlayAllowed());
  const allowed = await getAllowed();
  const s = await getSession();
  const lines = [
    `Accessibility ON in Android settings: ${inSettings ? 'yes' : 'NO'}`,
    `Accessibility service connected to the app: ${connected ? 'yes' : 'NO'}`,
    `Display over other apps: ${overlay ? 'yes' : 'NO'}`,
    `Allowed apps: ${allowed.length ? allowed.join(', ') : 'none'}`,
    `Chat session: ${s ? 'active with ' + s.contact : 'none'}`,
  ];
  let advice = 'Looks ready.';
  if (!inSettings) advice = 'Turn GVR Agent on in Accessibility settings (screen_enable).';
  else if (!connected) advice = 'It is ON in settings but not connected. Turn it off and on again, then open this app once.';
  else if (!allowed.length) advice = 'Connected. Allow an app with screen_allow before reading its screen.';
  return lines.join('\n') + '\n' + advice;
}

/* ── WHATSAPP SEND: search a contact, open the chat, type, press send, and verify on screen ─ */
function parseElements(dump: string): { n: number; kind: string; label: string }[] {
  const out: { n: number; kind: string; label: string }[] = [];
  for (const line of dump.split('\n')) {
    const m = /^\[(\d+)\]\s+(\w+)(?:\s+"(.*)")?$/.exec(line.trim());
    if (m) out.push({ n: Number(m[1]), kind: m[2], label: m[3] || '' });
  }
  return out;
}

const pause = (ms: number) => new Promise(r => setTimeout(r, ms));

export async function toolWhatsappSend(arg: string): Promise<string> {
  const parts = arg.split('|');
  const name = (parts[0] || '').trim();
  const message = parts.slice(1).join('|').trim();
  if (!name || !message) return 'Give: contact name | message text';
  if (!(await sync())) return OFF;
  if (!(await getSession())) {
    return 'Start a chat session first (session_start) so the user approves sending messages.';
  }
  if (!(await getAllowed()).includes('com.whatsapp')) {
    return 'WhatsApp is not allowed yet. Call screen_allow with com.whatsapp first.';
  }
  const dumpText = async () => String(await Native.screenDump());
  const read = async () => parseElements(await dumpText());

  // 1. open search, if the screen has a search button
  let els = await read();
  const search = els.find(e => /بحث|search/i.test(e.label));
  if (search) {
    await Native.screenTap(search.n);
    await pause(1200);
    els = await read();
  }

  // 2. type the contact name into the search box
  const box = els.find(e => e.kind === 'input');
  if (!box) return 'Could not find the search box. Screen shows: ' + (await dumpText()).slice(0, 300);
  await Native.screenType(box.n, name);
  await pause(1500);
  els = await read();

  // 3. open the chat with that exact name
  const row = els.find(e => e.label.trim() === name) || els.find(e => e.label.includes(name));
  if (!row) return `No chat named "${name}" appeared on screen.`;
  await Native.screenTap(row.n);
  await pause(1500);
  els = await read();

  // 4. type the message (session checks: disclosure, limits, forbidden topics)
  const input = els.find(e => e.kind === 'input');
  if (!input) return 'The chat opened but no message box was found.';
  const blocked = await sessionCheck(message);
  if (blocked) return blocked;
  await Native.screenType(input.n, message);
  await pause(800);
  els = await read();

  // 5. press send, then verify the message is on screen
  const send = els.find(e => /إرسال|ارسال|send/i.test(e.label));
  if (!send) return 'The message was typed but the send button was not found, so it was not sent.';
  await Native.screenTap(send.n);
  await pause(1500);
  const after = await dumpText();
  return after.includes(message.slice(0, 20))
    ? `Sent to ${name}: "${message}"`
    : 'Pressed send, but the message is not visible on screen yet. Check the chat before saying it was sent.';
}
