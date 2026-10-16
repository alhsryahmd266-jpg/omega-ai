/**
 * overlayChat.ts — tools to show or hide the floating chat window.
 */
import { requireNativeModule } from 'expo-modules-core';

const Native: any = requireNativeModule('Terminal');

export async function toolChatShow(): Promise<string> {
  return String(await Native.chatShow());
}

export async function toolChatHide(): Promise<string> {
  return String(await Native.chatHide());
}
