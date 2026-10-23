/** Tool-level integration: the REAL tools.ts / toolsExtra.ts on stubbed phone APIs. */
import './stubs/install';
import assert from 'node:assert/strict';
import { fresh, runner, WORK } from './harness';
import * as FS from './stubs/expo-file-system';
import * as Sharing from './stubs/expo-sharing';
import * as Intent from './stubs/expo-intent-launcher';
import { calls as termCalls, state as term } from './stubs/terminal';
import { flags } from './stubs/localLLM';
import { dispatchTool, setAutonomy, setToolConfirmHandler, stopAllTools, toolOutcome, beginToolRun, getAutonomy, toolBrowse, resetBrowseSession, toolWebSearch, normalizeToolName } from '../src/tools';

const { t, finish } = runner('tools');
const decisions: { tool: string; arg: string; risky: boolean }[] = [];
const answerWith = (d: 'once' | 'always' | 'deny') =>
  setToolConfirmHandler(async (tool, arg, risky) => { decisions.push({ tool, arg, risky }); return d; });
const fetchStub = (impl: (url: string, init?: any) => any) => { (globalThis as any).fetch = async (url: string, init?: any) => impl(url, init); };

export const done = (async () => {
  /* ── approvals ── */
  await t('auto mode: a safe command never asks', async () => {
    await fresh(); decisions.length = 0; answerWith('deny');
    term.handler = () => 'ok';
    const r = await dispatchTool('terminal', 'ls -la');
    assert.equal(decisions.length, 0);
    assert.match(r, /ok/);
  });
  await t('auto mode: rm outside the app asks (red card) and "deny" is reported as denied', async () => {
    await fresh(); decisions.length = 0; answerWith('deny');
    const r = await dispatchTool('terminal', 'rm -rf /sdcard/Download/x');
    assert.equal(decisions.length, 1);
    assert.equal(decisions[0].risky, true);
    assert.equal(toolOutcome(r), 'denied');
    assert.equal(termCalls.length, 0);
  });
  await t('auto mode: rm inside the workspace is contained → no question', async () => {
    await fresh(); decisions.length = 0; answerWith('deny');
    await dispatchTool('terminal', 'rm -rf /workspace/apk_work/old');
    assert.equal(decisions.length, 0);
    assert.equal(termCalls.length, 1);
  });
  await t('auto mode with the guard OFF: everything runs by itself', async () => {
    await fresh(); decisions.length = 0; answerWith('deny');
    setAutonomy({ guard: false });
    await dispatchTool('terminal', 'rm -rf /sdcard/Download/x');
    assert.equal(decisions.length, 0);
    assert.equal(termCalls.length, 1);
  });
  await t('ask mode: asks; "always" is remembered for normal tools but never for risky commands', async () => {
    await fresh(); decisions.length = 0; answerWith('always');
    setAutonomy({ mode: 'ask' });
    await dispatchTool('terminal', 'ls');
    await dispatchTool('terminal', 'pwd');
    assert.equal(decisions.length, 1, 'second call must not ask');
    await dispatchTool('terminal', 'rm -rf /sdcard/a');
    await dispatchTool('terminal', 'rm -rf /sdcard/b');
    assert.equal(decisions.length, 3, 'risky asks every time');
  });
  await t('ask mode without any confirmation UI refuses instead of running', async () => {
    await fresh(); setAutonomy({ mode: 'ask' });
    const r = await dispatchTool('terminal', 'ls');
    assert.match(r, /^Denied: no confirmation UI/);
    assert.equal(termCalls.length, 0);
  });
  await t('after Stop no tool starts until the next request', async () => {
    await fresh();
    stopAllTools();
    assert.match(await dispatchTool('terminal', 'ls'), /^Stopped by the user/);
    beginToolRun();
    assert.doesNotMatch(await dispatchTool('terminal', 'ls'), /^Stopped/);
    assert.equal(getAutonomy().mode, 'auto');
  });

  /* ── terminal ── */
  await t('terminal starts in the workspace', async () => {
    await fresh();
    await dispatchTool('terminal', 'ls');
    assert.match(termCalls[0].wrapped, /^cd '\/workspace' 2>\/dev\/null/);
  });
  await t('a missing program is installed once and the command retried (auto mode)', async () => {
    await fresh();
    term.handler = () => (term.installed.length ? 'Archive: ok' : 'sh: unzip: not found\n(exit code 127)');
    const r = await dispatchTool('terminal', 'unzip a.zip');
    assert.deepEqual(term.installed, ['unzip']);
    assert.match(r, /\[auto-installed unzip\]/);
    assert.match(r, /Archive: ok/);
    assert.equal(termCalls.length, 2);
  });
  await t('ask mode: no silent install, the model gets a hint instead', async () => {
    await fresh(); setAutonomy({ mode: 'ask' }); answerWith('once');
    term.handler = () => 'sh: unzip: not found\n(exit code 127)';
    const r = await dispatchTool('terminal', 'unzip a.zip');
    assert.deepEqual(term.installed, []);
    assert.match(r, /hint: 'unzip' is not installed/);
  });
  await t('long timeouts are allowed (build jobs), capped at 900 s', async () => {
    await fresh();
    await dispatchTool('terminal', '#timeout=900\nsleep 1');
    await dispatchTool('terminal', '#timeout=999\nsleep 1');
    assert.equal(termCalls[0].timeout, 900);
    assert.equal(termCalls[1].timeout, 900);
  });
  await t('curl "called as a tool" becomes a real web request instead of an error', async () => {
    await fresh();
    fetchStub(url => ({ ok: true, status: 200, url, headers: { get: () => 'text/plain' }, text: async () => `hello from ${url}` }));
    const r = await dispatchTool('curl', 'curl -sL https://example.com/hi');
    assert.match(r, /hello from https:\/\/example\.com\/hi/);
  });

  /* ── paths ── */
  await t('/workspace/... (what Alpine calls it) is the real workspace for the file tools too', async () => {
    await fresh();
    FS.__put(`${WORK}notes.txt`, 'hello');
    assert.match(await dispatchTool('read_file', '/workspace/notes.txt'), /hello/);
  });

  /* ── edit_file ── */
  const SRC = 'def a():\n    return 1\n\ndef b():\n    return 2\n';
  await t('edit_file: replaces only the lines asked for and shows a diff', async () => {
    await fresh(); FS.__put(`${WORK}a.py`, SRC);
    const r = await dispatchTool('edit_file', '/workspace/a.py\n<<<<<<< SEARCH\n    return 2\n=======\n    return 20\n>>>>>>> REPLACE');
    assert.match(r, /^Edited /);
    assert.match(r, /@@ line 5 @@/);
    assert.equal(FS.__get(`${WORK}a.py`), 'def a():\n    return 1\n\ndef b():\n    return 20\n');
  });
  await t('edit_file: a failed match changes nothing and says so (error outcome)', async () => {
    await fresh(); FS.__put(`${WORK}a.py`, SRC);
    const r = await dispatchTool('edit_file', '/workspace/a.py\n<<<<<<< SEARCH\n    return 3\n=======\n    return 30\n>>>>>>> REPLACE');
    assert.equal(toolOutcome(r), 'error');
    assert.match(r, /nothing was changed/i);
    assert.equal(FS.__get(`${WORK}a.py`), SRC);
  });
  await t('edit_file: empty SEARCH creates a new file (parent folders included)', async () => {
    await fresh();
    const r = await dispatchTool('edit_file', '/workspace/new/dir/x.txt\n<<<<<<< SEARCH\n=======\nhello\n>>>>>>> REPLACE');
    assert.match(r, /^Edited /);
    assert.equal(FS.__get(`${WORK}new/dir/x.txt`), 'hello');
  });
  await t('edit_file works with native tool-call arguments too', async () => {
    await fresh(); FS.__put(`${WORK}a.py`, SRC);
    const { nativeArgToString } = await import('../src/tools');
    const arg = nativeArgToString('edit_file', { path: '/workspace/a.py', search: '    return 1', replace: '    return 10' });
    const r = await dispatchTool('edit_file', arg);
    assert.match(r, /^Edited /);
    assert.match(FS.__get(`${WORK}a.py`) || '', /return 10/);
  });

  /* ── search_code ── */
  await t('search_code (no Linux: JS walker) finds matches, skips node_modules and binaries, filters by glob', async () => {
    await fresh({ alpine: false });
    FS.__put(`${WORK}proj/src/a.ts`, 'export const foo = 1;\n');
    FS.__put(`${WORK}proj/src/b.ts`, 'const bar = foo + 1;\nconsole.log(foo)\n');
    FS.__put(`${WORK}proj/node_modules/x/index.js`, 'foo foo foo');
    FS.__put(`${WORK}proj/README.md`, 'foo is documented');
    FS.__put(`${WORK}proj/logo.png`, 'foo');
    const r = await dispatchTool('search_code', 'foo\n/workspace/proj');
    assert.match(r, /src\/a\.ts:1:/);
    assert.match(r, /src\/b\.ts:1:/);
    assert.match(r, /README\.md:1:/);
    assert.doesNotMatch(r, /node_modules|logo\.png/);
    const ts = await dispatchTool('search_code', 'foo\n/workspace/proj\n*.ts');
    assert.doesNotMatch(ts, /README/);
    assert.match(await dispatchTool('search_code', 'zzz-not-there\n/workspace/proj'), /^No matches/);
  });

  await t('search_code with Linux: ripgrep first (installed when missing), grep only as the fallback', async () => {
    await fresh(); FS.__put(`${WORK}proj/src/a.ts`, 'x');
    term.handler = () => "./src/a.ts:12:export class Foo {\n./src/b.ts:3:new Foo()\n";
    const r = await dispatchTool('search_code', 'Foo\n/workspace/proj\n*.ts,*.tsx');
    const cmd = termCalls[0].command;
    assert.ok(cmd.includes("cd '/workspace/proj'"));
    assert.ok(cmd.includes('command -v rg'), 'ripgrep is looked for first');
    assert.ok(cmd.includes('apk add --no-cache ripgrep'), 'ripgrep is installed when missing (auto mode)');
    assert.ok(cmd.includes('rg --line-number --no-heading --color never'));
    assert.ok(cmd.includes("-g '*.ts' -g '*.tsx'"));
    assert.ok(cmd.includes("-g '!node_modules'"));
    assert.ok(cmd.includes("-F -e 'Foo'"), 'plain text is a fixed string');
    assert.ok(!cmd.includes('rg --line-number --no-heading --color never --max-count 5 --max-columns 220 --max-columns-preview -i'), 'capitalised pattern is case-sensitive');
    assert.ok(cmd.includes("-name 'node_modules'"), 'the grep fallback still skips node_modules');
    assert.match(r, /^2 matches under \/workspace\/proj:\nsrc\/a\.ts:12:export class Foo/);
    term.handler = () => '';
    assert.match(await dispatchTool('search_code', 'nothing-here\n/workspace/proj'), /^No matches/);
    term.handler = () => 'rg: regex parse error:\n    (?<x\n    ^\nerror: unclosed group';
    assert.match(await dispatchTool('search_code', '/(?<x/\n/workspace/proj'), /^Search failed/);
    term.handler = () => '';
    await dispatchTool('search_code', '/foo.*bar/i\n/workspace/proj');
    assert.ok(termCalls.at(-1)!.command.includes("-i -e 'foo.*bar'"), 'the /regex/i flag is honoured by ripgrep');
  });

  /* ── deliver_file ── */
  await t('deliver_file: saves in Downloads/GVR and opens the share sheet', async () => {
    await fresh(); FS.__put(`${WORK}out/report.pdf`, 'PDFDATA');
    const r = await dispatchTool('deliver_file', '/workspace/out/report.pdf');
    assert.match(r, /Saved at \/storage\/emulated\/0\/Download\/GVR\/report\.pdf/);
    assert.equal(FS.__get('file:///storage/emulated/0/Download/GVR/report.pdf'), 'PDFDATA');
    assert.equal(Sharing.calls.length, 1);
    assert.equal(Sharing.calls[0].opts.mimeType, 'application/pdf');
  });
  await t('deliver_file | install: opens the Android package installer for an APK', async () => {
    await fresh(); FS.__put(`${WORK}out/app-mod.apk`, 'APKDATA');
    const r = await dispatchTool('deliver_file', '/workspace/out/app-mod.apk | install');
    assert.equal(Intent.calls.length, 1);
    assert.equal(Intent.calls[0].action, 'android.intent.action.VIEW');
    assert.equal(Intent.calls[0].params.type, 'application/vnd.android.package-archive');
    assert.match(Intent.calls[0].params.data, /^content:\/\//);
    assert.match(r, /DIFFERENT signature/);
  });
  await t('deliver_file: no storage permission → still delivers and tells how to fix it', async () => {
    await fresh(); FS.__put(`${WORK}out/a.zip`, 'Z'); FS.failOn.push('Download/GVR');
    const r = await dispatchTool('deliver_file', '/workspace/out/a.zip');
    assert.match(r, /storage permission/i);
    assert.equal(Sharing.calls.length, 1);
  });
  await t('deliver_file | link: uploads and returns the public link (falls back to a second host)', async () => {
    await fresh(); FS.__put(`${WORK}out/a.zip`, 'Z');
    setToolConfirmHandler(async () => 'once');
    fetchStub(url => url.includes('catbox')
      ? { text: async () => 'upstream error' }
      : { text: async () => JSON.stringify({ status: 'success', data: { url: 'http://tmpfiles.org/123/a.zip' } }) });
    const r = await dispatchTool('deliver_file', '/workspace/out/a.zip | link');
    assert.match(r, /Public download link.*https:\/\/tmpfiles\.org\/dl\/123\/a\.zip/);
  });
  await t('deliver_file | link always asks first (it sends the file to a third party)', async () => {
    await fresh(); decisions.length = 0; answerWith('deny'); FS.__put(`${WORK}out/a.zip`, 'Z');
    const r = await dispatchTool('deliver_file', '/workspace/out/a.zip | link');
    assert.equal(decisions.length, 1);
    assert.equal(toolOutcome(r), 'denied');
  });

  /* ── view_image ── */
  await t('view_image: vision model when loaded', async () => {
    await fresh(); FS.__put(`${WORK}uploads/shot.png`, 'PNG'); flags.vision = true;
    assert.match(await dispatchTool('view_image', '/workspace/uploads/shot.png'), /vision model.*cat sitting on a laptop/s);
  });
  await t('view_image: without a vision model it reads the text with OCR (and says so)', async () => {
    await fresh(); FS.__put(`${WORK}uploads/shot.png`, 'PNG');
    term.handler = c => (c.includes('tesseract') ? 'مرحبا Hello' : '');
    const r = await dispatchTool('view_image', '/workspace/uploads/shot.png');
    assert.match(r, /OCR only/);
    assert.match(r, /مرحبا Hello/);
    assert.ok(termCalls[0].command.includes("tesseract '/workspace/uploads/shot.png'"));
    assert.ok(termCalls[0].command.includes('apk add --no-cache tesseract-ocr'));
  });
  await t('view_image: ask mode does not install OCR silently', async () => {
    await fresh(); FS.__put(`${WORK}uploads/shot.png`, 'PNG'); setAutonomy({ mode: 'ask' });
    await dispatchTool('view_image', '/workspace/uploads/shot.png');
    assert.ok(!termCalls[0].command.includes('apk add --no-cache'));
  });
  await t('view_image: no vision and no Linux → honest instructions', async () => {
    await fresh({ alpine: false }); FS.__put(`${WORK}uploads/shot.png`, 'PNG');
    assert.match(await dispatchTool('view_image', '/workspace/uploads/shot.png'), /no vision model is loaded/);
  });

  /* ── apk_tool ── */
  await t('apk_tool: needs Linux, validates the action, installs its helper once', async () => {
    await fresh({ alpine: false });
    assert.match(await dispatchTool('apk_tool', 'unpack a.apk'), /Alpine Linux is not installed/);
    await fresh();
    assert.match(await dispatchTool('apk_tool', 'explode a.apk'), /unknown action 'explode'/);
    term.handler = c => (c.includes('grep -c') ? '0' : c.includes('base64 -d') ? 'helper-installed' : 'Unpacked 3 files');
    const r = await dispatchTool('apk_tool', 'unpack uploads/a.apk');
    assert.match(r, /Unpacked 3 files/);
    assert.ok(termCalls.some(c => c.command.includes("base64 -d > /usr/local/bin/gvr-apk")), 'helper written');
    assert.ok(termCalls.at(-1)!.command.includes("python3 /usr/local/bin/gvr-apk 'unpack' '/workspace/uploads/a.apk'"));
    assert.ok(termCalls.at(-1)!.timeout >= 900);
  });

  /* ── permissions ── */
  await t('permission tool opens the install-apps and battery screens for this app', async () => {
    await fresh();
    await dispatchTool('permission', 'install_apps');
    await dispatchTool('permission', 'battery');
    assert.equal(Intent.calls[0].action, 'android.settings.MANAGE_UNKNOWN_APP_SOURCES');
    assert.equal(Intent.calls[0].params.data, 'package:com.ahmedxg.gvrchat');
    assert.equal(Intent.calls[1].action, 'android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS');
  });

  /* ── memory ── */
  await t('mem_save + mem_search find a fact by meaning-ish keywords', async () => {
    await fresh();
    await dispatchTool('mem_save', 'user_name | اسم المستخدم أحمد ويحب الردود بالعامية');
    await dispatchTool('mem_save', 'project | GVR chat uses Expo 51 and llama.rn');
    assert.match(await dispatchTool('mem_search', 'اسم المستخدم'), /user_name/);
    assert.match(await dispatchTool('mem_search', 'which framework Expo'), /project/);
    assert.match(await dispatchTool('mem_search', 'الطقس'), /Nothing in memory/);
  });


  await t('the word "browse" is the browse tool (not an alias of fetch_url)', () => {
    assert.equal(normalizeToolName('browse'), 'browse');
    assert.equal(normalizeToolName('visit'), 'browse');
    assert.equal(normalizeToolName('fetch_url'), 'fetch_url');
  });

  /* ── browse: going INSIDE a website (fetch mocked) ── */
  const setPages = (map: Record<string, string>) => {
    (globalThis as any).fetch = async (url: string) => {
      const body = map[url];
      return { status: body === undefined ? 404 : 200, url, headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'text/html; charset=utf-8' : null) }, text: async () => body ?? '' };
    };
  };
  const SITE: Record<string, string> = {
    'https://example.com/a/': '<html><head><title>Home</title></head><body><main><h1>Home</h1><p>Welcome to the example home page, read the second page for the install steps.</p><a href="/b">Second page</a><form method="get" action="/search"><input name="q" type="text"></form></main></body></html>',
    'https://example.com/b': '<html><head><title>Second</title></head><body><main><p>The second page has the install steps for the tool, download the jar first.</p><a href="/a/">Back home</a></main></body></html>',
  };
  await t('browse: open a page, follow a link, find text, go back, read a part', async () => {
    await fresh(); resetBrowseSession(); setPages(SITE);
    assert.match(await toolBrowse('open https://example.com/a/'), /^\[browse\] Home — https:\/\/example\.com\/a\//);
    assert.match(await toolBrowse('links'), /\[1\] Second page/);
    assert.match(await toolBrowse('follow 1'), /^\[browse\] Second — https:\/\/example\.com\/b/);
    assert.match(await toolBrowse('find install steps'), /part 1:/);
    assert.match(await toolBrowse('back'), /^\[browse\] Home/);
    assert.match(await toolBrowse('read 2'), /There are 1 part/);
  });

  await t('browse: the site search box builds the real GET URL; bad input gives clear errors', async () => {
    await fresh(); resetBrowseSession();
    setPages({ ...SITE, 'https://example.com/search?q=welcome': '<html><head><title>Results</title></head><body><main><p>Search results for welcome on the example site.</p></main></body></html>' });
    await toolBrowse('open https://example.com/a/');
    assert.match(await toolBrowse('site_search welcome'), /^\[browse\] Results — https:\/\/example\.com\/search\?q=welcome/);
    assert.match(await toolBrowse('open https://example.com/missing'), /Tool error \(browse\).*HTTP 404/);
    assert.match(await toolBrowse('open ftp://nope'), /Tool error \(browse\)/);
    assert.match(await toolBrowse('frobnicate'), /unknown browse command/);
    resetBrowseSession();
    assert.match(await toolBrowse('links'), /No page is open/);
  });

  await t('search: engines run together and the best pages are read, not only their titles', async () => {
    await fresh(); resetBrowseSession();
    const ddgHtml = `<html><body>
      <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=${encodeURIComponent('https://docs.example.org/apk')}&amp;rut=1">Apk docs</a>
      <a class="result__snippet" href="#">How to decode an apk</a>
      <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=${encodeURIComponent('https://blog.example.net/x')}&amp;rut=1">Blog</a>
      <a class="result__snippet" href="#">a blog post</a></body></html>`;
    (globalThis as any).fetch = async (url: string) => {
      if (/duckduckgo\.com\/html/.test(url)) return { status: 200, url, headers: { get: () => 'text/html' }, text: async () => ddgHtml };
      if (url === 'https://docs.example.org/apk') return { status: 200, url, headers: { get: () => 'text/html' }, text: async () => '<html><head><title>Apk docs</title></head><body><main><p>To decode an apk run the decoder with the d command and the file name, it writes smali files.</p></main></body></html>' };
      if (url === 'https://blog.example.net/x') return { status: 200, url, headers: { get: () => 'text/html' }, text: async () => '<html><body></body></html>' };
      return { status: 404, url, headers: { get: () => null }, text: async () => '' };
    };
    const out = await toolWebSearch('how to decode apk');
    assert.match(out, /^\[DuckDuckGo · /);
    assert.match(out, /Content of the top results/);
    assert.match(out, /run the decoder with the d command/);
    assert.match(out, /browse open <url>/);
  });
  finish();
})();
