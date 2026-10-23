/** M8 — going INSIDE a real website: a project page → its releases → the jar link → download → it is a real jar. */
import { assert, fs, path, W, mission, resetMission, dispatchTool } from '../missions.test';
import { resetBrowseSession } from '../../src/tools';

mission('M8', 'browse: project page → releases → find and download the tool (real GitHub)', async () => {
  await resetMission();
  resetBrowseSession();
  const home = await dispatchTool('browse', 'open https://github.com/iBotPeaches/Apktool');
  console.log('--- open ---\n' + home.slice(0, 600));
  assert.match(home, /^\[browse\] .*Apktool/m, home.slice(0, 200));
  assert.match(home, /Links: \d+/);
  const rel = await dispatchTool('browse', 'links releases');
  console.log('--- links releases ---\n' + rel.slice(0, 300));
  assert.match(rel, /github\.com\/iBotPeaches\/Apktool\/releases/, rel);
  const page = await dispatchTool('browse', 'open https://github.com/iBotPeaches/Apktool/releases');
  assert.match(page, /^\[browse\] .*[Rr]elease/m, page.slice(0, 200));
  const jars = await dispatchTool('browse', 'links .jar');
  console.log('--- jar links ---\n' + jars.slice(0, 500));
  const n = /\[(\d+)\] [^\n]*\.jar \(/.exec(jars)?.[1];
  assert.ok(n, `the latest release's jar must be listed:\n${jars.slice(0, 400)}`);
  const dl = await dispatchTool('browse', `download ${n}`);
  console.log('--- download ---\n' + dl);
  assert.doesNotMatch(dl, /Tool error/, dl);
  const jar = fs.readdirSync(W).find((f: string) => f.endsWith('.jar'));
  assert.ok(jar, `the downloaded jar must be in the workspace: ${dl}`);
  const size = fs.statSync(path.join(W, jar)).size;
  assert.ok(size > 1_000_000, `jar too small: ${size}`);
  assert.equal(fs.readFileSync(path.join(W, jar)).subarray(0, 2).toString('latin1'), 'PK', 'a jar is a zip');
});
