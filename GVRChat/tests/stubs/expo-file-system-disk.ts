/**
 * Disk-backed expo-file-system for the MISSION tests: the app's virtual paths live under a real temp folder,
 * so the JS file tools and the real shell (terminal-real.ts) see the very same files.
 *   file:///data/user/0/com.ahmedxg.gvrchat/files/  →  <ROOT>/files/      (/workspace in Alpine = files/workspace)
 *   file:///storage/emulated/0/                     →  <ROOT>/sdcard/
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export const ROOT = process.env.GVR_TEST_ROOT || fs.mkdtempSync(path.join(os.tmpdir(), 'gvr-mission-'));
// Every run gets its own folder; remove it at exit (a run can write GBs of APKs/video). GVR_KEEP=1 keeps it for debugging.
if (!process.env.GVR_TEST_ROOT && process.env.GVR_KEEP !== '1') {
  process.on('exit', () => { try { fs.rmSync(ROOT, { recursive: true, force: true }); } catch { /* best effort */ } });
}
for (const d of ['files/workspace', 'cache', 'sdcard/Download', 'bin', 'opt', 'root']) fs.mkdirSync(path.join(ROOT, d), { recursive: true });

export const documentDirectory = 'file:///data/user/0/com.ahmedxg.gvrchat/files/';
export const cacheDirectory = 'file:///data/user/0/com.ahmedxg.gvrchat/cache/';
export const failOn: string[] = [];

const MAP: [string, string][] = [
  ['/data/user/0/com.ahmedxg.gvrchat/files/', `${ROOT}/files/`],
  ['/data/user/0/com.ahmedxg.gvrchat/cache/', `${ROOT}/cache/`],
  ['/storage/emulated/0/', `${ROOT}/sdcard/`],
];
export function toReal(uriOrPath: string): string {
  const p = uriOrPath.replace(/^file:\/\//, '');
  for (const [v, r] of MAP) {
    if (p.startsWith(v)) return r + p.slice(v.length);
    if (`${p}/` === v) return r.replace(/\/$/, '');
  }
  return p;
}
const mustWork = (u: string) => { if (failOn.some(f => u.includes(f))) throw new Error(`EACCES: permission denied, ${u}`); };

export const __reset = () => { failOn.length = 0; };
export const __put = (uri: string, content: string) => { const r = toReal(uri); fs.mkdirSync(path.dirname(r), { recursive: true }); fs.writeFileSync(r, content); };
export const __get = (uri: string) => (fs.existsSync(toReal(uri)) ? fs.readFileSync(toReal(uri), 'utf8') : undefined);
export const __has = (uri: string) => fs.existsSync(toReal(uri));

export async function getInfoAsync(uri: string, _o?: unknown): Promise<any> {
  try {
    const st = fs.statSync(toReal(uri));
    return { exists: true, isDirectory: st.isDirectory(), uri, size: st.size, modificationTime: st.mtimeMs / 1000 };
  } catch { return { exists: false, isDirectory: false, uri }; }
}
export async function readAsStringAsync(uri: string, o?: { length?: number }): Promise<string> {
  const r = toReal(uri);
  if (!o?.length) return fs.readFileSync(r, 'utf8');
  const fd = fs.openSync(r, 'r');
  try { const b = Buffer.alloc(o.length); const n = fs.readSync(fd, b, 0, o.length, 0); return b.subarray(0, n).toString('utf8'); } finally { fs.closeSync(fd); }
}
export async function writeAsStringAsync(uri: string, content: string): Promise<void> {
  mustWork(uri);
  const r = toReal(uri); fs.mkdirSync(path.dirname(r), { recursive: true }); fs.writeFileSync(r, content);
}
export async function makeDirectoryAsync(uri: string): Promise<void> { mustWork(uri); fs.mkdirSync(toReal(uri), { recursive: true }); }
export async function copyAsync({ from, to }: { from: string; to: string }): Promise<void> {
  mustWork(to);
  const r = toReal(to); fs.mkdirSync(path.dirname(r), { recursive: true }); fs.copyFileSync(toReal(from), r);
}
export async function deleteAsync(uri: string, _o?: unknown): Promise<void> { fs.rmSync(toReal(uri), { recursive: true, force: true }); }
export async function readDirectoryAsync(uri: string): Promise<string[]> { return fs.readdirSync(toReal(uri)); }
export async function getContentUriAsync(uri: string): Promise<string> { return `content://com.test.fileprovider/${path.basename(toReal(uri))}`; }
export async function getFreeDiskStorageAsync(): Promise<number> { return 50e9; }
export async function getTotalDiskCapacityAsync(): Promise<number> { return 128e9; }
export async function downloadAsync(url: string, dest: string, opts?: { headers?: Record<string, string> }): Promise<any> {
  const res = await fetch(url, { headers: opts?.headers, redirect: 'follow' });
  const buf = Buffer.from(await res.arrayBuffer());
  const r = toReal(dest); fs.mkdirSync(path.dirname(r), { recursive: true }); fs.writeFileSync(r, buf);
  return { uri: dest, status: res.status, headers: {}, mimeType: res.headers.get('content-type') };
}
