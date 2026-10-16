/**
 * screen.ts — the agent's hands and eyes on the phone screen.
 * Needs the GVR Agent accessibility service turned on by the user, and only
 * acts inside apps the user put on the allowed list.
 */
import { requireNativeModule } from 'expo-modules-core';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as IntentLauncher from 'expo-intent-launcher';

const Native: any = requireNativeModule('Terminal');
const ALLOW_KEY = 'gvr_screen_allowed';
const OFF = 'The screen service is off. The user must turn on "GVR Agent" in Accessibility settings. Call screen_enable to open that page.';

async function getAllowed(): Promise<string[]> {
  try {
    return JSON.parse((await AsyncStorage.getItem(ALLOW_KEY)) || '[]');
  } catch {
    return [];
  }
}

async function sync(): Promise<boolean> {
  await Native.screenSetAllowed(await getAllowed());
  return !!(await Native.screenStatus());
}

export async function toolScreenRead(): Promise<string> {
  if (!(await sync())) return OFF;
  return String(await Native.screenDump());
}

export async function toolScreenTap(arg: string): Promise<string> {
  const n = parseInt(arg.trim(), 10);
  if (!n) return 'Give the element number from screen_read, e.g. 4';
  if (!(await sync())) return OFF;
  return String(await Native.screenTap(n));
}

export async function toolScreenType(arg: string): Promise<string> {
  const i = arg.indexOf('|');
  if (i < 0) return 'Give: number | text, e.g. 3 | hello';
  const n = parseInt(arg.slice(0, i).trim(), 10);
  const text = arg.slice(i + 1);
  if (!n) return 'Give the element number from screen_read first.';
  if (!(await sync())) return OFF;
  return String(await Native.screenType(n, text));
}

export async function toolScreenBack(): Promise<string> {
  if (!(await sync())) return OFF;
  return String(await Native.screenBack());
}

export async function toolScreenAllow(arg: string): Promise<string> {
  const pkgs = arg
    .split(/[\s,|]+/)
    .map(s => s.trim())
    .filter(s => /^[a-z0-9_]+(\.[a-z0-9_]+)+$/i.test(s));
  if (!pkgs.length) return 'Give package names, e.g. com.whatsapp';
  const next = Array.from(new Set([...(await getAllowed()), ...pkgs]));
  await AsyncStorage.setItem(ALLOW_KEY, JSON.stringify(next));
  return `Allowed apps: ${next.join(', ')}`;
}

export async function toolScreenEnable(): Promise<string> {
  await IntentLauncher.startActivityAsync('android.settings.ACCESSIBILITY_SETTINGS');
  return 'Opened Accessibility settings. Ask the user to turn on GVR Agent there.';
}
