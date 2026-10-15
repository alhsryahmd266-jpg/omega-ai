/**
 * browserBridge.tsx — a hidden WebView the agent can drive.
 * HiddenBrowser is mounted once in App.tsx. browseOnPage(url) loads a page there,
 * lets its JavaScript run, and returns the visible text plus numbered links.
 */
import React, { useEffect, useRef, useState } from 'react';
import { View } from 'react-native';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';

export interface PageLink { n: number; text: string; href: string }
export interface PageResult {
  ok: boolean;
  url?: string;
  title?: string;
  text?: string;
  links?: PageLink[];
  error?: string;
}

type Resolver = (r: PageResult) => void;
const LOAD_TIMEOUT_MS = 30000;
const SETTLE_MS = 2500;

const EXTRACT_JS = `
(function () {
  function post(obj) { window.ReactNativeWebView.postMessage(JSON.stringify(obj)); }
  try {
    var links = [];
    var as = document.querySelectorAll('a[href]');
    for (var i = 0; i < as.length && links.length < 60; i++) {
      var t = (as[i].innerText || as[i].getAttribute('aria-label') || '').trim().replace(/\\s+/g, ' ');
      var h = as[i].href || '';
      if (t && /^https?:/i.test(h)) links.push({ text: t.slice(0, 80), href: h });
    }
    var text = (document.body && document.body.innerText) || '';
    post({ ok: true, url: location.href, title: document.title, text: text.slice(0, 15000), links: links });
  } catch (e) {
    post({ ok: false, error: String(e) });
  }
})();
true;
`;

let registerLoad: ((url: string, resolve: Resolver) => void) | null = null;
let lastLinks: PageLink[] = [];

export function browseOnPage(url: string): Promise<PageResult> {
  return new Promise<PageResult>(resolve => {
    if (!registerLoad) {
      resolve({ ok: false, error: 'The browser is not ready yet. Wait a second and try again.' });
      return;
    }
    registerLoad(url, resolve);
  });
}

export function getLastLinks(): PageLink[] {
  return lastLinks;
}

export function HiddenBrowser() {
  const [req, setReq] = useState<{ url: string; id: number } | null>(null);
  const webRef = useRef<WebView>(null);
  const pending = useRef<Resolver | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const settleRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const finish = (r: PageResult) => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    if (settleRef.current) clearTimeout(settleRef.current);
    const cb = pending.current;
    pending.current = null;
    if (cb) cb(r);
  };

  useEffect(() => {
    registerLoad = (url, resolve) => {
      if (pending.current) finish({ ok: false, error: 'Replaced by a newer page request.' });
      pending.current = resolve;
      timeoutRef.current = setTimeout(() => finish({ ok: false, error: 'Timed out after 30 seconds.' }), LOAD_TIMEOUT_MS);
      setReq({ url, id: Date.now() });
    };
    return () => { registerLoad = null; };
  }, []);

  const onLoadEnd = () => {
    if (settleRef.current) clearTimeout(settleRef.current);
    settleRef.current = setTimeout(() => {
      webRef.current?.injectJavaScript(EXTRACT_JS);
    }, SETTLE_MS);
  };

  const onMessage = (e: WebViewMessageEvent) => {
    let data: any;
    try { data = JSON.parse(e.nativeEvent.data); } catch {
      finish({ ok: false, error: 'Could not read the page.' });
      return;
    }
    if (!data.ok) {
      finish({ ok: false, error: data.error || 'Page extraction failed.' });
      return;
    }
    const links: PageLink[] = (data.links || []).map((l: any, i: number) => ({ n: i + 1, text: l.text, href: l.href }));
    lastLinks = links;
    finish({ ok: true, url: data.url, title: data.title, text: data.text, links });
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
