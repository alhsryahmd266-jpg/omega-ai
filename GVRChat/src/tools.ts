/**
 * tools.ts — everything the agent can do
 * =======================================
 * Read-only tools run immediately. Tools with side effects (terminal, writing
 * or deleting files, running JavaScript, opening links, sharing) ask the user
 * first through the confirm handler that App.tsx registers.
 *
 * Honest limits:
 *  - `terminal` is the Android system shell (toybox): ls, cat, grep, sed, find,
 *    tar, ps, df, ping … It has NO package manager and NO python.
 *  - File tools write only inside the app's private folder or the shared
 *    storage root (/storage/emulated/0). Deleting is limited to the app folder.
 */
import * as FileSystem from 'expo-file-system';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Linking, Platform, Share } from 'react-native';
import Terminal from '../modules/terminal/src';
import { inspectFile } from './fileInspect';
import * as Clipboard from 'expo-clipboard';
import { installScript, parseInstallOutput, sanitizePkgName } from './toolCard';
import {
  PERM_KEYS, PERMISSION_LABELS, getPermissionsStatus, normalizePermKey, requestPermission,
} from './permissions';

import { browseOnPage, getLastLinks, actOnPage, type PageResult } from './browserBridge';
import { SKILLS, skillsCatalog, loadSkill } from './skills';
import { getSecret, ensureSecret } from './secrets';
import { toolSetAlarm, toolRemind, toolNoteAdd, toolNoteList } from './deviceTools';
import { toolScreenRead, toolScreenTap, toolScreenType, toolScreenBack, toolScreenAllow, toolScreenEnable, toolOpenApp } from './screen';
import { toolBubbleShow, toolBubbleHide } from './bubble';
import { describePick } from './modelSelector';
import { toolSessionStart, toolSessionStop, toolSessionStatus } from './screen';

const MEMORY_KEY = 'gvr_agent_memory';
const UA = 'Mozilla/5.0 (Linux; Android 14; GVRChat/2.0) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36';

const clip = (s: string, n: number): string =>
  s.length > n ? `${s.slice(0, n)}\n…[truncated ${s.length - n} chars]` : s;

/* ── CONFIRMATION GATE ─────────────────────────────────────────────────── */
export type ConfirmDecision = 'once' | 'always' | 'deny';
export type ConfirmHandler = (tool: string, arg: string) => Promise<ConfirmDecision>;

let confirmHandler: ConfirmHandler | null = null;
const alwaysAllowed = new Set<string>();

export function setToolConfirmHandler(fn: ConfirmHandler | null): void {
  confirmHandler = fn;
}
export function resetToolApprovals(): void {
  alwaysAllowed.clear();
}

/* ── PATHS ─────────────────────────────────────────────────────────────── */
const SANDBOX = FileSystem.documentDirectory ?? 'file:///';
const CACHE = FileSystem.cacheDirectory ?? 'file:///__none__/';
const WORKDIR = `${SANDBOX}workspace/`;
const SHARED_ROOT = 'file:///storage/emulated/0/';

interface ResolvedPath {
  uri: string;
  inSandbox: boolean;
  inShared: boolean;
}

function resolvePath(raw: string): ResolvedPath {
  let p = raw.trim().replace(/^["']|["']$/g, '');
  if (!p) throw new Error('Empty path.');
  let uri: string;
  if (p.startsWith('file://')) uri = p;
  else if (p.startsWith('/sdcard')) uri = `file://${p.replace(/^\/sdcard/, '/storage/emulated/0')}`;
  else if (p.startsWith('/')) uri = `file://${p}`;
  else uri = WORKDIR + p;

  if (/(^|\/)\.\.(\/|$)/.test(uri)) throw new Error('Paths containing ".." are not allowed.');

  const inSandbox = uri.startsWith(SANDBOX) || uri.startsWith(CACHE);
  const inShared = uri.startsWith(SHARED_ROOT);
  return { uri, inSandbox, inShared };
}

async function ensureParent(uri: string): Promise<void> {
  const parent = uri.slice(0, uri.lastIndexOf('/') + 1);
  if (!parent) return;
  const info = await FileSystem.getInfoAsync(parent);
  if (!info.exists) await FileSystem.makeDirectoryAsync(parent, { intermediates: true });
}

/* ── TERMINAL ──────────────────────────────────────────────────────────── */
const CWD_MARK = '__GVR_CWD__';
let termCwd = '';
const shQuote = (s: string): string => `'${s.replace(/'/g, `'\\''`)}'`;

/**
 * Runs a command in the Android shell. The working directory persists between
 * calls (cd works like in a real terminal). A first line "#timeout=120" raises
 * the time limit (default 30 s, max 300 s).
 */
export async function toolTerminal(arg: string): Promise<string> {
  let command = arg;
  let timeout = 30;
  const tm = /^#timeout=(\d{1,3})[ \t]*\r?\n/.exec(command);
  if (tm) {
    timeout = Math.min(300, Math.max(1, parseInt(tm[1], 10)));
    command = command.slice(tm[0].length);
  }
  if (!command.trim()) return 'Empty command.';

  const wrapped =
    (termCwd ? `cd ${shQuote(termCwd)} 2>/dev/null\n` : '') +
    `{\n${command}\n}\n__gvr_rc=$?\nprintf '\\n${CWD_MARK}%s\\n' "$(pwd)"\nexit $__gvr_rc`;

  const out = await Terminal.run(wrapped, timeout);
  const m = new RegExp(`\\n?${CWD_MARK}(.*)\\n?`).exec(out);
  let clean = out;
  if (m) {
    termCwd = m[1].trim();
    clean = out.replace(m[0], '\n');
  }
  return clip(clean.trim() || '(no output)', 8000);
}

export function resetTerminalState(): void {
  termCwd = '';
}

/* ── FILES ─────────────────────────────────────────────────────────────── */
export async function toolReadFile(arg: string): Promise<string> {
  const { uri } = resolvePath(arg.split('\n')[0]);
  const info = await FileSystem.getInfoAsync(uri, { size: true });
  if (!info.exists) return `File not found: ${uri}`;
  if (info.isDirectory) return `That is a directory. Use list_dir on: ${uri}`;
  try {
    const text = await FileSystem.readAsStringAsync(uri, { length: 80000 });
    return clip(text, 20000);
  } catch (e: any) {
    return `Could not read as text (binary file?): ${e?.message || e}`;
  }
}

export async function toolWriteFile(arg: string): Promise<string> {
  const nl = arg.indexOf('\n');
  const pathLine = nl === -1 ? arg : arg.slice(0, nl);
  const content = nl === -1 ? '' : arg.slice(nl + 1);
  const { uri, inSandbox, inShared } = resolvePath(pathLine);
  if (!inSandbox && !inShared) {
    return 'Refused: writing is only allowed inside the app folder or /storage/emulated/0.';
  }
  try {
    await ensureParent(uri);
    await FileSystem.writeAsStringAsync(uri, content);
    return `Wrote ${content.length} chars to ${uri}`;
  } catch (e: any) {
    return `Write failed: ${e?.message || e}${inShared ? ' (shared storage may need the "all_files" permission)' : ''}`;
  }
}

export async function toolListDir(arg: string): Promise<string> {
  const { uri } = resolvePath(arg.trim() || WORKDIR);
  const base = uri.endsWith('/') ? uri : `${uri}/`;
  const info = await FileSystem.getInfoAsync(base);
  if (!info.exists) return `Directory not found: ${base}`;
  try {
    const names = await FileSystem.readDirectoryAsync(base);
    if (names.length === 0) return `${base} is empty.`;
    const lines: string[] = [];
    for (const n of names.slice(0, 100)) {
      const i = await FileSystem.getInfoAsync(base + n, { size: true });
      if (i.exists) {
        lines.push(i.isDirectory ? `📁 ${n}/` : `📄 ${n}  (${i.size} bytes)`);
      }
    }
    const more = names.length > 100 ? `\n…and ${names.length - 100} more` : '';
    return `${base}\n${lines.join('\n')}${more}`;
  } catch (e: any) {
    return `List failed: ${e?.message || e}`;
  }
}

export async function toolDeleteFile(arg: string): Promise<string> {
  const { uri, inSandbox } = resolvePath(arg.split('\n')[0]);
  if (!inSandbox) return 'Refused: deleting is only allowed inside the app folder.';
  try {
    await FileSystem.deleteAsync(uri, { idempotent: true });
    return `Deleted ${uri}`;
  } catch (e: any) {
    return `Delete failed: ${e?.message || e}`;
  }
}

/* ── WEB ───────────────────────────────────────────────────────────────── */
function decodeEntities(s: string): string {
  return s
    .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'")
    .replace(/&#(\d+);/g, (_m, d) => String.fromCharCode(parseInt(d, 10)));
}

function htmlToText(html: string): string {
  return decodeEntities(
    html
      .replace(/<(script|style|noscript|svg)[\s\S]*?<\/\1>/gi, ' ')
      .replace(/<!--[\s\S]*?-->/g, ' ')
      .replace(/<(br|\/p|\/div|\/li|\/h[1-6]|\/tr)\s*\/?>/gi, '\n')
      .replace(/<[^>]+>/g, ' '),
  )
    .replace(/[ \t]+/g, ' ')
    .replace(/\n\s*\n\s*/g, '\n')
    .trim();
}

const sleep = (ms: number): Promise<void> => new Promise(r => setTimeout(r, ms));

interface FetchedText {
  status: number;
  contentType: string;
  url: string;
  text: string;
}

/** fetch + read the whole body under one timeout, retrying on network errors, 429 and 5xx. */
async function fetchText(
  url: string,
  init: { method?: string; headers?: Record<string, string>; body?: string } = {},
  opts: { timeoutMs?: number; retries?: number } = {},
): Promise<FetchedText> {
  const timeoutMs = opts.timeoutMs ?? 20000;
  const retries = opts.retries ?? 2;
  let lastErr: any = null;
  for (let attempt = 0; attempt <= retries; attempt++) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      const res = await fetch(url, {
        method: init.method,
        body: init.body,
        headers: {
          'User-Agent': UA,
          'Accept-Language': 'ar,en-US;q=0.8,en;q=0.6',
          ...(init.headers || {}),
        },
        signal: ctrl.signal,
      });
      const text = await res.text();
      if ((res.status === 429 || res.status >= 500) && attempt < retries) {
        lastErr = new Error(`HTTP ${res.status}`);
      } else {
        return { status: res.status, contentType: res.headers.get('content-type') || '', url: res.url || url, text };
      }
    } catch (e: any) {
      lastErr = e?.name === 'AbortError' ? new Error(`timed out after ${timeoutMs / 1000}s`) : e;
    } finally {
      clearTimeout(timer);
    }
    if (attempt < retries) await sleep(700 * (attempt + 1));
  }
  throw lastErr ?? new Error('network error');
}

interface SearchHit { title: string; url: string; snippet: string }

function cleanLink(href: string): string {
  let link = decodeEntities(href);
  const uddg = /[?&]uddg=([^&]+)/.exec(link);
  if (uddg) link = decodeURIComponent(uddg[1]);
  const bing = /[?&]u=a1([A-Za-z0-9_-]+)/.exec(link);
  if (bing && /bing\.com\/ck\//.test(link)) {
    try {
      const b64 = bing[1].replace(/-/g, '+').replace(/_/g, '/');
      const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
      const decoded = decodeBase64Ascii(padded);
      if (/^https?:\/\//.test(decoded)) link = decoded;
    } catch { /* keep the tracking link */ }
  }
  if (link.startsWith('//')) link = `https:${link}`;
  return link;
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function decodeBase64Ascii(b64: string): string {
  let buf = 0, bits = 0, out = '';
  for (const ch of b64.replace(/=+$/, '')) {
    const v = B64.indexOf(ch);
    if (v < 0) continue;
    buf = (buf << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((buf >> bits) & 0xff);
      buf &= (1 << bits) - 1;
    }
  }
  return out;
}

async function searchDdgHtml(q: string): Promise<SearchHit[]> {
  const r = await fetchText('https://html.duckduckgo.com/html/', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `q=${encodeURIComponent(q)}&b=`,
  }, { timeoutMs: 15000, retries: 1 });
  const links: { title: string; url: string }[] = [];
  const snippets: string[] = [];
  const aRe = /<a\b([^>]*)>([\s\S]*?)<\/a>/g;
  let m: RegExpExecArray | null;
  while ((m = aRe.exec(r.text))) {
    const attrs = m[1];
    if (/class=["'][^"']*result__a\b/.test(attrs)) {
      const href = /href=["']([^"']+)["']/.exec(attrs)?.[1];
      if (href) links.push({ title: htmlToText(m[2]), url: cleanLink(href) });
    } else if (/class=["'][^"']*result__snippet\b/.test(attrs)) {
      snippets.push(htmlToText(m[2]));
    }
  }
  return links.map((l, i) => ({ ...l, snippet: snippets[i] || '' }));
}

async function searchDdgLite(q: string): Promise<SearchHit[]> {
  const r = await fetchText('https://lite.duckduckgo.com/lite/', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `q=${encodeURIComponent(q)}`,
  }, { timeoutMs: 15000, retries: 1 });
  const links: { title: string; url: string }[] = [];
  const snippets: string[] = [];
  const aRe = /<a\b([^>]*)>([\s\S]*?)<\/a>/g;
  let m: RegExpExecArray | null;
  while ((m = aRe.exec(r.text))) {
    if (/class=["'][^"']*result-link/.test(m[1])) {
      const href = /href=["']([^"']+)["']/.exec(m[1])?.[1];
      if (href) links.push({ title: htmlToText(m[2]), url: cleanLink(href) });
    }
  }
  const sRe = /<td\b[^>]*class=["'][^"']*result-snippet[^"']*["'][^>]*>([\s\S]*?)<\/td>/g;
  while ((m = sRe.exec(r.text))) snippets.push(htmlToText(m[1]));
  return links.map((l, i) => ({ ...l, snippet: snippets[i] || '' }));
}

async function searchBing(q: string): Promise<SearchHit[]> {
  const r = await fetchText(`https://www.bing.com/search?q=${encodeURIComponent(q)}&setlang=en`, {}, { timeoutMs: 15000, retries: 1 });
  const hits: SearchHit[] = [];
  const blocks = r.text.split(/<li[^>]*class="[^"]*\bb_algo\b[^"]*"/).slice(1);
  for (const b of blocks) {
    const a = /<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/.exec(b);
    if (!a) continue;
    const p = /<p[^>]*>([\s\S]*?)<\/p>/.exec(b);
    hits.push({ title: htmlToText(a[2]), url: cleanLink(a[1]), snippet: p ? htmlToText(p[1]) : '' });
  }
  return hits;
}

async function searchWikipedia(q: string): Promise<SearchHit[]> {
  const lang = /[\u0600-\u06FF]/.test(q) ? 'ar' : 'en';
  const r = await fetchText(
    `https://${lang}.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodeURIComponent(q)}&srlimit=6&format=json&origin=*`,
    {}, { timeoutMs: 15000, retries: 1 },
  );
  const data = JSON.parse(r.text);
  return (data?.query?.search || []).map((x: any) => ({
    title: String(x.title),
    url: `https://${lang}.wikipedia.org/?curid=${x.pageid}`,
    snippet: htmlToText(String(x.snippet || '')),
  }));
}

const PROVIDERS: { name: string; run: (q: string) => Promise<SearchHit[]> }[] = [
  { name: 'DuckDuckGo', run: searchDdgHtml },
  { name: 'DuckDuckGo Lite', run: searchDdgLite },
  { name: 'Bing', run: searchBing },
  { name: 'Wikipedia', run: searchWikipedia },
];

/** Tries several search engines in turn, so one blocking us doesn't break search. */
export async function toolWebSearch(query: string): Promise<string> {
  const q = query.trim();
  if (!q) return 'Empty query.';
  const errors: string[] = [];
  for (const p of PROVIDERS) {
    try {
      const hits = (await p.run(q)).filter(h => h.url && h.title).slice(0, 6);
      if (hits.length > 0) {
        const lines = hits.map(h => `• ${h.title}\n  ${h.url}${h.snippet ? `\n  ${h.snippet.slice(0, 240)}` : ''}`);
        return `[${p.name}]\n${lines.join('\n')}\n\nTip: use fetch_url on a link above to read the full page.`;
      }
      errors.push(`${p.name}: no results`);
    } catch (e: any) {
      errors.push(`${p.name}: ${e?.message || e}`);
    }
  }
  return `Search failed on every provider:\n${errors.join('\n')}\nCheck the internet connection, or try fetch_url on a known site.`;
}

export async function toolFetchUrl(arg: string): Promise<string> {
  let url = arg.trim();
  let init: { method?: string; headers?: Record<string, string>; body?: string } = {};
  let maxChars = 8000;
  if (url.startsWith('{')) {
    try {
      const j = JSON.parse(url);
      url = String(j.url || '');
      init = {
        method: j.method || 'GET',
        headers: j.headers || {},
        body: j.body == null ? undefined : typeof j.body === 'string' ? j.body : JSON.stringify(j.body),
      };
      if (init.body && j.body !== null && typeof j.body !== 'string' && !init.headers?.['Content-Type']) {
        init.headers = { ...(init.headers || {}), 'Content-Type': 'application/json' };
      }
      if (Number(j.max) > 0) maxChars = Math.min(20000, Number(j.max));
    } catch {
      return 'Invalid JSON. Expected {"url": "...", "method": "GET", "headers": {}, "body": "", "max": 8000} or a plain URL.';
    }
  }
  if (!/^https?:\/\//i.test(url)) return 'URL must start with http:// or https://';

  try {
    const r = await fetchText(url, init, { timeoutMs: 25000, retries: init.method && init.method !== 'GET' ? 0 : 2 });
    let body = r.text;
    if (/html/i.test(r.contentType)) {
      const title = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(body);
      body = (title ? `Title: ${htmlToText(title[1])}\n\n` : '') + htmlToText(body);
    } else if (/json/i.test(r.contentType)) {
      try { body = JSON.stringify(JSON.parse(body), null, 1); } catch { /* keep raw */ }
    }
    const final = r.url && r.url !== url ? `\n(redirected to ${r.url})` : '';
    return `HTTP ${r.status} (${r.contentType || 'unknown type'})${final}\n${clip(body, maxChars)}`;
  } catch (e: any) {
    return `Fetch error: ${e?.message || e}`;
  }
}

export async function toolDownloadFile(arg: string): Promise<string> {
  let url = '';
  let dest = '';
  const a = arg.trim();
  if (a.startsWith('{')) {
    try {
      const j = JSON.parse(a);
      url = String(j.url || '');
      dest = String(j.path || j.dest || '');
    } catch { return 'Invalid JSON. Expected {"url": "...", "path": "..."} or: url | path'; }
  } else {
    const [u, ...rest] = a.split('|');
    url = u.trim();
    dest = rest.join('|').trim();
  }
  if (!/^https?:\/\//i.test(url)) return 'URL must start with http:// or https://';
  if (!dest) {
    const last = url.split('?')[0].split('#')[0].split('/').filter(Boolean).pop() || 'download';
    try { dest = decodeURIComponent(last); } catch { dest = last; }
  }
  const { uri, inSandbox, inShared } = resolvePath(dest);
  if (!inSandbox && !inShared) return 'Refused: downloads are only allowed into the app folder or /storage/emulated/0.';
  try {
    await ensureParent(uri);
    const r = await FileSystem.downloadAsync(url, uri, { headers: { 'User-Agent': UA } });
    if (r.status >= 400) {
      await FileSystem.deleteAsync(uri, { idempotent: true }).catch(() => {});
      return `Download failed: HTTP ${r.status}`;
    }
    const info = await FileSystem.getInfoAsync(r.uri, { size: true });
    return `Downloaded (HTTP ${r.status}) → ${r.uri} (${info.exists ? `${info.size} bytes` : 'size unknown'})`;
  } catch (e: any) {
    return `Download failed: ${e?.message || e}`;
  }
}

/* ── CALCULATOR (no eval) ──────────────────────────────────────────────── */
const MATH_FUNCS: Record<string, (...a: number[]) => number> = {
  sqrt: Math.sqrt, abs: Math.abs, sin: Math.sin, cos: Math.cos, tan: Math.tan,
  asin: Math.asin, acos: Math.acos, atan: Math.atan, log: Math.log10, ln: Math.log,
  exp: Math.exp, round: Math.round, floor: Math.floor, ceil: Math.ceil,
  min: Math.min, max: Math.max, pow: Math.pow,
};
const MATH_CONSTS: Record<string, number> = { pi: Math.PI, e: Math.E };

function evaluateExpression(src: string): number {
  const s = src.replace(/×/g, '*').replace(/÷/g, '/').replace(/,(?=\d{3}\b)/g, '');
  let i = 0;
  const peek = () => s[i];
  const skip = () => { while (i < s.length && /\s/.test(s[i])) i++; };

  function parseNumber(): number {
    skip();
    const m = /^(\d+\.?\d*|\.\d+)(e[+-]?\d+)?/i.exec(s.slice(i));
    if (!m) throw new Error(`Unexpected "${s[i] ?? 'end'}" at position ${i}`);
    i += m[0].length;
    return parseFloat(m[0]);
  }

  function parseAtom(): number {
    skip();
    const c = peek();
    if (c === '(') {
      i++;
      const v = parseExpr();
      skip();
      if (peek() !== ')') throw new Error('Missing )');
      i++;
      return v;
    }
    if (c === '-') { i++; return -parseUnary(); }
    if (c === '+') { i++; return parseUnary(); }
    if (c && /[a-z]/i.test(c)) {
      const m = /^[a-z]+/i.exec(s.slice(i))!;
      const name = m[0].toLowerCase();
      i += name.length;
      skip();
      if (peek() === '(') {
        i++;
        const args: number[] = [];
        skip();
        if (peek() !== ')') {
          args.push(parseExpr());
          skip();
          while (peek() === ',') { i++; args.push(parseExpr()); skip(); }
        }
        if (peek() !== ')') throw new Error('Missing ) after function arguments');
        i++;
        const fn = MATH_FUNCS[name];
        if (!fn) throw new Error(`Unknown function ${name}`);
        return fn(...args);
      }
      if (name in MATH_CONSTS) return MATH_CONSTS[name];
      throw new Error(`Unknown name ${name}`);
    }
    return parseNumber();
  }

  function parseUnary(): number { return parsePower(); }

  function parsePower(): number {
    const base = parseAtom();
    skip();
    if (s.startsWith('**', i)) { i += 2; return Math.pow(base, parseUnary()); }
    if (peek() === '^') { i++; return Math.pow(base, parseUnary()); }
    return base;
  }

  function parseTerm(): number {
    let v = parseUnary();
    for (;;) {
      skip();
      const c = peek();
      if (c === '*' && s[i + 1] !== '*') { i++; v *= parseUnary(); }
      else if (c === '/') { i++; v /= parseUnary(); }
      else if (c === '%') { i++; v %= parseUnary(); }
      else return v;
    }
  }

  function parseExpr(): number {
    let v = parseTerm();
    for (;;) {
      skip();
      const c = peek();
      if (c === '+') { i++; v += parseTerm(); }
      else if (c === '-') { i++; v -= parseTerm(); }
      else return v;
    }
  }

  const result = parseExpr();
  skip();
  if (i < s.length) throw new Error(`Unexpected "${s[i]}" at position ${i}`);
  return result;
}

export async function toolCalc(expr: string): Promise<string> {
  try {
    const v = evaluateExpression(expr.trim());
    if (!Number.isFinite(v)) return `Result is not a finite number (${v}).`;
    return String(Math.round(v * 1e12) / 1e12);
  } catch (e: any) {
    return `Calc error: ${e?.message || e}`;
  }
}

/* ── DATE / DEVICE ─────────────────────────────────────────────────────── */
export async function toolDatetime(): Promise<string> {
  const now = new Date();
  let tz = 'unknown';
  let local = now.toString();
  try {
    tz = Intl.DateTimeFormat().resolvedOptions().timeZone || tz;
    local = now.toLocaleString('en-GB', { hour12: false });
  } catch { /* Intl unavailable */ }
  return `Local: ${local} (${tz})\nISO (UTC): ${now.toISOString()}\nUnix: ${Math.floor(now.getTime() / 1000)}`;
}

export async function toolDeviceInfo(): Promise<string> {
  const lines: string[] = [];
  const c: any = (Platform as any).constants || {};
  lines.push(`📱 ${c.Brand || ''} ${c.Model || ''} — Android ${c.Release || '?'} (API ${Platform.Version})`.trim());
  try {
    const free = await FileSystem.getFreeDiskStorageAsync();
    const total = await FileSystem.getTotalDiskCapacityAsync();
    lines.push(`💾 Storage: ${(free / 1e9).toFixed(1)} GB free / ${(total / 1e9).toFixed(1)} GB total`);
  } catch (e: any) {
    lines.push(`💾 Storage unavailable: ${e?.message || e}`);
  }
  lines.push(`📁 App folder: ${SANDBOX}`);
  lines.push(`📂 Workspace: ${WORKDIR}`);
  return lines.join('\n');
}

/* ── ACTIONS ───────────────────────────────────────────────────────────── */
export async function toolOpenUrl(arg: string): Promise<string> {
  const url = arg.trim();
  if (!/^[a-z][a-z0-9+.-]*:/i.test(url)) return 'Needs a full URL such as https://…, tel:…, mailto:…, geo:…';
  try {
    await Linking.openURL(url);
    return `Opened ${url}`;
  } catch (e: any) {
    return `Could not open: ${e?.message || e}`;
  }
}

export async function toolShareText(arg: string): Promise<string> {
  try {
    await Share.share({ message: arg });
    return 'Share sheet shown to the user.';
  } catch (e: any) {
    return `Share failed: ${e?.message || e}`;
  }
}

/* ── CLIPBOARD ─────────────────────────────────────────────────────────── */
export async function toolCopyClipboard(arg: string): Promise<string> {
  const text = arg;
  if (!text) return 'Nothing to copy (empty argument).';
  try {
    await Clipboard.setStringAsync(text);
    return `Copied ${text.length} chars to the clipboard.`;
  } catch (e: any) {
    return `Copy failed: ${e?.message || e}`;
  }
}

export async function toolReadClipboard(): Promise<string> {
  try {
    const has = await Clipboard.hasStringAsync();
    if (!has) return '(the clipboard is empty or holds a non-text item)';
    const text = await Clipboard.getStringAsync();
    return text || '(the clipboard is empty)';
  } catch (e: any) {
    return `Reading the clipboard failed: ${e?.message || e}`;
  }
}

export async function toolPermission(arg: string): Promise<string> {
  const a = arg.trim().toLowerCase();
  if (!a || a === 'status' || a === 'list') {
    const st = await getPermissionsStatus();
    return PERM_KEYS.map(k => `${k} (${PERMISSION_LABELS[k]}): ${st[k]}`).join('\n');
  }
  const word = a.replace(/^(request|check|grant)\s+/, '');
  const key = normalizePermKey(word);
  if (!key) return `Unknown permission "${word}". Available: ${PERM_KEYS.join(', ')}`;
  const r = await requestPermission(key);
  return `${key}: ${r.status} — ${r.message}`;
}

/* ── JAVASCRIPT (gated) ────────────────────────────────────────────────── */
export async function toolJavascript(code: string): Promise<string> {
  if (/while\s*\(\s*(true|1)\s*\)|for\s*\(\s*;\s*;\s*\)/.test(code)) {
    return 'Rejected: infinite loops are not allowed (they would freeze the app).';
  }
  const logs: string[] = [];
  const fmt = (v: any): string => {
    if (typeof v === 'string') return v;
    try { return JSON.stringify(v); } catch { return String(v); }
  };
  const fakeConsole = {
    log: (...a: any[]) => { logs.push(a.map(fmt).join(' ')); },
    info: (...a: any[]) => { logs.push(a.map(fmt).join(' ')); },
    warn: (...a: any[]) => { logs.push(`warn: ${a.map(fmt).join(' ')}`); },
    error: (...a: any[]) => { logs.push(`error: ${a.map(fmt).join(' ')}`); },
  };
  try {
    // eslint-disable-next-line no-new-func
    const fn = new Function('console', `"use strict"; return (async () => {\n${code}\n})();`);
    const timeout = new Promise<never>((_, rej) => setTimeout(() => rej(new Error('Timed out after 15s')), 15000));
    const ret = await Promise.race([fn(fakeConsole) as Promise<any>, timeout]);
    const parts: string[] = [];
    if (logs.length) parts.push(logs.join('\n'));
    if (ret !== undefined) parts.push(`return: ${fmt(ret)}`);
    return clip(parts.join('\n') || '(no output — use console.log or return a value)', 6000);
  } catch (e: any) {
    return `JavaScript error: ${e?.message || e}${logs.length ? `\n${logs.join('\n')}` : ''}`;
  }
}

/* ── MEMORY ────────────────────────────────────────────────────────────── */
type MemStore = Record<string, { value: string; ts: string }>;

/* ── INSTALL + LEARN A TOOL ────────────────────────────────────────────── */
// Installs a package into Alpine and returns a "card" (executables, version, --help) so the model knows HOW
// to run what it just installed. The how-to is also remembered as "tool:NAME" (recall: mem_get tool:NAME).
export async function toolInstallTool(arg: string): Promise<string> {
  const name = sanitizePkgName(arg);
  if (!name) return 'Give the package/tool name, e.g. ffmpeg, git, tesseract-ocr.';
  const out = await Terminal.run(installScript(name), 240);
  const r = parseInstallOutput(name, out);
  if (r.ok && r.memory) {
    try { await toolMemorySave(`tool:${name}`, r.memory); } catch { /* memory is best-effort */ }
  }
  return r.card;
}

async function loadMemory(): Promise<MemStore> {
  try {
    const raw = await AsyncStorage.getItem(MEMORY_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch { return {}; }
}
async function persistMemory(mem: MemStore): Promise<void> {
  await AsyncStorage.setItem(MEMORY_KEY, JSON.stringify(mem));
}

export async function toolMemorySave(key: string, value: string): Promise<string> {
  const k = key.trim();
  if (!k) return 'Memory key is empty. Usage: key | value';
  const mem = await loadMemory();
  mem[k] = { value: value.trim(), ts: new Date().toISOString() };
  await persistMemory(mem);
  return `Saved '${k}' to memory.`;
}
export async function toolMemoryGet(key: string): Promise<string> {
  const mem = await loadMemory();
  const item = mem[key.trim()];
  return item ? item.value : `No memory found for '${key.trim()}'`;
}
export async function toolMemoryList(): Promise<string> {
  const mem = await loadMemory();
  const keys = Object.keys(mem);
  if (keys.length === 0) return 'Memory is empty.';
  return keys.map(k => `• ${k}: ${mem[k].value.slice(0, 80)}`).join('\n');
}
export async function toolMemoryDelete(key: string): Promise<string> {
  const mem = await loadMemory();
  const k = key.trim();
  if (!(k in mem)) return `No memory found for '${k}'`;
  delete mem[k];
  await persistMemory(mem);
  return `Deleted '${k}' from memory.`;
}

/** Short memory digest injected into the system prompt (empty if none). */
export async function memoryDigest(maxChars = 600): Promise<string> {
  const mem = await loadMemory();
  const keys = Object.keys(mem);
  const notes = keys.filter(k => !k.startsWith('tool:')).map(k => `${k}: ${mem[k].value.slice(0, 100)}`);
  const tools = keys.filter(k => k.startsWith('tool:')).map(k => k.slice(5));
  if (tools.length) notes.push(`installed tools (how to run: mem_get tool:NAME): ${tools.join(', ')}`);
  return notes.join('; ').slice(0, maxChars);
}

/* ── LINUX (Alpine) STATE ──────────────────────────────────────────────── */
let linuxReady = false;
export const isLinuxReady = (): boolean => linuxReady;
/** Lets the engine tell the user "waiting for your approval" instead of looking frozen. */
export const toolNeedsConfirm = (name: string): boolean =>
  !!TOOLS.find(t => t.name === normalizeToolName(name))?.confirm;

/** Re-checks whether Alpine Linux is installed and usable; call at startup and after installing. */
export async function refreshLinuxState(): Promise<boolean> {
  try {
    const st = await Terminal.alpineStatus();
    linuxReady = !!(st && st.installed && st.prootPresent && st.tallocPresent && st.shmemPresent);
  } catch {
    linuxReady = false;
  }
  return linuxReady;
}

/* ── OPEN THE TERMINAL PANEL ───────────────────────────────────────────── */
let openTerminalHandler: (() => void) | null = null;
export function setOpenTerminalHandler(fn: (() => void) | null): void {
  openTerminalHandler = fn;
}
export async function toolOpenTerminal(): Promise<string> {
  if (!openTerminalHandler) return 'The terminal panel is not available.';
  openTerminalHandler();
  const mode = await Terminal.mode();
  return `Terminal panel opened for the user (shell: ${mode === 'alpine' ? 'Alpine Linux' : 'Android shell'}).`;
}

/* ── INSPECT ANY FILE (APK, zip, office, pdf, binary) ──────────────────── */
export async function toolInspectFile(arg: string): Promise<string> {
  const { uri } = resolvePath(arg.split('\n')[0]);
  const info = await FileSystem.getInfoAsync(uri, { size: true });
  if (!info.exists) return `File not found: ${uri}`;
  if (info.isDirectory) return `That is a directory. Use list_dir on: ${uri}`;
  const name = uri.split('/').pop() || 'file';
  return inspectFile({ uri, name, size: info.size }, 14000);
}

/* ── BROWSER (hidden WebView that runs JavaScript) ─────────────────────── */
function formatPage(r: PageResult): string {
  if (!r.ok) return `Browser error: ${r.error || 'unknown'}`;
  const links = (r.links || []).slice(0, 40);
  const linkLines = links.map(l => `[${l.n}] ${l.text} — ${l.href}`).join('\n');
  const elementLines = (r.elements || []).slice(0, 40).map(e => `[${e.n}] ${e.kind} "${e.label}"`).join('\n');
  return [
    `Title: ${r.title || '(none)'}`,
    `URL: ${r.url || ''}`,
    '',
    clip((r.text || '').trim() || '(no visible text)', 6000),
    '',
    links.length ? `Links (use click_link with the number):\n${linkLines}` : 'Links: none found',
    elementLines ? `Elements (use browser_click or browser_type with the number):\n${elementLines}` : 'Elements: none found',
  ].join('\n');
}

export async function toolBrowser(arg: string): Promise<string> {
  const url = arg.trim().split('\n')[0].trim();
  if (!/^https?:\/\//i.test(url)) return 'URL must start with http:// or https://';
  return formatPage(await browseOnPage(url));
}

export async function toolClickLink(arg: string): Promise<string> {
  const raw = arg.trim();
  const n = parseInt(raw, 10);
  const link = getLastLinks().find(l => l.n === n);
  if (!link) return `No link number ${raw} on the last page. Use browser first, then pick a number from its links.`;
  return formatPage(await browseOnPage(link.href));
}

/* ── TOOL EVALUATOR (reads a tool's README so the agent learns how to use it) */
export async function toolToolEvaluator(arg: string): Promise<string> {
  const a = arg.trim();
  const m = /github\.com\/([\w.-]+)\/([\w.-]+)/i.exec(a) || /^([\w.-]+)\/([\w.-]+)$/.exec(a);
  if (!m) return 'Give a GitHub URL or owner/repo, e.g. https://github.com/owner/repo or owner/repo.';
  const owner = m[1];
  const repo = m[2].replace(/\.git$/i, '');
  const readme = `https://raw.githubusercontent.com/${owner}/${repo}/HEAD/README.md`;
  const out = await toolFetchUrl(JSON.stringify({ url: readme, max: 12000 }));
  return `README of ${owner}/${repo} (read it to decide how to install and call the tool):\n${out}`;
}

/* ── SKILLS + TOOL SEARCH (load procedures, find tools by keyword) ────── */
async function toolSearch(arg: string): Promise<string> {
  const words = arg.toLowerCase().split(/\s+/).filter(w => w.length > 1);
  if (!words.length) return 'Give keywords, e.g. "download file" or "github".';
  const scored = TOOLS.map(t => {
    const u = typeof t.usage === 'function' ? t.usage() : t.usage;
    const hay = (t.name + ' ' + u).toLowerCase();
    const score = words.reduce((n, w) => n + (hay.includes(w) ? 1 : 0), 0);
    return { t, u, score };
  }).filter(x => x.score > 0).sort((a, b) => b.score - a.score).slice(0, 6);
  if (!scored.length) return 'No matching tool. Try other keywords.';
  return scored.map(x => `${x.t.name}: ${x.u.slice(0, 220)}`).join('\n');
}

/* ── SECRETS + GITHUB API (the agent uses tokens by name, never sees them) ─ */
async function toolRequestSecret(arg: string): Promise<string> {
  const [namePart, ...rest] = arg.split('|');
  const name = namePart.trim().toUpperCase().replace(/[^A-Z0-9_]/g, '_');
  const reason = rest.join('|').trim();
  if (!name) return 'Give a name, e.g. GITHUB_TOKEN | to read private repos.';
  const ok = await ensureSecret(name, reason);
  return ok
    ? `Secret ${name} is stored. Use it by name (for example github_api with "secret": "${name}"). Never ask the user to paste it in chat.`
    : `Secret ${name} was not provided.`;
}

async function toolGithubApi(arg: string): Promise<string> {
  let j: any;
  try { j = JSON.parse(arg); } catch { j = { path: arg.trim() }; }
  const path = String(j.path || '').trim();
  if (!/^\/[\w./?=&%-]+$/.test(path)) return 'Give a path such as /repos/OWNER/REPO/releases/latest.';
  const method = String(j.method || 'GET').toUpperCase();
  if (!['GET', 'POST', 'PATCH', 'PUT', 'DELETE'].includes(method)) return 'Unsupported method.';
  const headers: Record<string, string> = { Accept: 'application/vnd.github+json', 'User-Agent': UA };
  let token: string | null = null;
  const secretName = String(j.secret || '').trim().toUpperCase();
  if (secretName) {
    token = await getSecret(secretName);
    if (!token) return `Secret ${secretName} is not stored yet. Call request_secret first.`;
    headers.Authorization = `Bearer ${token}`;
  }
  try {
    const r = await fetch(`https://api.github.com${path}`, {
      method,
      headers,
      body: j.body ? JSON.stringify(j.body) : undefined,
    });
    let text = await r.text();
    if (token) text = text.split(token).join('[secret hidden]');
    return `HTTP ${r.status}\n${clip(text, 8000)}`;
  } catch (e: any) {
    return `GitHub request failed: ${e?.message || e}`;
  }
}

/* ── TOOL REGISTRY ─────────────────────────────────────────────────────── */
export type ToolName =
  | 'search' | 'fetch_url' | 'download_file' | 'terminal' | 'javascript' | 'calc' | 'datetime'
  | 'read_file' | 'write_file' | 'list_dir' | 'delete_file' | 'inspect_file' | 'open_terminal'
  | 'device_info' | 'open_url' | 'share_text' | 'permission'
  | 'mem_save' | 'mem_get' | 'mem_list' | 'mem_delete'
  | 'copy_clipboard' | 'read_clipboard'
  | 'python' | 'pkg_install' | 'install_tool'
  | 'browser' | 'click_link' | 'tool_evaluator'
  | 'skill' | 'tool_search'
  | 'request_secret' | 'github_api'
  | 'set_alarm' | 'remind' | 'note_add' | 'note_list'
  | 'screen_read' | 'screen_tap' | 'screen_type' | 'screen_back' | 'screen_allow' | 'screen_enable' | 'open_app'
  | 'bubble_show' | 'bubble_hide' | 'session_start' | 'session_stop' | 'session_status'
  | 'browser_click' | 'browser_type' | 'model_pick';

interface ToolSpec {
  name: ToolName;
  /** Shown to the model. */
  usage: string | (() => string);
  /** Needs the user's OK before running. */
  confirm: boolean;
  /** Advertised to the model? May depend on runtime state (e.g. Linux installed). */
  advertise: boolean | (() => boolean);
  run: (arg: string) => Promise<string>;
}

const TOOLS: ToolSpec[] = [
  { name: 'search', confirm: false, advertise: true,
    usage: 'search the web. Argument: the query. Returns titles, links and snippets.',
    run: toolWebSearch },
  { name: 'fetch_url', confirm: false, advertise: true,
    usage: 'read a web page or call an API (GET/POST/...). Argument: a URL, or JSON {"url","method","headers","body","max"}. HTML is converted to readable text; redirects and retries are handled.',
    run: toolFetchUrl },
  { name: 'download_file', confirm: true, advertise: true,
    usage: 'download a file from the internet into the phone. Argument: url | path (path optional; relative = app workspace).',
    run: toolDownloadFile },
  { name: 'terminal', confirm: true, advertise: true,
    usage: () => linuxReady
      ? 'run a shell command inside Alpine Linux on the phone (apk, python3, node, git, curl, grep, sed, awk …). The folder you cd into is remembered between calls. Multi-line scripts work. Start with a line "#timeout=120" for long jobs. Argument: the command.'
      : 'run a shell command on the phone (Android toybox: ls cat grep sed find tar ps df ping …). The folder you cd into is remembered. Multi-line scripts work. Start with a line "#timeout=120" for long jobs. No python/package manager until Alpine Linux is installed from Settings. Argument: the command.',
    run: toolTerminal },
  { name: 'javascript', confirm: true, advertise: true,
    usage: 'run JavaScript for calculations or data processing. Use console.log(...) or return a value. No infinite loops.',
    run: toolJavascript },
  { name: 'calc', confirm: false, advertise: true,
    usage: 'evaluate a math expression. Supports + - * / % ^ ( ), sqrt, sin, cos, tan, log, ln, abs, round, floor, ceil, min, max, pow, pi, e.',
    run: toolCalc },
  { name: 'datetime', confirm: false, advertise: true,
    usage: 'current date, time and timezone. No argument.',
    run: () => toolDatetime() },
  { name: 'read_file', confirm: false, advertise: true,
    usage: 'read a text file. Argument: a path (relative paths are inside the app workspace; absolute paths like /storage/emulated/0/Download/x.txt work if permission is granted).',
    run: toolReadFile },
  { name: 'write_file', confirm: true, advertise: true,
    usage: 'write a text file. Argument: first line = path, the following lines = file content. Allowed: app workspace or /storage/emulated/0.',
    run: toolWriteFile },
  { name: 'list_dir', confirm: false, advertise: true,
    usage: 'list a folder. Argument: a path (empty = app workspace).',
    run: toolListDir },
  { name: 'inspect_file', confirm: false, advertise: true,
    usage: 'analyse ANY file: APK (package, permissions, components, signing), zip/office/pdf (text), or unknown binaries. Argument: a path.',
    run: toolInspectFile },
  { name: 'open_terminal', confirm: false, advertise: true,
    usage: 'open the terminal window for the user. No argument.',
    run: () => toolOpenTerminal() },
  { name: 'delete_file', confirm: true, advertise: true,
    usage: 'delete a file or folder inside the app workspace. Argument: the path.',
    run: toolDeleteFile },
  { name: 'device_info', confirm: false, advertise: true,
    usage: 'phone model, Android version, free storage, app folders. No argument.',
    run: () => toolDeviceInfo() },
  { name: 'open_url', confirm: true, advertise: true,
    usage: 'open a link or app on the phone (https://, tel:, mailto:, geo:). Argument: the URL.',
    run: toolOpenUrl },
  { name: 'share_text', confirm: true, advertise: true,
    usage: 'open the Android share sheet with some text. Argument: the text.',
    run: toolShareText },
  { name: 'copy_clipboard', confirm: false, advertise: true,
    usage: 'copy text to the system clipboard so the user can paste it anywhere. Argument: the text to copy.',
    run: toolCopyClipboard },
  { name: 'read_clipboard', confirm: false, advertise: true,
    usage: 'read whatever text is currently on the system clipboard. No argument.',
    run: () => toolReadClipboard() },
  { name: 'permission', confirm: false, advertise: true,
    usage: 'check or request an Android permission. Argument: "status", or one of storage, all_files, camera, microphone, location, notifications.',
    run: toolPermission },
  { name: 'mem_save', confirm: false, advertise: true,
    usage: 'remember something across chats. Argument: key | value',
    run: a => {
      const [k, ...rest] = a.split('|');
      return toolMemorySave(k, rest.join('|'));
    } },
  { name: 'mem_get', confirm: false, advertise: true,
    usage: 'read one saved memory. Argument: key.',
    run: toolMemoryGet },
  { name: 'mem_list', confirm: false, advertise: true,
    usage: 'list all saved memories. No argument.',
    run: () => toolMemoryList() },
  { name: 'mem_delete', confirm: false, advertise: true,
    usage: 'delete one saved memory. Argument: key.',
    run: toolMemoryDelete },
  // Real tools once Alpine Linux is installed (Settings → Linux); otherwise hidden but still answer honestly.
  { name: 'python', confirm: true, advertise: () => linuxReady,
    usage: 'run Python 3 code inside Alpine Linux and return its output. Argument: the code.',
    run: async a => {
      const r = await Terminal.runPython(a, 60);
      return r;
    } },
  { name: 'pkg_install', confirm: true, advertise: () => linuxReady,
    usage: 'install Alpine packages with apk. Argument: package names separated by spaces (e.g. python3 py3-pip git nodejs).',
    run: async a => Terminal.installPackages(a) },
  { name: 'install_tool', confirm: true, advertise: () => linuxReady,
    usage: 'install any command-line tool into Alpine Linux and learn how to run it. Argument: the package name (e.g. ffmpeg, git, tesseract-ocr). Returns its executables, version and --help, and remembers them.',
    run: a => toolInstallTool(a) },
  { name: 'browser', confirm: false, advertise: true,
    usage: 'open a web page in a hidden browser that runs JavaScript, and get its visible text plus a numbered list of links. Use it when fetch_url misses content or when you need to follow links. Argument: a URL.',
    run: toolBrowser },
  { name: 'click_link', confirm: false, advertise: true,
    usage: 'open link number N from the last browser page. Argument: the number.',
    run: toolClickLink },
  { name: 'tool_evaluator', confirm: false, advertise: true,
    usage: 'read a tool or library README (GitHub URL or owner/repo) to learn how to install and use it. Argument: the GitHub URL or owner/repo.',
    run: toolToolEvaluator },
  { name: 'skill', confirm: false, advertise: true,
    usage: () => 'load the step-by-step procedure for a task before starting it. Use it for installing or running programs, and for Python projects. Available skills:\n' + skillsCatalog() + '\nArgument: the skill name.',
    run: async (arg: string) => loadSkill(arg) ?? `Unknown skill "${arg.trim()}". Available: ${SKILLS.map(s => s.name).join(', ')}` },
  { name: 'tool_search', confirm: false, advertise: true,
    usage: 'find the right tool when unsure which one to use. Argument: keywords, e.g. "download file" or "run python". Returns matching tool names and how to use them.',
    run: toolSearch },
  { name: 'request_secret', confirm: false, advertise: true,
    usage: 'ask the user to enter a secret (API token, password) in a secure box. The value is stored encrypted and never returned to you. Argument: NAME | reason. Example: GITHUB_TOKEN | to read private repos.',
    run: toolRequestSecret },
  { name: 'github_api', confirm: true, advertise: true,
    usage: 'call the GitHub REST API. Argument: JSON {"path": "/repos/OWNER/REPO/releases/latest", "method": "GET", "body": {...}, "secret": "GITHUB_TOKEN"}. Set "secret" only when you need private access or a write; the token is added for you and never shown to you.',
    run: toolGithubApi },
  { name: 'set_alarm', confirm: false, advertise: true,
    usage: 'set an alarm. Opens the clock app with the time filled in; the user confirms there. Argument: HH:MM | label, e.g. 07:30 | Gym.',
    run: toolSetAlarm },
  { name: 'remind', confirm: false, advertise: true,
    usage: 'schedule a phone notification reminder. Argument: minutes | text, e.g. 10 | call Ahmed.',
    run: toolRemind },
  { name: 'note_add', confirm: false, advertise: true,
    usage: 'save a note in the phone notebook. Argument: the note text.',
    run: toolNoteAdd },
  { name: 'note_list', confirm: false, advertise: true,
    usage: 'show the latest notes from the phone notebook. No argument needed.',
    run: async () => toolNoteList() },
  { name: 'screen_read', confirm: false, advertise: true,
    usage: 'read the visible screen of an allowed app: a numbered list of text, buttons and input fields. Call it before tapping or typing. No argument.',
    run: async () => toolScreenRead() },
  { name: 'screen_tap', confirm: false, advertise: true,
    usage: 'tap a button from the last screen_read by its number. Argument: the number, e.g. 4.',
    run: toolScreenTap },
  { name: 'screen_type', confirm: false, advertise: true,
    usage: 'type text into an input field from the last screen_read. Argument: number | text, e.g. 3 | hello.',
    run: toolScreenType },
  { name: 'screen_back', confirm: false, advertise: true,
    usage: 'press the phone back button. No argument.',
    run: async () => toolScreenBack() },
  { name: 'screen_allow', confirm: true, advertise: true,
    usage: 'ask the user to allow an app for screen control. Argument: package names, e.g. com.whatsapp. The user must confirm.',
    run: toolScreenAllow },
  { name: 'screen_enable', confirm: false, advertise: true,
    usage: 'open the Accessibility settings page so the user can turn on GVR Agent. No argument.',
    run: async () => toolScreenEnable() },
  { name: 'open_app', confirm: false, advertise: true,
    usage: 'open an installed app by its package name, e.g. com.whatsapp. Use it when the user asks to open an app. Then screen_allow it if you need to read its screen.',
    run: toolOpenApp },
  { name: 'bubble_show', confirm: false, advertise: true,
    usage: 'show a floating stop button over every app. The user taps it to pause the agent. Call it before the agent acts on the screen.',
    run: async () => toolBubbleShow() },
  { name: 'bubble_hide', confirm: false, advertise: true,
    usage: 'hide the floating stop button. No argument.',
    run: async () => toolBubbleHide() },
  { name: 'session_start', confirm: true, advertise: true,
    usage: 'start a chat session with a person in an open app: the goal, the message limit and the time limit. The user must confirm. Argument: contact | goal | max messages | minutes. Example: Ahmed | catch up | 20 | 30.',
    run: toolSessionStart },
  { name: 'session_stop', confirm: false, advertise: true,
    usage: 'stop the chat session now. No argument.',
    run: async () => toolSessionStop() },
  { name: 'session_status', confirm: false, advertise: true,
    usage: 'show the active chat session: contact, messages sent, time left. No argument.',
    run: async () => toolSessionStatus() },
  { name: 'browser_click', confirm: false, advertise: true,
    usage: 'click an element of the last browser page by its number, such as a button or a link. The page is read again after the click. Argument: the number.',
    run: async (arg: string) => {
      const n = parseInt(arg.trim(), 10);
      if (!n) return 'Give the element number from the last browser page.';
      return formatPage(await actOnPage('click', n, ''));
    } },
  { name: 'browser_type', confirm: true, advertise: true,
    usage: 'type text into an input field of the last browser page. The user must confirm. Argument: number | text, e.g. 3 | hello.',
    run: async (arg: string) => {
      const i = arg.indexOf('|');
      if (i < 0) return 'Give: number | text';
      const n = parseInt(arg.slice(0, i).trim(), 10);
      if (!n) return 'Give the element number from the last browser page.';
      return formatPage(await actOnPage('type', n, arg.slice(i + 1)));
    } },
  { name: 'model_pick', confirm: false, advertise: true,
    usage: 'recommend the best model and speed settings for this phone. Argument: the phone RAM in GB, e.g. 16.',
    run: async (arg: string) => {
      const ram = parseFloat(arg.trim());
      if (!(ram > 0)) return 'Give the phone RAM in GB, e.g. 16.';
      return describePick(ram);
    } },
];

const isAdvertised = (t: ToolSpec): boolean => (typeof t.advertise === 'function' ? t.advertise() : t.advertise);
const usageOf = (t: ToolSpec): string => (typeof t.usage === 'function' ? t.usage() : t.usage);

export function availableTools(): ToolName[] {
  return TOOLS.filter(isAdvertised).map(t => t.name);
}
export const AVAILABLE_TOOLS: ToolName[] = TOOLS.filter(t => typeof t.advertise === 'boolean' && t.advertise).map(t => t.name);

/** The small set shown to weak models so they are not overwhelmed. */
export const CORE_TOOLS: ToolName[] = ['search', 'fetch_url', 'terminal', 'calc', 'datetime', 'read_file', 'list_dir', 'open_terminal', 'mem_save', 'mem_get'];

/** Lines for the system prompt: "- name: usage" */
export function toolPromptLines(only?: ToolName[]): string {
  return TOOLS.filter(t => isAdvertised(t) && (!only || only.includes(t.name)))
    .map(t => `- ${t.name}: ${usageOf(t)}`).join('\n');
}

const ALIASES: Record<string, ToolName> = {
  web_search: 'search', websearch: 'search', google: 'search',
  fetch: 'fetch_url', http: 'fetch_url', get: 'fetch_url',
  download: 'download_file', wget: 'download_file', save_url: 'download_file', curl: 'fetch_url', browse: 'fetch_url', url: 'fetch_url',
  package_installer: 'install_tool', code_executor: 'python', open_page: 'browser', click: 'click_link',
  shell: 'terminal', bash: 'terminal', sh: 'terminal', cmd: 'terminal', command: 'terminal',
  js: 'javascript', node: 'javascript', code: 'javascript',
  calculator: 'calc', math: 'calc',
  time: 'datetime', date: 'datetime', now: 'datetime',
  read: 'read_file', cat: 'read_file', write: 'write_file', ls: 'list_dir', list: 'list_dir',
  rm: 'delete_file', delete: 'delete_file',
  device: 'device_info', info: 'device_info',
  open: 'open_url', share: 'share_text',
  copy: 'copy_clipboard', clipboard_copy: 'copy_clipboard', paste: 'read_clipboard', clipboard_read: 'read_clipboard', clipboard: 'read_clipboard',
  inspect: 'inspect_file', analyze: 'inspect_file', apk_info: 'inspect_file', file_info: 'inspect_file',
  terminal_open: 'open_terminal', open_shell: 'open_terminal',
  py: 'python', python3: 'python', apk: 'pkg_install', install: 'pkg_install',
  permissions: 'permission', perm: 'permission',
  memory_save: 'mem_save', remember: 'mem_save', memory_get: 'mem_get', memory_list: 'mem_list',
  memory_delete: 'mem_delete', forget: 'mem_delete',
  get_tool: 'install_tool', install_package: 'install_tool', learn_tool: 'install_tool',
};

export function normalizeToolName(raw: string): string {
  const k = raw.trim().toLowerCase().replace(/[\s-]+/g, '_');
  return ALIASES[k] ?? k;
}

/* ── NATIVE (OpenAI-style) TOOL SCHEMAS ────────────────────────────────── */
// llama.rn can hand these to the model as `tools` (Jinja chat template + grammar-constrained
// tool-call JSON). That is far more reliable than asking a model to hand-write <tool> tags:
// the call is always well-formed JSON with the right argument names.
interface NativeSpec {
  props: Record<string, { type: 'string'; description: string }>;
  required: string[];
  toArg: (o: Record<string, unknown>) => string;
}
const P = (description: string) => ({ type: 'string' as const, description });
const asStr = (v: unknown): string =>
  typeof v === 'string' ? v : v == null ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v);
const NO_ARGS: NativeSpec = { props: {}, required: [], toArg: () => '' };

const NATIVE: Partial<Record<ToolName, NativeSpec>> = {
  search: { props: { query: P('what to search the web for') }, required: ['query'], toArg: o => asStr(o.query) },
  fetch_url: { props: { url: P('http(s) URL to read') }, required: ['url'], toArg: o => asStr(o.url) },
  download_file: {
    props: { url: P('file URL'), path: P('where to save it (optional)') }, required: ['url'],
    toArg: o => (o.path ? `${asStr(o.url)} | ${asStr(o.path)}` : asStr(o.url)),
  },
  terminal: { props: { command: P('shell command to run (multi-line scripts allowed)') }, required: ['command'], toArg: o => asStr(o.command) },
  javascript: { props: { code: P('JavaScript code') }, required: ['code'], toArg: o => asStr(o.code) },
  calc: { props: { expression: P('math expression, e.g. 12*(3+4)') }, required: ['expression'], toArg: o => asStr(o.expression) },
  datetime: NO_ARGS,
  read_file: { props: { path: P('file path') }, required: ['path'], toArg: o => asStr(o.path) },
  write_file: {
    props: { path: P('file path'), content: P('text to write into the file') }, required: ['path', 'content'],
    toArg: o => `${asStr(o.path)}\n${asStr(o.content)}`,
  },
  list_dir: { props: { path: P('folder path (empty = app workspace)') }, required: [], toArg: o => asStr(o.path) },
  inspect_file: { props: { path: P('file path') }, required: ['path'], toArg: o => asStr(o.path) },
  open_terminal: NO_ARGS,
  delete_file: { props: { path: P('file or folder path') }, required: ['path'], toArg: o => asStr(o.path) },
  device_info: NO_ARGS,
  open_url: { props: { url: P('URL or app link (https:, tel:, mailto:, geo:)') }, required: ['url'], toArg: o => asStr(o.url) },
  share_text: { props: { text: P('text to share') }, required: ['text'], toArg: o => asStr(o.text) },
  copy_clipboard: { props: { text: P('text to copy') }, required: ['text'], toArg: o => asStr(o.text) },
  read_clipboard: NO_ARGS,
  permission: {
    props: { name: P('"status" or one of storage, all_files, camera, microphone, location, notifications') }, required: [],
    toArg: o => asStr(o.name) || 'status',
  },
  mem_save: {
    props: { key: P('short memory name'), value: P('what to remember') }, required: ['key', 'value'],
    toArg: o => `${asStr(o.key)} | ${asStr(o.value)}`,
  },
  mem_get: { props: { key: P('memory name') }, required: ['key'], toArg: o => asStr(o.key) },
  mem_list: NO_ARGS,
  mem_delete: { props: { key: P('memory name') }, required: ['key'], toArg: o => asStr(o.key) },
  python: { props: { code: P('Python 3 code to run') }, required: ['code'], toArg: o => asStr(o.code) },
  install_tool: { props: { name: P('tool/package name, e.g. ffmpeg, git, tesseract-ocr') }, required: ['name'], toArg: o => asStr(o.name) },
  pkg_install: { props: { packages: P('space-separated Alpine package names, e.g. "ffmpeg tesseract-ocr"') }, required: ['packages'], toArg: o => asStr(o.packages) },
};

/** Tools offered to capable models through the native `tools` parameter (kept short: every tool costs prompt tokens). */
export const NATIVE_TOOLS_DEFAULT: ToolName[] = [
  'search', 'fetch_url', 'terminal', 'calc', 'datetime', 'read_file', 'write_file', 'list_dir',
  'install_tool', 'mem_save', 'mem_get', 'python',
];
/** Big/slow models pay for every prompt token — give them only the essentials (the router covers time/files). */
export const NATIVE_TOOLS_LEAN: ToolName[] = ['search', 'fetch_url', 'terminal', 'install_tool', 'calc', 'browser', 'click_link', 'skill', 'tool_search'];

/** OpenAI-style function schemas for llama.rn's `tools` option. */
export function nativeToolSpecs(only: ToolName[] = NATIVE_TOOLS_DEFAULT): object[] {
  return TOOLS
    .filter(t => isAdvertised(t) && only.includes(t.name) && NATIVE[t.name])
    .map(t => {
      const n = NATIVE[t.name] as NativeSpec;
      let description = usageOf(t).replace(/\s*Arguments?:[^.]*\.?/gi, ' ').replace(/\s+/g, ' ').trim();
      const cut = description.indexOf('. ');
      if (cut > 20) description = description.slice(0, cut + 1);   // first sentence only: every token counts on a phone
      description = description.slice(0, 130);
      return {
        type: 'function',
        function: { name: t.name, description, parameters: { type: 'object', properties: n.props, required: n.required } },
      };
    });
}

/** Turns the named arguments of a native tool call back into the single string our tools take. */
export function nativeArgToString(tool: string, args: unknown): string {
  if (typeof args === 'string') return args;
  if (!args || typeof args !== 'object') return '';
  const o = args as Record<string, unknown>;
  const spec = NATIVE[normalizeToolName(tool) as ToolName];
  if (spec) return spec.toArg(o);
  const first = Object.values(o).find(v => typeof v === 'string') as string | undefined;
  return first ?? JSON.stringify(o);
}

/* ── DESTRUCTIVE COMMAND GUARD ─────────────────────────────────────────── */
// Shared storage is mounted inside Alpine (/sdcard), so a hallucinated `rm -rf` could wipe real files.
// These always need an explicit OK — even if the user ticked "always allow" for the terminal.
const DESTRUCTIVE_CMD: RegExp[] = [
  /\brm\s+(?:-\S*\s+)*-\S*[rRfF]/,
  /\brm\s+--(?:recursive|force)/,
  /\b(?:mkfs(?:\.\w+)?|shred|wipefs|fdisk|sfdisk|parted)\b/,
  /\bdd\b[^\n]*\bof=/,
  /\bfind\b[^\n]*(?:-delete|-exec\s+rm)/,
  />\s*\/dev\/(?:sd|mmc|block|nvme)/,
  /\b(?:chmod|chown)\s+-R\b/,
  /:\(\)\s*\{\s*:\|:&\s*\}\s*;\s*:/,
];
export const isDestructiveCommand = (cmd: string): boolean => DESTRUCTIVE_CMD.some(re => re.test(cmd));

export async function dispatchTool(rawTool: string, arg: string): Promise<string> {
  const name = normalizeToolName(rawTool);
  const spec = TOOLS.find(t => t.name === name);
  if ((name === 'python' || name === 'pkg_install') && !linuxReady) {
    return 'Alpine Linux is not installed yet. Ask the user to install it from Settings → Linux, then try again.';
  }
  if (!spec) return `Unknown tool '${rawTool}'. Available: ${availableTools().join(', ')}`;

  const risky = spec.name === 'terminal' && isDestructiveCommand(arg);
  if (spec.confirm && (risky || !alwaysAllowed.has(spec.name))) {
    if (!confirmHandler) return 'Denied: no confirmation UI is available for this action.';
    const decision = await confirmHandler(spec.name, risky ? `⚠️ أمر خطير (حذف/تعديل واسع):\n${arg}` : arg);
    if (decision === 'deny') return 'The user denied this action. Do not retry it; explain or ask what they prefer.';
    if (decision === 'always' && !risky) alwaysAllowed.add(spec.name);
  }

  try {
    return await spec.run(arg);
  } catch (e: any) {
    return `Tool error (${spec.name}): ${e?.message || e}`;
  }
}
