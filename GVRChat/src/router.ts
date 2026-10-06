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

export interface RouteHit {
  tool: string;
  arg: string;
  why: string;
}

export interface RouteOptions {
  hasAttachment?: boolean;
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

function cleanPath(p: string): string {
  return p.replace(/^[`"'«»(]+|[`"'«»).,،؛;:!?؟]+$/g, '');
}

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

  // 2) open the terminal panel
  if (/(افتح|شغل|اعرض|وريني|اظهر|open|show|launch|start|run)\s+(?:the\s+)?(ال)?(ترمنال|تيرمنال|تريمنال|ترمينال|تيرمينال|terminal|shell|console|طرفيه)/.test(n)) {
    add('open_terminal', '', 'open_terminal');
  }

  // 3) explicit shell commands: fenced block, or "run: cmd"
  const fence = /```(sh|bash|shell|zsh|terminal|console)?[ \t]*\r?\n?([\s\S]*?)```/i.exec(raw);
  const runVerb = /(نفذ|شغل|run|execute|exec|جرب|اكتب)/.test(n);
  if (fence && (fence[1] || runVerb) && fence[2].trim()) {
    add('terminal', fence[2].trim(), 'code_block');
  } else {
    const m = /^(?:نفذ|شغل|run|execute|exec)(?:\s*[:：]\s*|\s+)(?:لي\s+)?(?:(?:الامر|الأمر|امر|أمر|command|cmd)\s*[:：]?\s*)?`?([^`\n]+?)`?\s*$/i.exec(raw);
    if (m && m[1] && /[a-z]/i.test(m[1]) && !/^(?:the\s+|ال)?(?:terminal|shell|console|ترمنال|تيرمنال|تريمنال|ترمينال|تيرمينال)$/i.test(m[1].trim())) {
      add('terminal', m[1].trim(), 'run_command');
    }
  }

  // 4) install packages
  const inst = /(ثبت|نزل|حمل|install|setup|اعمل\s*(?:ال)?تثبيت)\s+(?:لي\s+)?(?:ال)?(.+)/.exec(n);
  if (inst) {
    const pkgs = new Set<string>();
    for (const [re, pkg] of PACKAGES) if (re.test(inst[2])) pkg.split(' ').forEach(p => pkgs.add(p));
    if (pkgs.size) add('pkg_install', Array.from(pkgs).join(' '), 'install');
  }

  // 5) web search
  const sm = /(?:[اأإآ]بحث|دور|سيرش|بحث|search|google|look\s*up|find\s*out)\s*(?:لي|لى|عن|في|about|for|on|up)?\s*(?:عن|about|for)?\s*(.{2,})/i.exec(raw);
  if (sm && !opts.hasAttachment) {
    const q = sm[1].replace(/[?؟!]+$/, '').trim();
    if (q && q.length <= 200) add('search', q, 'search');
  } else if (
    raw.length <= 140 && !fence && !urls.length && !opts.hasAttachment &&
    /(اخبار|الاخبار|سعر|اسعار|الطقس|درجه الحراره|نتيجه مباراه|نتايج|اخر اصدار|احدث|latest|news|price of|weather|score of)/.test(n)
  ) {
    add('search', raw.replace(/[?؟!]+$/, ''), 'freshness');
  }

  // 6) date / time
  if (/(الساعه كام|كام الساعه|الوقت (?:الان|دلوقتي)|التاريخ|النهارده|اليوم كام|what time|current time|today'?s date|what'?s the date)/.test(n)) {
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

  return hits.slice(0, 3);
}
