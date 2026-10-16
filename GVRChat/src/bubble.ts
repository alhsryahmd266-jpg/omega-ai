/**
 * bubble.ts — the floating stop/resume button. While it is paused, every screen tool refuses to act.
 */
import { requireNativeModule } from 'expo-modules-core';

const Native: any = requireNativeModule('Terminal');

export async function toolBubbleShow(): Promise<string> {
  return String(await Native.bubbleShow());
}

export async function toolBubbleHide(): Promise<string> {
  return String(await Native.bubbleHide());
}
