import assert from 'node:assert/strict';
import { autoRoute, type RouteAttachment } from '../src/router';

let pass = 0;
let fail = 0;
function t(name: string, fn: () => void): void {
  try { fn(); pass++; } catch (e) { fail++; console.error(`✗ ${name}\n    ${(e as Error).message.split('\n').join('\n    ')}`); }
}

const apk: RouteAttachment = {
  name: 'app-release (3).apk', kind: 'file',
  path: '/data/user/0/com.ahmedxg.gvrchat/files/workspace/uploads/app-release_3.apk',
  linuxPath: '/workspace/uploads/app-release_3.apk',
};
const img: RouteAttachment = { name: 'shot.png', kind: 'image', path: '/data/user/0/x/files/workspace/uploads/shot.png', linuxPath: '/workspace/uploads/shot.png' };
const zip: RouteAttachment = { name: 'data.zip', kind: 'file', path: '/data/user/0/x/files/workspace/uploads/data.zip', linuxPath: '/workspace/uploads/data.zip' };
const tools = (hits: { tool: string }[]) => hits.map(h => h.tool);

/* ── existing behaviour must not change ── */
t('url → fetch_url', () => assert.deepEqual(tools(autoRoute('شوفلي https://example.com/a')), ['fetch_url']));
t('search verb → search', () => assert.deepEqual(tools(autoRoute('ابحث عن سعر الذهب اليوم')), ['search']));
t('math → calc', () => assert.deepEqual(autoRoute('احسب 2+2*3').map(h => h.tool), ['calc']));
t('install a single tool → install_tool', () => {
  const h = autoRoute('ثبت ffmpeg');
  assert.equal(h[0]?.tool, 'install_tool');
});
t('plain chat → nothing', () => assert.deepEqual(autoRoute('صباح الخير'), []));
t('fenced code + run verb → terminal', () => {
  const h = autoRoute('نفذ\n```sh\nls -la\n```');
  assert.equal(h[0]?.tool, 'terminal');
  assert.equal(h[0]?.arg, 'ls -la');
});

/* ── "run X and tell me …": only X is a command ── */
t('run + prose tail → only the command runs', () => {
  assert.equal(autoRoute('شغل ls وقولي الملفات اللي موجودة')[0]?.arg, 'ls');
  assert.equal(autoRoute('نفذ pwd واعرضلي المسار')[0]?.arg, 'pwd');
  assert.equal(autoRoute('run uname -a and then tell me the kernel')[0]?.arg, 'uname -a');
  assert.equal(autoRoute('شغل echo hello وبعدين شغل echo world')[0]?.arg, 'echo hello');
});
t('run + plain command is unchanged', () => {
  assert.equal(autoRoute('شغل: df -h')[0]?.arg, 'df -h');
  assert.equal(autoRoute('run ls -la /workspace')[0]?.arg, 'ls -la /workspace');
});

/* ── the request from the video ── */
const VIDEO_MSG = 'فكك الملف ده بالترمنال واستخدم الادوات اللازمه لتعديل الملف';
t('video message + APK + Linux → apk_tool unpack with the REAL path', () => {
  const h = autoRoute(VIDEO_MSG, { hasAttachment: true, attachment: apk, linuxReady: true });
  assert.equal(h.length, 1);
  assert.equal(h[0].tool, 'apk_tool');
  assert.equal(h[0].arg, 'unpack /workspace/uploads/app-release_3.apk /workspace/apk_work/app-release_3');
});
t('video message + APK without Linux → terminal unzip on the Android path', () => {
  const h = autoRoute(VIDEO_MSG, { hasAttachment: true, attachment: apk, linuxReady: false });
  assert.equal(h[0]?.tool, 'terminal');
  assert.match(h[0].arg, /unzip -o -q '\/data\/user\/0\/com\.ahmedxg\.gvrchat\/files\/workspace\/uploads\/app-release_3\.apk'/);
  assert.match(h[0].arg, /-d '\/data\/user\/0\/com\.ahmedxg\.gvrchat\/files\/workspace\/apk_work\/app-release_3'/);
});
t('"unzip it" with a zip → unpacked/, English too', () => {
  const h = autoRoute('please unzip this file', { hasAttachment: true, attachment: zip, linuxReady: true });
  assert.equal(h[0]?.tool, 'terminal');
  assert.match(h[0].arg, /\/workspace\/unpacked\/data/);
});
t('a how-to question with an attachment is NOT executed', () => {
  assert.deepEqual(autoRoute('ازاي افك ضغط الملف ده؟', { hasAttachment: true, attachment: apk, linuxReady: true }), []);
  assert.deepEqual(autoRoute('how do I unzip this?', { hasAttachment: true, attachment: zip, linuxReady: true }), []);
});
t('no attachment → the unpack route stays silent', () => {
  assert.deepEqual(autoRoute(VIDEO_MSG, { linuxReady: true }), []);
});

/* ── images ── */
t('image without a vision model → view_image (OCR)', () => {
  const h = autoRoute('ايه المكتوب في الصورة دي', { hasAttachment: true, attachment: img, linuxReady: true, visionReady: false });
  assert.equal(h[0]?.tool, 'view_image');
  assert.equal(h[0].arg, '/workspace/uploads/shot.png');
});
t('image with a vision model → the model looks at it itself', () => {
  assert.deepEqual(autoRoute('ايه المكتوب في الصورة دي', { hasAttachment: true, attachment: img, linuxReady: true, visionReady: true }), []);
});

console.log(`router: ${pass} passed, ${fail} failed`);
if (fail) process.exit(1);
