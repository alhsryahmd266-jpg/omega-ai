/**
 * Run:  npx tsx tests/agentLogic.test.ts      (from GVRChat/)
 * Pure logic only — no React Native, no device needed.
 */
import assert from 'node:assert/strict';
import {
  addNote, applyEditBlocks, commandRisk, cwdIsSafe, endsWithConfirmQuestion, extractFiles, extractShellBlock,
  grepText, isAffirmation, journalDigest, missingCommands, mimeForName, newMission, packagesFor, parseEditArgs,
  rankMemories, recordStep, repeatCount, safeFileName, searchRegex, stubOldToolResults, toolOutcome, tokenize, wantsAction,
  clipResult, extractExports, globToRegExp, headTail, htmlParagraphs, pickPassages, planCurl, shellSplit,
  type MsgLike,
} from '../src/agentLogic';

let pass = 0;
let fail = 0;
function t(name: string, fn: () => void): void {
  try { fn(); pass++; } catch (e) { fail++; console.error(`✗ ${name}\n    ${(e as Error).message.split('\n').join('\n    ')}`); }
}

/* ── command risk ─────────────────────────────────────────────────────── */
t('risk: plain commands are safe', () => {
  for (const c of ['ls -la', 'unzip -o a.apk -d out', 'cat /sdcard/x.txt', 'python3 a.py', 'curl -sL https://x.y | head']) {
    assert.equal(commandRisk(c), 'safe', c);
  }
});
t('risk: rm inside the workspace is safe', () => {
  for (const c of ['rm -rf apk_work/out', 'rm -rf ./out', 'rm -rf /workspace/out', 'rm -rf /tmp/x', 'rm -rf ~/foo', 'rm -rf out 2>/dev/null', "find . -name '*.o' -delete"]) {
    assert.equal(commandRisk(c), 'safe', c);
  }
});
t('risk: rm outside the app is risky', () => {
  for (const c of ['rm -rf /', 'rm -rf /*', 'rm -rf /sdcard/Download', 'rm -rf ../x', 'rm -rf /storage/emulated/0/DCIM', 'find /sdcard -delete', 'rm -rf /etc']) {
    assert.equal(commandRisk(c), 'risky', c);
  }
});
t('risk: unknown variables / substitutions are risky, known ones are not', () => {
  assert.equal(commandRisk('rm -rf "$X"'), 'risky');
  assert.equal(commandRisk('rm -rf $(pwd)/x'), 'risky');
  assert.equal(commandRisk('X=/workspace/a; rm -rf "$X"'), 'safe');
  assert.equal(commandRisk('OUT=/sdcard/a\nrm -rf "$OUT"'), 'risky');
  assert.equal(commandRisk('rm -rf $HOME/x'), 'safe');
});
t('risk: device-level commands are always risky', () => {
  for (const c of ['dd if=a of=/dev/sda', 'mkfs.ext4 /dev/x', ':(){ :|:& };:', 'rm -rf --no-preserve-root /']) assert.equal(commandRisk(c), 'risky', c);
});
t('risk: cwd outside the app makes relative deletes risky', () => {
  assert.equal(commandRisk('rm -rf a', '/sdcard/Download'), 'risky');
  assert.equal(commandRisk('rm -rf a', '/workspace'), 'safe');
  assert.equal(commandRisk('rm -rf a', ''), 'safe');
  assert.equal(cwdIsSafe('/data/user/0/com.x/files/workspace'), true);
  assert.equal(cwdIsSafe('/storage/emulated/0'), false);
});

/* ── outcome ──────────────────────────────────────────────────────────── */
t('outcome classification', () => {
  assert.equal(toolOutcome('The user denied this action. Do not retry it.'), 'denied');
  assert.equal(toolOutcome('Stopped by the user.'), 'denied');
  assert.equal(toolOutcome('Tool error (terminal): boom'), 'error');
  assert.equal(toolOutcome('File not found: x'), 'error');
  assert.equal(toolOutcome('unzip: not found\n(exit code 127)'), 'error');
  assert.equal(toolOutcome('total 4\n(exit code 0)'), 'ok');
  assert.equal(toolOutcome('Archive: a.apk\n  inflating: x'), 'ok');
  assert.equal(toolOutcome('Edit failed — nothing was changed.'), 'error');
  for (const m of ['Download failed: HTTP 404', 'Fetch error: timed out', 'Search failed on every provider:\n- ddg', 'Path not found: /x', 'URL must start with http://', 'Calc error: x']) assert.equal(toolOutcome(m), 'error', m);
});

/* ── affirmation / confirmation question / shell block ────────────────── */
t('affirmation', () => {
  for (const s of ['نعم', 'نعم افعل كل مايلزم', 'قلت لك نعم', 'اه كمل', 'ايوه', 'yes', 'Yes, do everything needed', 'كمّل', 'go ahead', 'يلا']) assert.ok(isAffirmation(s), s);
  for (const s of ['فكك الملف ده بالترمنال واستخدم الادوات اللازمه لتعديل الملف', 'ايه هو الـ apk؟', 'no', 'لا', '']) assert.ok(!isAffirmation(s), s);
});
t('confirmation question at the end of a reply (the loop from the video)', () => {
  const video = 'سنقوم أولاً بفك ضغط الملف.\n\n```sh\nunzip a.apk -d out\n```\n\nهذا الأمر سيفك الضغط.\n\nهل تريد متابعة هذه الخطوة؟';
  assert.ok(endsWithConfirmQuestion(video));
  assert.ok(endsWithConfirmQuestion('بعد فك الضغط سنجد مجلداً.\n\nهل تريد متابعة هذه الخطوة؟\n\n```sh\nunzip x\n```'));
  assert.ok(endsWithConfirmQuestion('I can unzip it. Do you want me to continue?'));
  assert.ok(!endsWithConfirmQuestion('تم فك الضغط. الملفات في المجلد out.'));
  assert.ok(!endsWithConfirmQuestion('هل الملف كبير؟ لا.'));
});
t('wantsAction', () => {
  assert.ok(wantsAction('فكك الملف ده بالترمنال واستخدم الادوات اللازمه لتعديل الملف'));
  assert.ok(wantsAction('unzip this apk and fix it'));
  assert.ok(wantsAction('نعم افعل كل مايلزم'));
  assert.ok(!wantsAction('ازاي افك ضغط ملف في لينكس؟'));
  assert.ok(!wantsAction('how do I unzip a file?'));
  assert.ok(!wantsAction('مين رئيس مصر؟'));
});
t('extractShellBlock', () => {
  const r = extractShellBlock('سنفك الملف:\n\n```sh\n$ unzip app.apk -d out\n```\nتمام');
  assert.ok(r);
  assert.equal(r!.command, 'unzip app.apk -d out');
  assert.equal(r!.before, 'سنفك الملف:');
  assert.equal(extractShellBlock('```python\nprint(1)\n```'), null);
  assert.equal(extractShellBlock('no code here'), null);
  assert.equal(extractShellBlock('```bash\n\n```'), null);
});
t('safeFileName / mime', () => {
  assert.equal(safeFileName('app-release (3).apk'), 'app-release_3.apk');
  assert.equal(safeFileName('تقرير.pdf'), 'file.pdf');
  assert.equal(safeFileName('a b  c.tar.gz'), 'a_b_c.tar.gz');
  assert.equal(safeFileName('..hidden'), 'hidden');
  assert.equal(mimeForName('x.APK'), 'application/vnd.android.package-archive');
  assert.equal(mimeForName('x.bin'), 'application/octet-stream');
});
t('missing programs → alpine packages', () => {
  assert.deepEqual(missingCommands('sh: unzip: not found'), ['unzip']);
  assert.deepEqual(missingCommands('bash: curl: command not found\n/bin/sh: 1: zip: not found'), ['curl', 'zip']);
  assert.deepEqual(missingCommands('/system/bin/sh: unzip: inaccessible or not found'), ['unzip']);
  assert.deepEqual(missingCommands('all good'), []);
  assert.deepEqual(packagesFor(['unzip', 'java', 'weirdtool', 'ffprobe', 'ffmpeg']), ['unzip', 'openjdk17-jre-headless', 'ffmpeg']);
});

/* ── Aider-style edits ────────────────────────────────────────────────── */
const FILE = 'def a():\n    return 1\n\ndef b():\n    return 2\n';
const blk = (path: string, s: string, r: string) => `${path}\n<<<<<<< SEARCH\n${s}\n=======\n${r}\n>>>>>>> REPLACE`;

t('edit: parse single + multiple blocks', () => {
  const p = parseEditArgs(blk('src/x.py', 'return 1', 'return 10'));
  assert.ok(!('error' in p));
  if (!('error' in p)) { assert.equal(p.path, 'src/x.py'); assert.equal(p.blocks.length, 1); assert.equal(p.blocks[0].replace, 'return 10'); }
  const two = parseEditArgs(`${blk('f', 'a', 'b')}\n<<<<<<< SEARCH\nc\n=======\nd\n>>>>>>> REPLACE`);
  assert.ok(!('error' in two) && two.blocks.length === 2);
});
t('edit: parse errors are explained', () => {
  assert.ok('error' in parseEditArgs('just a path'));
  assert.ok('error' in parseEditArgs('f\n<<<<<<< SEARCH\nx\n'));
  assert.ok('error' in parseEditArgs('f\nhello'));
});
t('edit: exact replace + diff', () => {
  const r = applyEditBlocks(FILE, [{ search: '    return 2', replace: '    return 20' }]);
  assert.ok(r.ok);
  if (r.ok) { assert.equal(r.content, 'def a():\n    return 1\n\ndef b():\n    return 20\n'); assert.match(r.diff, /@@ line 5 @@/); assert.match(r.diff, /^- {5}return 2$/m); assert.match(r.diff, /^\+ {5}return 20$/m); }
});
t('edit: ambiguous match is refused', () => {
  const r = applyEditBlocks(FILE, [{ search: 'return', replace: 'yield' }]);
  assert.ok(!r.ok);
  if (!r.ok) assert.match(r.error, /matches 2 places/);
});
t('edit: not found → nothing changed + closest hint', () => {
  const r = applyEditBlocks(FILE, [{ search: 'def b():\n    return 3', replace: 'x' }]);
  assert.ok(!r.ok);
  if (!r.ok) { assert.match(r.error, /NOTHING was changed/); assert.match(r.error, /Closest place \(line 4\)/); }
});
t('edit: closest hint is not fooled by a short generic fragment earlier in a big file (real APKEditor strings.xml bug)', () => {
  // "</string>" alone is a substring of the WRONG search line too, and appears on its own line much earlier
  // (a wrapped multi-line resource) — the real target, further down, must still win on shared distinctive words.
  const lines = [
    '<resources>',
    '  <string name="catalyst_copy_button">Copy',
    '</string>',                                                 // generic closing tag, standalone, line 3
    '  <string name="catalyst_debug_connecting">Connecting to debugger...</string>',
    '  <string name="app_name">GVRChat</string>',                 // the real target, line 5
    '</resources>',
  ];
  const file = lines.join('\n');
  const r = applyEditBlocks(file, [{ search: '  <string name="app_name">GVR Chat</string>', replace: 'x' }]);
  assert.ok(!r.ok);
  if (!r.ok) {
    assert.match(r.error, /Closest place \(line 5\)/, r.error);
    assert.match(r.error, /GVRChat/);
  }
});
t('edit: closest hint gives up honestly when nothing shares real words (no false positive)', () => {
  const r = applyEditBlocks('aaa\nbbb\nccc\n', [{ search: 'totally different content here', replace: 'x' }]);
  assert.ok(!r.ok);
  if (!r.ok) assert.match(r.error, /does not appear anywhere in the file/);
});
t('edit: all-or-nothing across blocks', () => {
  const r = applyEditBlocks(FILE, [{ search: 'return 1', replace: 'return 100' }, { search: 'does not exist', replace: 'x' }]);
  assert.ok(!r.ok);
});
t('edit: tolerant of trailing spaces and wrong indentation', () => {
  const messy = 'if x:  \n    foo()\n    bar()\n';
  const r1 = applyEditBlocks(messy, [{ search: 'if x:\n    foo()', replace: 'if y:\n    foo()' }]);
  assert.ok(r1.ok && r1.content === 'if y:\n    foo()\n    bar()\n');
  const r2 = applyEditBlocks('class A:\n    def f(self):\n        pass\n', [{ search: 'def f(self):\n    pass', replace: 'def f(self):\n    return 1' }]);
  assert.ok(r2.ok);
  if (r2.ok) assert.equal(r2.content, 'class A:\n    def f(self):\n        return 1\n');
});
t('edit: new file with empty SEARCH; empty SEARCH on existing file is refused', () => {
  const r = applyEditBlocks(null, [{ search: '', replace: 'hello\n' }]);
  assert.ok(r.ok && r.content === 'hello\n');
  assert.ok(!applyEditBlocks('x', [{ search: '', replace: 'y' }]).ok);
});
t('edit: CRLF files stay CRLF', () => {
  const r = applyEditBlocks('a\r\nb\r\nc\r\n', [{ search: 'b', replace: 'B' }]);
  assert.ok(r.ok && r.content === 'a\r\nB\r\nc\r\n');
});
t('code search helpers', () => {
  assert.equal(grepText('a\nfoo bar\nb\nfoo', /foo/, 10).length, 2);
  assert.equal(grepText('foo\nfoo\nfoo', /foo/, 2).length, 2);
  assert.ok(searchRegex('Hello').test('Hello') && !searchRegex('Hello').test('hello'));   // smart-case
  assert.ok(searchRegex('hello').test('HELLO'));
  assert.ok(searchRegex('/a+b/').test('caab'));
  assert.ok(searchRegex('a(').test('a('));                                                   // invalid regex → literal
});

/* ── mission journal ──────────────────────────────────────────────────── */
t('journal: records steps, files and outcomes', () => {
  let m = newMission('فكك الـ APK وعدّل الاسم');
  m = recordStep(m, 'terminal', 'unzip -o /workspace/uploads/app.apk -d /workspace/apk_work/app', 'Archive: ... 312 files', 1);
  m = recordStep(m, 'edit_file', '/workspace/apk_work/app/assets/config.json\n<<<<<<< SEARCH...', 'Edited /workspace/apk_work/app/assets/config.json (1 change)', 2);
  m = recordStep(m, 'terminal', 'zip x', 'zip: not found\n(exit code 127)', 3);
  assert.equal(m.steps.length, 3);
  assert.equal(m.steps[2].outcome, 'error');
  assert.ok(m.files.includes('/workspace/apk_work/app/assets/config.json'));
  const d = journalDigest(m, 1500);
  assert.match(d, /Work log/);
  assert.match(d, /3 steps/);
  assert.match(d, /ERROR/);
  assert.match(d, /Files:/);
});
t('journal: digest respects the budget and keeps the newest steps', () => {
  let m = newMission('long job');
  for (let i = 1; i <= 60; i++) m = recordStep(m, 'terminal', `cmd number ${i} with a fairly long argument to take up space`, `output of step ${i} `.repeat(8), i);
  const d = journalDigest(m, 900);
  assert.ok(d.length <= 1000, `digest too long: ${d.length}`);
  assert.match(d, /60\. terminal/);
  assert.match(d, /earlier steps omitted/);
  assert.ok(!/\b1\. terminal/.test(d));
});
t('journal: step list is capped but counts survive', () => {
  let m = newMission('x');
  for (let i = 0; i < 100; i++) m = recordStep(m, 'calc', '1+1', '2', i);
  assert.ok(m.steps.length <= 80);
  assert.equal(m.dropped + m.steps.length, 100);
  assert.match(journalDigest(m, 1200), /100 steps/);
});
t('journal: empty mission gives no digest; notes work', () => {
  assert.equal(journalDigest(null, 500), '');
  assert.equal(journalDigest(newMission('x'), 500), '');
  const m = addNote(recordStep(newMission('x'), 'calc', '1', '1', 1), 'user prefers Arabic');
  assert.match(journalDigest(m, 800), /Notes: user prefers Arabic/);
});
t('repeatCount', () => {
  assert.equal(repeatCount([]), 0);
  assert.equal(repeatCount([{ tool: 'a', arg: '1' }, { tool: 'b', arg: '1' }, { tool: 'b', arg: '1' }, { tool: 'b', arg: '1' }]), 3);
  assert.equal(repeatCount([{ tool: 'b', arg: '1' }, { tool: 'b', arg: '2' }]), 1);
});
t('extractFiles', () => {
  assert.deepEqual(extractFiles('saved to /workspace/out/app-mod.apk and /sdcard/Download/GVR/a.zip.'), ['/workspace/out/app-mod.apk', '/sdcard/Download/GVR/a.zip']);
});
t('stubOldToolResults shrinks old results, keeps recent ones and the calls', () => {
  const big = 'x'.repeat(2000);
  const msgs: MsgLike[] = [
    { role: 'user', content: 'goal' },
    { role: 'assistant', content: 'calling' },
    { role: 'tool', content: big },
    { role: 'assistant', content: 'again' },
    { role: 'user', content: `<tool_result name="terminal">\n${big}\n</tool_result>` },
    { role: 'assistant', content: 'last call' },
    { role: 'tool', content: big },
  ];
  const n = stubOldToolResults(msgs, 2);
  assert.equal(n, 2);
  assert.ok((msgs[2].content as string).length < 300);
  assert.match(msgs[4].content as string, /^<tool_result name="terminal">/);
  assert.match(msgs[4].content as string, /older result trimmed/);
  assert.equal((msgs[6].content as string).length, 2000);       // recent: untouched
  assert.equal(stubOldToolResults(msgs, 2), 0);                 // idempotent
});

/* ── memory ranking ───────────────────────────────────────────────────── */
t('tokenize handles Arabic forms', () => {
  assert.deepEqual(tokenize('الأَندرويد'), ['اندرويد']);
  assert.ok(tokenize('Hello, World!').includes('hello'));
});
t('rankMemories finds the relevant note (Arabic + English)', () => {
  const items = [
    { key: 'user_name', value: 'اسم المستخدم أحمد ويفضل الردود بالعامية المصرية' },
    { key: 'ep:1', value: 'فكيت app-release.apk وعدّلت config.json وسلّمت /sdcard/Download/GVR/app-mod.apk' },
    { key: 'project', value: 'The GVR-Chat project uses Expo 51 and llama.rn' },
    { key: 'tool:ffmpeg', value: 'ffmpeg -i in.mp4 out.mp3' },
  ];
  assert.equal(rankMemories('عايز اعدل ملف apk', items, 1)[0]?.key, 'ep:1');
  assert.equal(rankMemories('which framework does the project use', items, 1)[0]?.key, 'project');
  assert.equal(rankMemories('ايه الطقس النهارده', items).length, 0);
  assert.equal(rankMemories('', items).length, 0);
});


/* ── terminal output + session env ────────────────────────────────────── */
t('headTail keeps the beginning AND the end (errors live at the end)', () => {
  const long = `START${'x'.repeat(20000)}Error: boom\n(exit code 2)`;
  const r = headTail(long, 2500, 5000);
  assert.ok(r.omitted > 10000);
  assert.ok(r.text.startsWith('START'));
  assert.ok(r.text.endsWith('(exit code 2)'));
  assert.match(r.text, /characters omitted/);
  assert.equal(headTail('short', 2500, 5000).omitted, 0);
  assert.equal(toolOutcome(r.text), 'error');                       // the exit code survives the cut
  assert.ok(clipResult(long, 3000).length < 3200);
  assert.ok(clipResult(long, 3000).endsWith('(exit code 2)'));
});
t('toolOutcome sees a failure even when the app appended a hint after the exit code', () => {
  assert.equal(toolOutcome("sh: unzip: not found\n(exit code 127)\n(hint: 'unzip' is not installed — call install_tool (unzip), then run it again)"), 'error');
  assert.equal(toolOutcome('all good\n(exit code 0)'), 'ok');
  const note = headTail(`S${'x'.repeat(20000)}\n(exit code 4)`, 2500, 5000, ' — saved in /workspace/.last_output.txt').text;
  assert.match(note, /saved in \/workspace\/\.last_output\.txt/);
  assert.ok(note.endsWith('(exit code 4)'));
});
t('extractExports: only simple top-level exports persist (also when chained)', () => {
  assert.deepEqual(extractExports('export A=1\nls\nexport PATH=$PATH:/opt/x').map(e => e.name), ['A', 'PATH']);
  assert.deepEqual(extractExports('pwd && export A=1 && export B="x y"; echo hi').map(e => e.line), ['export A=1', 'export B="x y"']);
  assert.deepEqual(extractExports('echo "not export A=1"'), []);
  assert.deepEqual(extractExports('  export NESTED=1\nif true; then\n  export X=2\nfi'), []);
  assert.deepEqual(extractExports('export A=$(whoami)'), []);
  assert.deepEqual(extractExports('export MSG="a;b"').map(e => e.name), ['MSG']);
});

/* ── web pages / curl / glob / shell split ────────────────────────────── */
const PAGE = '<html><head><title>T</title><script>var x=1</script></head><body><nav>Home | About | Contact us now please</nav>' +
  '<article><h1>Apktool guide to decoding resources</h1>' +
  '<p>Apktool is a tool for reverse engineering Android apk files. It can decode resources to nearly original form.</p>' +
  '<p>Cookie consent: we use cookies, accept all to continue browsing this site today.</p>' +
  '<p>You can rebuild the decoded resources after making some modifications to the application source files.</p>' +
  '<p>' + 'Unrelated filler text about gardening and the weather in spring. '.repeat(8) + '</p></article><footer>All rights reserved</footer></body></html>';
t('htmlParagraphs drops menus, scripts, cookie banners and footers', () => {
  const ps = htmlParagraphs(PAGE);
  assert.ok(ps.some(p => /reverse engineering/.test(p)));
  assert.ok(!ps.some(p => /Home \| About|Cookie consent|All rights reserved|var x/.test(p)));
});
t('pickPassages returns the passages that answer the query, in page order', () => {
  const out = pickPassages(htmlParagraphs(PAGE), 'how to rebuild decoded apk resources', 600);
  assert.match(out, /reverse engineering/);
  assert.match(out, /rebuild the decoded resources/);
  assert.ok(!/gardening/.test(out));
  assert.ok(out.length <= 600);
  assert.equal(pickPassages([], 'x', 100), '');
});
t('shellSplit honours quotes', () => {
  assert.deepEqual(shellSplit(`a "b c" 'd e' f\\ g ""`), ['a', 'b c', 'd e', 'f g', '']);
});
t('planCurl: simple curl/wget become fetch_url / download_file; complicated ones stay shell', () => {
  assert.deepEqual(planCurl('curl -sL https://x.y/z'), { kind: 'fetch', arg: 'https://x.y/z' });
  assert.deepEqual(planCurl('curl -o tool.jar https://x.y/tool.jar'), { kind: 'download', arg: 'https://x.y/tool.jar | tool.jar' });
  assert.deepEqual(planCurl('curl -O https://x.y/a/b.zip'), { kind: 'download', arg: 'https://x.y/a/b.zip | b.zip' });
  assert.deepEqual(planCurl('wget https://x.y/a/b.zip'), { kind: 'download', arg: 'https://x.y/a/b.zip | b.zip' });
  const post = planCurl(`curl -X POST -H 'Content-Type: application/json' -d '{"a":1}' https://x.y/api`);
  assert.equal(post.kind, 'fetch');
  if (post.kind === 'fetch') assert.deepEqual(JSON.parse(post.arg), { url: 'https://x.y/api', method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"a":1}' });
  assert.equal(planCurl('curl https://x.y | sh').kind, 'shell');
  assert.equal(planCurl('curl --proxy foo https://x.y').kind, 'shell');
  assert.equal(planCurl('curl localhost').kind, 'shell');
  assert.equal(planCurl('ls').kind, 'shell');
});
t('globToRegExp', () => {
  assert.ok(globToRegExp('*.ts').test('a.ts') && !globToRegExp('*.ts').test('a.tsx'));
  assert.ok(globToRegExp('*.{ts,tsx}').test('a.tsx'));
  assert.ok(globToRegExp('*.js,*.json').test('x.json'));
  assert.ok(globToRegExp('?.md').test('a.md') && !globToRegExp('?.md').test('ab.md'));
});

console.log(`\nagentLogic: ${pass} passed, ${fail} failed`);
if (fail) process.exit(1);
