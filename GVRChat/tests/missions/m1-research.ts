/** M1 — deep web research, then download a tool from its project page and USE it (with a failed first source). */
import { assert, fs, path, W, mission, ask, call, full, lastFull, toolsUsed, resetMission, toolOutcome } from '../missions.test';

const link = (u: string, title: string, snip: string) =>
  `<div class="result"><h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=${encodeURIComponent(u)}&amp;rut=1">${title}</a></h2><a class="result__snippet" href="#">${snip}</a></div>`;
const DDG = `<html><body>${[
  link('https://github.com/iBotPeaches/Apktool', 'Apktool - A tool for reverse engineering Android apk files', 'Decode resources and rebuild them'),
  link('https://github.com/patrickfav/uber-apk-signer', 'uber-apk-signer', 'Sign, zipalign and verify APKs'),
  link('https://pypi.org/project/requests/', 'requests · PyPI', 'Python HTTP for Humans'),
].join('')}</body></html>`;

/** Only the search ENGINE is replaced (the sandbox blocks DuckDuckGo); every result page is fetched for real. */
function bridgeSearchEngine(): void {
  const real = globalThis.fetch.bind(globalThis);
  (globalThis as any).fetch = async (input: any, init?: any) => {
    const url = typeof input === 'string' ? input : input.url;
    if (/duckduckgo\.com/.test(url)) return new Response(DDG, { status: 200, headers: { 'content-type': 'text/html; charset=utf-8' } });
    return real(input, init);
  };
}

mission('M1', 'deep web research → download a tool → use it', async () => {
  await resetMission();
  bridgeSearchEngine();

  const { res } = await ask('ابحث في الويب إزاي أفك ضغط APK بأداة apktool، وبعدين نزّلها واستخدمها وقولي النتيجة', [
    call('search', 'apktool decode rebuild apk resources smali'),
    // first source is wrong on purpose (a release that does not exist) → the model must try another source
    call('download_file', 'https://github.com/iBotPeaches/Apktool/releases/download/v0.0.0-nope/apktool_0.0.0.jar | /workspace/tools/bad.jar'),
    call('fetch_url', 'https://api.github.com/repos/iBotPeaches/Apktool/releases/latest'),
    (_p, rest) => {
      const m = /"browser_download_url"\s*:\s*"(https:[^"]+?\.jar)"/.exec(lastFull());
      if (m) return call('download_file', `${m[1]} | /workspace/tools/apktool.jar`);
      // The API refused (unauthenticated rate limit — it really happens): another source, the public releases page.
      rest.unshift(() => {
        const tag = /releases\/tag\/(v[\d.]+)/.exec(lastFull())?.[1];
        assert.ok(tag, `no release tag on the releases page:\n${lastFull().slice(0, 300)}`);
        return call('download_file', `https://github.com/iBotPeaches/Apktool/releases/download/${tag}/apktool_${tag!.slice(1)}.jar | /workspace/tools/apktool.jar`);
      });
      return call('fetch_url', 'https://github.com/iBotPeaches/Apktool/releases/latest');
    },
    call('terminal', 'java -jar /workspace/tools/apktool.jar --version'),
    call('terminal', 'java -jar /workspace/tools/apktool.jar --help 2>&1 | head -8'),
    'قريت عن apktool من صفحة المشروع، نزّلت آخر إصدار منه واشتغل.',
  ]);

  const resultOf = (tool: string, nth = 0): string => {
    const idx = toolsUsed.map((t, i) => (t === tool ? i : -1)).filter(i => i >= 0)[nth];
    assert.ok(idx !== undefined, `${tool} #${nth} was never called (called: ${toolsUsed.join(', ')})`);
    return full[idx];
  };
  // 1) the search returned the CONTENT of the pages, not just titles (the router already ran one before the model)
  const search = resultOf('search');
  assert.match(search, /Content of the top results/);
  assert.match(search, /reverse engineering/i);
  assert.ok(search.split('▸').length - 1 >= 2, 'at least two pages were read');
  // 2) the wrong source failed and was recognised as a failure
  assert.match(resultOf('download_file', 0), /Download failed: HTTP 404/);
  assert.equal(toolOutcome(resultOf('download_file', 0)), 'error');
  // 3) the real download worked and is a real jar
  const dl = full[toolsUsed.lastIndexOf('download_file')];
  assert.match(dl, /apktool\.jar/);
  const jar = path.join(W, 'tools', 'apktool.jar');
  assert.ok(fs.statSync(jar).size > 5_000_000, 'jar too small');
  assert.equal(fs.readFileSync(jar).subarray(0, 2).toString('latin1'), 'PK');
  // 4) the tool was USED
  assert.match(full[full.length - 2], /^\d+\.\d+\.\d+/m);
  assert.match(full[full.length - 1], /usage|apktool|decode/i);
  assert.match(res.answer, /apktool/);
});
