/** `npm test` — runs every test file in order; the first failing file stops the run with exit code 1. */
(async () => {
  for (const f of ['agentLogic', 'accel', 'router', 'config', 'engine', 'tools']) {
    const mod = (await import(`./${f}.test`)) as { done?: Promise<void> };
    if (mod.done) await mod.done;
  }
})().catch(e => { console.error(e); process.exit(1); });
