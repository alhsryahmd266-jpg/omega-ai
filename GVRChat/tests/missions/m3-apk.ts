/**
 * M3 — the request from the video, finished for real, on a complex APK (the GVR app itself: React Native, Hermes,
 * 1,163 files, native llama.rn libs):
 *   turn 1  "فكك الملف ده …"   → the router unpacks it by itself (no model needed, real path)
 *   turn 2  add a feature      → decode → inject smali (Log on start) → add a permission → rename the app
 *                                 (one edit fails on purpose and is repaired from the hint) → build → sign → verify
 *                                 → decode the result again → deliver with the installer.
 * M3B     the lighter route when apktool cannot be used: unpack → change assets → pack → sign.
 */
import { assert, fs, path, W, ROOT, OUT, mission, ask, call, full, lastFull, toolsUsed, attachmentFor, sh, resetMission, toolOutcome, Intent } from '../missions.test';

const APK_SRC = '/tmp/apkt/test.apk';
const stage = (): void => {
  fs.mkdirSync(path.join(W, 'uploads'), { recursive: true });
  fs.copyFileSync(APK_SRC, path.join(W, 'uploads', 'app-release_3.apk'));
  for (const j of ['uber-apk-signer.jar', 'apktool.jar']) {                       // cached download: speeds the run up
    const cached = `/home/claude/gvr_tools/${j}`;
    if (fs.existsSync(cached)) fs.copyFileSync(cached, path.join(ROOT, 'opt', j));
  }
};
const idx = (t: string, nth = 0) => toolsUsed.map((x, i) => (x === t ? i : -1)).filter(i => i >= 0)[nth];
const result = (t: string, nth = 0) => full[idx(t, nth)];

mission('M3', 'complex APK: unpack (router) → add a feature → rebuild → sign → verify → install', async () => {
  await resetMission();
  stage();
  const IN = '/workspace/uploads/app-release_3.apk';

  // ── turn 1: exactly the message from the video ───────────────────────────
  const t1 = await ask('فكك الملف ده بالترمنال واستخدم الادوات اللازمه لتعديل الملف', [
    'فكّيت الملف في /workspace/apk_work/app-release_3 وجواه الـ dex والـ assets والمكتبات. قولّي تحب أعدّل إيه.',
  ], attachmentFor('app-release (3).apk'));
  assert.equal(toolsUsed[0], 'apk_tool', 'the router must unpack by itself');
  assert.match(full[0], /Unpacked 1163 files to \/workspace\/apk_work\/app-release_3/);
  assert.ok(fs.existsSync(path.join(W, 'apk_work/app-release_3/AndroidManifest.xml')));
  assert.equal(t1.modelCalls, 1, 'the model is only needed to talk, not to unpack');
  assert.ok(!/هل تريد/.test(t1.res.answer));
  const stepsAfterTurn1 = full.length;

  // ── turn 2: add a feature ────────────────────────────────────────────────
  const D = '/workspace/apk_work/decoded';
  const STR = `${D}/resources/package_1/res/values/strings.xml`;
  const { res } = await ask('ممتاز. ضيف له ميزة: يسجّل Log لما التطبيق يفتح، وصلاحية الاهتزاز، وغيّر اسمه لـ "GVR Agent+"، وبعدين جمّعه ووقّعه وتأكد إنه اتبنى صح وسلّمهولي', [
    call('apk_tool', `decode ${IN} ${D}`),
    call('search_code', `/^\\.class .*gvrchat.MainActivity;$/\n${D}\n*.smali`),
    (_p) => {
      const m = /^(\S+MainActivity\.smali):\d+:/m.exec(lastFull());
      assert.ok(m, `MainActivity.smali not found:\n${lastFull().slice(0, 300)}`);
      return call('terminal', `sed -n '/^\\.method protected onCreate(Landroid\\/os\\/Bundle;)V/,/^\\.end method/p' ${D}/${m![1]}`);
    },
    (_p, rest) => {
      const method = lastFull();
      const loc = /\.locals (\d+)/.exec(method);
      assert.ok(loc, `no .locals in:\n${method}`);
      const locals = Math.max(Number(loc![1]), 2);
      const file = /(smali\S*MainActivity\.smali)/.exec(full.find(r => /MainActivity\.smali:\d+:/.test(r))!)![1];
      // next steps: manifest check → manifest edit → strings (first attempt is wrong) → repair → build …
      rest.unshift(
        call('terminal', `grep -c 'android.permission.VIBRATE' ${D}/AndroidManifest.xml || true`),
        (_p2, rest2) => {
          const perm = lastFull().trim() === '0' ? 'android.permission.VIBRATE' : 'android.permission.READ_CALENDAR';
          rest2.unshift(
            // wrong on purpose: the real value is "GVRChat" → the edit must fail, change nothing, and point at the line
            call('edit_file', `${STR}\n<<<<<<< SEARCH\n  <string name="app_name">GVR Chat</string>\n=======\n  <string name="app_name">GVR Agent+</string>\n>>>>>>> REPLACE`),
            (_p3, rest3) => {
              assert.equal(toolOutcome(lastFull()), 'error');
              assert.match(lastFull(), /nothing was changed/i);
              const near = /\n\d+\| (.*app_name.*)\n/.exec(lastFull());
              assert.ok(near, `no closest-line hint:\n${lastFull()}`);
              rest3.unshift(
                call('apk_tool', `build ${D} /workspace/out/gvr-feature.apk`),
                call('apk_tool', `info /workspace/out/gvr-feature.apk`),
                call('apk_tool', `decode /workspace/out/gvr-feature.apk /workspace/apk_work/verify`),
                call('terminal', `cd /workspace/apk_work/verify && grep -rn "feature added by GVR agent" smali* | head -2; grep -n "${perm}" AndroidManifest.xml; grep -n 'name="app_name"' resources/package_1/res/values/strings.xml`),
                call('deliver_file', '/workspace/out/gvr-feature.apk | install'),
                'تمّ: ضفت تسجيل Log عند فتح التطبيق وصلاحية الاهتزاز، غيّرت الاسم لـ GVR Agent+، جمّعته ووقّعته وتأكدت من النتيجة، وفتحتلك المثبّت.',
              );
              return call('edit_file', `${STR}\n<<<<<<< SEARCH\n${near![1]}\n=======\n  <string name="app_name">GVR Agent+</string>\n>>>>>>> REPLACE`);
            },
          );
          // the decoder's manifest is CRLF with a space before "/>" — the exact bytes a real agent would read back first
          return call('edit_file', `${D}/AndroidManifest.xml\n<<<<<<< SEARCH\r\n  <uses-permission android:name="android.permission.CAMERA" />\r\n=======\r\n  <uses-permission android:name="android.permission.CAMERA" />\r\n  <uses-permission android:name="${perm}" />\r\n>>>>>>> REPLACE`);
        },
      );
      return call('edit_file', `${D}/${file}\n<<<<<<< SEARCH\n.method protected onCreate(Landroid/os/Bundle;)V\n    .locals ${loc![1]}\n\n    .line 18\n=======\n.method protected onCreate(Landroid/os/Bundle;)V\n    .locals ${locals}\n\n    const-string v0, "GVR"\n\n    const-string v1, "feature added by GVR agent: onCreate reached"\n\n    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I\n\n    .line 18\n>>>>>>> REPLACE`);
    },
  ]);

  // ── what the agent saw ───────────────────────────────────────────────────
  assert.match(result('apk_tool', 1), /Decoded to \/workspace\/apk_work\/decoded/);
  const smaliEdit = full.find(r => /^Edited .*MainActivity\.smali/.test(r))!;
  assert.match(smaliEdit, /@@ line \d+ @@/);
  assert.match(smaliEdit, /\+ {5}const-string v1, "feature added by GVR agent/);  // diff format: "+ " prefix + 4-space smali indent
  const failedEdit = full.find(r => /^Edit failed/.test(r))!;
  assert.match(failedEdit, /Closest place/);
  const build = full.find(r => /^Packed .*files|Result: \/workspace\/out\/gvr-feature\.apk/m.test(r))!;
  assert.match(build, /Result: \/workspace\/out\/gvr-feature\.apk/);
  assert.match(build, /Zip:\s+aligned OK/);
  assert.match(build, /Signed: debug key \(v1\+v2\+v3 verified in the file\)/);
  assert.match(build, /DIFFERENT|different signature|uninstall the original/i);
  const info = full.find(r => /^APK: \/workspace\/out\/gvr-feature\.apk/.test(r))!;
  assert.match(info, /signature: signed \(APK Signing Block, v2\/v3\)/);
  const verifyOut = full.find(r => /feature added by GVR agent/.test(r) && !/^Edited/.test(r))!;
  assert.match(verifyOut, /smali\S*\/MainActivity\.smali:\d+:\s+const-string v1, "feature added by GVR agent/);
  assert.match(verifyOut, /android\.permission\.(VIBRATE|READ_CALENDAR)/);
  assert.match(verifyOut, /name="app_name">GVR Agent\+</);

  // ── independent verification (not through the agent) ─────────────────────
  const out = path.join(W, 'out/gvr-feature.apk');
  assert.equal(sh(`python3 -c "import zipfile; z=zipfile.ZipFile('${out}'); print(z.testzip())"`), 'None');
  const verify = sh(`java -jar ${ROOT}/opt/uber-apk-signer.jar -a ${out} -y 2>&1 | grep -E "verified|ERROR|WARNING" | head -6`);
  assert.match(verify, /\[v2, v3\]|v2/, verify);
  assert.doesNotMatch(verify, /ERROR|invalid/i);
  // the new feature is REALLY compiled into the dex
  const dex = sh(`for d in classes.dex classes2.dex; do unzip -p ${out} $d | strings | grep -c "feature added by GVR agent"; done | paste -sd+ | bc`);
  assert.ok(Number(dex) >= 1, 'the Log string must be inside a dex file');
  // everything we did not touch is byte-identical to the original
  const same = sh(`python3 - <<'EOF'
import zipfile, hashlib
a = zipfile.ZipFile('${APK_SRC}'); b = zipfile.ZipFile('${out}')
for n in ('assets/index.android.bundle', 'lib/arm64-v8a/librnllama_v8_2_dotprod.so', 'assets/bash'):
    print(n, hashlib.md5(a.read(n)).hexdigest() == hashlib.md5(b.read(n)).hexdigest())
EOF`);
  assert.doesNotMatch(same, /False/, same);
  assert.equal((same.match(/True/g) || []).length, 3);
  // delivered: public copy + the Android package installer was opened
  assert.ok(fs.existsSync(path.join(OUT, 'gvr-feature.apk')));
  assert.equal(Intent.calls.at(-1)?.action, 'android.intent.action.VIEW');
  assert.equal(Intent.calls.at(-1)?.params.type, 'application/vnd.android.package-archive');
  assert.match(res.answer, /تمّ/);
  assert.ok(stepsAfterTurn1 < full.length);
});

mission('M3B', 'APK without apktool: unpack → change assets/config → add a file → pack → sign', async () => {
  await resetMission();
  stage();
  const IN = '/workspace/uploads/app-release_3.apk';
  const U = '/workspace/apk_work/plain';
  const { res } = await ask('عدّل إعدادات الـ APK ده من غير ما تفك الموارد: غيّر الإصدار في ملف الإعدادات وضيف ملف feature.json، ووقّعه', [
    call('apk_tool', `unpack ${IN} ${U}`),
    call('terminal', `head -c 220 ${U}/assets/app.config`),
    call('edit_file', `${U}/assets/app.config\n<<<<<<< SEARCH\n"version":"2.0.0"\n=======\n"version":"2.1.0-agent"\n>>>>>>> REPLACE`),
    call('write_file', `${U}/assets/feature.json\n{"feature":"agent-added","enabled":true}\n`),
    call('apk_tool', `pack ${U} /workspace/out/plain-mod.apk`),
    call('terminal', `cd /workspace/out && unzip -p plain-mod.apk assets/app.config | head -c 120; echo; unzip -p plain-mod.apk assets/feature.json`),
    call('deliver_file', '/workspace/out/plain-mod.apk'),
    'اتعدّل الإصدار وضفت feature.json وجمّعته ووقّعته.',
  ]);
  assert.match(full[0], /Unpacked 1163 files/);
  assert.match(result('apk_tool', 1), /Signed: debug key \(v1\+v2\+v3 verified in the file\)/);
  assert.match(result('apk_tool', 1), /Zip:\s+aligned OK/);
  const check = full.find(r => /agent-added/.test(r))!;
  assert.match(check, /"version":"2\.1\.0-agent"/);
  const out = path.join(W, 'out/plain-mod.apk');
  assert.match(sh(`java -jar ${ROOT}/opt/uber-apk-signer.jar -a ${out} -y 2>&1 | grep -E "verified" | head -2`), /verified/);
  assert.ok(fs.existsSync(path.join(OUT, 'plain-mod.apk')));
  assert.match(res.answer, /وقّعته/);
});
