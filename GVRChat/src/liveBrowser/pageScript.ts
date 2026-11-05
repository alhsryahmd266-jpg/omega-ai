/**
 * pageScript.ts — the JavaScript that runs INSIDE the page (in the WebView).
 *
 * It is kept as a plain string (never `fn.toString()`): Hermes release builds return "[bytecode]"
 * for Function.prototype.toString, so a stringified function would silently break in the real app.
 *
 * What it gives the agent (window.__gvr):
 *   snapshot()  – title, readable text, and a numbered list of the visible interactive elements
 *   click(id) / type(id,text,submit) / select(id,option) / scroll(dir) / find(text) / idle(maxMs)
 *   loginFields()/fill()  – used ONLY by the native side for the credential vault (never by the model)
 *
 * It never returns password values, and it flags card / OTP / ID-like fields as sensitive.
 */
export const PAGE_SCRIPT: string = String.raw`(function () {
  if (window.__gvr && window.__gvr.v === 1) return;
  var G = { v: 1, els: [null], items: [], layout: false };
  window.__gvr = G;

  var CLICKABLE = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=switch],[role=option],[onclick],[contenteditable=true],[contenteditable=""]';
  var SENSITIVE = /(card.?(no|num)|cc-|cvv|cvc|iban|ssn|passport|national.?id|one-time|otp|\bpin\b|رقم.?البطاقة|الرقم.?السري|كلمة.?المرور)/i;

  function norm(s) { return (s == null ? '' : String(s)).replace(/\s+/g, ' ').trim(); }
  function rawText(el) { var t = el.innerText; if (typeof t !== 'string') t = el.textContent; return norm(t); }
  function hasLayout() {
    try {
      var r = document.documentElement.getBoundingClientRect();
      return r.height > 0 || (!!document.body && document.body.scrollHeight > 0);
    } catch (e) { return false; }
  }
  function hiddenByAncestor(el) {
    for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
      if (n.hidden || n.getAttribute('aria-hidden') === 'true') return true;
      var cs = null;
      try { cs = window.getComputedStyle(n); } catch (e) {}
      if (cs && (cs.display === 'none' || cs.visibility === 'hidden')) return true;
    }
    return false;
  }
  function inView(el) {
    if (!G.layout) return true;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }
  function kindOf(el) {
    var tag = el.tagName.toLowerCase();
    if (tag === 'a') return 'link';
    if (tag === 'select') return 'select';
    if (tag === 'textarea') return 'textarea';
    if (tag === 'input') {
      var ty = (el.getAttribute('type') || 'text').toLowerCase();
      if (ty === 'password') return 'password';
      if (ty === 'submit' || ty === 'button' || ty === 'reset' || ty === 'image') return 'button';
      if (ty === 'checkbox' || ty === 'radio' || ty === 'range' || ty === 'file' || ty === 'color' || ty === 'date') return ty;
      return 'input:' + ty;
    }
    if (tag === 'button' || tag === 'summary') return 'button';
    var role = el.getAttribute('role');
    if (role) return role === 'button' ? 'button' : role === 'link' ? 'link' : role;
    if (el.isContentEditable) return 'editable';
    return 'clickable';
  }
  function labelOf(el, kind) {
    var aria = el.getAttribute('aria-label');
    if (aria && norm(aria)) return norm(aria);
    var tag = el.tagName.toLowerCase();
    if (tag === 'input' && kind === 'button') return norm(el.getAttribute('value') || el.getAttribute('alt') || el.getAttribute('title') || 'button');
    if (tag === 'input' || tag === 'textarea' || tag === 'select') {
      var lab = null;
      if (el.id) { try { lab = document.querySelector('label[for="' + String(el.id).replace(/"/g, '') + '"]'); } catch (e) {} }
      if (!lab && el.closest) lab = el.closest('label');
      var l = lab ? rawText(lab) : '';
      return norm(l || el.getAttribute('placeholder') || el.getAttribute('title') || el.getAttribute('name') || el.getAttribute('type') || tag);
    }
    var t = rawText(el);
    if (!t) {
      var img = el.querySelector ? el.querySelector('img[alt]') : null;
      t = norm(el.getAttribute('title') || el.getAttribute('alt') || (img && img.getAttribute('alt')) || el.getAttribute('value') || '');
    }
    return t;
  }
  function isSensitive(el, kind) {
    if (kind === 'password') return true;
    var ac = (el.getAttribute('autocomplete') || '').toLowerCase();
    if (ac.indexOf('cc-') === 0 || ac === 'one-time-code') return true;
    return SENSITIVE.test([el.getAttribute('name'), el.id, el.getAttribute('placeholder'), el.getAttribute('aria-label')].join(' '));
  }

  function collect(max) {
    G.layout = hasLayout();
    var nodes = document.querySelectorAll(CLICKABLE), out = [], seen = [];
    G.els = [null];
    for (var i = 0; i < nodes.length && out.length < max; i++) {
      var el = nodes[i];
      if (hiddenByAncestor(el) || !inView(el)) continue;
      var tag = el.tagName.toLowerCase();
      if (tag === 'a' || tag === 'button') {
        var outer = el.parentElement ? el.parentElement.closest('a[href],button') : null;
        if (outer && seen.indexOf(outer) !== -1) continue;
      }
      var kind = kindOf(el), label = labelOf(el, kind);
      if (!label && kind === 'clickable') continue;
      seen.push(el); G.els.push(el);
      var it = { i: G.els.length - 1, t: kind, l: label.slice(0, 90) };
      if (kind === 'link') it.h = String(el.href || el.getAttribute('href') || '').slice(0, 160);
      if ((kind.indexOf('input') === 0 || kind === 'textarea') && el.value) it.v = String(el.value).slice(0, 60);
      if (kind === 'checkbox' || kind === 'radio') it.c = !!el.checked;
      if (kind === 'select') {
        var os = [];
        for (var k = 0; k < el.options.length && k < 12; k++) os.push(norm(el.options[k].text));
        it.o = os;
        it.v = el.selectedIndex >= 0 ? norm(el.options[el.selectedIndex].text) : '';
      }
      if (el.disabled) it.d = true;
      if (isSensitive(el, kind)) it.s = true;
      out.push(it);
    }
    G.items = out;
    return out;
  }

  function pageText(max) {
    var main = document.querySelector('main,[role=main],article');
    var root = main || document.body;
    if (!root) return '';
    function textOfRoot(r) {
      var t;
      if (typeof r.innerText === 'string') { t = r.innerText; }
      else {
        var c = r.cloneNode(true);
        var kill = c.querySelectorAll('script,style,noscript,template,svg,[hidden],[aria-hidden=true]');
        for (var i = 0; i < kill.length; i++) { if (kill[i].parentNode) kill[i].parentNode.removeChild(kill[i]); }
        var blocks = c.querySelectorAll('p,div,li,h1,h2,h3,h4,h5,h6,tr,section,article,br,ul,ol,table,form');
        for (var j = 0; j < blocks.length; j++) { blocks[j].insertAdjacentText('afterend', '\n'); }
        t = c.textContent || '';
      }
      return t.replace(/[ \t\r\f\v]+/g, ' ').replace(/ ?\n ?/g, '\n').replace(/\n{2,}/g, '\n').trim();
    }
    var t = textOfRoot(root);
    if (main && t.length < 120 && document.body) t = textOfRoot(document.body);
    return t.length > max ? t.slice(0, max) : t;
  }

  function byId(id) {
    var el = G.els[id];
    if (!el || !document.contains(el)) return null;
    return el;
  }
  function setNative(el, value) {
    var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
    var d = Object.getOwnPropertyDescriptor(proto, 'value');
    if (d && d.set) d.set.call(el, value); else el.value = value;
  }

  G.snapshot = function (o) {
    o = o || {};
    var items = collect(o.max || 60);
    var sh = Math.max(document.documentElement ? document.documentElement.scrollHeight : 0, document.body ? document.body.scrollHeight : 0);
    return {
      url: location.href, title: document.title || '', text: pageText(o.textMax || 1800), items: items,
      scroll: { y: Math.round(window.pageYOffset || 0), h: sh, vh: window.innerHeight || 0 },
      forms: document.forms.length, ready: document.readyState
    };
  };

  G.click = function (id) {
    var el = byId(id);
    if (!el) return { ok: false, err: 'stale' };
    try { el.scrollIntoView({ block: 'center' }); } catch (e) {}
    if (el.tagName === 'A' && el.target === '_blank') el.target = '_self';
    var names = ['pointerdown', 'mousedown', 'pointerup', 'mouseup'];
    for (var i = 0; i < names.length; i++) {
      try { el.dispatchEvent(new MouseEvent(names[i], { bubbles: true, cancelable: true, view: window })); } catch (e) {}
    }
    el.click();
    return { ok: true };
  };

  G.submit = function (id) {
    var el = byId(id);
    if (!el) return { ok: false, err: 'stale' };
    var kd = null;
    try {
      kd = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true });
      el.dispatchEvent(kd);
      el.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
      el.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
    } catch (e) {}
    if (kd && kd.defaultPrevented) return { ok: true, via: 'handler' };
    var form = el.form || (el.closest ? el.closest('form') : null);
    if (form) {
      var btn = form.querySelector('button[type=submit],input[type=submit],button:not([type])');
      if (typeof form.requestSubmit === 'function') {
        try { form.requestSubmit(btn || undefined); return { ok: true, via: 'requestSubmit' }; } catch (e) {}
      }
      if (btn) { btn.click(); return { ok: true, via: 'button' }; }
      form.submit();
      return { ok: true, via: 'submit' };
    }
    return { ok: true, via: 'enter' };
  };

  G.fill = function (id, text, submit) {
    var el = byId(id);
    if (!el) return { ok: false, err: 'stale' };
    var tag = el.tagName.toLowerCase();
    if (tag === 'input' || tag === 'textarea') {
      try { el.focus(); } catch (e) {}
      setNative(el, String(text));
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    } else if (el.isContentEditable) {
      try { el.focus(); } catch (e) {}
      el.textContent = String(text);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    } else { return { ok: false, err: 'not-typable' }; }
    if (submit) return G.submit(id);
    return { ok: true };
  };

  G.select = function (id, wanted) {
    var el = byId(id);
    if (!el) return { ok: false, err: 'stale' };
    if (el.tagName !== 'SELECT') return { ok: false, err: 'not-select' };
    var w = norm(wanted).toLowerCase(), hit = null, i, o;
    for (i = 0; i < el.options.length && !hit; i++) { o = el.options[i]; if (norm(o.text).toLowerCase() === w || String(o.value).toLowerCase() === w) hit = o; }
    for (i = 0; i < el.options.length && !hit; i++) { o = el.options[i]; if (norm(o.text).toLowerCase().indexOf(w) !== -1) hit = o; }
    if (!hit) return { ok: false, err: 'no-option' };
    el.value = hit.value;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return { ok: true, chose: norm(hit.text) };
  };

  G.scroll = function (dir) {
    var vh = window.innerHeight || 600;
    var dy = dir === 'up' ? -0.8 * vh : dir === 'top' ? -1e9 : dir === 'bottom' ? 1e9 : 0.8 * vh;
    try { window.scrollBy(0, dy); } catch (e) {}
    var sh = Math.max(document.documentElement ? document.documentElement.scrollHeight : 0, document.body ? document.body.scrollHeight : 0);
    return { y: Math.round(window.pageYOffset || 0), h: sh, vh: vh };
  };

  G.find = function (q) {
    q = norm(q).toLowerCase();
    if (!q) return { hits: [] };
    var w = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT, null);
    var hits = [], n, first = null;
    while ((n = w.nextNode()) && hits.length < 5) {
      var p = n.parentElement;
      if (!p) continue;
      var tg = p.tagName;
      if (tg === 'SCRIPT' || tg === 'STYLE' || tg === 'NOSCRIPT') continue;
      var v = n.nodeValue || '', idx = v.toLowerCase().indexOf(q);
      if (idx === -1 || hiddenByAncestor(p)) continue;
      if (!first) first = p;
      hits.push(norm(v.slice(Math.max(0, idx - 80), idx + q.length + 120)));
    }
    if (first) { try { first.scrollIntoView({ block: 'center' }); } catch (e) {} }
    return { hits: hits };
  };

  G.idle = function (maxMs, quietMs) {
    maxMs = maxMs || 6000; quietMs = quietMs || 400;
    return new Promise(function (resolve) {
      var last = Date.now(), start = last, mo = null;
      try {
        mo = new MutationObserver(function () { last = Date.now(); });
        mo.observe(document.documentElement, { childList: true, subtree: true, attributes: true, characterData: true });
      } catch (e) {}
      var iv = setInterval(function () {
        var now = Date.now();
        if ((now - last >= quietMs && document.readyState !== 'loading') || now - start >= maxMs) {
          clearInterval(iv);
          try { if (mo) mo.disconnect(); } catch (e) {}
          resolve({ idle: now - last >= quietMs, waited: now - start });
        }
      }, 60);
    });
  };

  G.loginFields = function () {
    var items = collect(80), pass = null, user = null, next = null, i;
    for (i = 0; i < items.length; i++) { if (items[i].t === 'password') { pass = items[i]; break; } }
    var idxPass = pass ? items.indexOf(pass) : items.length;
    for (i = 0; i < idxPass; i++) {
      var ty = items[i].t;
      if (ty === 'input:text' || ty === 'input:email' || ty === 'input:tel') user = items[i];
    }
    var hint = /(user|email|e-mail|login|account|phone|mobile|بريد|مستخدم|اسم|هاتف|جوال)/i;
    for (i = 0; i < idxPass; i++) { if (items[i].t.indexOf('input') === 0 && hint.test(items[i].l)) user = items[i]; }
    var btnRe = /(log ?in|sign ?in|submit|continue|next|دخول|تسجيل|متابعة|التالي|التالى|استمرار)/i;
    var from = pass ? idxPass : (user ? items.indexOf(user) : 0), m;
    for (m = from + 1; m < items.length; m++) { if (items[m].t === 'button' && btnRe.test(items[m].l)) { next = items[m]; break; } }
    if (!next) { for (m = from + 1; m < items.length; m++) { if (items[m].t === 'button') { next = items[m]; break; } } }
    return { user: user ? user.i : 0, pass: pass ? pass.i : 0, next: next ? next.i : 0 };
  };
})(); true;`;
