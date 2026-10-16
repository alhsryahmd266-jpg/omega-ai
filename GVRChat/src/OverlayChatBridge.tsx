/**
 * OverlayChatBridge.tsx — connects the floating chat window to the agent.
 * A message typed in the window runs one agent turn, and the answer goes back to the window.
 */
import { useEffect } from 'react';
import { requireNativeModule, EventEmitter } from 'expo-modules-core';
import { runWithAttachment } from './gvrEngine';
import { isModelLoaded } from './localLLM';

const Native: any = requireNativeModule('Terminal');
let busy = false;

export function OverlayChatBridge() {
  useEffect(() => {
    const emitter = new EventEmitter(Native);
    const sub = emitter.addListener('onOverlayMessage', async (e: { text: string }) => {
      const text = (e?.text || '').trim();
      if (!text) return;
      if (busy) {
        Native.chatAppend('GVR: لسه بفكر في الرسالة اللي قبلها، استنى ثانية.');
        return;
      }
      if (!isModelLoaded()) {
        Native.chatAppend('GVR: حمّل نموذج من الإعدادات الأول.');
        return;
      }
      busy = true;
      try {
        const res = await runWithAttachment(text, null, () => {}, 5);
        Native.chatAppend('GVR: ' + (res.answer || '(مفيش رد)'));
      } catch (err: any) {
        Native.chatAppend('GVR خطأ: ' + (err?.message || String(err)));
      } finally {
        busy = false;
      }
    });
    return () => sub.remove();
  }, []);
  return null;
}
