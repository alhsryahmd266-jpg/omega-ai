/**
 * fileInspect.ts — turns ANY file into something the model can read.
 * =================================================================
 * Designed for phones: big files (a 130 MB APK) are never loaded whole. We read
 * small byte ranges (ZIP end record → central directory → single entries).
 *
 * Supported: APK (manifest, permissions, components, signing, dex stats, native
 * libs), ZIP/JAR, Office (docx/xlsx/pptx/odt), PDF (Flate streams), text/code,
 * and a binary fallback (magic-byte sniffing + strings + hex head).
 *
 * Scope note: this *inspects* files. It does not decompile an APK to source or
 * modify/re-sign it.
 */
import * as FileSystem from 'expo-file-system';
import { Inflate, inflateSync, strFromU8, unzlibSync } from 'fflate';

/* ── bytes ─────────────────────────────────────────────────────────────── */
const B64 = new Uint8Array(256).fill(255);
'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'.split('').forEach((c, i) => { B64[c.charCodeAt(0)] = i; });

export function b64ToBytes(b64: string): Uint8Array {
  const clean = b64.replace(/[\r\n=\s]/g, '');
  const out = new Uint8Array(Math.floor((clean.length * 3) / 4));
  let o = 0, buf = 0, bits = 0;
  for (let i = 0; i < clean.length; i++) {
    const v = B64[clean.charCodeAt(i)];
    if (v === 255) continue;
    buf = (buf << 6) | v;
    bits += 6;
    if (bits >= 8) { bits -= 8; out[o++] = (buf >> bits) & 0xff; buf &= (1 << bits) - 1; }
  }
  return out.subarray(0, o);
}

const MAX_READ = 6 * 1024 * 1024;

export async function readRange(uri: string, pos: number, len: number): Promise<Uint8Array> {
  if (len <= 0) return new Uint8Array(0);
  const parts: Uint8Array[] = [];
  let done = 0;
  while (done < len) {
    const n = Math.min(MAX_READ, len - done);
    const b64 = await FileSystem.readAsStringAsync(uri, {
      encoding: FileSystem.EncodingType.Base64, position: pos + done, length: n,
    });
    const bytes = b64ToBytes(b64);
    parts.push(bytes);
    done += n;
    if (bytes.length < n) break;
  }
  if (parts.length === 1) return parts[0];
  const total = parts.reduce((a, p) => a + p.length, 0);
  const out = new Uint8Array(total);
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}

const u16 = (b: Uint8Array, o: number): number => b[o] | (b[o + 1] << 8);
const u32 = (b: Uint8Array, o: number): number => (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0;
const u64 = (b: Uint8Array, o: number): number => u32(b, o) + u32(b, o + 4) * 4294967296;
const hex = (b: Uint8Array): string => Array.from(b, x => x.toString(16).padStart(2, '0')).join('');
const fmtSize = (n: number): string =>
  n >= 1048576 ? `${(n / 1048576).toFixed(1)} MB` : n >= 1024 ? `${(n / 1024).toFixed(1)} KB` : `${n} B`;

/* ── SHA-256 (pure JS; used for certificate fingerprints) ──────────────── */
const K256 = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

export function sha256Hex(data: Uint8Array): string {
  const h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
  const padLen = ((data.length + 9 + 63) >> 6) << 6;
  const m = new Uint8Array(padLen);
  m.set(data);
  m[data.length] = 0x80;
  const bitsHi = Math.floor((data.length * 8) / 4294967296);
  const bitsLo = (data.length * 8) >>> 0;
  const dv = new DataView(m.buffer);
  dv.setUint32(padLen - 8, bitsHi);
  dv.setUint32(padLen - 4, bitsLo);
  const w = new Uint32Array(64);
  for (let off = 0; off < padLen; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = dv.getUint32(off + i * 4);
    for (let i = 16; i < 64; i++) {
      const a = w[i - 15], b = w[i - 2];
      const s0 = ((a >>> 7) | (a << 25)) ^ ((a >>> 18) | (a << 14)) ^ (a >>> 3);
      const s1 = ((b >>> 17) | (b << 15)) ^ ((b >>> 19) | (b << 13)) ^ (b >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let [a, b, c, d, e, f, g, hh] = h as unknown as number[];
    for (let i = 0; i < 64; i++) {
      const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      const ch = (e & f) ^ (~e & g);
      const t1 = (hh + S1 + ch + K256[i] + w[i]) >>> 0;
      const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + maj) >>> 0;
      hh = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0;
    }
    h[0] = (h[0] + a) >>> 0; h[1] = (h[1] + b) >>> 0; h[2] = (h[2] + c) >>> 0; h[3] = (h[3] + d) >>> 0;
    h[4] = (h[4] + e) >>> 0; h[5] = (h[5] + f) >>> 0; h[6] = (h[6] + g) >>> 0; h[7] = (h[7] + hh) >>> 0;
  }
  let s = '';
  for (let i = 0; i < 8; i++) s += h[i].toString(16).padStart(8, '0');
  return s;
}

/* ── ZIP ───────────────────────────────────────────────────────────────── */
export interface ZipEntry {
  name: string;
  method: number;
  compSize: number;
  size: number;
  offset: number;
  crc: number;
}

export interface ZipListing {
  entries: ZipEntry[];
  cdOffset: number;
}

export async function listZip(uri: string, fileSize: number): Promise<ZipListing> {
  const tailLen = Math.min(fileSize, 65557);
  const tail = await readRange(uri, fileSize - tailLen, tailLen);
  let eocd = -1;
  for (let i = tail.length - 22; i >= 0; i--) {
    if (tail[i] === 0x50 && tail[i + 1] === 0x4b && tail[i + 2] === 5 && tail[i + 3] === 6) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('Not a ZIP/APK (end-of-central-directory record not found).');

  let total = u16(tail, eocd + 10);
  let cdSize = u32(tail, eocd + 12);
  let cdOff = u32(tail, eocd + 16);

  if (total === 0xffff || cdSize === 0xffffffff || cdOff === 0xffffffff) {
    const locAt = eocd - 20;
    if (locAt >= 0 && u32(tail, locAt) === 0x07064b50) {
      const z64off = u64(tail, locAt + 8);
      const z = await readRange(uri, z64off, 56);
      if (u32(z, 0) === 0x06064b50) {
        total = u64(z, 32); cdSize = u64(z, 40); cdOff = u64(z, 48);
      }
    }
  }
  if (cdSize > 48 * 1024 * 1024) throw new Error('ZIP central directory is unreasonably large.');

  const cd = await readRange(uri, cdOff, cdSize);
  const entries: ZipEntry[] = [];
  let p = 0;
  while (p + 46 <= cd.length && u32(cd, p) === 0x02014b50) {
    const method = u16(cd, p + 10);
    const crc = u32(cd, p + 16);
    let compSize = u32(cd, p + 20);
    let size = u32(cd, p + 24);
    const nameLen = u16(cd, p + 28), extraLen = u16(cd, p + 30), commentLen = u16(cd, p + 32);
    let offset = u32(cd, p + 42);
    const name = strFromU8(cd.subarray(p + 46, p + 46 + nameLen));
    if (size === 0xffffffff || compSize === 0xffffffff || offset === 0xffffffff) {
      let e = p + 46 + nameLen;
      const end = e + extraLen;
      while (e + 4 <= end) {
        const id = u16(cd, e), sz = u16(cd, e + 2);
        if (id === 0x0001) {
          let q = e + 4;
          if (size === 0xffffffff) { size = u64(cd, q); q += 8; }
          if (compSize === 0xffffffff) { compSize = u64(cd, q); q += 8; }
          if (offset === 0xffffffff) { offset = u64(cd, q); }
        }
        e += 4 + sz;
      }
    }
    entries.push({ name, method, compSize, size, offset, crc });
    p += 46 + nameLen + extraLen + commentLen;
  }
  return { entries, cdOffset: cdOff };
}

export async function readZipEntry(uri: string, e: ZipEntry, maxCompressed = 48 * 1024 * 1024): Promise<Uint8Array> {
  const lh = await readRange(uri, e.offset, 30);
  if (u32(lh, 0) !== 0x04034b50) throw new Error(`Bad local header for ${e.name}`);
  const start = e.offset + 30 + u16(lh, 26) + u16(lh, 28);
  if (e.compSize > maxCompressed) throw new Error(`${e.name} is too large to read on the phone (${fmtSize(e.compSize)}).`);
  const comp = await readRange(uri, start, e.compSize);
  if (e.method === 0) return comp;
  if (e.method === 8) return inflateSync(comp);
  throw new Error(`Unsupported ZIP compression method ${e.method} for ${e.name}`);
}

/** Reads only the first `n` bytes of an entry (enough for headers) without inflating all of it. */
export async function readZipEntryHead(uri: string, e: ZipEntry, n: number): Promise<Uint8Array> {
  const lh = await readRange(uri, e.offset, 30);
  if (u32(lh, 0) !== 0x04034b50) throw new Error(`Bad local header for ${e.name}`);
  const start = e.offset + 30 + u16(lh, 26) + u16(lh, 28);
  if (e.method === 0) return readRange(uri, start, Math.min(n, e.compSize));
  const comp = await readRange(uri, start, Math.min(e.compSize, 96 * 1024));
  const chunks: Uint8Array[] = [];
  let got = 0;
  const inf = new Inflate((chunk) => { if (got < n) { chunks.push(chunk); got += chunk.length; } });
  try { inf.push(comp, comp.length >= e.compSize); } catch { /* partial stream is fine */ }
  const out = new Uint8Array(got);
  let o = 0;
  for (const c of chunks) { out.set(c, o); o += c.length; }
  return out.subarray(0, n);
}

/* ── Android binary XML (AndroidManifest.xml) ──────────────────────────── */
interface XmlElement { name: string; attrs: Record<string, string>; parents: string[] }

const RES_ATTR_NAMES: Record<number, string> = {
  0x01010003: 'name', 0x01010001: 'label', 0x0101021b: 'versionCode', 0x0101021c: 'versionName',
  0x0101020c: 'minSdkVersion', 0x01010270: 'targetSdkVersion', 0x01010010: 'exported',
  0x0101000e: 'enabled', 0x0101000f: 'debuggable', 0x01010280: 'allowBackup',
  0x010104ec: 'usesCleartextTraffic', 0x010104eb: 'extractNativeLibs', 0x01010024: 'value',
};

function readPool(u8: Uint8Array, base: number): string[] {
  const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  const headerSize = dv.getUint16(base + 2, true);
  const count = dv.getUint32(base + 8, true);
  const flags = dv.getUint32(base + 16, true);
  const stringsStart = dv.getUint32(base + 20, true);
  const utf8 = (flags & 0x100) !== 0;
  const out: string[] = [];
  for (let i = 0; i < count; i++) {
    let p = base + stringsStart + dv.getUint32(base + headerSize + i * 4, true);
    if (utf8) {
      let l = u8[p++]; if (l & 0x80) l = ((l & 0x7f) << 8) | u8[p++];
      let bl = u8[p++]; if (bl & 0x80) bl = ((bl & 0x7f) << 8) | u8[p++];
      out.push(strFromU8(u8.subarray(p, p + bl)));
    } else {
      let l = dv.getUint16(p, true); p += 2;
      if (l & 0x8000) { l = ((l & 0x7fff) << 16) | dv.getUint16(p, true); p += 2; }
      let s = '';
      for (let j = 0; j < l; j++) s += String.fromCharCode(dv.getUint16(p + j * 2, true));
      out.push(s);
    }
  }
  return out;
}

export function parseAxml(u8: Uint8Array): XmlElement[] {
  const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  if (dv.getUint16(0, true) !== 0x0003) throw new Error('Not an Android binary XML file.');
  let pool: string[] = [];
  let resIds: number[] = [];
  const stack: string[] = [];
  const elements: XmlElement[] = [];
  let p = dv.getUint16(2, true);
  while (p + 8 <= u8.length) {
    const type = dv.getUint16(p, true);
    const size = dv.getUint32(p + 4, true);
    if (size < 8) break;
    if (type === 0x0001) pool = readPool(u8, p);
    else if (type === 0x0180) {
      const n = (size - dv.getUint16(p + 2, true)) / 4;
      resIds = [];
      for (let i = 0; i < n; i++) resIds.push(dv.getUint32(p + dv.getUint16(p + 2, true) + i * 4, true));
    } else if (type === 0x0102) {
      const name = pool[dv.getUint32(p + 20, true)] ?? '';
      const attrStart = dv.getUint16(p + 24, true);
      const attrSize = dv.getUint16(p + 26, true);
      const attrCount = dv.getUint16(p + 28, true);
      const attrs: Record<string, string> = {};
      for (let i = 0; i < attrCount; i++) {
        const a = p + 16 + attrStart + i * attrSize;
        const nameIdx = dv.getUint32(a + 4, true);
        let an = pool[nameIdx] ?? '';
        if (!an && resIds[nameIdx] !== undefined) an = RES_ATTR_NAMES[resIds[nameIdx]] ?? `res_0x${resIds[nameIdx].toString(16)}`;
        const raw = dv.getUint32(a + 8, true);
        const dtype = u8[a + 15];
        const data = dv.getUint32(a + 16, true);
        let val: string;
        if (dtype === 0x03) val = pool[raw !== 0xffffffff ? raw : data] ?? '';
        else if (dtype === 0x10) val = String(dv.getInt32(a + 16, true));
        else if (dtype === 0x11) val = `0x${data.toString(16)}`;
        else if (dtype === 0x12) val = data !== 0 ? 'true' : 'false';
        else if (dtype === 0x01) val = `@0x${data.toString(16)}`;
        else if (raw !== 0xffffffff && pool[raw] !== undefined) val = pool[raw];
        else val = `0x${data.toString(16)}`;
        attrs[an] = val;
      }
      elements.push({ name, attrs, parents: [...stack] });
      stack.push(name);
    } else if (type === 0x0103) stack.pop();
    p += size;
  }
  return elements;
}

const DANGEROUS = new Set([
  'CAMERA', 'RECORD_AUDIO', 'READ_CONTACTS', 'WRITE_CONTACTS', 'GET_ACCOUNTS', 'READ_CALENDAR', 'WRITE_CALENDAR',
  'READ_CALL_LOG', 'WRITE_CALL_LOG', 'PROCESS_OUTGOING_CALLS', 'READ_PHONE_STATE', 'READ_PHONE_NUMBERS', 'CALL_PHONE',
  'ANSWER_PHONE_CALLS', 'SEND_SMS', 'RECEIVE_SMS', 'READ_SMS', 'RECEIVE_MMS', 'RECEIVE_WAP_PUSH',
  'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'ACCESS_BACKGROUND_LOCATION',
  'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE', 'MANAGE_EXTERNAL_STORAGE', 'READ_MEDIA_IMAGES',
  'READ_MEDIA_VIDEO', 'READ_MEDIA_AUDIO', 'BODY_SENSORS', 'ACTIVITY_RECOGNITION', 'BLUETOOTH_CONNECT',
  'BLUETOOTH_SCAN', 'SYSTEM_ALERT_WINDOW', 'REQUEST_INSTALL_PACKAGES', 'BIND_ACCESSIBILITY_SERVICE',
  'BIND_DEVICE_ADMIN', 'QUERY_ALL_PACKAGES', 'READ_LOGS', 'WRITE_SETTINGS', 'INSTALL_PACKAGES',
  'USE_FULL_SCREEN_INTENT', 'READ_PRECISE_PHONE_STATE', 'BIND_NOTIFICATION_LISTENER_SERVICE',
]);

/* ── X.509 / PKCS#7 (signing certificate) ──────────────────────────────── */
interface Tlv { tag: number; start: number; end: number; hdr: number }
function tlv(b: Uint8Array, p: number): Tlv {
  const tag = b[p];
  let len = b[p + 1];
  let hdr = 2;
  if (len & 0x80) {
    const n = len & 0x7f;
    len = 0;
    for (let i = 0; i < n; i++) len = len * 256 + b[p + 2 + i];
    hdr = 2 + n;
  }
  return { tag, start: p + hdr, end: p + hdr + len, hdr };
}
function children(b: Uint8Array, t: Tlv): Tlv[] {
  const out: Tlv[] = [];
  let p = t.start;
  while (p < t.end) {
    const c = tlv(b, p);
    out.push(c);
    p = c.end;
  }
  return out;
}

const OID_NAMES: Record<string, string> = { '550403': 'CN', '55040a': 'O', '55040b': 'OU', '550406': 'C', '550408': 'ST', '550407': 'L' };

function parseName(b: Uint8Array, name: Tlv): string {
  const parts: string[] = [];
  for (const rdn of children(b, name)) {
    for (const atv of children(b, rdn)) {
      const kv = children(b, atv);
      if (kv.length < 2) continue;
      const oid = hex(b.subarray(kv[0].start, kv[0].end));
      const val = strFromU8(b.subarray(kv[1].start, kv[1].end));
      parts.push(`${OID_NAMES[oid] ?? oid}=${val}`);
    }
  }
  return parts.join(', ');
}

function parseTime(b: Uint8Array, t: Tlv): string {
  const s = strFromU8(b.subarray(t.start, t.end));
  const m = t.tag === 0x17 ? /^(\d\d)(\d\d)(\d\d)/.exec(s) : /^(\d{4})(\d\d)(\d\d)/.exec(s);
  if (!m) return s;
  const year = t.tag === 0x17 ? (parseInt(m[1], 10) >= 50 ? 1900 : 2000) + parseInt(m[1], 10) : parseInt(m[1], 10);
  return `${year}-${m[2]}-${m[3]}`;
}

export interface CertInfo { sha256: string; subject: string; issuer: string; notBefore: string; notAfter: string }

export function parsePkcs7Cert(b: Uint8Array): CertInfo | null {
  try {
    const top = tlv(b, 0);
    const topKids = children(b, top);
    const explicit = topKids.find(k => k.tag === 0xa0);
    if (!explicit) return null;
    const signedData = children(b, explicit)[0];
    const certSet = children(b, signedData).find(k => k.tag === 0xa0);
    if (!certSet) return null;
    const cert = children(b, certSet)[0];
    const der = b.subarray(cert.start - cert.hdr, cert.end);
    const tbs = children(b, cert)[0];
    let kids = children(b, tbs);
    if (kids[0].tag === 0xa0) kids = kids.slice(1);
    // kids: serial, sigAlg, issuer, validity, subject
    const validity = children(b, kids[3]);
    return {
      sha256: sha256Hex(der),
      issuer: parseName(b, kids[2]),
      subject: parseName(b, kids[4]),
      notBefore: parseTime(b, validity[0]),
      notAfter: parseTime(b, validity[1]),
    };
  } catch {
    return null;
  }
}

/* ── APK ───────────────────────────────────────────────────────────────── */
export async function inspectApk(uri: string, fileSize: number, name: string): Promise<string> {
  const { entries, cdOffset } = await listZip(uri, fileSize);
  const byName = new Map(entries.map(e => [e.name, e]));
  const lines: string[] = [];
  lines.push(`APK: ${name}  (${fmtSize(fileSize)}, ${entries.length} entries)`);

  const manifestEntry = byName.get('AndroidManifest.xml');
  if (!manifestEntry) {
    lines.push('No AndroidManifest.xml — this is a ZIP but not a valid APK.');
  } else {
    try {
      const els = parseAxml(await readZipEntry(uri, manifestEntry));
      const man = els.find(e => e.name === 'manifest');
      const app = els.find(e => e.name === 'application');
      const sdk = els.find(e => e.name === 'uses-sdk');
      if (man) {
        lines.push('', '== App ==');
        lines.push(`package: ${man.attrs['package'] ?? '?'}`);
        lines.push(`version: ${man.attrs['versionName'] ?? '?'} (code ${man.attrs['versionCode'] ?? '?'})`);
        if (man.attrs['compileSdkVersion']) lines.push(`compileSdk: ${man.attrs['compileSdkVersion']}`);
      }
      if (sdk) lines.push(`minSdk: ${sdk.attrs['minSdkVersion'] ?? '?'}   targetSdk: ${sdk.attrs['targetSdkVersion'] ?? '(not set)'}`);
      if (app) {
        const flags = ['debuggable', 'allowBackup', 'usesCleartextTraffic', 'extractNativeLibs', 'requestLegacyExternalStorage']
          .filter(k => app.attrs[k] !== undefined).map(k => `${k}=${app.attrs[k]}`);
        if (app.attrs['name']) lines.push(`application class: ${app.attrs['name']}`);
        if (flags.length) lines.push(`flags: ${flags.join(', ')}`);
      }

      const perms = Array.from(new Set(
        els.filter(e => e.name === 'uses-permission' || e.name === 'uses-permission-sdk-23')
          .map(e => e.attrs['name']).filter(Boolean),
      ));
      const risky = perms.filter(p => DANGEROUS.has(p.split('.').pop() || ''));
      lines.push('', `== Permissions requested (${perms.length}; ${risky.length} sensitive ⚠) ==`);
      for (const p of perms.slice(0, 120)) {
        lines.push(`${DANGEROUS.has(p.split('.').pop() || '') ? '⚠ ' : '  '}${p}`);
      }
      if (perms.length > 120) lines.push(`…and ${perms.length - 120} more`);

      const comp = (tag: string) => els.filter(e => e.name === tag);
      const launcher = els.findIndex((e, i) =>
        e.name === 'category' && e.attrs['name'] === 'android.intent.category.LAUNCHER' && i > 0);
      let launcherName = '';
      if (launcher > 0) {
        for (let i = launcher; i >= 0; i--) {
          if (els[i].name === 'activity' || els[i].name === 'activity-alias') { launcherName = els[i].attrs['name'] ?? ''; break; }
        }
      }
      lines.push('', '== Components ==');
      lines.push(`activities: ${comp('activity').length + comp('activity-alias').length}, services: ${comp('service').length}, ` +
        `receivers: ${comp('receiver').length}, providers: ${comp('provider').length}`);
      if (launcherName) lines.push(`launcher activity: ${launcherName}`);
      const exported = els.filter(e => ['activity', 'service', 'receiver', 'provider'].includes(e.name) && e.attrs['exported'] === 'true');
      if (exported.length) {
        lines.push(`explicitly exported (${exported.length}): ` + exported.slice(0, 12).map(e => e.attrs['name']).join(', ') + (exported.length > 12 ? ', …' : ''));
      }
      const feats = els.filter(e => e.name === 'uses-feature').map(e => e.attrs['name'] ?? `glEs ${e.attrs['glEsVersion'] ?? ''}`);
      if (feats.length) lines.push(`features: ${feats.slice(0, 20).join(', ')}`);
    } catch (e: any) {
      lines.push(`(manifest could not be parsed: ${e?.message || e})`);
    }
  }

  // contents
  const dex = entries.filter(e => /^classes\d*\.dex$/.test(e.name));
  const libs = entries.filter(e => e.name.startsWith('lib/') && e.name.endsWith('.so'));
  const abis = Array.from(new Set(libs.map(e => e.name.split('/')[1])));
  const assets = entries.filter(e => e.name.startsWith('assets/'));
  const res = entries.filter(e => e.name.startsWith('res/'));
  lines.push('', '== Contents ==');
  lines.push(`dex files: ${dex.length} (${fmtSize(dex.reduce((a, e) => a + e.size, 0))} uncompressed)`);
  for (const d of dex.slice(0, 8)) {
    try {
      const h = await readZipEntryHead(uri, d, 112);
      if (h.length >= 100 && strFromU8(h.subarray(0, 3)) === 'dex') {
        lines.push(`  ${d.name}: ${u32(h, 96)} classes, ${u32(h, 88)} methods, ${u32(h, 80)} fields, ${u32(h, 56)} strings`);
      }
    } catch { /* header unreadable */ }
  }
  lines.push(`native libs: ${libs.length}${abis.length ? ` (ABIs: ${abis.join(', ')})` : ''}`);
  const bigLibs = [...libs].sort((a, b) => b.size - a.size).slice(0, 6);
  if (bigLibs.length) lines.push('  largest: ' + bigLibs.map(l => `${l.name.split('/').pop()} ${fmtSize(l.size)}`).join(', '));
  lines.push(`assets: ${assets.length} files, resources: ${res.length} files`);
  const hints: string[] = [];
  if (byName.has('resources.arsc')) hints.push('resources.arsc');
  if (entries.some(e => e.name.startsWith('kotlin/'))) hints.push('Kotlin');
  if (libs.some(l => /libflutter\.so$/.test(l.name))) hints.push('Flutter');
  if (libs.some(l => /libhermes|libreactnative|libreact/.test(l.name))) hints.push('React Native');
  if (libs.some(l => /libunity\.so$/.test(l.name))) hints.push('Unity');
  if (assets.some(a => /index\.android\.bundle/.test(a.name))) hints.push('RN JS bundle');
  if (hints.length) lines.push(`framework hints: ${hints.join(', ')}`);

  // signing
  lines.push('', '== Signing ==');
  const sigEntries = entries.filter(e => /^META-INF\/[^/]+\.(RSA|DSA|EC)$/i.test(e.name));
  let certShown = false;
  for (const s of sigEntries.slice(0, 2)) {
    try {
      const cert = parsePkcs7Cert(await readZipEntry(uri, s, 2 * 1024 * 1024));
      if (cert) {
        certShown = true;
        lines.push(`v1 certificate (${s.name}):`);
        lines.push(`  subject: ${cert.subject || '(empty)'}`);
        lines.push(`  issuer:  ${cert.issuer || '(empty)'}`);
        lines.push(`  valid:   ${cert.notBefore} → ${cert.notAfter}`);
        lines.push(`  SHA-256: ${cert.sha256}`);
      }
    } catch { /* skip unreadable */ }
  }
  try {
    const tailStart = Math.max(0, cdOffset - 24);
    const t = await readRange(uri, tailStart, Math.min(24, cdOffset));
    const magic = strFromU8(t.subarray(Math.max(0, t.length - 16)));
    if (magic === 'APK Sig Block 42') {
      const blockSize = u64(t, t.length - 24);
      const blk = await readRange(uri, cdOffset - blockSize - 8, Math.min(blockSize, 4 * 1024 * 1024));
      const schemes: string[] = [];
      let q = 8;
      while (q + 12 <= blk.length - 24) {
        const pairLen = u64(blk, q);
        const id = u32(blk, q + 8);
        if (id === 0x7109871a) schemes.push('v2');
        else if (id === 0xf05368c0) schemes.push('v3');
        else if (id === 0x1b93ad61) schemes.push('v3.1');
        if (pairLen <= 0) break;
        q += 8 + pairLen;
      }
      lines.push(`APK Signing Block present${schemes.length ? ` (${schemes.join(', ')})` : ''}`);
    } else if (!certShown) {
      lines.push('No v1 certificate found and no v2/v3 signing block detected (unsigned or unusual layout).');
    }
  } catch { /* signing block unreadable */ }
  if (!sigEntries.length && !certShown) lines.push('No META-INF signature files (v1) — signed with v2+ only, or unsigned.');

  lines.push('', 'Note: this is an inspection report (manifest, permissions, structure, signing). It is not a decompilation to source code.');
  return lines.join('\n');
}

/* ── generic ZIP / Office ──────────────────────────────────────────────── */
const decodeXml = (s: string): string =>
  s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_m, d) => String.fromCharCode(parseInt(d, 10))).replace(/&amp;/g, '&');

async function officeText(uri: string, entries: ZipEntry[], ext: string): Promise<string | null> {
  const get = async (n: string): Promise<string | null> => {
    const e = entries.find(x => x.name === n);
    return e ? strFromU8(await readZipEntry(uri, e, 24 * 1024 * 1024)) : null;
  };
  if (ext === 'docx') {
    const xml = await get('word/document.xml');
    if (!xml) return null;
    return decodeXml(xml.replace(/<\/w:p>/g, '\n').replace(/<w:tab\/>/g, '\t').replace(/<w:br\/>/g, '\n').replace(/<[^>]+>/g, ''));
  }
  if (ext === 'xlsx') {
    const sst = await get('xl/sharedStrings.xml');
    const wb = await get('xl/workbook.xml');
    const sheets = wb ? Array.from(wb.matchAll(/<sheet [^>]*name="([^"]+)"/g)).map(m => decodeXml(m[1])) : [];
    const strings = sst ? Array.from(sst.matchAll(/<si>([\s\S]*?)<\/si>/g)).map(m => decodeXml(m[1].replace(/<[^>]+>/g, ''))) : [];
    return `Sheets: ${sheets.join(', ') || '?'}\nShared strings (${strings.length}):\n${strings.slice(0, 400).join('\n')}`;
  }
  if (ext === 'pptx') {
    const slides = entries.filter(e => /^ppt\/slides\/slide\d+\.xml$/.test(e.name))
      .sort((a, b) => parseInt(a.name.replace(/\D/g, ''), 10) - parseInt(b.name.replace(/\D/g, ''), 10));
    const out: string[] = [];
    for (const s of slides.slice(0, 60)) {
      const xml = strFromU8(await readZipEntry(uri, s));
      const text = decodeXml(Array.from(xml.matchAll(/<a:t>([\s\S]*?)<\/a:t>/g)).map(m => m[1]).join(' '));
      out.push(`--- ${s.name.split('/').pop()} ---\n${text}`);
    }
    return out.join('\n');
  }
  if (ext === 'odt' || ext === 'ods' || ext === 'odp') {
    const xml = await get('content.xml');
    return xml ? decodeXml(xml.replace(/<\/text:p>/g, '\n').replace(/<[^>]+>/g, '')) : null;
  }
  return null;
}

async function inspectZip(uri: string, size: number, name: string, ext: string): Promise<string> {
  const { entries } = await listZip(uri, size);
  if (['docx', 'xlsx', 'pptx', 'odt', 'ods', 'odp'].includes(ext)) {
    const text = await officeText(uri, entries, ext);
    if (text && text.trim()) return `Document text of ${name}:\n${text}`;
  }
  const lines = [`${ext.toUpperCase() || 'ZIP'} archive: ${name} (${fmtSize(size)}, ${entries.length} entries)`];
  const sorted = entries.filter(e => !e.name.endsWith('/'));
  for (const e of sorted.slice(0, 150)) lines.push(`${String(e.size).padStart(10)}  ${e.name}`);
  if (sorted.length > 150) lines.push(`…and ${sorted.length - 150} more`);
  // peek small text files
  const textual = sorted.find(e => /(^|\/)(readme|manifest\.mf|package\.json|pom\.xml|build\.gradle)/i.test(e.name) && e.size < 20000);
  if (textual) {
    try { lines.push('', `--- ${textual.name} ---`, strFromU8(await readZipEntry(uri, textual)).slice(0, 3000)); } catch { /* ignore */ }
  }
  return lines.join('\n');
}

/* ── PDF ───────────────────────────────────────────────────────────────── */
function latin1(b: Uint8Array): string {
  let s = '';
  for (let i = 0; i < b.length; i += 8192) s += String.fromCharCode.apply(null, Array.from(b.subarray(i, i + 8192)));
  return s;
}

function pdfStringToText(s: string): string {
  return s.replace(/\\([nrtbf()\\])|\\(\d{1,3})/g, (_m, c, oct) => {
    if (oct) return String.fromCharCode(parseInt(oct, 8));
    return ({ n: '\n', r: '', t: '\t', b: '', f: '' } as Record<string, string>)[c] ?? c;
  });
}

export function extractPdfText(bytes: Uint8Array, maxPages = 30): { text: string; pages: number; streams: number } {
  const raw = latin1(bytes);
  const pageCount = (raw.match(/\/Type\s*\/Page[^s]/g) || []).length;
  const out: string[] = [];
  let streams = 0;
  const re = /stream\r?\n/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(raw)) && out.length < maxPages * 40) {
    const start = m.index + m[0].length;
    const end = raw.indexOf('endstream', start);
    if (end < 0) break;
    const dictStart = raw.lastIndexOf('obj', m.index);
    const dict = raw.slice(Math.max(0, dictStart), m.index);
    let data = bytes.subarray(start, end);
    streams++;
    if (/\/FlateDecode/.test(dict)) {
      try { data = unzlibSync(data); } catch { try { data = inflateSync(data.subarray(2)); } catch { re.lastIndex = end; continue; } }
    } else if (/\/(DCTDecode|JPXDecode|CCITTFaxDecode|LZWDecode|ASCII85Decode|RunLengthDecode)/.test(dict) || /\/Subtype\s*\/Image/.test(dict)) {
      re.lastIndex = end; continue;
    }
    const content = latin1(data);
    if (!/(BT|Tj|TJ)/.test(content)) { re.lastIndex = end; continue; }
    const lineParts: string[] = [];
    const opRe = /\(((?:\\.|[^\\()])*)\)\s*(?:Tj|'|")|\[((?:\\.|[^\]])*)\]\s*TJ|(T\*|Td|TD|ET)\b/g;
    let o: RegExpExecArray | null;
    while ((o = opRe.exec(content))) {
      if (o[1] !== undefined) lineParts.push(pdfStringToText(o[1]));
      else if (o[2] !== undefined) {
        lineParts.push(Array.from(o[2].matchAll(/\(((?:\\.|[^\\()])*)\)/g)).map(x => pdfStringToText(x[1])).join(''));
      } else lineParts.push('\n');
    }
    const t = lineParts.join('').replace(/\n{3,}/g, '\n\n').trim();
    if (t) out.push(t);
    re.lastIndex = end;
  }
  return { text: out.join('\n\n'), pages: pageCount, streams };
}

async function inspectPdf(uri: string, size: number, name: string): Promise<string> {
  if (size > 40 * 1024 * 1024) return `PDF ${name} is ${fmtSize(size)} — too large to read on the phone.`;
  const bytes = await readRange(uri, 0, size);
  const r = extractPdfText(bytes);
  const printable = (r.text.match(/[\p{L}\p{N}]/gu) || []).length;
  const head = `PDF: ${name} (${fmtSize(size)}, ~${r.pages || '?'} pages)`;
  if (printable < 40) {
    return `${head}\nLittle or no extractable text was found. The PDF is probably scanned images or uses embedded fonts ` +
      '(common with Arabic). Reading scanned pages needs OCR, which is not built into the app.';
  }
  return `${head}\n${r.text}`;
}

/* ── text / binary fallback ────────────────────────────────────────────── */
const TEXT_EXT = new Set(['txt', 'md', 'markdown', 'json', 'jsonl', 'csv', 'tsv', 'xml', 'html', 'htm', 'css', 'js', 'jsx', 'ts', 'tsx',
  'py', 'java', 'kt', 'kts', 'c', 'cc', 'cpp', 'h', 'hpp', 'cs', 'go', 'rs', 'rb', 'php', 'swift', 'sh', 'bash', 'zsh', 'bat', 'ps1',
  'yaml', 'yml', 'toml', 'ini', 'cfg', 'conf', 'properties', 'gradle', 'log', 'sql', 'tex', 'srt', 'vtt', 'env', 'gitignore', 'lock', 'smali', 'rtf', 'svg']);

const MAGIC: Array<[number[], string, number?]> = [
  [[0x89, 0x50, 0x4e, 0x47], 'PNG image'], [[0xff, 0xd8, 0xff], 'JPEG image'], [[0x47, 0x49, 0x46, 0x38], 'GIF image'],
  [[0x25, 0x50, 0x44, 0x46], 'PDF document'], [[0x50, 0x4b, 0x03, 0x04], 'ZIP-based archive'], [[0x1f, 0x8b], 'gzip data'],
  [[0x7f, 0x45, 0x4c, 0x46], 'ELF executable/library (Linux/Android native code)'], [[0x64, 0x65, 0x78, 0x0a], 'Android DEX bytecode'],
  [[0x53, 0x51, 0x4c, 0x69, 0x74, 0x65], 'SQLite database'], [[0x37, 0x7a, 0xbc, 0xaf], '7-Zip archive'], [[0x52, 0x61, 0x72, 0x21], 'RAR archive'],
  [[0x47, 0x47, 0x55, 0x46], 'GGUF model file (llama.cpp)'], [[0x4f, 0x67, 0x67, 0x53], 'Ogg audio/video'], [[0x49, 0x44, 0x33], 'MP3 audio'],
  [[0x52, 0x49, 0x46, 0x46], 'RIFF container (WAV/AVI/WEBP)'], [[0x66, 0x74, 0x79, 0x70], 'MP4/MOV video', 4], [[0x42, 0x4d], 'BMP image'],
  [[0x00, 0x01, 0x00, 0x00], 'TrueType font'], [[0x4d, 0x5a], 'Windows PE executable'], [[0xca, 0xfe, 0xba, 0xbe], 'Java class / Mach-O fat binary'],
];

function sniff(b: Uint8Array): string {
  for (const [sig, label, off] of MAGIC) {
    const o = off ?? 0;
    if (sig.every((v, i) => b[o + i] === v)) return label;
  }
  return 'unknown binary';
}

function looksTextual(b: Uint8Array): boolean {
  if (b.length === 0) return true;
  let bad = 0;
  for (const x of b.subarray(0, 4096)) { if (x === 0 || (x < 9) || (x > 13 && x < 32)) bad++; }
  return bad / Math.min(b.length, 4096) < 0.02;
}

function stringsOf(b: Uint8Array, min = 6, maxCount = 60): string[] {
  const out: string[] = [];
  let cur = '';
  for (let i = 0; i < b.length && out.length < maxCount; i++) {
    const c = b[i];
    if (c >= 32 && c < 127) cur += String.fromCharCode(c);
    else { if (cur.length >= min) out.push(cur.slice(0, 120)); cur = ''; }
  }
  return out;
}

export interface InspectInput { uri: string; name: string; size: number; mime?: string | null }

export async function inspectFile(inp: InspectInput, maxChars = 24000): Promise<string> {
  const ext = (inp.name.split('.').pop() || '').toLowerCase();
  let size = inp.size;
  if (!size) {
    const info = await FileSystem.getInfoAsync(inp.uri, { size: true });
    size = info.exists ? info.size : 0;
  }
  const head = await readRange(inp.uri, 0, Math.min(size, 8192));
  const kind = sniff(head);
  let text: string;

  if (ext === 'apk' || ext === 'apks' || ext === 'xapk' || /android\.package-archive/.test(inp.mime || '')) {
    text = await inspectApk(inp.uri, size, inp.name);
  } else if (kind === 'PDF document' || ext === 'pdf') {
    text = await inspectPdf(inp.uri, size, inp.name);
  } else if (kind === 'ZIP-based archive' || ['zip', 'jar', 'aar', 'docx', 'xlsx', 'pptx', 'odt', 'ods', 'odp', 'epub'].includes(ext)) {
    text = await inspectZip(inp.uri, size, inp.name, ext);
  } else if (TEXT_EXT.has(ext) || (kind === 'unknown binary' && looksTextual(head))) {
    const bytes = await readRange(inp.uri, 0, Math.min(size, maxChars * 3));
    text = `Text file ${inp.name} (${fmtSize(size)}):\n${strFromU8(bytes)}`;
  } else {
    const sample = await readRange(inp.uri, 0, Math.min(size, 131072));
    const strs = stringsOf(sample);
    text = [
      `Binary file: ${inp.name}`,
      `size: ${fmtSize(size)}   type (by header): ${kind}`,
      `first bytes: ${hex(head.subarray(0, 32))}`,
      strs.length ? `readable strings (${strs.length}):\n${strs.join('\n')}` : 'no readable strings in the first 128 KB',
    ].join('\n');
  }
  return text.length > maxChars ? `${text.slice(0, maxChars)}\n…[truncated ${text.length - maxChars} chars]` : text;
}
