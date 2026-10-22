/** In-memory file system: enough of expo-file-system for the tools under test. */
const files = new Map<string, string>();
const dirs = new Set<string>();
export const documentDirectory = 'file:///data/user/0/com.ahmedxg.gvrchat/files/';
export const cacheDirectory = 'file:///data/user/0/com.ahmedxg.gvrchat/cache/';
/** Test hook: paths (substring) whose writes/copies must fail, e.g. a missing storage permission. */
export const failOn: string[] = [];

const asDir = (u: string) => (u.endsWith('/') ? u : `${u}/`);
const stripDir = (u: string) => u.replace(/\/+$/, '');
const mustWork = (u: string) => { if (failOn.some(f => u.includes(f))) throw new Error(`EACCES: permission denied, ${u}`); };

export const __reset = () => { files.clear(); dirs.clear(); failOn.length = 0; };
export const __put = (uri: string, content: string) => { files.set(uri, content); };
export const __get = (uri: string) => files.get(uri);
export const __has = (uri: string) => files.has(uri);

export async function getInfoAsync(uri: string, _o?: unknown): Promise<any> {
  if (files.has(uri)) return { exists: true, isDirectory: false, uri, size: Buffer.byteLength(files.get(uri)!), modificationTime: 0 };
  const d = asDir(uri);
  if (dirs.has(stripDir(uri)) || [...files.keys()].some(k => k.startsWith(d))) return { exists: true, isDirectory: true, uri, size: 0, modificationTime: 0 };
  return { exists: false, isDirectory: false, uri };
}
export async function readAsStringAsync(uri: string, o?: { length?: number }): Promise<string> {
  if (!files.has(uri)) throw new Error(`ENOENT: ${uri}`);
  const t = files.get(uri)!;
  return o?.length ? t.slice(0, o.length) : t;
}
export async function writeAsStringAsync(uri: string, content: string): Promise<void> { mustWork(uri); files.set(uri, content); }
export async function makeDirectoryAsync(uri: string): Promise<void> { mustWork(uri); dirs.add(stripDir(uri)); }
export async function copyAsync({ from, to }: { from: string; to: string }): Promise<void> {
  mustWork(to);
  if (!files.has(from)) throw new Error(`ENOENT: ${from}`);
  files.set(to, files.get(from)!);
}
export async function deleteAsync(uri: string, _o?: unknown): Promise<void> { files.delete(uri); }
export async function readDirectoryAsync(uri: string): Promise<string[]> {
  const d = asDir(uri);
  const names = new Set<string>();
  for (const k of files.keys()) if (k.startsWith(d)) names.add(k.slice(d.length).split('/')[0]);
  for (const k of dirs) if (k.startsWith(d)) names.add(k.slice(d.length).split('/')[0]);
  return [...names].filter(Boolean);
}
export async function getContentUriAsync(uri: string): Promise<string> { return `content://com.test.fileprovider/${uri.split('/').pop()}`; }
export async function getFreeDiskStorageAsync(): Promise<number> { return 50e9; }
export async function getTotalDiskCapacityAsync(): Promise<number> { return 128e9; }
export async function downloadAsync(): Promise<never> { throw new Error('no network in tests'); }
