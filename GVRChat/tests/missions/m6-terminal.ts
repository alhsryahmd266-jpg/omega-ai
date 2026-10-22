/** M6 — the terminal must behave like a real one: state between commands, big output, time limits, Stop. */
import { assert, fs, path, W, mission, ask, call, lastFull, full, dispatchTool, requestStop, toolOutcome, resetMission } from '../missions.test';

mission('M6', 'terminal: session state, big output, timeouts, Stop, real files', async () => {
  await resetMission();

  // The user asks for a job; the model drives it with several terminal calls (like Claude Code / a real shell).
  const { res } = await ask('جهّز لي بيئة شغل: اعمل فولدرات وسجّل متغيرات وبعدين اتأكد إن كل حاجة اشتغلت', [
    call('terminal', 'mkdir -p proj/src/deep && cd proj/src/deep && pwd'),
    call('terminal', 'pwd && export BUILD_TAG=v1.2.3 && export OUT_DIR=/workspace/proj/out && echo set'),
    call('terminal', 'echo "tag=$BUILD_TAG out=$OUT_DIR" && mkdir -p "$OUT_DIR" && pwd'),
    call('terminal', "cat > note.txt <<'EOF'\nسطر عربي\nline two\nEOF\nwc -l note.txt && python3 -c \"print(open('note.txt', encoding='utf-8').read().splitlines()[0])\""),
    call('terminal', 'seq 1 20000'),
    call('terminal', 'seq 1 20000; echo FAILED-AT-THE-END >&2; exit 3'),
    call('terminal', '#timeout=2\nsleep 6; echo never'),
    'تمام: كل حاجة اشتغلت.',
  ]);

  assert.match(full[0], /^\/workspace\/proj\/src\/deep/, 'cd must persist and be reported');
  assert.match(full[1], /^\/workspace\/proj\/src\/deep\nset/, 'next call starts where the last one ended');
  assert.match(full[2], /tag=v1\.2\.3 out=\/workspace\/proj\/out/, 'exports persist like in a real session');
  assert.match(full[3], /^2 note\.txt\nسطر عربي/m, 'heredoc + python + Arabic text work');
  // 20000 lines: the model gets the beginning AND the end, the whole output is saved in a file
  assert.match(full[4], /^1\n2\n3/);
  assert.match(full[4], /19999\n20000$/);                       // the END of the output is what the model sees last
  assert.match(full[4], /characters omitted/);
  assert.match(full[4], /\.last_output\.txt/);
  // the file always holds the LATEST long output (here: the failing run = 20000 numbers + the error line + the exit code)
  const saved = fs.readFileSync(path.join(W, '.last_output.txt'), 'utf8').trim().split('\n');
  assert.equal(saved.length, 20002);
  assert.equal(saved[0], '1');
  assert.equal(saved[saved.length - 1], '(exit code 3)');
  // a long output that FAILS must still show the failure (it is at the end)
  assert.equal(toolOutcome(full[5]), 'error');
  assert.match(full[5], /FAILED-AT-THE-END/);
  assert.match(full[5], /\(exit code 3\)$/);
  // timeout
  assert.match(full[6], /timed out after 2s/);
  assert.doesNotMatch(full[6], /never/);
  assert.match(res.answer, /كل حاجة اشتغلت/);
  assert.ok(fs.existsSync(path.join(W, 'proj/out')), 'directory created through $OUT_DIR');

  // Stop must kill a command that is RUNNING, quickly
  await resetMission();
  const started = Date.now();
  const running = dispatchTool('terminal', '#timeout=60\nsleep 30; echo done');
  setTimeout(() => requestStop(), 400);
  const out = await running;
  assert.ok(Date.now() - started < 5000, `Stop took ${Date.now() - started} ms`);
  assert.doesNotMatch(out, /done/);
  assert.match(out, /exit code/);
});
