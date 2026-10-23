/**
 * webBrowse.ts — the page model behind the `browse` tool.
 * Pure TypeScript (no React Native imports), so it is unit-tested in plain Node.
 * A web page becomes: title, description, headings, readable text parts, numbered links and search forms.
 * Navigation (follow, back, site search, download) lives in tools.ts, which owns the session and the network.
 */

export interface PageLink { text: string; url: string; same: boolean }
export interface PageForm { method: 'get' | 'post'; action: string; textField: string; hidden: string[] }
export interface PageModel {
  url: string;
  title: string;
  description: string;
  outline: string[];
  chunks: string[];
  links: PageLink[];
  forms: PageForm[];
}

/** Boilerplate and non-content: nothing inside these is read or listed as a link. */
const SKIP_TAGS = new Set(['script', 'style', 'noscript', 'template', 'svg', 'iframe', 'object', 'canvas', 'nav', 'header', 'footer', 'aside', 'select', 'button', 'textarea']);
const BLOCK_TAGS = new Set(['p', 'li', 'pre', 'blockquote', 'td', 'th', 'dd', 'dt', 'div', 'section', 'article', 'main', 'br', 'tr', 'table', 'ul', 'ol', 'figcaption', 'hr', 'dl', 'figure', 'details', 'summary']);
/** Interface text that websites print in their own pages (session banners, cookie notices): not content. */
const UI_NOISE = /reload to refresh your session|signed (in|out) (with|in) another tab|skip to content|accept (all )?cookies|we use cookies/i;
const ENTITY_NAMES: Record<string, string> = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ndash: '–', mdash: '—', hellip: '…',
  copy: '©', reg: '®', laquo: '«', raquo: '»', lsquo: '‘', rsquo: '’', ldquo: '“', rdquo: '”',
};
const TOKEN_SRC = '<!--[\\s\\S]*?-->|<![^>]*>|<\\?[\\s\\S]*?\\?>|<(\\/?)([a-zA-Z][a-zA-Z0-9:-]*)((?:[^>"\']|"[^"]*"|\'[^\']*\')*)>|([^<]+)|<';
const ATTR_SRC = '([^\\s"\'<>\\/=]+)(?:\\s*=\\s*(?:"([^"]*)"|\'([^\']*)\'|([^\\s"\'<>`]+)))?';

export function decodeEntities(s: string): string {
  return s.replace(/&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);/g, (all: string, body: string) => {
    if (body.charAt(0) === '#') {
      const code = body.charAt(1).toLowerCase() === 'x' ? parseInt(body.slice(2), 16) : parseInt(body.slice(1), 10);
      return code > 0 && code < 0x110000 ? String.fromCodePoint(code) : all;
    }
    return ENTITY_NAMES[body.toLowerCase()] ?? all;
  });
}

const clean = (s: string): string => s.replace(/\s+/g, ' ').trim();
const short = (s: string, n: number): string => (s.length > n ? `${s.slice(0, Math.max(0, n - 1))}…` : s);
/** Lower case, no harakat, common alef/ta-marbuta variants unified: so "أداة" matches "اداة". */
const normText = (s: string): string =>
  s.toLowerCase().replace(/[\u064B-\u065F\u0670\u0640]/g, '').replace(/[أإآ]/g, 'ا').replace(/ى/g, 'ي').replace(/ة/g, 'ه');

export function hostOf(url: string): string {
  const m = /^https?:\/\/([^\/?#]+)/i.exec(url);
  return m ? m[1].toLowerCase().replace(/^www\./, '') : '';
}

/** Collapses "." and ".." and doubled slashes; keeps the query string. */
function normPath(p: string): string {
  const qi = p.search(/[?#]/);
  const path = qi < 0 ? p : p.slice(0, qi);
  const rest = qi < 0 ? '' : p.slice(qi);
  const parts = path.split('/');
  const segs: string[] = [];
  for (let i = 0; i < parts.length; i++) {
    const s = parts[i];
    if (s === '' && i > 0 && i < parts.length - 1) continue;
    if (s === '.') continue;
    if (s === '..') { if (segs.length > 1) segs.pop(); continue; }
    segs.push(s);
  }
  let out = segs.join('/');
  if (out.charAt(0) !== '/') out = `/${out}`;
  return out + rest;
}

/** Turns an href found on `base` into an absolute http(s) URL (or null: anchors, mail, scripts, …). */
export function resolveUrl(raw: string, base: string): string | null {
  const href = raw.trim();
  if (!href || href.charAt(0) === '#') return null;
  if (/^(javascript|mailto|tel|sms|data|blob|file|intent|about):/i.test(href)) return null;
  const stripFrag = (u: string): string => u.replace(/#.*$/, '');
  if (/^https?:\/\//i.test(href)) return stripFrag(href);
  const b = /^(https?):\/\/([^\/?#]+)([^?#]*)/i.exec(base);
  if (!b) return null;
  const scheme = b[1].toLowerCase();
  const origin = `${scheme}://${b[2]}`;
  const basePath = b[3] || '/';
  if (href.slice(0, 2) === '//') return stripFrag(`${scheme}:${href}`);
  if (href.charAt(0) === '?') return stripFrag(origin + basePath + href);
  if (href.charAt(0) === '/') return stripFrag(origin + normPath(href));
  const dir = basePath.slice(0, basePath.lastIndexOf('/') + 1) || '/';
  return stripFrag(origin + normPath(dir + href));
}

function labelOf(url: string): string {
  let p = url.replace(/[?#].*$/, '').replace(/\/+$/, '');
  p = p.slice(p.lastIndexOf('/') + 1);
  try { p = decodeURIComponent(p); } catch { /* keep the raw segment */ }
  return p || hostOf(url);
}

/** Splits text into parts of at most `size` characters, on sentence or word boundaries where possible. */
function splitLong(p: string, size: number): string[] {
  const out: string[] = [];
  let s = p;
  while (s.length > size) {
    let cut = s.lastIndexOf('. ', size);
    if (cut < size / 2) cut = s.lastIndexOf(' ', size);
    if (cut < size / 2) cut = size - 1;
    out.push(s.slice(0, cut + 1).trim());
    s = s.slice(cut + 1).trim();
  }
  if (s) out.push(s);
  return out;
}

/** Groups paragraphs into readable parts of about `size` characters each. */
export function toChunks(paras: string[], size = 900): string[] {
  const out: string[] = [];
  let cur = '';
  for (const p of paras) {
    for (const piece of p.length > size ? splitLong(p, size) : [p]) {
      if (cur && cur.length + piece.length + 1 > size) { out.push(cur); cur = piece; }
      else cur = cur ? `${cur}\n${piece}` : piece;
    }
  }
  if (cur) out.push(cur);
  return out;
}

function parseAttrs(s: string): Record<string, string> {
  const out: Record<string, string> = {};
  const re = new RegExp(ATTR_SRC, 'g');
  let m: RegExpExecArray | null;
  while ((m = re.exec(s))) out[m[1].toLowerCase()] = decodeEntities(m[2] ?? m[3] ?? m[4] ?? '');
  return out;
}

/** Parses an HTML document: content only (no navigation, footers, scripts or styles), with absolute links. */
export function parseHtmlPage(html: string, baseUrl: string): PageModel {
  const skipCount: Record<string, number> = {};
  let skipping = 0;
  let inTitle = false;
  let title = '';
  let description = '';
  const outline: string[] = [];
  const paras: string[] = [];
  let buf = '';
  let headLevel = 0;
  let headBuf = '';
  let linkHref: string | null = null;
  let linkBuf = '';
  const rawLinks: { text: string; href: string }[] = [];
  const forms: PageForm[] = [];
  let form: PageForm | null = null;
  const flush = (): void => {
    const t = clean(buf);
    if (t.length >= 12 && !UI_NOISE.test(t)) paras.push(t);
    buf = '';
  };
  const re = new RegExp(TOKEN_SRC, 'g');
  let m: RegExpExecArray | null;
  while ((m = re.exec(html))) {
    if (m[4] !== undefined) {                                  // plain text
      if (skipping > 0) continue;
      const t = decodeEntities(m[4]);
      if (inTitle) title += t;
      else if (headLevel > 0) headBuf += t;
      else buf += t;
      if (linkHref !== null) linkBuf += t;
      continue;
    }
    if (m[2] === undefined) continue;                          // a comment or a stray "<"
    const closing = m[1] === '/';
    const name = m[2].toLowerCase();
    const attrText = m[3] || '';
    if (name === 'title') { inTitle = !closing; continue; }
    // Search boxes often sit in the header or the nav: forms are read even inside skipped areas.
    if (name === 'form') {
      if (!closing) {
        const a = parseAttrs(attrText);
        form = { method: (a.method || 'get').toLowerCase() === 'post' ? 'post' : 'get', action: a.action || '', textField: '', hidden: [] };
      } else if (form) {
        forms.push(form);
        form = null;
      }
      continue;
    }
    if (name === 'input' && form && !closing) {
      const a = parseAttrs(attrText);
      const type = (a.type || 'text').toLowerCase();
      if (a.name) {
        if (type === 'hidden') form.hidden.push(`${a.name}=${a.value || ''}`);
        else if ((type === 'text' || type === 'search') && !form.textField) form.textField = a.name;
      }
      continue;
    }
    if (SKIP_TAGS.has(name)) {
      if (closing) { if (skipCount[name] > 0) { skipCount[name]--; skipping--; } }
      else if (!/\/\s*$/.test(attrText)) { skipCount[name] = (skipCount[name] || 0) + 1; skipping++; }
      continue;
    }
    if (skipping > 0) continue;
    if (name === 'meta' && !closing) {
      const a = parseAttrs(attrText);
      const key = (a.name || a.property || '').toLowerCase();
      if (!description && (key === 'description' || key === 'og:description') && a.content) description = clean(a.content);
      continue;
    }
    if (/^h[1-6]$/.test(name)) {
      if (!closing) { flush(); headLevel = Number(name.charAt(1)); headBuf = ''; }
      else {
        const t = clean(headBuf);
        if (t) { if (headLevel <= 3) outline.push(t); paras.push(`${'#'.repeat(headLevel)} ${t}`); }
        headLevel = 0;
        headBuf = '';
      }
      continue;
    }
    if (name === 'a') {
      if (!closing) { linkHref = parseAttrs(attrText).href ?? null; linkBuf = ''; }
      else if (linkHref !== null) { rawLinks.push({ text: clean(linkBuf), href: linkHref }); linkHref = null; linkBuf = ''; }
      continue;
    }
    if (BLOCK_TAGS.has(name)) flush();
  }
  flush();

  const host = hostOf(baseUrl);
  const seen = new Set<string>();
  const sameLinks: PageLink[] = [];
  const otherLinks: PageLink[] = [];
  for (const l of rawLinks) {
    const url = resolveUrl(l.href, baseUrl);
    if (!url || seen.has(url)) continue;
    seen.add(url);
    const link: PageLink = { text: short(l.text || labelOf(url), 90), url, same: hostOf(url) === host };
    (link.same ? sameLinks : otherLinks).push(link);
  }
  const formsOut: PageForm[] = forms.map(f => ({
    method: f.method,
    action: f.action ? resolveUrl(f.action, baseUrl) || baseUrl : baseUrl,
    textField: f.textField,
    hidden: f.hidden,
  }));
  return {
    url: baseUrl,
    title: clean(title) || (outline.length ? outline[0] : '') || hostOf(baseUrl),
    description: short(description, 260),
    outline: outline.slice(0, 12),
    chunks: toChunks(paras),
    links: [...sameLinks, ...otherLinks],
    forms: formsOut,
  };
}

/** A plain-text or JSON response, shown the same way as a page (no links). */
export function textPage(text: string, url: string): PageModel {
  const lines = text.split(/\n+/).map(clean).filter(l => l.length >= 2);
  return {
    url,
    title: lines[0] ? short(lines[0], 120) : hostOf(url),
    description: '',
    outline: [],
    chunks: toChunks(lines),
    links: [],
    forms: [],
  };
}

/** What the user typed as an address → an https URL (null when it is not an address). */
export function normalizeOpenUrl(input: string): string | null {
  const s = (input.trim().split(/\s+/)[0]) || '';
  if (/^https?:\/\/[^\s\/?#]+/i.test(s)) return s;
  if (/^[a-z0-9-]+(\.[a-z0-9-]+)+(\/\S*)?$/i.test(s)) return `https://${s}`;
  return null;
}

/** The URL a site's own GET search box produces for `words`. */
export function siteSearchUrl(form: PageForm, words: string): string {
  const parts = [`${encodeURIComponent(form.textField)}=${encodeURIComponent(words)}`];
  for (const h of form.hidden) {
    const i = h.indexOf('=');
    if (i > 0) parts.push(`${encodeURIComponent(h.slice(0, i))}=${encodeURIComponent(h.slice(i + 1))}`);
  }
  return `${form.action}${form.action.indexOf('?') >= 0 ? '&' : '?'}${parts.join('&')}`;
}

export type BrowseCommand =
  | { action: 'open'; url: string }
  | { action: 'read'; part?: number }
  | { action: 'links'; filter: string }
  | { action: 'follow'; n: number }
  | { action: 'find'; words: string }
  | { action: 'back' }
  | { action: 'download'; n: number }
  | { action: 'site_search'; words: string }
  | { action: 'info' }
  | { action: 'error'; message: string };

const err = (message: string): BrowseCommand => ({ action: 'error', message });

/** One browse command: "open URL", "read 2", "links releases", "follow 3", "find word", "site_search words", "back", "download 4". */
export function parseBrowseCommand(raw: string): BrowseCommand {
  const s = raw.trim();
  if (!s) return err('empty browse command. Examples: open https://…, read 2, links, follow 3, find word, back.');
  const m = /^(\S+)\s*([\s\S]*)$/.exec(s);
  const verb = (m ? m[1] : s).toLowerCase().replace(/[:：]$/, '');
  const rest = (m ? m[2] : '').trim();
  const num = (x: string): number => { const n = parseInt(x, 10); return Number.isFinite(n) && n > 0 ? n : NaN; };
  switch (verb) {
    case 'open': case 'go': case 'visit':
      return rest ? { action: 'open', url: rest.split(/\s+/)[0] } : err('open needs an address, e.g. open https://example.com');
    case 'read': case 'part': case 'page': {
      if (!rest) return { action: 'read' };
      const n = num(rest);
      return Number.isNaN(n) ? err('read takes a part number, e.g. read 2') : { action: 'read', part: n };
    }
    case 'links': case 'link':
      return { action: 'links', filter: rest };
    case 'follow': case 'click': {
      const n = num(rest);
      return Number.isNaN(n) ? err('follow takes a link number from the links list, e.g. follow 3') : { action: 'follow', n };
    }
    case 'find': case 'grep':
      return rest ? { action: 'find', words: rest } : err('find needs words, e.g. find install');
    case 'back':
      return { action: 'back' };
    case 'download': case 'get': {
      const n = num(rest);
      return Number.isNaN(n) ? err('download takes a link number, e.g. download 4') : { action: 'download', n };
    }
    case 'site_search': case 'search_site': case 'sitesearch':
      return rest ? { action: 'site_search', words: rest } : err('site_search needs words, e.g. site_search apk');
    case 'info': case 'status':
      return { action: 'info' };
    default:
      if (normalizeOpenUrl(s)) return { action: 'open', url: s.split(/\s+/)[0] };
      return err(`unknown browse command "${verb}". Use: open URL, read N, links [word], follow N, find word, site_search words, back, download N.`);
  }
}

/** Parts that contain every word (accent-insensitive). Returns a short snippet per part. */
export function findInChunks(chunks: string[], words: string, max = 6): { part: number; snippet: string }[] {
  const terms = normText(words).split(/\s+/).filter(Boolean);
  if (!terms.length) return [];
  const out: { part: number; snippet: string }[] = [];
  chunks.forEach((c, i) => {
    if (out.length >= max) return;
    const n = normText(c);
    if (!terms.every(t => n.indexOf(t) >= 0)) return;
    const at = n.indexOf(terms[0]);
    const from = Math.max(0, at - 90);
    out.push({ part: i + 1, snippet: `${from > 0 ? '…' : ''}${clean(n.slice(from, at + 220))}…` });
  });
  return out;
}

/** The first thing the model sees of a page: enough to decide what to read or follow next. */
export function renderPage(p: PageModel): string {
  const lines: string[] = [`[browse] ${short(p.title, 120)} — ${p.url}`];
  if (p.description) lines.push(p.description);
  if (p.outline.length) lines.push(`Outline: ${p.outline.slice(0, 8).map(o => short(o, 60)).join(' / ')}`);
  if (!p.chunks.length) {
    lines.push('No readable text in the HTML: this page is probably a JavaScript app. Open another page of the site (docs, releases, repository), or use site_search / find.');
  } else {
    lines.push(`Text: ${p.chunks.length} part(s). Part 1:`);
    lines.push(short(p.chunks[0], 900));
  }
  if (p.links.length) {
    lines.push(`Links: ${p.links.length} (site links first). First ones:`);
    lines.push(p.links.slice(0, 10).map((l, i) => `[${i + 1}] ${short(l.text, 50)}`).join('\n'));
  }
  const next = p.chunks.length > 1 ? 'read 2 · ' : '';
  lines.push(`Next: ${next}links [word] · follow N · find word · site_search words · back · download N`);
  return lines.join('\n');
}

/** The numbered links (the numbers are the ones `follow N` and `download N` take). */
export function renderLinks(p: PageModel, filter: string, perPage = 25): string {
  const f = normText(filter).trim();
  const matches = p.links.map((l, i) => ({ l, i })).filter(({ l }) => !f || normText(`${l.text} ${l.url}`).indexOf(f) >= 0);
  if (!p.links.length) return 'This page has no links.';
  if (!matches.length) return `No link matches "${filter}". Try another word, find, or site_search.`;
  const head = `Links${f ? ` matching "${filter}"` : ''}: ${matches.length} of ${p.links.length}`;
  const body = matches.slice(0, perPage).map(({ l, i }) => `[${i + 1}] ${short(l.text, 60)} → ${short(l.url, 100)}`).join('\n');
  return `${head}\n${body}${matches.length > perPage ? `\n(first ${perPage} shown; use a word to narrow it)` : ''}`;
}
