/**
 * OverlayChatBridge.tsx — connects the floating chat window to the agent.
 * If a task needs screen control and it is off, the task is kept, the user is asked
 * for one "موافق", the accessibility page opens, and the task resumes by itself
 * as soon as the service is turned on.
 */
import { useEffect } from 'react';
import { requireNativeModule, EventEmitter } from 'expo-modules-core';
import { runWithAttachment, type StepEvent } from './gvrEngine';
import { isModelLoaded } from './localLLM';
import { toolScreenEnable } from './screen';

const Native: any = requireNativeModule('Terminal');
const OFF_TEXT = /screen service is off/i;
const APPROVE = /^(موافق|اوك|أوك|تمام|ok|yes|ايوه|أيوه|اه|آه)$/i;
const MAX_WAIT_MS = 5 * 60 * 1000;

let busy = false;
let pendingText: string | null = null;
let waiting = false;
let timer: any = null;
let waitStarted = 0;

function ask(text: string) {
  Native.chatAppend('GVR: ' + text);
}

async function accessibilityReady(): Promise<boolean> {
  try {
    return !!(await Native.screenStatus()) && !!(await Native.accessibilityEnabledInSettings());
  } catch {
    return false;
  }
}

function stopWaiting() {
  if (timer) clearInterval(timer);
  timer = null;
  waiting = false;
}

function startWaiting() {
  stopWaiting();
  waiting = true;
  waitStarted = Date.now();
  timer = setInterval(async () => {
    if (Date.now() - waitStarted > MAX_WAIT_MS) {
      stopWaiting();
      pendingText = null;
      ask('وقفت الانتظار بعد ٥ دقايق. قولي الطلب تاني لما تكون جاهز.');
      return;
    }
    if (!(await accessibilityReady())) return;
    stopWaiting();
    const t = pendingText;
    pendingText = null;
    ask('خدمة الوصول اتفعّلت، بكمّل المهمة.');
    if (t) await runTurn(t);
  }, 2000);
}

async function runTurn(text: string) {
  if (!isModelLoaded()) {
    ask('حمّل نموذج من الإعدادات الأول.');
    return;
  }
  busy = true;
  let needsScreen = false;
  try {
    const res = await runWithAttachment(text, null, (ev: StepEvent) => {
      if (ev.type === 'tool_result' && OFF_TEXT.test(String((ev as any).result || ''))) needsScreen = true;
    }, 5);
    ask(res.answer || '(مفيش رد)');
  } catch (err: any) {
    ask('خطأ: ' + (err?.message || String(err)));
  } finally {
    busy = false;
  }
  if (needsScreen) {
    pendingText = text;
    ask('المهمة دي محتاجة خدمة الوصول. اكتب "موافق" وأنا أفتحلك الإعدادات، فعّل GVR Agent وبعدها أكمل لوحدي.');
  }
}

export function OverlayChatBridge() {
  useEffect(() => {
    Native.bubbleShow().catch(() => {});
    const emitter = new EventEmitter(Native);
    const sub = emitter.addListener('onOverlayMessage', async (e: { text: string }) => {
      const text = (e?.text || '').trim();
      if (!text) return;
      if (pendingText && APPROVE.test(text)) {
        await toolScreenEnable();
        startWaiting();
        ask('فتحتلك صفحة إمكانية الوصول. فعّل GVR Agent هناك، وأنا هكمل أول ما تتفعّل.');
        return;
      }
      if (busy) {
        ask('لسه بفكر في الرسالة اللي قبلها، استنى ثانية.');
        return;
      }
      await runTurn(text);
    });
    return () => {
      sub.remove();
      stopWaiting();
    };
  }, []);
  return null;
}
