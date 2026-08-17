/**
 * tools.ts — GVRChat tools
 * Includes: terminal (proot+Alpine), python, pkg_install,
 *           web search, device info, persistent memory
 */

import * as FileSystem from 'expo-file-system';
import AsyncStorage from '@react-native-async-storage/async-storage';
import Terminal from '../modules/terminal/src';

const MEMORY_KEY = 'gvr_agent_memory';

/* ── TERMINAL SETUP ──────────────────────────────────────────────────────── */
let terminalReady: boolean | null = null;

async function ensureTerminal(): Promise<string | null> {
  if (terminalReady === true) return null;
  if (terminalReady === null) {
    terminalReady = await Terminal.isSetupDone();
    if (terminalReady) return null;
  }
  terminalReady = false;
  try {
    await Terminal.setupTerminal();
    terminalReady = true;
    return null;
  } catch (e: any) {
    terminalReady = null;
    return `Terminal setup failed: ${e.message}`;
  }
}

/* ── TERMINAL ─────────────────────────────────────────────────────────────── */
export async function toolTerminal(command: string, timeout = 30): Promise<string> {
  const err = await ensureTerminal();
  if (err) return err;
  return Terminal.run(command, timeout);
}

/* ── PYTHON ───────────────────────────────────────────────────────────────── */
export async function toolPython(code: string, timeout = 60): Promise<string> {
  const err = await ensureTerminal();
  if (err) return err;
  return Terminal.runPython(code, timeout);
}

/* ── PKG INSTALL ──────────────────────────────────────────────────────────── */
export async function toolPkgInstall(pkg: string): Promise<string> {
  const err = await ensureTerminal();
  if (err) return err;
  return Terminal.installPackage(pkg);
}

/* ── WEB SEARCH ───────────────────────────────────────────────────────────── */
export async function toolWebSearch(query: string): Promise<string> {
  try {
    const res = await fetch(
      `https://html.duckduckgo.com/html/?q=${encodeURIComponent(query)}`,
      { headers: { 'User-Agent': 'Mozilla/5.0 (compatible; GVRChat/2.0)' } }
    );
    const html = await res.text();
    const clean = (s: string) => s.replace(/<[^>]+>/g, '').trim();
    const titleRe = /class="result__a"[^>]*>(.*?)<\/a>/g;
    const snipRe  = /class="result__snippet"[^>]*>(.*?)<\/a>/g;
    const titles: string[] = [], snippets: string[] = [];
    let m;
    while ((m = titleRe.exec(html)) && titles.length < 4) titles.push(clean(m[1]));
    while ((m = snipRe.exec(html)) && snippets.length < 4) snippets.push(clean(m[1]));
    if (titles.length === 0) return 'No results found.';
    return titles.map((t, i) => `• ${t}: ${(snippets[i] || '').slice(0, 170)}`).join('\n');
  } catch (e: any) {
    return `Search error: ${e.message}`;
  }
}

/* ── DEVICE INFO (no expo-battery dependency) ─────────────────────────────── */
export async function toolDeviceInfo(): Promise<string> {
  try {
    const free  = await FileSystem.getFreeDiskStorageAsync();
    const total = await FileSystem.getTotalDiskCapacityAsync();
    return [
      `💾 Storage: ${(free / 1e9).toFixed(1)} GB free / ${(total / 1e9).toFixed(1)} GB total`,
      `📁 App dir: ${FileSystem.documentDirectory}`,
    ].join('\n');
  } catch (e: any) {
    return `Device info error: ${e.message}`;
  }
}

/* ── MEMORY ───────────────────────────────────────────────────────────────── */
async function loadMemory(): Promise<Record<string, { value: string; ts: string }>> {
  try {
    const raw = await AsyncStorage.getItem(MEMORY_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch { return {}; }
}
async function persistMemory(mem: Record<string, any>) {
  await AsyncStorage.setItem(MEMORY_KEY, JSON.stringify(mem));
}
export async function toolMemorySave(key: string, value: string): Promise<string> {
  const mem = await loadMemory();
  mem[key.trim()] = { value: value.trim(), ts: new Date().toISOString() };
  await persistMemory(mem);
  return `Saved '${key.trim()}' to memory.`;
}
export async function toolMemoryGet(key: string): Promise<string> {
  const mem = await loadMemory();
  const item = mem[key.trim()];
  return item ? item.value : `No memory found for '${key.trim()}'`;
}
export async function toolMemoryList(): Promise<string> {
  const mem = await loadMemory();
  const keys = Object.keys(mem);
  if (keys.length === 0) return 'Memory is empty.';
  return keys.map(k => `• ${k}: ${mem[k].value.slice(0, 60)}`).join('\n');
}

/* ── DISPATCH ─────────────────────────────────────────────────────────────── */
export type ToolName =
  | 'search' | 'device_info'
  | 'mem_save' | 'mem_get' | 'mem_list'
  | 'terminal' | 'python' | 'pkg_install';

export const AVAILABLE_TOOLS: ToolName[] = [
  'search', 'device_info',
  'mem_save', 'mem_get', 'mem_list',
  'terminal', 'python', 'pkg_install',
];

export async function dispatchTool(tool: string, arg: string): Promise<string> {
  switch (tool) {
    case 'search':       return toolWebSearch(arg);
    case 'device_info':  return toolDeviceInfo();
    case 'mem_save': {
      const [k, ...rest] = arg.split('|');
      return toolMemorySave(k, rest.join('|'));
    }
    case 'mem_get':      return toolMemoryGet(arg);
    case 'mem_list':     return toolMemoryList();
    case 'terminal':     return toolTerminal(arg);
    case 'python':       return toolPython(arg);
    case 'pkg_install':  return toolPkgInstall(arg);
    default:
      return `Unknown tool '${tool}'. Available: ${AVAILABLE_TOOLS.join(', ')}`;
  }
}
