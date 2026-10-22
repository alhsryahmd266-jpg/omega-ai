import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { GVR_APK_PY, GVR_APK_VERSION } from '../src/gvrApkScript';
import { toBase64 } from '../src/agentLogic';

let pass = 0;
let fail = 0;
function t(name: string, fn: () => void): void {
  try { fn(); pass++; } catch (e) { fail++; console.error(`✗ ${name}\n    ${(e as Error).message.split('\n').join('\n    ')}`); }
}
const root = join(__dirname, '..');
const app = JSON.parse(readFileSync(join(root, 'app.json'), 'utf8')).expo;
const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));

t('permissions.ts APP_ID matches android.package', () => {
  const src = readFileSync(join(root, 'src/permissions.ts'), 'utf8');
  assert.equal(/APP_ID = '([^']+)'/.exec(src)?.[1], app.android.package);
});
t('manifest declares what the new tools need', () => {
  for (const p of ['REQUEST_INSTALL_PACKAGES', 'REQUEST_IGNORE_BATTERY_OPTIMIZATIONS', 'WAKE_LOCK', 'FOREGROUND_SERVICE', 'MANAGE_EXTERNAL_STORAGE']) {
    assert.ok(app.android.permissions.includes(`android.permission.${p}`), p);
  }
});
t('native-library plugin is registered and exists', () => {
  assert.ok(app.plugins.includes('./plugins/withNativeLibs'));
  assert.ok(existsSync(join(root, 'plugins/withNativeLibs.js')));
});
t('dependencies for the new tools are installed', () => {
  for (const d of ['expo-sharing', 'expo-keep-awake', 'expo-intent-launcher']) assert.ok(pkg.dependencies[d], d);
});
t('versionCode was bumped so the new build installs over the old one', () => assert.ok(app.android.versionCode >= 8));
t('embedded gvr-apk helper equals scripts/gvr_apk.py and is ASCII', () => {
  const file = readFileSync(join(root, 'scripts/gvr_apk.py'), 'utf8');
  assert.equal(GVR_APK_PY, file);
  assert.match(file, new RegExp(`^GVR_APK_VERSION = ${GVR_APK_VERSION}$`, 'm'));
  assert.ok(/^[\x00-\x7f]*$/.test(file));
});
t('toBase64 matches Node for the helper script and edge lengths', () => {
  for (const s of ['', 'a', 'ab', 'abc', 'abcd', GVR_APK_PY]) assert.equal(toBase64(s), Buffer.from(s, 'latin1').toString('base64'));
  assert.throws(() => toBase64('عربي'));
});
t('every Kotlin function the JS wrapper calls exists', () => {
  const kt = readFileSync(join(root, 'modules/terminal/android/src/main/java/expo/modules/terminal/TerminalModule.kt'), 'utf8');
  for (const fn of ['cancelRunning', 'beginJob', 'endJob']) assert.ok(kt.includes(`AsyncFunction("${fn}")`), fn);
  assert.equal((kt.match(/:\/workspace"/g) || []).length, 2, 'workspace bind in both proot builders');
});

console.log(`config: ${pass} passed, ${fail} failed`);
if (fail) process.exit(1);
