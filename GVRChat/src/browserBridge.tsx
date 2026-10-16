/**
 * browserBridge.tsx — a hidden WebView the agent can read and drive.
 * HiddenBrowser is mounted once in App.tsx. It opens a page, lists its links and
 * interactive elements with numbers, clicks an element, and types into a field.
 * No vision model is needed: the page's own structure is used.
 */
import React, { useEffect, useRef, useState } from 'react';
import { View } from 'react-native';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';

export interface PageLink { n: number; text: string; href: string }
export interface PageElement { n: number; kind: string; label: string }
export interface PageResult {
  ok: boolean;
  url?: string;
  title?: string;
  text?: string;
  links?: PageLink[];
  elements?: PageElement[];
  error?: string;
}

type Resolver = (r: PageResult) => void;
const LOAD_TIMEOUT_MS = 30000;
const SETTLE_MS = 2500;

const EXTRACT_JS = `
(function () {
  function post(obj) { window.ReactNativeWebView.postMessage(JSON.stringify(obj)); }
  try {
    var nodes = document.querySelectorAll('a[href], button, input, textarea, select, [role="button"], [onclick]');
    var elements = [];
    var links = [];
    var n = 0;
    for (var i = 0; i < nodes.length && n < 80; i++) {
      var el = nodes[i];
      if (el.offsetParent === null && el.tagName !== 'BODY') continue;
      var isInput = /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName);
      var label = (el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || el.title || '').trim().replace(/\\s+/g, ' ');
      if (!label && !isInput) continue;
      n++;
      el.setAttribute('data-gvr', String(n));
      var kind = isInput ? 'input' : (el.tagName === 'A' ? 'link' : 'button');
      elements.push({ n: n, kind: kind, label: label.slice(0, 80) });
      if (el.tagName === 'A' && /^https?:/i.test(el.href || '') && label) {
        links.push({ text: label.slice(0, 80), href: el.href });
      }
    }
    var text = (document.body && document.body.innerText) || '';
    post({ kind: 'page', ok: true, url: location.href, title: document.title, text: text.slice(0, 15000), links: links.slice(0, 60), elements: elements });
  } catch (e) {
    post({ kind: 'page', ok: false, error: String(e) });
  }
})();
true;
`;

let registerLoad: ((url: string, resolve: Resolver) => void) | null = null;
let runJsImpl: ((js: string, afterMs: number, resolve: Resolver) => void) | null = null;
let lastLinks: PageLink[] = [];
let lastElements: PageElement[] = [];

export function browseOnPage(url: string): Promise<PageResult> {
  return new Promise<PageResult>(resolve => {
    if (!registerLoad) {
      resolve({ ok: false, error: 'The browser is not ready yet. Wait a second and try again.' });
      return;
    }
    registerLoad(url, resolve);
  });
}

function actionJs(kind: 'click' | 'type', n: number, text: string): string {
  const body = kind === 'click'
    ? "el.scrollIntoView({ block: 'center' }); el.click(); return 'clicked';"
    : "el.focus(); var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype; var setter = Object.getOwnPropertyDescriptor(proto, 'value'); if (setter && setter.set) { setter.set.call(el, " + JSON.stringify(text) + "); } else { el.value = " + JSON.stringify(text) + "; } el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); return 'typed';";
  return "(function(){ try { var el = document.querySelector('[data-gvr=\"" + n + "\"]'); if (!el) { window.ReactNativeWebView.postMessage(JSON.stringify({ kind: 'action', ok: false, error: 'element " + n + " not found, read the page again' })); return; } var r = (function(){ " + body + " })(); window.ReactNativeWebView.postMessage(JSON.stringify({ kind: 'action', ok: true, result: r })); } catch (e) { window.ReactNativeWebView.postMessage(JSON.stringify({ kind: 'action', ok: false, error: String(e) })); } })(); true;";
}

export function actOnPage(kind: 'click' | 'type', n: number, text: string): Promise<PageResult> {
  return new Promise<PageResult>(resolve => {
    if (!runJsImpl) {
      resolve({ ok: false, error: 'The browser is not ready yet.' });
      return;
    }
    runJsImpl(actionJs(kind, n, text), 2500, resolve);
  });
}

export function getLastLinks(): PageLink[] {
  return lastLinks;
}

export function getLastElements(): PageElement[] {
  return lastElements;
}

export function HiddenBrowser() {
  const [req, setReq] = useState<{ url: string; id: number } | null>(null);
  const webRef = useRef<WebView>(null);
  const pending = useRef<Resolver | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const settleRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const afterRef = useRef<number>(SETTLE_MS);

  const finish = (r: PageResult) => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    if (settleRef.current) clearTimeout(settleRef.current);
    const cb = pending.current;
    pending.current = null;
    if (cb) cb(r);
  };

  const armTimeout = () => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    timeoutRef.current = setTimeout(() => finish({ ok: false, error: 'Timed out after 30 seconds.' }), LOAD_TIMEOUT_MS);
  };

  useEffect(() => {
    registerLoad = (url, resolve) => {
      if (pending.current) finish({ ok: false, error: 'Replaced by a newer page request.' });
      pending.current = resolve;
      armTimeout();
      setReq({ url, id: Date.now() });
    };
    runJsImpl = (js, afterMs, resolve) => {
      if (!webRef.current) {
        resolve({ ok: false, error: 'The browser is not ready yet.' });
        return;
      }
      if (pending.current) finish({ ok: false, error: 'Replaced by a newer action.' });
      pending.current = resolve;
      afterRef.current = afterMs;
      armTimeout();
      webRef.current.injectJavaScript(js);
    };
    return () => {
      registerLoad = null;
      runJsImpl = null;
    };
  }, []);

  const onLoadEnd = () => {
    if (settleRef.current) clearTimeout(settleRef.current);
    settleRef.current = setTimeout(() => {
      webRef.current?.injectJavaScript(EXTRACT_JS);
    }, SETTLE_MS);
  };

  const onMessage = (e: WebViewMessageEvent) => {
    let data: any;
    try {
      data = JSON.parse(e.nativeEvent.data);
    } catch {
      finish({ ok: false, error: 'Could not read the page.' });
      return;
    }
    if (data.kind === 'action') {
      if (!data.ok) {
        finish({ ok: false, error: data.error || 'The action failed.' });
        return;
      }
      if (settleRef.current) clearTimeout(settleRef.current);
      settleRef.current = setTimeout(() => {
        webRef.current?.injectJavaScript(EXTRACT_JS);
      }, afterRef.current);
      return;
    }
    if (!data.ok) {
      finish({ ok: false, error: data.error || 'Page extraction failed.' });
      return;
    }
    const links: PageLink[] = (data.links || []).map((l: any, i: number) => ({ n: i + 1, text: l.text, href: l.href }));
    const elements: PageElement[] = (data.elements || []).map((x: any) => ({ n: x.n, kind: x.kind, label: x.label }));
    lastLinks = links;
    lastElements = elements;
    finish({ ok: true, url: data.url, title: data.title, text: data.text, links, elements });
  };

  if (!req) return null;
  return (
    <View pointerEvents="none" style={{ position: 'absolute', width: 1, height: 1, opacity: 0, left: -10, top: -10 }}>
      <WebView
        key={req.id}
        ref={webRef}
        source={{ uri: req.url }}
        javaScriptEnabled
        domStorageEnabled
        userAgent="Mozilla/5.0 (Linux; Android 14; GVRChat/2.0) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
        onLoadEnd={onLoadEnd}
        onMessage={onMessage}
        onError={() => finish({ ok: false, error: 'The page failed to load.' })}
        style={{ width: 800, height: 1200 }}
      />
    </View>
  );
}
