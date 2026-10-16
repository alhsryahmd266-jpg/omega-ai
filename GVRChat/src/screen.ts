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
  'واتس': 'whatsapp', 'واتساب': 'whatsapp', 'فيسبوك': 'facebook', 'فيس بوك': 'facebook',
  'يوتيوب': 'youtube', 'انستا': 'instagram', 'انستجرام': 'instagram', 'انستغرام': 'instagram',
  'جيت هب': 'github', 'جيثهب': 'github', 'كروم': 'chrome', 'تيليجرام': 'telegram',
  'تلغرام': 'telegram', 'سبوتيفاي': 'spotify', 'خرايط': 'maps', 'جوجل ماب': 'maps',
  'ماب': 'maps', 'جيميل': 'gmail', 'تويتر': 'twitter', 'اكس': 'x',
};
const STOP_WORDS = ['كدا', 'كده', 'وكلمني', 'وكلم', 'واكتب', 'وابعت', 'ثم', 'عشان', 'لو', 'و'];

export async function toolFindApp(arg: string): Promise<string> {
  let q = arg.trim().replace(/^.*?(افتح|اشغل|شغل|اطلق|open|launch)\s*/i, '');
  const words: string[] = [];
  for (const w of q.split(/\s+/)) {
    if (!w || STOP_WORDS.includes(w)) break;
    words.push(w.replace(/^ال/, ''));
  }
  if (!words.length) return 'Give the app name, e.g. فتح واتساب.';
  const phrase = words.join(' ');
  const alias = APP_ALIASES[phrase] || APP_ALIASES[words[0]];
  const needles = [alias, phrase, words[0]].filter(Boolean).map(s => String(s).toLowerCase());
  const apps: string[] = await Native.listApps();
  const all = apps.map(s => {
    const i = s.lastIndexOf('|');
    return { label: s.slice(0, i), pkg: s.slice(i + 1) };
  });
  const matches = all.filter(a => needles.some(nd => a.label.toLowerCase().includes(nd) || a.pkg.toLowerCase().includes(nd)));
  if (!matches.length) return `No installed app matches "${phrase}". Tell the user it is not installed or ask for the exact name.`;
  if (matches.length > 1) {
    return 'Several apps match: ' + matches.slice(0, 8).map(m => `${m.label} (${m.pkg})`).join(', ') + '. Ask the user which one.';
  }
  return toolOpenApp(matches[0].pkg);
}
