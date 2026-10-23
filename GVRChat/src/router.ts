/**
 * router.ts — runs obvious tool requests WITHOUT depending on the model.
 * ======================================================================
 * Small or non-instruct models can't reliably emit tool calls. This pure
 * function reads the user's message (Arabic or English) and returns the tool
 * calls that are clearly being asked for. The engine executes them first and
 * hands the results to the model, so "open the terminal", "search for X" or a
 * pasted URL work even with a 0.5B model.
 *
 * It only fires on explicit patterns; anything ambiguous is left to the model.
 */

import { wantsAction } from './agentLogic';

export interface RouteHit {
  tool: string;
  arg: string;
  why: string;
}

/** Natural language that follows a command: "…وقولي الملفات", "…وبعدين شغل …", "… and then show me …". */
const PROSE_TAIL = /\s+(?:و?(?:قولي|قول\s*لي|قوللي|اعرضلي|اعرض|وريني|ورّيني|شوفلي|بعدين|بعد\s*كده|بعد\s*ذلك|بعدها|ثم|لخصلي|اشرحلي|وبعد)(?=\s|$)|and\s+then|and\s+tell|and\s+show|then(?=\s)).*$/i;

export interface RouteAttachment {
  name: string;
  kind: string;
  /** real path of the staged copy (Android shell, file tools) */
  path: string;
  /** the same file as Alpine Linux sees it (/workspace/uploads/<name>) */
  linuxPath: string;
}

export interface RouteOptions {
  hasAttachment?: boolean;
  /** The attached file, when there is one (lets the router act on it without the model). */
  attachment?: RouteAttachment;
  /** Alpine Linux is installed (apk_tool, tar, … available). */
  linuxReady?: boolean;
  /** A vision model is loaded (images are handed to it directly). */
  visionReady?: boolean;
  /**
   * Weak (tiny/base) models can't decide to search by themselves, so for them EVERY knowledge
   * question ("كم عدد…", "من هو…", "what is…") goes to the web automatically.
   */
  broad?: boolean;
}

/** Arabic-insensitive text for matching (diacritics, alef/ya/ta-marbuta variants). */
export function normalizeAr(s: string): string {
  return s
    .replace(/[\u064B-\u065F\u0670\u0640]/g, '')
    .replace(/[أإآ]/g, 'ا')
    .replace(/ى/g, 'ي')
    .replace(/ة/g, 'ه')
    .toLowerCase();
}

const PACKAGES: Array<[RegExp, string]> = [
  [/(python|بايثون|بيثون|pip)/, 'python3 py3-pip'],
  [/(nodejs|node|npm|نود)/, 'nodejs npm'],
  [/\bgit\b|جيت/, 'git'],
  [/\bcurl\b/, 'curl'],
  [/\bgcc\b|compiler|مترجم/, 'build-base'],
  [/\bnano\b/, 'nano'],
  [/\bvim\b/, 'vim'],
  [/\bffmpeg\b/, 'ffmpeg'],
  [/\bjq\b/, 'jq'],
  [/\bwget\b/, 'wget'],
  [/\bopenssh|ssh\b/, 'openssh-client'],
  [/\bnmap\b/, 'nmap'],
];

const FILE_LIKE = /\.(apk|apks|xapk|zip|jar|aar|pdf|docx|xlsx|pptx|odt|epub|gz|tar|7z|rar|db|sqlite|so|dex|bin|gguf)$/i;

/**
 * Run inline (no separate screen) the moment the user asks to "open the
 * terminal" — gives an immediate, real console card right in the chat
 * instead of navigating away.
 */
export const TERMINAL_PROBE =
  'echo "📂 $(pwd)"; id -un 2>/dev/null; uname -sm 2>/dev/null; ' +
  '(command -v apk >/dev/null 2>&1 && echo "🐧 Alpine Linux ready") || echo "🤖 Android shell (Alpine Linux not installed yet)"';

function cleanPath(p: string): string {
  return p.replace(/^[`"'«»(]+|[`"'«»).,،؛;:!?؟]+$/g, '');
}

/* ── search / time helpers ─────────────────────────────────────────────── */
const LETTER = /[A-Za-z\u0621-\u064A]/;
// The search verb may come first ("ابحث عن X") or LAST ("X ... ابحثلي على النت").
const SEARCH_VERB = /(?:[اأإآ]بحث|دور|سيرش|بحث|search|google|look\s*up|find\s*out)(?:\s*(?:لي|لى|ليا|لينا|عن|في|about|for|on|up))*(?![A-Za-z\u0621-\u064A])/i;
const ENGINE = '(?:ال[اأإ]نترنت|النت|الويب|جوجل|جوجول|جووجل|قوقل|google|the\\s+(?:web|internet|net)|web|internet|online)';
const PREP_ENGINE = new RegExp(`(?:^|\\s)(?:على|علي|في|ع|عن طريق|من|on|in|via|using|through)\\s+${ENGINE}(?=\\s|$|[.,،؛;:!?؟])`, 'gi');
const LEAD_ENGINE = new RegExp(`^${ENGINE}\\s+(?:عن|about|for)\\s+`, 'i');
const POLITE = /(?:^|\s)(?:لو\s+سمحت|من\s+فضلك|please|plz|pls)(?=\s|$|[.,،؛;:!?؟])/gi;
const BARE_ENGINE = new RegExp(`^${ENGINE}$`, 'i');

/** Strips "on the internet / on google / please" noise so only the topic is searched. */
export function cleanQuery(s: string): string {
  return s
    .replace(PREP_ENGINE, ' ')
    .replace(POLITE, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(LEAD_ENGINE, '')
    .replace(/^(?:عن|about|for)\s+/i, '')
    .replace(/[?؟!.,،؛;:\s]+$/g, '')
    .replace(/^[.,،؛;:\s]+/g, '')
    .trim()
    .replace(BARE_ENGINE, '');   // "search online" -> the topic is in the other half of the sentence
}

// "Is this a factual question?" — used for the broad auto-search and for the "I don't know → search" fallback.
const Q_START = /^(?:(?:طيب|بص|هو|هي|لو سمحت|من فضلك|يا باشا|وبعدين|و)\s+)*(?:كم|كام|ما|ماذا|من|مين|منو|ايه|اي|متي|امتي|اين|فين|لماذا|ليه|كيف|ازاي|هل|what|who|whom|when|where|which|why|how|is|are|was|were|do|does|did|can|could|will|would)(?:\s|$)/;
const SELF_REF = /(?:^|\s)(?:انت|انتا|انتي|اسمك|نفسك|بتعمل|بتقدر|تقدر|you|your|yourself)(?:\s|$)/;
const COMMAND_WORD = /(?:^|\s)(?:افتح|شغل|اعمل|اكتب|ترجم|لخص|احسب|ثبت|نزل|حمل|امسح|اقرا|اشرح|فسر|قولي|حكيلي|هات|ارسم|صمم|عدل|غير)(?:\s|$)/;

export function isInfoQuestion(text: string): boolean {
  const raw = text.trim();
  const n = normalizeAr(raw);
  const words = n.split(/\s+/).filter(Boolean);
  if (words.length < 3 || raw.length > 200) return false;
  if (SELF_REF.test(n) || COMMAND_WORD.test(n)) return false;
  return Q_START.test(n) || (/[?؟]\s*$/.test(raw) && words.length >= 4);
}

const TIME_QUESTION = /(الساعه كام|كام الساعه|كم الساعه|الساعه كم|الوقت (?:الان|دلوقتي|حاليا)|(?:ايه|ما|كم|كام)\s+(?:هو\s+)?(?:ال)?تاريخ|التاريخ (?:اليوم|النهارده|الان|دلوقتي)|النهارده\s+(?:كام|ايه|اي)|اليوم كام|what time|current time|today'?s date|what'?s the date|what day)/;
const FRESH_WORDS = /(اخبار|الاخبار|سعر|اسعار|الطقس|درجه الحراره|نتيجه مباراه|نتايج|اخر اصدار|احدث|latest|news|price of|weather|score of)/;
// "how many people are on earth NOW" — facts that change, asked without an explicit "search".
const NOW_QUESTION = /(?:^|\s)(?:كم|ما هو|ما هي|من هو|مين|ايه|what is|who is|how many|how much)(?:\s|$).*(?:حاليا|الان|دلوقتي|current|currently|right now|these days|as of now)/;

/** "افتح الترمنال" · "هل يمكنك فتح تريمنال" · "ممكن تفتح الترمنال" · "open the terminal" */
const OPEN_TERMINAL_RE =
  /(?:^|\s)(?:هل\s+)?(?:(?:ممكن|تقدر|يمكنك|يمكن|ينفع|عايز|عاوز|حاول|جرب)\s+)?(?:تفتح|افتح|فتح|فتحلي|شغل|شغّل|اعرض|اعرضلي|وريني|وريلي|اظهر|اظهرلي|open|show|launch|start|run)\s+(?:the\s+)?(?:ال)?(?:ترمنال|تيرمنال|تريمنال|ترمينال|تيرمينال|terminal|shell|console|طرفيه|طرفية)(?=\s|$|[?؟.!،])/i;

export function autoRoute(text: string, opts: RouteOptions = {}): RouteHit[] {
  const hits: RouteHit[] = [];
  const add = (tool: string, arg: string, why: string) => {
    if (!hits.some(h => h.tool === tool && h.arg === arg)) hits.push({ tool, arg, why });
  };
  const raw = text.trim();
  if (!raw) return hits;
  const n = normalizeAr(raw);

  // 1) URLs → fetch
  const urls = raw.match(/https?:\/\/[^\s<>"'`)\]]+/gi) || [];
  for (const u of urls.slice(0, 2)) add('fetch_url', u.replace(/[.,،;:!?؟]+$/, ''), 'url');

  // 2) "open the terminal" → run a real probe command inline, right now
  if (OPEN_TERMINAL_RE.test(n)) {
    add('terminal', TERMINAL_PROBE, 'open_terminal');
  }

  // 3) explicit shell commands: fenced block, or "run: cmd"
  const fence = /```(sh|bash|shell|zsh|terminal|console)?[ \t]*\r?\n?([\s\S]*?)```/i.exec(raw);
  const runVerb = /(نفذ|شغل|run|execute|exec|جرب|اكتب)/.test(n);
  if (fence && (fence[1] || runVerb) && fence[2].trim()) {
    add('terminal', fence[2].trim(), 'code_block');
  } else {
    const m = /^(?:نفذ|شغل|run|execute|exec)(?:\s*[:：]\s*|\s+)(?:لي\s+)?(?:(?:الامر|الأمر|امر|أمر|command|cmd)\s*[:：]?\s*)?`?([^`\n]+?)`?\s*$/i.exec(raw);
    // "شغل ls وقولي الملفات" → the command is `ls`; what follows is a request to the assistant, not shell input.
    const cmd = m ? m[1].replace(PROSE_TAIL, '').trim() : '';
    if (cmd && /[a-z]/i.test(cmd) && !/^(?:the\s+|ال)?(?:terminal|shell|console|ترمنال|تيرمنال|تريمنال|ترمينال|تيرمينال)$/i.test(cmd)) {
      add('terminal', cmd, 'run_command');
    }
  }

  // 4) install packages
  const inst = /(ثبت|نزل|حمل|install|setup|اعمل\s*(?:ال)?تثبيت)\s+(?:لي\s+)?(?:ال)?(.+)/.exec(n);
  if (inst) {
    const pkgs = new Set<string>();
    for (const [re, pkg] of PACKAGES) if (re.test(inst[2])) pkg.split(' ').forEach(p => pkgs.add(p));
    // A single named tool ("نزل ffmpeg") → install it AND learn how to run it (install_tool returns a usage card).
    // Multi-package aliases (python → python3 + pip) and Arabic names keep the plain apk install.
    const tok = /^([a-z0-9][a-z0-9._+-]{1,40})(?:\s|$)/i.exec(inst[2].trim());
    if (pkgs.size > 1 || (pkgs.size === 1 && !tok)) {
      add('pkg_install', Array.from(pkgs).join(' '), 'install');
    } else if (tok && !/^(?:the|a|an|app|apk|package|tool|program)$/i.test(tok[1])) {
      add('install_tool', pkgs.size === 1 ? Array.from(pkgs)[0] : tok[1].toLowerCase(), 'install_tool');
    }
  }

  // 5) web search — verb first ("ابحث عن X") or last ("X ... ابحثلي على النت")
  const timeQuestion = TIME_QUESTION.test(n);
  let searched = false;
  const sm = SEARCH_VERB.exec(raw);
  if (sm && !opts.hasAttachment && (sm.index === 0 || !LETTER.test(raw[sm.index - 1]))) {
    const after = cleanQuery(raw.slice(sm.index + sm[0].length));
    const before = cleanQuery(raw.slice(0, sm.index));
    const q = after.length >= 2 ? after : before;
    if (q.length >= 2 && q.length <= 200) { add('search', q, 'search'); searched = true; }
  }
  if (
    !searched && !timeQuestion && raw.length <= 140 && !fence && !urls.length && !opts.hasAttachment &&
    (FRESH_WORDS.test(n) || NOW_QUESTION.test(n))
  ) {
    add('search', raw.replace(/[?؟!]+$/, ''), 'freshness');
  }

  // 6) date / time
  if (timeQuestion) {
    add('datetime', '', 'time');
  }

  // 7) arithmetic
  const calcM = /^(?:احسب|حساب|calculate|compute|calc)\s*[:：]?\s*(.+)$/i.exec(raw);
  const exprCandidate = (calcM ? calcM[1] : raw).replace(/[=؟?]+$/, '').trim();
  const exprNorm = exprCandidate.replace(/[x×]/gi, '*').replace(/÷/g, '/');
  if (/^[\d\s+\-*/().,^%]+$/.test(exprNorm) && /\d/.test(exprNorm) && /[+\-*/^%]/.test(exprNorm.replace(/^\s*-/, ''))) {
    add('calc', exprNorm.replace(/,/g, ''), 'math');
  }

  // 8) device / storage info
  if (/(مساحه (?:ال)?(?:تخزين|جهاز)|المساحه الفاضيه|storage (?:left|space|info)|free (?:space|storage)|معلومات (?:ال)?جهاز|device info|specs)/.test(n)) {
    add('device_info', '', 'device');
  }

  // 9) read / inspect a file by path
  const pm = /(?:[اأإ]قر[اأ]|اعرض|افتح|حلل|[اأإ]?فحص|read|show|open|cat|inspect|analy[sz]e)\s+(?:لي\s+)?(?:ال)?(?:ملف|file)?\s*[:：]?\s*(["'`]?(?:\/|file:\/\/|~\/)[^\s"'`]+["'`]?)/i.exec(raw);
  if (pm) {
    const p = cleanPath(pm[1]);
    add(FILE_LIKE.test(p) ? 'inspect_file' : 'read_file', p, 'file_path');
  }

  // 10) list a folder
  const lm = /(?:اعرض|اظهر|وريني|list|ls|show)\s+(?:ال)?(?:ملفات|files|محتويات|مجلد|folder|dir(?:ectory)?)\s*(?:في|فى|داخل|in|of)?\s*(["'`]?\/[^\s"'`]*)?/i.exec(raw);
  if (lm && !pm) add('list_dir', lm[1] ? cleanPath(lm[1]) : '', 'list_dir');

  // 11) weak models: any factual question goes to the web (they can't decide that themselves)
  if (opts.broad && hits.length === 0 && !fence && !urls.length && !opts.hasAttachment && isInfoQuestion(raw)) {
    add('search', raw.replace(/[?؟!]+$/, '').trim(), 'knowledge');
  }

  // 12) an attached archive / APK + "unpack it": do it right now. The slow model never has to write the command
  //     (it used to print it as text and ask "continue?"), and it cannot get the file's path wrong.
  const att = opts.attachment;
  if (att && hits.length === 0 && wantsAction(raw) && /(?:^|\s)(?:فك|فكك|فكي|فكوا|استخرج|unzip|unpack|extract|decompile|decode)(?=\s|$)/.test(n)) {
    const isApk = /\.(?:apk|xapk|apks)$/i.test(att.name);
    const stem = att.name.replace(/\.[^.]+$/, '').replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^_+|_+$/g, '') || 'archive';
    const root = opts.linuxReady ? '/workspace' : att.path.replace(/\/uploads\/[^/]*$/, '');
    const src = opts.linuxReady ? att.linuxPath : att.path;
    const out = `${root}/${isApk ? 'apk_work' : 'unpacked'}/${stem}`;
    if (isApk && opts.linuxReady) {
      add('apk_tool', `unpack ${src} ${out}`, 'unpack_apk');
    } else if (/\.(?:apk|xapk|apks|zip|jar|aar)$/i.test(att.name)) {
      add('terminal', `mkdir -p '${out}' && unzip -o -q '${src}' -d '${out}' && echo "Unpacked to ${out}" && ls -la '${out}' | head -40 && echo "files: $(find '${out}' -type f | wc -l)"`, 'unpack_zip');
    } else if (/\.(?:tar|tgz|tar\.gz|gz)$/i.test(att.name)) {
      add('terminal', `mkdir -p '${out}' && tar -xf '${src}' -C '${out}' && echo "Unpacked to ${out}" && ls -la '${out}' | head -40`, 'unpack_tar');
    }
  }

  // 13) an attached picture and no vision model: read what is written in it (OCR) instead of ignoring it
  const imageNoVision = !!att && att.kind === 'image' && !opts.visionReady && hits.length === 0;
  if (imageNoVision && (wantsAction(raw) || /(?:ايه|اي|what|read|اقرا|وصف|describe|مكتوب|نص|text|شوف|look|see|حلل|analy)/.test(n))) {
    add('view_image', opts.linuxReady ? att!.linuxPath : att!.path, 'image_ocr');
  }

  return hits.slice(0, 3);
}
