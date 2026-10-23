/**
 * toolsExtra.ts — the newer tools, wired into the registry by tools.ts via `createExtraTools(deps)`
 *   edit_file    Aider-style SEARCH/REPLACE edits (the model sends only the lines that change)
 *   search_code  grep over a folder (plain text or /regex/, smart-case, glob filter)
 *   view_image   look at a picture: vision model if loaded, otherwise OCR (tesseract) through Alpine
 *   deliver_file hand a result to the user: Downloads/GVR + share sheet, install an APK, or a public link
 *   apk_tool     unpack / pack / sign / decode / build APKs (the gvr-apk helper runs inside Alpine)
 *   deepenSearch / readPageText   read the CONTENT of web pages, not just titles
 *
 * Dependencies come in as a parameter, so this file never imports tools.ts (no import cycle).
 */
import * as FileSystem from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import * as IntentLauncher from 'expo-intent-launcher';
import { analyzeImage, isVisionReady } from './localLLM';
import { GVR_APK_PY, GVR_APK_VERSION } from './gvrApkScript';
import {
  applyEditBlocks, globToRegExp, grepText, htmlParagraphs, mimeForName, oneLine, parseEditArgs, pickPassages,
  safeFileName, searchRegex, shellSplit, toBase64,
} from './agentLogic';

export interface ExtraDeps {
  resolvePath(raw: string): { uri: string; inSandbox: boolean; inShared: boolean };
  ensureParent(uri: string): Promise<void>;
  /** Runs a command in the terminal (same as the `terminal` tool). */
  terminal(cmd: string): Promise<string>;
  linuxReady(): boolean;
  /** May tools install packages by themselves? (autonomy 'auto') */
  autoInstall(): boolean;
  fetchText(
    url: string,
    init?: { method?: string; headers?: Record<string, string>; body?: string },
    opts?: { timeoutMs?: number; retries?: number },
  ): Promise<{ status: number; contentType: string; url: string; text: string }>;
  /** file:// URI of the app workspace, with a trailing slash */
  workdir: string;
  /** file:///storage/emulated/0/ */
  sharedRoot: string;
}

const clip = (s: string, n: number): string => (s.length > n ? `${s.slice(0, n)}\n…[truncated ${s.length - n} chars]` : s);
const shq = (s: string): string => `'${s.replace(/'/g, `'\\''`)}'`;

const SKIP_DIRS = new Set(['node_modules', '.git', 'build', 'dist', '.gradle', '__pycache__', '.idea', 'Pods', '.next', 'venv', '.venv', 'apk_work_cache']);
const BINARY_EXT = /\.(?:png|jpe?g|gif|webp|ico|so|dex|arsc|apk|zip|gz|jar|mp[34]|webm|ogg|wav|ttf|otf|woff2?|pdf|gguf|bin|class|o|a|keystore|jks)$/i;
const HELPER_PATH = '/usr/local/bin/gvr-apk';

export function createExtraTools(d: ExtraDeps) {
  /** Any path the model may give (app-private, /workspace, /sdcard, relative) → the path Alpine sees. */
  function toLinuxPath(raw: string): string {
    let p = raw.trim().replace(/^["']|["']$/g, '');
    if (p.startsWith('file://')) p = p.slice(7);
    const work = d.workdir.replace(/^file:\/\//, '');                 // /data/user/0/<pkg>/files/workspace/
    if (p + '/' === work) return '/workspace';
    if (p.startsWith(work)) return `/workspace/${p.slice(work.length)}`;
    if (p === '/workspace' || p.startsWith('/workspace/')) return p;
    if (p.startsWith('/storage/emulated/0')) return p.replace('/storage/emulated/0', '/sdcard');
    if (p.startsWith('/')) return p;
    return `/workspace/${p}`;
  }

  /* ── edit_file ───────────────────────────────────────────────────────── */
  async function editFile(arg: string): Promise<string> {
    const parsed = parseEditArgs(arg);
    if ('error' in parsed) return parsed.error;
    let r: ReturnType<ExtraDeps['resolvePath']>;
    try { r = d.resolvePath(parsed.path); } catch (e: any) { return `Edit failed — ${e?.message || e}`; }
    if (!r.inSandbox && !r.inShared) return 'Refused: editing is only allowed inside the app workspace or /storage/emulated/0.';
    const info = await FileSystem.getInfoAsync(r.uri, { size: true });
    if (info.exists && info.isDirectory) return `Edit failed — ${r.uri} is a folder.`;
    if (info.exists && info.size > 1_500_000) return 'Edit failed — the file is larger than 1.5 MB. Use the terminal (sed / python) for big files.';
    let content: string | null = null;
    if (info.exists) {
      try { content = await FileSystem.readAsStringAsync(r.uri); } catch (e: any) { return `Edit failed — cannot read it as text: ${e?.message || e}`; }
    }
    const res = applyEditBlocks(content, parsed.blocks);
    if (!res.ok) return `Edit failed — nothing was changed. ${res.error}`;
    try {
      await d.ensureParent(r.uri);
      await FileSystem.writeAsStringAsync(r.uri, res.content);
    } catch (e: any) {
      return `Edit failed — write error: ${e?.message || e}`;
    }
    return `Edited ${r.uri} (${res.applied} change${res.applied > 1 ? 's' : ''}).\n${clip(res.diff, 2500)}`;
  }

  /* ── search_code ─────────────────────────────────────────────────────── */
  async function searchCode(arg: string): Promise<string> {
    const [patLine = '', pathLine = '', globLine = ''] = arg.split('\n').map(s => s.trim());
    if (!patLine) return 'Empty pattern. Argument: pattern (plain text or /regex/) — optional 2nd line: folder — optional 3rd line: file filter like *.ts';
    const re = searchRegex(patLine);
    const globRe = globLine ? globToRegExp(globLine) : null;
    let baseUri: string;
    try { baseUri = d.resolvePath(pathLine || d.workdir).uri; } catch (e: any) { return `Search failed — ${e?.message || e}`; }
    const info = await FileSystem.getInfoAsync(baseUri);
    if (!info.exists) return `Path not found: ${baseUri}`;

    // With Linux, let find + grep do it natively: a decoded APK has 16,000+ smali files and every file read through
    // the JavaScript bridge is a round trip. (Without Linux the JS walker below is the fallback.)
    if (d.linuxReady()) {
      const base = toLinuxPath(baseUri);
      if (base === '/workspace' || base.startsWith('/workspace/') || base.startsWith('/sdcard')) {
        return searchWithShell(patLine, base, globLine);
      }
    }

    // A decoded APK has 1,000+ resource files before the first .smali: the budgets must be generous, and a search
    // that stops early must SAY so (a silent "no matches" would make the model believe the code is not there).
    const deadline = Date.now() + 25_000;
    let capped = false;
    const files: string[] = [];
    if (!info.isDirectory) files.push(baseUri);
    else {
      const queue: { uri: string; depth: number }[] = [{ uri: baseUri.endsWith('/') ? baseUri : `${baseUri}/`, depth: 0 }];
      let visited = 0;
      while (queue.length) {
        if (visited >= 30_000 || files.length >= 5000 || Date.now() > deadline) { capped = true; break; }
        const cur = queue.shift()!;
        let names: string[] = [];
        try { names = await FileSystem.readDirectoryAsync(cur.uri); } catch { continue; }
        for (const n of names) {
          visited++;
          if (SKIP_DIRS.has(n) || BINARY_EXT.test(n)) continue;
          const child = cur.uri + n;
          if (!n.includes('.')) {                                           // probably a folder: check (one bridge call)
            if (cur.depth < 6) {
              const ci = await FileSystem.getInfoAsync(child);
              if (ci.exists && ci.isDirectory) { queue.push({ uri: `${child}/`, depth: cur.depth + 1 }); continue; }
            }
          }
          if (globRe && !globRe.test(n)) continue;
          files.push(child);
        }
      }
    }

    const out: string[] = [];
    let matches = 0;
    let scanned = 0;
    const base = baseUri.endsWith('/') ? baseUri : `${baseUri}/`;
    for (let i = 0; i < files.length && matches < 60; i += 8) {
      if (scanned >= 3000 || Date.now() > deadline) { capped = true; break; }
      const batch = files.slice(i, i + 8);
      const texts = await Promise.all(batch.map(async f => {
        try { return await FileSystem.readAsStringAsync(f, { length: 400000 }); } catch { return null; }
      }));
      texts.forEach((t, k) => {
        if (t === null || t.includes('\u0000')) return;
        scanned++;
        for (const g of grepText(t, re, 5)) {
          if (matches >= 60) break;
          matches++;
          out.push(`${batch[k].startsWith(base) ? batch[k].slice(base.length) : batch[k]}:${g.line}: ${g.text.trim()}`);
        }
      });
    }
    const note = capped ? '\n(the search stopped early because the folder is very large: give a narrower folder or a file filter like *.smali)' : '';
    if (!matches) return `No matches for ${JSON.stringify(patLine)} in ${scanned} files under ${baseUri}.${note}`;
    return `${matches}${matches >= 60 ? '+' : ''} matches (searched ${scanned} files under ${baseUri}):\n${out.join('\n')}${note}`;
  }

  /**
   * Code search inside Alpine. ripgrep first (the strongest: parallel, .gitignore-aware, skips binaries itself);
   * it is installed by itself in autonomous mode. Plain find+grep only when ripgrep cannot be had.
   */
  async function searchWithShell(patLine: string, base: string, globLine: string): Promise<string> {
    const rx = /^\/(.+)\/([gimsuy]*)$/.exec(patLine.trim());
    const pat = rx ? rx[1] : patLine.trim();
    const ci = rx ? rx[2].includes('i') : !/[A-Z]/.test(patLine);                    // smart-case
    const globs = globLine ? globLine.split(/,(?![^{]*\})/).map(g => g.trim()).filter(Boolean) : [];

    const rgGlobs = globs.map(g => `-g ${shq(g)}`).join(' ');
    const rgSkip = ['node_modules', '.git', 'build', 'dist', '.gradle', '__pycache__', 'Pods', '.idea']
      .map(n => `-g ${shq(`!${n}`)}`).join(' ');
    const rgCmd =
      `rg --line-number --no-heading --color never --max-count 5 --max-columns 220 --max-columns-preview ` +
      `${ci ? '-i ' : ''}${rx ? '' : '-F '}-e ${shq(pat)} ${rgGlobs ? `${rgGlobs} ` : ''}${rgSkip} . | head -n 60`;

    const names = globs.length ? `\\( ${globs.map(g => `-name ${shq(g)}`).join(' -o ')} \\)` : '';
    const skip = ['node_modules', '.git', 'build', 'dist', '.gradle', '__pycache__', 'Pods'].map(n => `-name ${shq(n)}`).join(' -o ');
    const binary = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'so', 'dex', 'arsc', 'apk', 'zip', 'jar', 'ttf', 'otf', 'mp3', 'mp4', 'ogg', 'pdf', 'gguf', 'bin', 'class', 'o', 'keystore']
      .map(e => `! -name '*.${e}'`).join(' ');
    const grepCmd =
      `find . \\( ${skip} \\) -prune -o -type f ${binary} ${names} -print0 2>/dev/null | ` +
      `xargs -0 grep -n ${ci ? '-i ' : ''}${rx ? '-E' : '-F'} -e ${shq(pat)} 2>/dev/null | grep -v '^Binary file' | cut -c1-220 | head -n 60`;

    const install = d.autoInstall() ? 'apk add --no-cache ripgrep >/dev/null 2>&1' : 'true';
    const cmd =
      `#timeout=180\n` +
      `cd ${shq(base)} 2>/dev/null || exit 3\n` +
      `if ! command -v rg >/dev/null 2>&1; then ${install}; fi\n` +
      `if command -v rg >/dev/null 2>&1; then ${rgCmd}; else ${grepCmd}; fi`;

    const out = (await d.terminal(cmd)).trim();
    const lines = out.split('\n').filter(l => /^.+?:\d+:/.test(l)).map(l => l.replace(/^\.\//, ''));
    if (!lines.length) {
      // a bad pattern makes rg print "regex parse error" and nothing else: say so instead of "no matches"
      if (out.length < 600 && /error/i.test(out)) return `Search failed: ${oneLine(out, 300)}`;
      return `No matches for ${JSON.stringify(patLine)} under ${base}${globLine ? ` (${globLine})` : ''}.`;
    }
    return `${lines.length}${lines.length >= 60 ? '+' : ''} matches under ${base}:\n${lines.join('\n')}`;
  }

  /* ── view_image ──────────────────────────────────────────────────────── */
  async function viewImage(arg: string): Promise<string> {
    const lines = arg.split('\n');
    const pathLine = (lines[0] || '').trim();
    const question = lines.slice(1).join('\n').trim() || 'Describe this image in detail. Transcribe any text you can read.';
    if (!pathLine) return 'Tool error (view_image): give the image path (first line) and optionally a question (next lines).';
    let r: ReturnType<ExtraDeps['resolvePath']>;
    try { r = d.resolvePath(pathLine); } catch (e: any) { return `Tool error (view_image): ${e?.message || e}`; }
    const info = await FileSystem.getInfoAsync(r.uri);
    if (!info.exists) return `File not found: ${r.uri}`;
    if (info.isDirectory) return `That is a folder, not an image: ${r.uri}`;

    let visionError = '';
    if (isVisionReady()) {
      try {
        const text = await analyzeImage(r.uri, question, 500);
        return `Image analysis (vision model):\n${text}`;
      } catch (e: any) { visionError = ` (the vision model failed: ${e?.message || e})`; }
    }
    if (!d.linuxReady()) {
      return `I cannot look at pictures yet${visionError}: no vision model is loaded. Fix: load a vision model with its mmproj file (Settings → Models → "نموذج رؤية"), or install Alpine Linux (Settings → Linux) so I can read the TEXT in the image with OCR.`;
    }
    let linuxPath = toLinuxPath(r.uri);
    if (!linuxPath.startsWith('/workspace/') && !linuxPath.startsWith('/sdcard/')) {
      const tmp = `${d.workdir}tmp_view/${safeFileName(r.uri.split('/').pop() || 'img.png')}`;
      await d.ensureParent(tmp);
      await FileSystem.copyAsync({ from: r.uri, to: tmp });
      linuxPath = toLinuxPath(tmp);
    }
    const installOcr = d.autoInstall()
      ? 'if ! command -v tesseract >/dev/null 2>&1; then apk add --no-cache tesseract-ocr tesseract-ocr-data-eng tesseract-ocr-data-ara >/dev/null 2>&1; fi\n'
      : 'if ! command -v tesseract >/dev/null 2>&1; then echo "OCR is not installed: call install_tool with tesseract-ocr (and tesseract-ocr-data-ara for Arabic), then view_image again."; exit 3; fi\n';
    // Arabic + English when the Arabic data is installed. tesseract silently skips a missing language and still
    // "succeeds", so ask it which languages exist, read with what is there, and say what is missing.
    const ocr =
      `LANGS=eng; NOTE=''\n` +
      `if tesseract --list-langs 2>/dev/null | grep -qx ara; then LANGS=ara+eng; else NOTE='(Arabic OCR data is not installed, so only Latin text was read: apk add tesseract-ocr-data-ara)'; fi\n` +
      `OUT=$(tesseract ${shq(linuxPath)} - -l "$LANGS" 2>/dev/null) || OUT=$(tesseract ${shq(linuxPath)} - -l eng 2>&1)\n` +
      `printf '%s\\n%s' "$OUT" "$NOTE" | head -c 4000`;
    const out = await d.terminal(`#timeout=300\n${installOcr}${ocr}`);
    return `No vision model is loaded${visionError}, so this is OCR only (it reads the text in the image; it cannot describe the picture):\n${out}`;
  }

  /* ── deliver_file ────────────────────────────────────────────────────── */
  async function uploadForLink(uri: string, name: string, mime: string): Promise<string> {
    const errors: string[] = [];
    try {
      const form = new FormData();
      form.append('reqtype', 'fileupload');
      form.append('fileToUpload', { uri, name, type: mime } as any);
      const res = await fetch('https://catbox.moe/user/api.php', { method: 'POST', body: form as any });
      const text = (await res.text()).trim();
      if (/^https?:\/\/\S+$/.test(text)) return text;
      errors.push(`catbox: ${text.slice(0, 100)}`);
    } catch (e: any) { errors.push(`catbox: ${e?.message || e}`); }
    try {
      const form = new FormData();
      form.append('file', { uri, name, type: mime } as any);
      const res = await fetch('https://tmpfiles.org/api/v1/upload', { method: 'POST', body: form as any });
      const j = JSON.parse(await res.text());
      const url = String(j?.data?.url || '');
      if (/^https?:\/\//.test(url)) return `${url.replace(/^http:/, 'https:').replace('tmpfiles.org/', 'tmpfiles.org/dl/')}  (expires after about 1 hour)`;
      errors.push(`tmpfiles: ${JSON.stringify(j).slice(0, 100)}`);
    } catch (e: any) { errors.push(`tmpfiles: ${e?.message || e}`); }
    throw new Error(errors.join(' | '));
  }

  async function deliverFile(arg: string): Promise<string> {
    const [first = '', ...rest] = arg.split('\n');
    let pathPart = first;
    let action = rest.join(' ').trim().toLowerCase();
    const bar = first.lastIndexOf('|');
    if (bar > 0 && !action) { action = first.slice(bar + 1).trim().toLowerCase(); pathPart = first.slice(0, bar); }
    if (!['share', 'install', 'link'].includes(action)) action = 'share';
    if (!pathPart.trim()) return 'Tool error (deliver_file): give the file path. Argument: path (optionally "path | install" for an APK, or "path | link" for a public download link).';

    let r: ReturnType<ExtraDeps['resolvePath']>;
    try { r = d.resolvePath(pathPart); } catch (e: any) { return `Tool error (deliver_file): ${e?.message || e}`; }
    const info = await FileSystem.getInfoAsync(r.uri, { size: true });
    if (!info.exists) return `File not found: ${r.uri}`;
    if (info.isDirectory) return `That is a folder. Zip it first (terminal: zip -r name.zip folder) and deliver the zip: ${r.uri}`;

    const name = safeFileName(r.uri.split('/').pop() || 'file');
    const mime = mimeForName(name);
    const sizeTxt = `${(info.size / 1048576).toFixed(1)} MB`;
    const notes: string[] = [];

    // 1) a private copy the share sheet / installer can read (FileProvider covers the app folder)
    const outbox = `${d.workdir.replace(/workspace\/$/, '')}outbox/${name}`;
    try {
      await d.ensureParent(outbox);
      await FileSystem.deleteAsync(outbox, { idempotent: true });
      await FileSystem.copyAsync({ from: r.uri, to: outbox });
    } catch (e: any) { return `Copy failed: ${e?.message || e}`; }

    // 2) a public copy the user can find in the Files app
    const publicUri = `${d.sharedRoot}Download/GVR/${name}`;
    let savedPublic = false;
    try {
      await d.ensureParent(publicUri);
      await FileSystem.deleteAsync(publicUri, { idempotent: true });
      await FileSystem.copyAsync({ from: r.uri, to: publicUri });
      savedPublic = true;
    } catch {
      notes.push('Could not save to Downloads/GVR (storage permission missing): use the permission tool with "storage" or "all_files".');
    }
    const where = savedPublic ? `/storage/emulated/0/Download/GVR/${name}` : outbox.replace(/^file:\/\//, '');
    const head = `Ready: ${name} (${sizeTxt}). Saved at ${where}.`;

    if (action === 'link') {
      try {
        const link = await uploadForLink(outbox, name, mime);
        return `${head}\nPublic download link (anyone who has it can download the file): ${link}\n${notes.join('\n')}`.trim();
      } catch (e: any) {
        return `${head}\nUpload failed (${e?.message || e}). The file itself is ready at the path above, and I can open the share sheet instead.\n${notes.join('\n')}`.trim();
      }
    }
    if (action === 'install') {
      if (!/\.apk$/i.test(name)) return `${head}\nOnly .apk files can be installed.`;
      try {
        const content = await FileSystem.getContentUriAsync(outbox);
        await IntentLauncher.startActivityAsync('android.intent.action.VIEW', { data: content, flags: 1, type: mime });
        return `${head}\nThe Android installer is open. If it says installing from this app is blocked, allow it (permission tool: "install_apps"). The APK has a DIFFERENT signature than the original app, so uninstall the original first or it will refuse to update.\n${notes.join('\n')}`.trim();
      } catch (e: any) {
        notes.push(`Could not open the installer (${e?.message || e}). Open the file from the Files app instead.`);
      }
    }
    try {
      if (await Sharing.isAvailableAsync()) {
        await Sharing.shareAsync(outbox, { mimeType: mime, dialogTitle: name });
        return `${head}\nThe share sheet is open so the user can send or save it.\n${notes.join('\n')}`.trim();
      }
    } catch (e: any) { notes.push(`Share sheet failed: ${e?.message || e}`); }
    return `${head}\n${notes.join('\n')}`.trim();
  }

  /* ── apk_tool ────────────────────────────────────────────────────────── */
  async function ensureApkHelper(): Promise<string | null> {
    const probe = await d.terminal(`grep -c '^GVR_APK_VERSION = ${GVR_APK_VERSION}$' ${HELPER_PATH} 2>/dev/null || echo 0`);
    if (/^1\s*$/m.test(probe.trim())) return null;
    const out = await d.terminal(`mkdir -p /usr/local/bin && printf '%s' '${toBase64(GVR_APK_PY)}' | base64 -d > ${HELPER_PATH} && chmod +x ${HELPER_PATH} && echo helper-installed`);
    return out.includes('helper-installed') ? null : `could not install the gvr-apk helper: ${clip(out, 300)}`;
  }

  const APK_ACTIONS = ['setup', 'info', 'unpack', 'pack', 'sign', 'decode', 'build'];

  async function apkTool(arg: string): Promise<string> {
    if (!d.linuxReady()) return 'The APK tools need Alpine Linux. Ask the user to install it from Settings → Linux, then try again.';
    const toks = shellSplit((arg.split('\n')[0] || '').trim());
    const action = (toks.shift() || '').toLowerCase();
    if (!APK_ACTIONS.includes(action)) {
      return `Tool error (apk_tool): unknown action '${action}'. Use one of: ${APK_ACTIONS.join(', ')}. Example: unpack /workspace/uploads/app.apk   then   pack /workspace/apk_work/app /workspace/out/app-mod.apk`;
    }
    const args = toks.map(t => (t.startsWith('--') ? t : toLinuxPath(t)));
    const problem = await ensureApkHelper();
    if (problem) return `Tool error (apk_tool): ${problem}`;
    const cmd = `#timeout=900\n(command -v python3 >/dev/null 2>&1 || apk add --no-cache python3 >/dev/null 2>&1)\nexport GVR_TOOLS=/opt/gvr\npython3 ${HELPER_PATH} ${[action, ...args].map(shq).join(' ')}`;
    return d.terminal(cmd);
  }

  /* ── deep web reading ────────────────────────────────────────────────── */
  const SKIP_HOSTS = /\.(?:pdf|zip|apk|mp4|mp3|exe|rar|7z)(?:[?#]|$)|(?:youtube\.com|youtu\.be|facebook\.com|instagram\.com|tiktok\.com|twitter\.com|\/\/x\.com|linkedin\.com|pinterest\.)/i;

  /**
   * Reads the best result pages in parallel and keeps the first three that really have text. A page that is blocked,
   * empty (a JavaScript app) or not HTML is skipped, and the next candidate takes its place.
   */
  async function deepenSearch(query: string, hits: { title: string; url: string }[], candidates: number, perPage: number): Promise<string> {
    const targets = hits.filter(h => /^https?:\/\//i.test(h.url) && !SKIP_HOSTS.test(h.url)).slice(0, candidates);
    const settled = await Promise.allSettled(targets.map(async h => {
      const r = await d.fetchText(h.url, {}, { timeoutMs: 8000, retries: 0 });
      if (r.status >= 400) throw new Error(`HTTP ${r.status}`);
      if (r.contentType && !/html|text|xml/i.test(r.contentType)) throw new Error('not a web page');
      const text = pickPassages(htmlParagraphs(r.text), query, perPage);
      if (text.length < 60) throw new Error('no readable text');
      return { h, text };
    }));
    const blocks: string[] = [];
    for (const r of settled) {
      if (blocks.length >= 3) break;
      if (r.status === 'fulfilled') blocks.push(`▸ ${oneLine(r.value.h.title, 90)}\n  ${r.value.h.url}\n  ${r.value.text.replace(/\n/g, '\n  ')}`);
    }
    return blocks.join('\n');
  }

  /** Readable text of an HTML page when it has real paragraphs; null → caller falls back to plain tag stripping. */
  function readPageText(html: string): string | null {
    const paras = htmlParagraphs(html);
    return paras.length >= 3 ? paras.join('\n') : null;
  }

  return { editFile, searchCode, viewImage, deliverFile, apkTool, deepenSearch, readPageText, toLinuxPath };
}
