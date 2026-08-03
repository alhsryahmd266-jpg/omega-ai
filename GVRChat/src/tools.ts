/**
 * tools.ts — Real on-device tools
 * =================================
 * Honest constraint: standard Android apps (even with New Architecture /
 * native modules) CANNOT execute arbitrary shell commands without root or
 * Termux. There is no "terminal" tool here — a fake one would just print
 * canned text and mislead the user. What IS real and included:
 *
 *  - web_search   : actual HTTP request to DuckDuckGo, real results
 *  - device_info  : actual battery %, actual free storage (Expo APIs)
 *  - memory_save / memory_get / memory_list : real persistent storage
 */
import * as Battery from 'expo-battery';
import * as FileSystem from 'expo-file-system';
import AsyncStorage from '@react-native-async-storage/async-storage';

const MEMORY_KEY = 'gvr_agent_memory';

/* ── WEB SEARCH (real) ────────────────────────────────────────────────────── */
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

    const titles: string[] = [];
    const snippets: string[] = [];
    let m;
    while ((m = titleRe.exec(html)) && titles.length < 4) titles.push(clean(m[1]));
    while ((m = snipRe.exec(html)) && snippets.length < 4) snippets.push(clean(m[1]));

    if (titles.length === 0) return 'No results found.';

    return titles
      .map((t, i) => `• ${t}: ${(snippets[i] || '').slice(0, 170)}`)
      .join('\n');
  } catch (e: any) {
    return `Search error: ${e.message}`;
  }
}

/* ── DEVICE INFO (real, no shell needed) ─────────────────────────────────── */
export async function toolDeviceInfo(): Promise<string> {
  try {
    const level = await Battery.getBatteryLevelAsync();
    const state = await Battery.getBatteryStateAsync();
    const stateNames: Record<number, string> = {
      0: 'unknown', 1: 'unplugged', 2: 'charging', 3: 'full',
    };

    const freeBytes = await FileSystem.getFreeDiskStorageAsync();
    const totalBytes = await FileSystem.getTotalDiskCapacityAsync();
    const freeGB = (freeBytes / 1e9).toFixed(1);
    const totalGB = (totalBytes / 1e9).toFixed(1);

    return [
      `🔋 Battery: ${Math.round(level * 100)}% (${stateNames[state] ?? 'unknown'})`,
      `💾 Storage: ${freeGB} GB free / ${totalGB} GB total`,
    ].join('\n');
  } catch (e: any) {
    return `Device info error: ${e.message}`;
  }
}

/* ── MEMORY (real, persistent across sessions) ───────────────────────────── */
async function loadMemory(): Promise<Record<string, { value: string; ts: string }>> {
  try {
    const raw = await AsyncStorage.getItem(MEMORY_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
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

/* ── DISPATCH TABLE ───────────────────────────────────────────────────────── */
export type ToolName = 'search' | 'device_info' | 'mem_save' | 'mem_get' | 'mem_list';

export const AVAILABLE_TOOLS: ToolName[] = [
  'search', 'device_info', 'mem_save', 'mem_get', 'mem_list',
];

export async function dispatchTool(tool: string, arg: string): Promise<string> {
  switch (tool) {
    case 'search':      return toolWebSearch(arg);
    case 'device_info':  return toolDeviceInfo();
    case 'mem_save': {
      const [k, ...rest] = arg.split('|');
      return toolMemorySave(k, rest.join('|'));
    }
    case 'mem_get':      return toolMemoryGet(arg);
    case 'mem_list':     return toolMemoryList();
    default:
      return `Unknown tool '${tool}'. Note: terminal/shell commands are not ` +
             `available in this on-device build (Android sandboxing without ` +
             `Termux/root). Available tools: ${AVAILABLE_TOOLS.join(', ')}.`;
  }
}
