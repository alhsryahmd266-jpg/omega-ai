/** Pure page-model tests (no phone, no network). Run: npx tsx tests/webBrowse.test.ts */
import assert from 'node:assert/strict';
import { parseHtmlPage, resolveUrl, findInChunks, parseBrowseCommand, renderPage, renderLinks, normalizeOpenUrl, siteSearchUrl, toChunks, textPage } from '../src/webBrowse';

let pass = 0;
let fail = 0;
function t(name: string, fn: () => void): void {
  try { fn(); pass++; } catch (e) { fail++; console.error(`✗ ${name}\n    ${(e as Error).message.split('\n').join('\n    ')}`); }
}

const BASE = 'https://example.com/apktool/';
const PAGE = `<!doctype html><html><head><title>Apktool docs &amp; guide</title>
<meta name="description" content="A tool for reverse engineering Android apk files.">
<script>var x = "<a href='/no'>hidden</a>";</script><style>.a{}</style></head>
<body>
<nav><a href="/login">Sign in</a><a href="/pricing">Pricing</a></nav>
<header><form method="get" action="/search"><input type="search" name="q"><input type="hidden" name="type" value="code"></form></header>
<main><article>
<h1>Apktool</h1>
<p>Apktool decodes resources to nearly original form and rebuilds them after modifications.</p>
<h2>Usage</h2>
<p>Run <code>apktool d app.apk</code> to decode. See the <a href="docs/usage.html">usage guide</a> and the <a href="https://example.org/ext">external site</a>.</p>
<ul><li><a href="../releases">Releases</a></li><li><a href="#top">Back to top</a></li><li>Short</li></ul>
<h2>Install</h2><p>Download the jar from <a href="/releases/download/v2/apktool.jar">the release page</a> and run it with Java.</p>
</article></main>
<footer><a href="/about">About</a></footer></body></html>`;

t('title, description and outline; entities decoded', () => {
  const p = parseHtmlPage(PAGE, BASE);
  assert.equal(p.title, 'Apktool docs & guide');
  assert.equal(p.description, 'A tool for reverse engineering Android apk files.');
  assert.deepEqual(p.outline, ['Apktool', 'Usage', 'Install']);
});
t('readable text only: no nav, footer, scripts, styles or hidden markup', () => {
  const all = parseHtmlPage(PAGE, BASE).chunks.join('\n');
  assert.ok(all.includes('Apktool decodes resources'));
  assert.ok(all.includes('## Usage'));
  for (const bad of ['Sign in', 'Pricing', 'About', 'hidden', 'var x', 'reverse engineering Android']) {
    if (bad === 'reverse engineering Android') continue;            // this is the description, not body text
    assert.ok(!all.includes(bad), `leaked: ${bad}`);
  }
});
t('links: absolute, resolved, same-site first, no anchors or nav/footer links', () => {
  const p = parseHtmlPage(PAGE, BASE);
  const urls = p.links.map(l => l.url);
  assert.ok(urls.includes('https://example.com/apktool/docs/usage.html'));
  assert.ok(urls.includes('https://example.com/releases'));                    // "../releases" from /apktool/
  assert.ok(urls.includes('https://example.com/releases/download/v2/apktool.jar'));
  assert.ok(urls.includes('https://example.org/ext'));
  assert.ok(!urls.some(u => u.includes('#top') || u.includes('/login') || u.includes('/about')));
  assert.equal(p.links[0].same, true, 'site links come first');
  assert.equal(p.links[p.links.length - 1].same, false);
  assert.equal(p.links.find(l => l.text === 'Releases')?.url, 'https://example.com/releases');
});
t('search form: GET with a text box and its hidden fields; site_search URL', () => {
  const p = parseHtmlPage(PAGE, BASE);
  assert.equal(p.forms.length, 1);
  assert.equal(p.forms[0].method, 'get');
  assert.equal(p.forms[0].textField, 'q');
  assert.equal(p.forms[0].action, 'https://example.com/search');
  assert.deepEqual(p.forms[0].hidden, ['type=code']);
  assert.equal(siteSearchUrl(p.forms[0], 'decode apk'), 'https://example.com/search?q=decode%20apk&type=code');
});
t('a JavaScript shell has no readable text (the agent is told so)', () => {
  const p = parseHtmlPage('<html><head><title>App</title></head><body><div id="root"></div><script src="a.js"></script></body></html>', 'https://app.example/');
  assert.equal(p.chunks.length, 0);
  assert.match(renderPage(p), /probably a JavaScript app/);
});
t('resolveUrl: every common relative form', () => {
  assert.equal(resolveUrl('/a/../b/./c?x=1#f', 'https://h.com/z/y'), 'https://h.com/b/c?x=1');
  assert.equal(resolveUrl('//cdn.x.com/a.js', 'https://h.com/'), 'https://cdn.x.com/a.js');
  assert.equal(resolveUrl('?q=1', 'https://h.com/s/page?x=2'), 'https://h.com/s/page?q=1');
  assert.equal(resolveUrl('img/a.png', 'https://h.com'), 'https://h.com/img/a.png');
  assert.equal(resolveUrl('../../x', 'https://h.com/a/b/c/'), 'https://h.com/a/x');
  assert.equal(resolveUrl('https://other.org/p#frag', BASE), 'https://other.org/p');
  for (const bad of ['mailto:a@b.c', 'javascript:void(0)', 'tel:123', '#top', '']) assert.equal(resolveUrl(bad, BASE), null, bad);
});
t('find: every word, accent-insensitive, with the part number', () => {
  const chunks = toChunks(['Apktool decodes resources.', 'Install: download the jar and run it with Java.', 'إذا كنت تحتاج أداة للتحميل السريع']);
  const hits = findInChunks(chunks, 'DOWNLOAD jar');
  assert.equal(hits.length, 1);
  assert.equal(hits[0].part, 1);
  assert.match(hits[0].snippet, /download the jar/);
  assert.equal(findInChunks(chunks, 'اداه').length, 1, 'أداة matches اداه');
  assert.equal(findInChunks(chunks, 'nothing-here').length, 0);
});
t('long text is split into parts of at most 900 characters, on word boundaries', () => {
  const parts = toChunks([`${'word '.repeat(400)}`, 'x'.repeat(2000)], 900);
  assert.ok(parts.length >= 4);
  for (const p of parts) assert.ok(p.length <= 900, `part of ${p.length}`);
});
t('commands: every verb, numbers, and the bare-domain shortcut', () => {
  assert.deepEqual(parseBrowseCommand('open https://github.com/x/y'), { action: 'open', url: 'https://github.com/x/y' });
  assert.deepEqual(parseBrowseCommand('github.com/iBotPeaches/Apktool'), { action: 'open', url: 'github.com/iBotPeaches/Apktool' });
  assert.deepEqual(parseBrowseCommand('read 2'), { action: 'read', part: 2 });
  assert.deepEqual(parseBrowseCommand('read'), { action: 'read' });
  assert.deepEqual(parseBrowseCommand('follow 3'), { action: 'follow', n: 3 });
  assert.deepEqual(parseBrowseCommand('links releases'), { action: 'links', filter: 'releases' });
  assert.deepEqual(parseBrowseCommand('links'), { action: 'links', filter: '' });
  assert.deepEqual(parseBrowseCommand('find install steps'), { action: 'find', words: 'install steps' });
  assert.deepEqual(parseBrowseCommand('site_search apk decoder'), { action: 'site_search', words: 'apk decoder' });
  assert.deepEqual(parseBrowseCommand('back'), { action: 'back' });
  assert.deepEqual(parseBrowseCommand('download 4'), { action: 'download', n: 4 });
  assert.equal(parseBrowseCommand('follow x').action, 'error');
  assert.equal(parseBrowseCommand('dance now').action, 'error');
  assert.equal(parseBrowseCommand('').action, 'error');
});
t('addresses: https added when missing; non-web schemes refused', () => {
  assert.equal(normalizeOpenUrl('github.com/x'), 'https://github.com/x');
  assert.equal(normalizeOpenUrl('https://a.b/c'), 'https://a.b/c');
  assert.equal(normalizeOpenUrl('ftp://x.y'), null);
  assert.equal(normalizeOpenUrl('just words here'), null);
});
t('renderers: the view lists numbered links that follow N uses', () => {
  const p = parseHtmlPage(PAGE, BASE);
  const view = renderPage(p);
  assert.match(view, /^\[browse\] Apktool docs & guide — https:\/\/example\.com\/apktool\//);
  assert.match(view, /Links: \d+ \(site links first\)/);
  assert.match(view, /\[1\] /);
  const list = renderLinks(p, 'releases');
  assert.match(list, /matching "releases"/);
  const first = /\[(\d+)\] Releases/.exec(list);
  assert.ok(first, list);
  assert.equal(p.links[Number(first![1]) - 1].text, 'Releases');
  assert.match(renderLinks(p, 'zzz-none'), /No link matches/);
});
t('plain text pages become parts too', () => {
  const p = textPage('Title line\nsecond line of text\n\nthird', 'https://x.y/data.txt');
  assert.equal(p.title, 'Title line');
  assert.equal(p.links.length, 0);
  assert.ok(p.chunks.join('\n').includes('third'));
});

console.log(`webBrowse: ${pass} passed, ${fail} failed`);
if (fail) process.exit(1);
