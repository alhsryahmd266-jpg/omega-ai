/**
 * agentLogic.ts — pure helpers for the agent (NO React Native imports)
 * ====================================================================
 * Everything here is plain TypeScript so it can be unit-tested in Node
 * (see GVRChat/tests). The RN-specific glue lives in tools.ts / gvrEngine.ts.
 *
 *  1. Autonomy + command risk        3. Aider-style SEARCH/REPLACE edits
 *  2. Result classification + text   4. Mission journal (long-running memory)
 *     rescue (shell blocks, "continue?")  5. Memory ranking (BM25-lite)
 */

/* ═══ 1. AUTONOMY + COMMAND RISK ═══════════════════════════════════════════ */
export type AutonomyMode = 'auto' | 'ask';
export interface AutonomyState {
  /** 'auto' = tools run by themselves. 'ask' = every tool that has side effects asks first. */
  mode: AutonomyMode;
  /** In 'auto' mode, still ask before commands that could destroy things OUTSIDE the app's own folders. */
  guard: boolean;
}
export const DEFAULT_AUTONOMY: AutonomyState = { mode: 'auto', guard: true };

/** Dangerous wherever they point: they act on devices or on the whole filesystem. */
const ALWAYS_RISKY: RegExp[] = [
  /\b(?:mkfs(?:\.\w+)?|wipefs|fdisk|sfdisk|parted|blkdiscard)\b/,
  /\bdd\b[^\n]*\bof=\/dev\//,
  />\s*\/dev\/(?:sd|mmc|block|nvme)/,
  /:\(\)\s*\{\s*:\|:&\s*\}\s*;\s*:/,
  /--no-preserve-root/,
];

/** Destructive verbs. Only a problem when they can reach files the user cares about. */
export const DESTRUCTIVE_CMD: RegExp[] = [
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

/** Absolute paths that belong to the app / the sandboxed Linux (safe to wipe). */
const SAFE_ABS =
  /^\/(?:workspace|tmp|root|home|var\/tmp)(?:\/|$)|^\/data\/(?:user\/0|data)\/[^/]+\/(?:files|cache)(?:\/|$)|^\/dev\/(?:null|zero|stdout|stderr|stdin|urandom|random)$/;

/** Replaces $VAR / ${VAR} when the same command assigns them (e.g. `OUT=/workspace/x; rm -rf "$OUT"`). */
function expandKnownVars(cmd: string): string {
  const vars: Record<string, string> = { HOME: '/root', TMPDIR: '/tmp' };
  const re = /(?:^|[;&\n])\s*(?:export\s+|local\s+)?([A-Za-z_]\w*)=("([^"\n]*)"|'([^'\n]*)'|[^\s;&|]+)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(cmd))) vars[m[1]] = m[3] ?? m[4] ?? m[2];
  return cmd.replace(/\$\{(\w+)\}|\$(\w+)/g, (all, a, b) => {
    const k = (a || b) as string;
    return vars[k] !== undefined ? vars[k] : all;
  });
}

const stripQuotes = (w: string): string => w.replace(/^['"]+|['"]+$/g, '');

/** True when a path/word in the command could point outside the app's own folders. */
export function reachesOutside(cmdRaw: string): boolean {
  const cmd = expandKnownVars(cmdRaw);
  if (/\$\(|`/.test(cmd)) return true;                         // command substitution: unknowable
  for (const w0 of cmd.split(/[\s;|&<>()]+/)) {
    const w = stripQuotes(w0);
    if (!w) continue;
    if (w.includes('$')) return true;                          // unresolved variable (could be empty → "/")
    if (/(^|\/)\.\.(\/|$)/.test(w)) return true;               // directory traversal
    if (w === '/' || w === '/*' || w === '/.') return true;
    const eq = w.indexOf('=');
    const val = eq > 0 && !w.startsWith('/') ? w.slice(eq + 1) : w;
    if (val.startsWith('/') && !SAFE_ABS.test(val)) return true;
    if (val.startsWith('~') && !/^~(?:\/|$)/.test(val)) return true;   // ~user
  }
  return false;
}

/** Is this working directory inside the app's own folders? ('' = the default workspace.) */
export const cwdIsSafe = (cwd: string): boolean => !cwd || !cwd.startsWith('/') || SAFE_ABS.test(cwd);

/** 'risky' = ask the user (in guard mode) before running. Plain commands and contained cleanups are 'safe'. */
export function commandRisk(cmd: string, cwd = ''): 'safe' | 'risky' {
  if (ALWAYS_RISKY.some(re => re.test(cmd))) return 'risky';
  if (!isDestructiveCommand(cmd)) return 'safe';
  if (!cwdIsSafe(cwd)) return 'risky';
  return reachesOutside(cmd) ? 'risky' : 'safe';
}

/* ═══ 2. RESULT CLASSIFICATION + TEXT RESCUE ═══════════════════════════════ */
export type ToolOutcome = 'ok' | 'denied' | 'error';

export const DENIED_PREFIX = 'The user denied this action';
export const STOPPED_PREFIX = 'Stopped by the user';

/** Used by the UI (✓ / ⛔ / ⚠️) and the journal. */
export function toolOutcome(result: string): ToolOutcome {
  const s = (result || '').trimStart();
  if (/^(?:The user denied|Denied:|Stopped by the user)/i.test(s)) return 'denied';
  if (/^(?:Tool error|Unknown tool|Refused:|Write failed|Delete failed|List failed|File not found|Directory not found|Path not found|Could not read|Alpine Linux is not installed|Edit failed|Empty command|Copy failed|Upload failed|Download failed|Fetch error|Search failed|Calc error|JavaScript error|Share failed|Invalid JSON|URL must start|Reading the clipboard failed)/i.test(s)) return 'error';
  // The exit code is at the END of a failed command's output, possibly followed by a hint line the app adds.
  if (/\(exit code (?!0\b)\d+[^)]*\)/.test(s.slice(-600))) return 'error';
  return 'ok';
}

/** NFKD when the engine supports it; otherwise the text as it is (the marks are removed by the regex below anyway). */
const nfkd = (s: string): string => (typeof s.normalize === 'function' ? s.normalize('NFKD') : s);

/**
 * Letters and digits WITHOUT Unicode property escapes (\p{L}): Hermes, the engine that runs this code on the phone,
 * does not guarantee them. Covers Latin (incl. accents), Arabic, Arabic supplement/extended and presentation forms.
 */
const LETTERS = 'A-Za-z0-9\\u00C0-\\u024F\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF';

const stripMarks = (s: string): string =>
  nfkd(s).replace(/[\u064B-\u065F\u0670\u0640]/g, '')
    .replace(/[أإآٱ]/g, 'ا').replace(/ى/g, 'ي').replace(/ة/g, 'ه');

/** "نعم", "قلت لك نعم", "yes do everything needed", "كمل" … a short approval of a plan the assistant proposed. */
export function isAffirmation(text: string): boolean {
  const t = stripMarks(text.toLowerCase()).replace(/[.!،,؟?~"'`]+/g, ' ').replace(/\s+/g, ' ').trim();
  if (!t) return false;
  const words = t.split(' ');
  if (words.length > 8) return false;
  const START = /^(?:(?:قلت(?:\s*ل(?:ك|كم|ي))?)\s+)?(?:نعم|ايوه|ايوا|اه|اجل|تمام|ماشي|موافق|اوكي|اوك|اكيد|طبعا|يلا|كمل|استمر|نفذ|نفذها|ابدا|ابدأ|افعل|اعمل|go|yes|yep|yeah|yup|sure|ok|okay|proceed|continue|do it|go ahead|please do)(?=\s|$)/;
  return START.test(t);
}

/** The model ends with "Do you want to continue?" instead of acting. */
export function endsWithConfirmQuestion(text: string): boolean {
  const tail = stripMarks(text.slice(-420).toLowerCase());
  const phrase =
    /(?:هل\s+(?:تريد|ترغب|تود|تحب|نكمل|اكمل|ابدا|انفذ|اقوم|استمر|اتابع|نتابع)|متابعه هذه الخطوه|المتابعه\s*\?|تحب\s+(?:اكمل|انفذ|ابدا)|do you want me to|would you like me to|shall i (?:proceed|continue|go)|should i (?:proceed|continue|go)|want me to (?:proceed|continue|go ahead)|do you want to (?:proceed|continue)|ready to proceed)/;
  return phrase.test(tail) && /[؟?]/.test(tail);
}

const HOW_TO = /^\s*(?:ازاي|كيف|كيفيه|how\b|what\b|why\b|ليه|لماذا|ما\s|ما هو|ما هي|ايه|اشرح|explain|عايز افهم|معني)/i;
const ACTION_VERB =
  /(?:^|\s)(?:فك|فكك|فكوا|اعمل|اعملي|نفذ|شغل|ثبت|نزل|حمل|عدل|غير|صلح|احذف|امسح|ابني|اعد|رتب|ضيف|اضف|اكتب|انشئ|ولد|اضغط|ركب|استخرج|حلل|افحص|جرب|ابدا|كمل|استخدم|سلمني|ارفع|وقع|رقع|اصلح|بدل|انسخ|انقل|ادمج|حول|run|execute|install|download|unzip|extract|decompile|decode|build|edit|modify|fix|patch|create|write|delete|remove|rename|convert|compile|sign|zip|unpack|repack|apply|use|deliver|upload)(?=\s|$|[.!؟?,،])/i;

/** Does the user want something DONE (vs. an explanation)? Gates the shell-block rescue. */
export function wantsAction(text: string): boolean {
  const t = stripMarks(text.toLowerCase());
  if (HOW_TO.test(t)) return false;
  return ACTION_VERB.test(t) || isAffirmation(text);
}

/** First fenced ```sh / ```bash block in a reply: the model "described" a command instead of calling the tool. */
export function extractShellBlock(text: string): { command: string; before: string } | null {
  const m = /```[ \t]*(?:sh|bash|shell|zsh|console|terminal|cmd)[ \t]*\r?\n([\s\S]*?)```/i.exec(text);
  if (!m) return null;
  const command = m[1].replace(/^\s*\$ ?/gm, '').trim();
  if (!command) return null;
  return { command, before: text.slice(0, m.index).trim() };
}

/** ASCII-safe file name for staging uploads: "app-release (3).apk" → "app-release_3.apk". */
export function safeFileName(nameRaw: string): string {
  const name = nameRaw.replace(/^\.+/, '');
  const dot = name.lastIndexOf('.');
  const stem = dot > 0 ? name.slice(0, dot) : name;
  const ext = dot > 0 ? name.slice(dot + 1) : '';
  const clean = (s: string) => nfkd(s).replace(/[^A-Za-z0-9._-]+/g, '_').replace(/_+/g, '_').replace(/^[_.]+|_+$/g, '');
  const st = clean(stem) || 'file';
  const ex = clean(ext).slice(0, 12);
  return ex ? `${st}.${ex}` : st;
}

const MIME: Record<string, string> = {
  apk: 'application/vnd.android.package-archive', zip: 'application/zip', pdf: 'application/pdf',
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp',
  txt: 'text/plain', md: 'text/markdown', json: 'application/json', html: 'text/html', csv: 'text/csv',
  mp4: 'video/mp4', mp3: 'audio/mpeg', wav: 'audio/wav', gguf: 'application/octet-stream',
};
export const mimeForName = (name: string): string => MIME[(name.split('.').pop() || '').toLowerCase()] ?? 'application/octet-stream';

/* ── missing-program bootstrap (Alpine) ─────────────────────────────────── */
export const CMD_TO_APK: Record<string, string> = {
  unzip: 'unzip', zip: 'zip', curl: 'curl', wget: 'wget', git: 'git',
  python: 'python3', python3: 'python3', pip: 'py3-pip', pip3: 'py3-pip',
  node: 'nodejs', npm: 'npm', npx: 'npm', java: 'openjdk17-jre-headless', javac: 'openjdk17',
  jq: 'jq', rg: 'ripgrep', fd: 'fd', file: 'file', xxd: 'xxd', ffmpeg: 'ffmpeg', ffprobe: 'ffmpeg',
  tesseract: 'tesseract-ocr', convert: 'imagemagick', magick: 'imagemagick', identify: 'imagemagick',
  sqlite3: 'sqlite', make: 'make', gcc: 'build-base', 'g++': 'build-base', cc: 'build-base',
  rsync: 'rsync', nano: 'nano', vim: 'vim', tree: 'tree', bzip2: 'bzip2', xz: 'xz', openssl: 'openssl',
  ssh: 'openssh-client', scp: 'openssh-client', nc: 'netcat-openbsd', dig: 'bind-tools', nslookup: 'bind-tools',
  patch: 'patch', diff: 'diffutils', awk: 'gawk', bash: 'bash',
};

/** Program names a shell reported as missing (Alpine/busybox, bash and Android toybox wording). */
export function missingCommands(output: string): string[] {
  const found = new Set<string>();
  const res = [
    /(?:^|\n)[^\n:]*?:?\s*(?:line \d+:\s*|\d+:\s*)?([A-Za-z0-9_.+-]+): (?:command )?not found/g,
    /(?:^|\n)[^\n]*?([A-Za-z0-9_.+-]+): inaccessible or not found/g,
  ];
  for (const re of res) {
    let m: RegExpExecArray | null;
    while ((m = re.exec(output))) found.add(m[1]);
  }
  return [...found];
}

/** Alpine packages that would provide the missing programs (unknown programs are ignored). */
export function packagesFor(cmds: string[]): string[] {
  return [...new Set(cmds.map(c => CMD_TO_APK[c]).filter((p): p is string => !!p))];
}

/* ═══ 3. AIDER-STYLE SEARCH/REPLACE EDITS ═════════════════════════════════ */
export interface EditBlock { search: string; replace: string }

export const EDIT_USAGE =
  'Format:\npath/to/file\n<<<<<<< SEARCH\nexact old lines\n=======\nnew lines\n>>>>>>> REPLACE\n(several blocks allowed; empty SEARCH creates a new file)';

export function parseEditArgs(argRaw: string): { path: string; blocks: EditBlock[] } | { error: string } {
  const arg = argRaw.replace(/\r\n/g, '\n');
  const nl = arg.indexOf('\n');
  if (nl === -1) return { error: `Edit failed — no SEARCH/REPLACE block. ${EDIT_USAGE}` };
  const path = arg.slice(0, nl).trim().replace(/^[`'"*\s]+|[`'":*\s]+$/g, '');
  if (!path) return { error: `Edit failed — first line must be the file path. ${EDIT_USAGE}` };
  const lines = arg.slice(nl + 1).split('\n');
  const blocks: EditBlock[] = [];
  let i = 0;
  while (i < lines.length) {
    if (/^<{5,9}\s*SEARCH\s*$/.test(lines[i].trim())) {
      i++;
      const s: string[] = [];
      while (i < lines.length && !/^={5,9}\s*$/.test(lines[i].trim())) s.push(lines[i++]);
      if (i >= lines.length) return { error: `Edit failed — missing the ======= divider. ${EDIT_USAGE}` };
      i++;
      const r: string[] = [];
      while (i < lines.length && !/^>{5,9}\s*REPLACE\s*$/.test(lines[i].trim())) r.push(lines[i++]);
      if (i >= lines.length) return { error: `Edit failed — missing the >>>>>>> REPLACE line. ${EDIT_USAGE}` };
      i++;
      blocks.push({ search: s.join('\n'), replace: r.join('\n') });
    } else i++;
  }
  if (!blocks.length) return { error: `Edit failed — no SEARCH/REPLACE block found. ${EDIT_USAGE}` };
  return { path, blocks };
}

const leadingWs = (s: string): string => /^[ \t]*/.exec(s)![0];

function lineMatches(file: string[], search: string[], norm: (s: string) => string): number[] {
  const out: number[] = [];
  const target = search.map(norm);
  for (let i = 0; i + search.length <= file.length; i++) {
    let ok = true;
    for (let j = 0; j < search.length; j++) if (norm(file[i + j]) !== target[j]) { ok = false; break; }
    if (ok) out.push(i);
  }
  return out;
}

const WORD_RE = new RegExp(`[${LETTERS}_]{3,}`, 'g');

/**
 * Nearest line to the first (non-blank) SEARCH line, by shared distinctive words — not raw substring
 * containment, which a short generic fragment (a lone `</string>` closing tag, a bare `}`) can satisfy on
 * the WRONG line of a large file before ever reaching the right one. An exact line match always wins first.
 */
function closestHint(file: string[], search: string[]): string {
  const first = search.find(l => l.trim())?.trim();
  if (!first) return '';
  let idx = file.findIndex(l => l.trim() === first);
  if (idx < 0) {
    const firstWords = new Set(first.match(WORD_RE) ?? []);
    if (firstWords.size) {
      let bestScore = 0;
      for (let i = 0; i < file.length; i++) {
        const line = file[i];
        if (!line.trim()) continue;
        let score = 0;
        for (const w of new Set(line.match(WORD_RE) ?? [])) if (firstWords.has(w)) score++;
        if (score > bestScore) { bestScore = score; idx = i; }
      }
    }
  }
  if (idx < 0) return ' The first SEARCH line does not appear anywhere in the file — read the file again and copy the lines exactly.';
  const from = Math.max(0, idx - 1);
  const ctx = file.slice(from, idx + 3).map((l, k) => `${from + k + 1}| ${l}`).join('\n');
  return ` Closest place (line ${idx + 1}):\n${ctx}`;
}

function diffText(startLine: number, oldLines: string[], newLines: string[]): string {
  const cap = (a: string[], p: string) => {
    const shown = a.slice(0, 12).map(l => `${p} ${l}`);
    if (a.length > 12) shown.push(`${p} … (${a.length - 12} more lines)`);
    return shown;
  };
  return [`@@ line ${startLine} @@`, ...cap(oldLines, '-'), ...cap(newLines, '+')].join('\n');
}

export type EditResult =
  | { ok: true; content: string; applied: number; diff: string }
  | { ok: false; error: string };

/** Applies the blocks in order, all-or-nothing. `content === null` means the file does not exist yet. */
export function applyEditBlocks(content: string | null, blocks: EditBlock[]): EditResult {
  const crlf = content !== null && content.includes('\r\n');
  let text = content === null ? '' : content.replace(/\r\n/g, '\n');
  const diffs: string[] = [];

  for (let bi = 0; bi < blocks.length; bi++) {
    const b = blocks[bi];
    const label = blocks.length > 1 ? `Block ${bi + 1}: ` : '';
    const search = b.search.replace(/\r\n/g, '\n');
    const replace = b.replace.replace(/\r\n/g, '\n');

    if (search.trim() === '') {
      if (content === null || text.trim() === '') { text = replace; diffs.push(diffText(1, [], replace.split('\n'))); continue; }
      return { ok: false, error: `${label}an empty SEARCH is only allowed for a NEW file. Copy the lines you want to change into SEARCH.` };
    }

    // 1) exact substring
    const hits: number[] = [];
    for (let p = text.indexOf(search); p !== -1; p = text.indexOf(search, p + Math.max(1, search.length))) hits.push(p);
    if (hits.length > 1) {
      return { ok: false, error: `${label}SEARCH matches ${hits.length} places. Add more surrounding lines so it matches exactly one.` };
    }
    if (hits.length === 1) {
      const p = hits[0];
      const startLine = text.slice(0, p).split('\n').length;
      text = text.slice(0, p) + replace + text.slice(p + search.length);
      diffs.push(diffText(startLine, search.split('\n'), replace.split('\n')));
      continue;
    }

    // 2) whole-line match ignoring trailing spaces, then ignoring indentation
    const fileLines = text.split('\n');
    const searchLines = search.split('\n');
    let strat: 'rstrip' | 'trim' = 'rstrip';
    let m = lineMatches(fileLines, searchLines, s => s.replace(/\s+$/, ''));
    if (m.length === 0) { strat = 'trim'; m = lineMatches(fileLines, searchLines, s => s.trim()); }
    if (m.length > 1) {
      return { ok: false, error: `${label}SEARCH matches ${m.length} places. Add more surrounding lines so it matches exactly one.` };
    }
    if (m.length === 0) {
      return { ok: false, error: `${label}SEARCH text was not found, so NOTHING was changed.${closestHint(fileLines, searchLines)}` };
    }
    const at = m[0];
    let repLines = replace.split('\n');
    if (strat === 'trim') {
      const fi = leadingWs(fileLines[at]);
      const si = leadingWs(searchLines[0]);
      if (fi !== si) repLines = repLines.map(l => (l.startsWith(si) ? fi + l.slice(si.length) : l));
    }
    fileLines.splice(at, searchLines.length, ...repLines);
    diffs.push(diffText(at + 1, searchLines, repLines));
    text = fileLines.join('\n');
  }

  return { ok: true, content: crlf ? text.replace(/\n/g, '\r\n') : text, applied: blocks.length, diff: diffs.join('\n') };
}

/** Plain-text grep over one file's content. */
export function grepText(text: string, re: RegExp, max: number): { line: number; text: string }[] {
  const out: { line: number; text: string }[] = [];
  const lines = text.split('\n');
  for (let i = 0; i < lines.length && out.length < max; i++) {
    if (re.test(lines[i])) out.push({ line: i + 1, text: lines[i].length > 180 ? `${lines[i].slice(0, 180)}…` : lines[i] });
  }
  return out;
}

/** Builds the RegExp for a code search: plain text or /regex/ ; smart-case. */
export function searchRegex(pattern: string): RegExp {
  const p = pattern.trim();
  const rx = /^\/(.+)\/([gimsuy]*)$/.exec(p);
  const src = rx ? rx[1] : p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const flags = rx ? rx[2].replace(/g/g, '') : /[A-Z]/.test(p) ? '' : 'i';
  try { return new RegExp(src, flags); } catch { return new RegExp(p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'); }
}

/* ═══ 4. MISSION JOURNAL (long-running memory) ════════════════════════════ */
export interface JournalStep { n: number; tool: string; arg: string; outcome: ToolOutcome; out: string; t: number }
export interface Mission {
  id: string;
  goal: string;
  startedAt: number;
  updatedAt: number;
  status: 'running' | 'paused' | 'done' | 'failed';
  steps: JournalStep[];
  files: string[];
  notes: string[];
  /** steps that were dropped from `steps` to keep the journal small (still counted) */
  dropped: number;
}

export const MAX_JOURNAL_STEPS = 80;
export const oneLine = (s: string, n: number): string => {
  const t = (s || '').replace(/\s+/g, ' ').trim();
  return t.length > n ? `${t.slice(0, n - 1)}…` : t;
};

export function newMission(goal: string, now = Date.now()): Mission {
  return { id: `m${now.toString(36)}`, goal: oneLine(goal, 300), startedAt: now, updatedAt: now, status: 'running', steps: [], files: [], notes: [], dropped: 0 };
}

const FILE_RE = /(?:\/[\w.@%+~-]+)+\.(?:apk|zip|pdf|png|jpe?g|gif|webp|txt|json|py|js|ts|md|html|css|sh|xml|csv|so|dex|arsc|mp4|mp3)\b/g;

export function extractFiles(...texts: string[]): string[] {
  const out = new Set<string>();
  for (const t of texts) {
    const re = new RegExp(FILE_RE.source, 'g');                 // fresh state per text (a shared /g regex keeps lastIndex)
    let m: RegExpExecArray | null;
    while ((m = re.exec(t || ''))) out.add(m[0]);
  }
  return [...out];
}

export function recordStep(m: Mission, tool: string, arg: string, result: string, now = Date.now()): Mission {
  const n = m.dropped + m.steps.length + 1;
  const step: JournalStep = { n, tool, arg: oneLine(arg.split('\n')[0] === arg ? arg : `${arg.split('\n')[0]} …`, 140), outcome: toolOutcome(result), out: oneLine(result, 200), t: now };
  const steps = [...m.steps, step];
  let dropped = m.dropped;
  while (steps.length > MAX_JOURNAL_STEPS) { steps.shift(); dropped++; }
  const written = tool === 'write_file' || tool === 'edit_file' || tool === 'deliver_file' || tool === 'apk_tool' || tool === 'download_file';
  const files = [...new Set([...m.files, ...(written ? extractFiles(arg.split('\n')[0], result) : extractFiles(result).filter(f => /\.(?:apk|zip|pdf)$/.test(f)))])].slice(-14);
  return { ...m, steps, files, dropped, updatedAt: now };
}

export function addNote(m: Mission, note: string): Mission {
  const notes = [...m.notes, oneLine(note, 200)].slice(-8);
  return { ...m, notes };
}

/** Compact text the model reads at the start of a turn. Newest steps win when the budget is tight. */
export function journalDigest(m: Mission | null, budgetChars: number): string {
  if (!m || (m.steps.length === 0 && m.notes.length === 0)) return '';
  const head = `[Work log — job: "${oneLine(m.goal, 120)}" · ${m.dropped + m.steps.length} steps · status: ${m.status}]`;
  const tail: string[] = [];
  if (m.files.length) tail.push(`Files: ${m.files.slice(-8).join(', ')}`);
  if (m.notes.length) tail.push(`Notes: ${m.notes.slice(-4).join(' | ')}`);
  let room = budgetChars - head.length - tail.join('\n').length - 40;
  const lines: string[] = [];
  for (let i = m.steps.length - 1; i >= 0; i--) {
    const s = m.steps[i];
    const mark = s.outcome === 'ok' ? 'ok' : s.outcome === 'denied' ? 'DENIED' : 'ERROR';
    const line = `${s.n}. ${s.tool} ${oneLine(s.arg, 90)} → ${mark}: ${oneLine(s.out, i === m.steps.length - 1 ? 160 : 90)}`;
    if (room - line.length < 0 && lines.length >= 2) break;
    room -= line.length + 1;
    lines.unshift(line);
  }
  const omitted = m.dropped + (m.steps.length - lines.length);
  return [head, omitted > 0 ? `(+${omitted} earlier steps omitted)` : '', ...lines, ...tail].filter(Boolean).join('\n');
}

/** Same tool + same argument repeated at the end of the step list. */
export function repeatCount(steps: { tool: string; arg: string }[]): number {
  if (!steps.length) return 0;
  const last = steps[steps.length - 1];
  let n = 0;
  for (let i = steps.length - 1; i >= 0 && steps[i].tool === last.tool && steps[i].arg === last.arg; i--) n++;
  return n;
}

export interface MsgLike { role: string; content?: unknown }

/**
 * Context compaction inside one long turn: results older than the last `keepLast` messages shrink to a stub
 * (the call itself stays, so the model still knows WHAT it did). Returns how many were shrunk.
 */
export function stubOldToolResults(msgs: MsgLike[], keepLast: number, stubChars = 160): number {
  let n = 0;
  for (let i = 0; i < msgs.length - keepLast; i++) {
    const m = msgs[i];
    if (typeof m.content !== 'string') continue;
    const legacy = m.role === 'user' && m.content.startsWith('<tool_result');
    if (m.role !== 'tool' && !legacy) continue;
    if (m.content.length <= stubChars + 80 || m.content.includes('[older result trimmed')) continue;
    if (legacy) {
      const head = /^<tool_result name="([^"]*)">\n?/.exec(m.content);
      const body = m.content.replace(/^<tool_result[^>]*>\n?/, '').replace(/\n?<\/tool_result>\s*$/, '');
      m.content = `<tool_result name="${head ? head[1] : 'tool'}">\n${oneLine(body, stubChars)} …[older result trimmed]\n</tool_result>`;
    } else {
      m.content = `${oneLine(m.content, stubChars)} …[older result trimmed]`;
    }
    n++;
  }
  return n;
}

/* ═══ 5. MEMORY RANKING (BM25-lite, Arabic + English) ═════════════════════ */
export interface MemItem { key: string; value: string; ts?: string }

/** Words that say nothing about the topic (already in the normalised spelling used by tokenize). */
const STOPWORDS = new Set([
  'the', 'an', 'and', 'or', 'of', 'to', 'in', 'is', 'are', 'was', 'were', 'be', 'for', 'on', 'with', 'at', 'by', 'it', 'this',
  'that', 'which', 'what', 'how', 'why', 'who', 'when', 'where', 'do', 'does', 'did', 'can', 'my', 'me', 'you',
  'من', 'في', 'علي', 'الي', 'عن', 'هل', 'ما', 'ماذا', 'كيف', 'ليه', 'ازاي', 'ده', 'دي', 'دا', 'هو', 'هي', 'انا', 'انت',
  'او', 'ان', 'لا', 'لم', 'مع', 'كل', 'بعد', 'قبل', 'اللي', 'فيه', 'ايه', 'عايز', 'عاوز',
]);

export function tokenize(s: string): string[] {
  const t = stripMarks(s.toLowerCase()).replace(new RegExp(`[^${LETTERS}]+`, 'g'), ' ').trim();
  if (!t) return [];
  return t.split(' ')
    .map(w => (w.startsWith('ال') && w.length > 4 ? w.slice(2) : w))
    .filter(w => w.length > 1 && !STOPWORDS.has(w));
}

export function rankMemories(query: string, items: MemItem[], topK = 3, minScore = 1.0): MemItem[] {
  const q = [...new Set(tokenize(query))];
  if (!q.length || !items.length) return [];
  const docs = items.map(it => {
    const k = tokenize(it.key);
    return { it, toks: [...k, ...k, ...tokenize(it.value)] };
  });
  const N = docs.length;
  const avg = docs.reduce((n, d) => n + d.toks.length, 0) / N || 1;
  const df = new Map<string, number>();
  for (const d of docs) for (const w of new Set(d.toks)) df.set(w, (df.get(w) || 0) + 1);
  const k1 = 1.2, b = 0.75;
  const scored = docs.map(d => {
    let score = 0;
    for (const w of q) {
      const tf = d.toks.filter(x => x === w || (w.length > 3 && x.startsWith(w))).length;
      if (!tf) continue;
      const n = df.get(w) || 0;
      const idf = Math.log(1 + (N - n + 0.5) / (n + 0.5));
      score += idf * ((tf * (k1 + 1)) / (tf + k1 * (1 - b + (b * d.toks.length) / avg)));
    }
    return { it: d.it, score };
  });
  return scored
    .filter(s => s.score >= minScore)
    .sort((a, c) => c.score - a.score || (c.it.ts || '').localeCompare(a.it.ts || ''))
    .slice(0, topK)
    .map(s => s.it);
}


/* ═══ 6. WEB PAGES, CURL, GLOB, BASE64 ════════════════════════════════════ */
const decodeEnt = (s: string): string =>
  s.replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'")
    .replace(/&#(\d+);/g, (_m, d) => String.fromCharCode(parseInt(d, 10)));

const BOILERPLATE = /(?:cookie|consent|subscribe|sign up|newsletter|all rights reserved|javascript is (?:disabled|required)|accept all|privacy policy|اشترك|ملفات تعريف الارتباط|جميع الحقوق)/i;

/** Readable paragraphs of an HTML page (prefers <article>/<main>; drops menus, footers, scripts). */
export function htmlParagraphs(html: string): string[] {
  let h = html
    .replace(/<(script|style|noscript|svg|nav|footer|header|aside|form|iframe|template)[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ');
  const art = /<(article|main)\b[^>]*>([\s\S]*?)<\/\1>/i.exec(h);
  if (art && art[2].length > 800) h = art[2];
  const out: string[] = [];
  const push = (raw: string) => {
    const t = decodeEnt(raw.replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim();
    if (t.length >= 40 && !BOILERPLATE.test(t) && out[out.length - 1] !== t) out.push(t);
  };
  const re = /<(p|li|h[1-4]|td|blockquote|pre)\b[^>]*>([\s\S]*?)<\/\1>/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(h))) push(m[2]);
  if (out.length < 3) {
    for (const line of decodeEnt(h.replace(/<(br|\/p|\/div|\/li|\/h[1-6]|\/tr)\s*\/?>/gi, '\n').replace(/<[^>]+>/g, ' ')).split('\n')) {
      const t = line.replace(/\s+/g, ' ').trim();
      if (t.length >= 60 && !BOILERPLATE.test(t) && out[out.length - 1] !== t) out.push(t);
    }
  }
  return out;
}

/** The paragraphs most relevant to the query, in page order, within maxChars. */
export function pickPassages(paras: string[], query: string, maxChars: number): string {
  if (!paras.length) return '';
  const q = [...new Set(tokenize(query))];
  const scored = paras.map((p, i) => {
    const toks = new Set(tokenize(p));
    let hit = 0;
    for (const w of q) if (toks.has(w) || [...toks].some(x => w.length > 3 && x.startsWith(w))) hit++;
    return { p, i, score: hit + (i < 3 ? 0.25 : 0) };
  });
  const ranked = scored.filter(s => s.score >= 1).sort((a, b) => b.score - a.score || a.i - b.i);
  const chosen = (ranked.length ? ranked : scored.slice(0, 3)).slice(0, 6);
  const picked: typeof scored = [];
  let used = 0;
  for (const c of chosen.slice().sort((a, b) => b.score - a.score || a.i - b.i)) {
    const len = Math.min(c.p.length, 420);
    if (used + len > maxChars && picked.length) break;
    picked.push(c);
    used += len + 1;
  }
  return picked.sort((a, b) => a.i - b.i).map(c => oneLine(c.p, 420)).join('\n').slice(0, maxChars);
}

/** Simple glob list → RegExp: "*.ts,*.tsx", "*.{js,jsx}", "?.md". */
export function globToRegExp(glob: string): RegExp {
  const parts = glob.split(/,(?![^{]*\})/).map(g => g.trim()).filter(Boolean);
  const alts = parts.map(g =>
    g.replace(/[.+^$()|[\]\\]/g, '\\$&')
      .replace(/\{([^}]*)\}/g, (_m, a: string) => `(?:${a.split(',').map(x => x.trim()).join('|')})`)
      .replace(/\*/g, '.*').replace(/\?/g, '.'));
  return new RegExp(`^(?:${alts.join('|')})$`, 'i');
}

/** Splits a shell-ish string into tokens, honouring quotes and backslashes. */
export function shellSplit(s: string): string[] {
  const out: string[] = [];
  let cur = '';
  let q: '"' | "'" | '' = '';
  let has = false;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (q) {
      if (c === q) q = '';
      else if (c === '\\' && q === '"' && i + 1 < s.length) cur += s[++i];
      else cur += c;
    } else if (c === '"' || c === "'") { q = c; has = true; }
    else if (c === '\\' && i + 1 < s.length) { cur += s[++i]; has = true; }
    else if (/\s/.test(c)) { if (cur || has) out.push(cur); cur = ''; has = false; }
    else cur += c;
  }
  if (cur || has) out.push(cur);
  return out;
}

export type CurlPlan = { kind: 'fetch'; arg: string } | { kind: 'download'; arg: string } | { kind: 'shell' };

/**
 * The model often "calls" curl/wget as if it were a tool. Turn a simple curl/wget command line into the
 * fetch_url / download_file arguments (no Linux needed); anything complicated stays a real shell command.
 */
export function planCurl(cmdRaw: string): CurlPlan {
  const m = /^\s*(curl|wget)\b\s*([\s\S]*)$/.exec(cmdRaw);
  if (!m) return { kind: 'shell' };
  const bare = m[2].replace(/'[^']*'|"[^"]*"/g, '');
  if (/[|;&<>`\n]|\$\(/.test(bare.replace(/&&/g, ''))) return { kind: 'shell' };
  const toks = shellSplit(m[2]);
  let url = '';
  let method = '';
  let body: string | undefined;
  const headers: Record<string, string> = {};
  let out = '';
  let remoteName = false;
  for (let i = 0; i < toks.length; i++) {
    const t = toks[i];
    if (m[1] === 'curl') {
      if (t === '-X' || t === '--request') method = toks[++i] || '';
      else if (t === '-H' || t === '--header') {
        const h = toks[++i] || '';
        const k = h.indexOf(':');
        if (k > 0) headers[h.slice(0, k).trim()] = h.slice(k + 1).trim();
      } else if (t === '-d' || t === '--data' || t === '--data-raw' || t === '--data-binary') body = toks[++i] ?? '';
      else if (t === '-o' || t === '--output') out = toks[++i] || '';
      else if (t === '-O' || t === '--remote-name') remoteName = true;
      else if (/^-[sSLkvif]+$/.test(t) || ['--silent', '--location', '--insecure', '--compressed', '--fail', '--show-error'].includes(t)) continue;
      else if (t.startsWith('-')) return { kind: 'shell' };
      else url = t;
    } else if (t === '-O' || t === '--output-document') out = toks[++i] || '';
    else if (['-q', '--quiet', '-nv', '--no-check-certificate'].includes(t)) continue;
    else if (t.startsWith('-')) return { kind: 'shell' };
    else url = t;
  }
  if (!/^https?:\/\//i.test(url)) return { kind: 'shell' };
  if (out === '-') out = '';
  if (out || remoteName || m[1] === 'wget') {
    const dest = out || (url.split(/[?#]/)[0].split('/').filter(Boolean).pop() || 'download');
    return { kind: 'download', arg: `${url} | ${dest}` };
  }
  if (method || body !== undefined || Object.keys(headers).length) {
    return { kind: 'fetch', arg: JSON.stringify({ url, method: method || (body !== undefined ? 'POST' : 'GET'), headers, body }) };
  }
  return { kind: 'fetch', arg: url };
}

/** Base64 of an ASCII/Latin-1 string (used to ship the gvr-apk helper into Alpine without quoting problems). */
export function toBase64(s: string): string {
  const T = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  let out = '';
  for (let i = 0; i < s.length; i += 3) {
    const a = s.charCodeAt(i);
    const b = i + 1 < s.length ? s.charCodeAt(i + 1) : NaN;
    const c = i + 2 < s.length ? s.charCodeAt(i + 2) : NaN;
    if (a > 255 || b > 255 || c > 255) throw new Error('toBase64: only Latin-1 text is supported');
    const n = (a << 16) | ((b || 0) << 8) | (c || 0);
    out += T[(n >> 18) & 63] + T[(n >> 12) & 63] + (Number.isNaN(b) ? '=' : T[(n >> 6) & 63]) + (Number.isNaN(c) ? '=' : T[n & 63]);
  }
  return out;
}


/* ═══ 7. TERMINAL OUTPUT + SESSION ENVIRONMENT ════════════════════════════ */
/**
 * Long output keeps its BEGINNING and its END. Errors and the "(exit code N)" line are at the end — a plain
 * head-only cut used to throw exactly the part that explains a failed build or install away.
 */
export function headTail(text: string, head: number, tail: number, note = ''): { text: string; omitted: number } {
  if (text.length <= head + tail + 200) return { text, omitted: 0 };
  const omitted = text.length - head - tail;
  return { text: `${text.slice(0, head)}\n… [${omitted} characters omitted from the middle${note}] …\n${text.slice(-tail)}`, omitted };
}

/** Clip a tool result for the model: 40 % beginning, 60 % end. */
export function clipResult(text: string, max: number): string {
  if (text.length <= max) return text;
  return headTail(text, Math.floor(max * 0.4), Math.floor(max * 0.6)).text;
}

/**
 * Top-level `export NAME=value` of a command (at the start of a line or chained with && / ;), kept for the next
 * commands like in a real terminal session. Indented ones (inside if/for) and $(…) values are ignored.
 */
export function extractExports(command: string): { name: string; line: string }[] {
  const out: { name: string; line: string }[] = [];
  const re = /(?:^|&&[ \t]*|;[ \t]*)export[ \t]+([A-Za-z_]\w*)=("[^"\n]*"|'[^'\n]*'|[^\s;&|]*)/gm;
  let m: RegExpExecArray | null;
  while ((m = re.exec(command))) {
    if (/\$\(|`/.test(m[2])) continue;
    out.push({ name: m[1], line: `export ${m[1]}=${m[2]}` });
  }
  return out;
}
