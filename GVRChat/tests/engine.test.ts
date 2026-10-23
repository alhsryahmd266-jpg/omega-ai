/** Integration: the REAL engine + tools + router + memory with a scripted model (no phone needed). */
import './stubs/install';
import assert from 'node:assert/strict';
import { fresh, runner } from './harness';
import { calls as termCalls, state as term } from './stubs/terminal';
import { queue, seen } from './stubs/localLLM';
import AsyncStorage, { __dump } from './stubs/async-storage';
import { requestStop, runWithAttachment, type StepEvent } from '../src/gvrEngine';
import { currentMission } from '../src/agentMemory';
import { setAutonomy, setToolConfirmHandler, toolOutcome } from '../src/tools';

const { t, finish } = runner('engine');
const noop = (_e: StepEvent): void => {};
const VIDEO_MSG = 'فكك الملف ده بالترمنال واستخدم الادوات اللازمه لتعديل الملف';
const apk: any = {
  kind: 'file', name: 'app-release (3).apk', uri: 'file:///cache/DocumentPicker/x/app-release (3).apk',
  extractedContent: 'APK summary: package com.example', warnings: [],
  workPath: '/data/user/0/com.ahmedxg.gvrchat/files/workspace/uploads/app-release_3.apk',
  linuxPath: '/workspace/uploads/app-release_3.apk',
};
const lastUser = (call: any): string => {
  const m = call.messages[call.messages.length - 1];
  return typeof m.content === 'string' ? m.content : JSON.stringify(m.content);
};

export const done = (async () => {
  await t('video request: APK + "unpack it" → apk_tool runs with the REAL path and the model is told where the file is', async () => {
    await fresh();
    term.handler = c => (c.includes('GVR_APK_VERSION') ? '1' : c.includes("'unpack'") ? 'Unpacked 1163 files to /workspace/apk_work/app-release_3' : '');
    queue('فكّيت الملف: جواه 1163 ملف.');
    const res = await runWithAttachment(VIDEO_MSG, apk, noop);
    assert.equal(res.steps[0].tool, 'apk_tool');
    assert.match(res.steps[0].result, /Unpacked 1163 files/);
    assert.ok(termCalls.some(c => c.command.includes("'unpack' '/workspace/uploads/app-release_3.apk' '/workspace/apk_work/app-release_3'")), 'wrong unpack command');
    assert.match(lastUser(seen[0]), /saved at: \/workspace\/uploads\/app-release_3\.apk \(in Linux\) = \/data\/user\/0\//);
    assert.match(res.answer, /فكّيت/);
  });

  await t('the loop from the video: a command printed as text is RUN, and "continue?" never reaches the user', async () => {
    await fresh();
    term.handler = c => (c === 'mkdir reports && ls' ? 'reports' : '');
    queue('سننشئ الفولدر:\n\n```sh\nmkdir reports && ls\n```\n\nهل تريد متابعة هذه الخطوة؟', 'تم إنشاء الفولدر reports.');
    const res = await runWithAttachment('اعمل لي فولدر جديد اسمه reports', null, noop);
    assert.ok(termCalls.some(c => c.command === 'mkdir reports && ls'));
    assert.equal(res.steps.length, 1);
    assert.match(res.answer, /reports/);
    assert.ok(!res.answer.includes('هل تريد'), res.answer);
  });

  await t('"do you want me to continue?" with no command → the engine answers "yes" itself and the tool runs', async () => {
    await fresh();
    term.handler = () => '';
    queue('أقدر أنشئه. هل تريد أن أقوم بإنشائه؟', '<tool name="terminal">mkdir build</tool>', 'تم إنشاء build.');
    const res = await runWithAttachment('اعمل لي فولدر جديد اسمه build', null, noop);
    assert.equal(seen.length, 3);
    assert.match(JSON.stringify(seen[1].messages), /you have my permission/);
    assert.equal(res.steps[0].arg, 'mkdir build');
    assert.match(res.answer, /build/);
  });

  await t('a how-to question is NOT executed even if the model shows a command', async () => {
    await fresh();
    queue('تقدر تستخدم:\n```sh\nunzip a.zip\n```');
    const res = await runWithAttachment('ازاي افك ضغط ملف zip في لينكس؟', null, noop);
    assert.equal(termCalls.length, 0);
    assert.equal(res.steps.length, 0);
    assert.match(res.answer, /unzip a\.zip/);
  });

  await t('ask mode: the model is not rescued and nothing runs without an answer', async () => {
    await fresh();
    setAutonomy({ mode: 'ask' });
    queue('```sh\nmkdir reports\n```\nهل تريد المتابعة؟');
    const res = await runWithAttachment('اعمل لي فولدر جديد اسمه reports', null, noop);
    assert.equal(termCalls.length, 0);
    assert.equal(res.steps.length, 0);
  });

  await t('Stop: a half-written tool call never runs, the running command is killed, the job is paused', async () => {
    await fresh();
    let ran = false;
    term.handler = () => { ran = true; return 'x'; };
    queue(() => { requestStop(); return '<tool name="terminal">mkdir stopme</tool>'; });
    const res = await runWithAttachment('اعمل لي فولدر اسمه stopme', null, noop);
    assert.equal(ran, false);
    assert.match(res.answer, /اتوقّفت/);
    assert.ok(term.cancelled >= 1);
  });

  await t('Stop while an approval card is waiting dismisses it (denied) and nothing runs', async () => {
    await fresh();
    setAutonomy({ mode: 'ask' });
    let cancel: (() => void) | null = null;
    let release: ((d: 'once' | 'always' | 'deny') => void) | null = null;
    setToolConfirmHandler(() => new Promise(r => { release = r; }), () => { release?.('deny'); cancel = null; });
    queue('<tool name="terminal">mkdir waiting</tool>', 'تمام.');
    const p = runWithAttachment('اعمل لي فولدر اسمه waiting', null, noop);
    await new Promise(r => setTimeout(r, 30));
    assert.ok(release, 'the approval card should be showing');
    requestStop();
    const res = await p;
    assert.equal(termCalls.length, 0);
    assert.ok(cancel === null);
    assert.ok(res.steps.every(s => toolOutcome(s.result) === 'denied'));
  });

  await t('journal: steps are recorded, the job finishes as done and leaves an episode in memory', async () => {
    await fresh();
    term.handler = c => c;
    queue('<tool name="terminal">mkdir a</tool>', '<tool name="terminal">mkdir b</tool>', 'خلصت الاتنين.');
    await runWithAttachment('اعمل فولدر a وبعدين فولدر b', null, noop);
    const m = currentMission();
    assert.equal(m?.steps.length, 2);
    assert.equal(m?.status, 'done');
    assert.ok(Object.values(__dump()).some(v => v.includes('"ep:')), 'episode should be saved');
  });

  await t('resume: after Stop, "كمل" gets the work log of the unfinished job at the END of the prompt', async () => {
    await fresh();
    term.handler = c => c;
    queue('<tool name="terminal">mkdir part-a</tool>', () => { requestStop(); return 'x'; });
    await runWithAttachment('اعمل فولدر part-a وبعدها كمّل الباقي', null, noop);
    assert.equal(currentMission()?.status, 'paused');
    queue('تمام، كملت.');
    const before = seen.length;
    await runWithAttachment('كمل', null, noop);
    const prompt = lastUser(seen[before]);
    assert.match(prompt, /Work log/);
    assert.match(prompt, /mkdir part-a/);
    assert.match(prompt, /The user approved/);
    assert.ok(prompt.indexOf('كمل') < prompt.indexOf('Work log'), 'the log must come after the question (cache-friendly)');
  });

  await t('an unrelated question does not receive the old job log', async () => {
    await fresh();
    term.handler = c => c;
    queue('<tool name="terminal">mkdir part-a</tool>', () => { requestStop(); return 'x'; });
    await runWithAttachment('اعمل فولدر part-a', null, noop);
    queue('أهلاً!');
    const before = seen.length;
    await runWithAttachment('اكتب لي جملة عن البحر', null, noop);
    assert.ok(!lastUser(seen[before]).includes('Work log'));
  });

  await t('repeat guard: the same call again and again is stopped after 4 and the model must answer', async () => {
    await fresh();
    term.handler = () => 'same output';
    for (let i = 0; i < 4; i++) queue('<tool name="terminal">cat file.txt</tool>');
    queue('مش قادر أتقدم أكتر من كده.');
    const res = await runWithAttachment('اعمل لي تقرير من الملف', null, noop);
    assert.equal(termCalls.length, 4);
    assert.match(res.answer, /مش قادر/);
  });

  await t('error streak: five failing steps in a row stop the job with an explanation', async () => {
    await fresh();
    term.handler = () => 'it failed\n(exit code 1)';
    for (let i = 1; i <= 5; i++) queue(`<tool name="terminal">cmd${i}</tool>`);
    queue('فشلت 5 مرات ورا بعض، المشكلة في الأذونات.');
    const res = await runWithAttachment('حاول توصل لنتيجة مستحيلة', null, noop);
    assert.equal(termCalls.length, 5);
    assert.match(res.answer, /فشلت 5 مرات/);
  });

  await t('long job: more than 5 rounds are allowed in autonomous mode', async () => {
    await fresh();
    term.handler = c => c;
    for (let i = 1; i <= 8; i++) queue(`<tool name="terminal">mkdir job${i}</tool>`);
    queue('خلصت التمانية.');
    const res = await runWithAttachment('اعمل لي ٨ خطوات متتالية', null, noop);
    assert.equal(res.steps.length, 8);
    assert.match(res.answer, /خلصت/);
  });

  await t('conversation is saved for the next app start', async () => {
    await fresh();
    queue('أهلاً بيك.');
    await runWithAttachment('اكتب لي جملة عن البحر', null, noop);
    await new Promise(r => setTimeout(r, 10));
    const saved = JSON.parse((await AsyncStorage.getItem('gvr_history_v1')) || '[]');
    assert.equal(saved.length, 2);
    assert.equal(saved[1].content, 'أهلاً بيك.');
  });


  await t('greeting: answered locally, the model is not called', async () => {
    await fresh();
    const res = await runWithAttachment('مرحبا', null, noop);
    assert.equal(seen.length, 0, 'a greeting must not reach the model');
    assert.match(res.answer, /GVR/);
  });

  await t('"هل يمكنك فتح تريمنال": the terminal opens locally, no model call', async () => {
    await fresh();
    term.handler = () => '/workspace';
    const res = await runWithAttachment('هل يمكنك فتح تريمنال', null, noop);
    assert.equal(seen.length, 0, 'opening the terminal must not wait for the model');
    assert.match(res.answer, /فتحت الترمنال/);
  });

  await t('the instructions sent to the model stay small (phone CPU prefill budget)', async () => {
    await fresh();
    queue('تمام');
    await runWithAttachment('ازاي اعمل فولدر في لينكس؟', null, noop);
    const sys = seen[0].messages[0].content as string;
    assert.ok(sys.length <= 3300, `system prompt is ${sys.length} chars`);
  });

  finish();
})();
