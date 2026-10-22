/**
 * M2 — build a complex app from nothing: TypeScript + Node HTTP API with a store, tests and a build step.
 * Two things go wrong on purpose and the model has to recover (like a real run):
 *   1) the dependency it first picks does not exist → alternative: Node's built-in http, no dependency
 *   2) the first server.ts has a type error → read the compiler output, fix it with edit_file, build again
 */
import { assert, fs, path, W, OUT, mission, ask, call, full, lastFull, toolsUsed, sh, resetMission, toolOutcome, Terminal } from '../missions.test';

const PKG_FIX = (cmd: string) => cmd;

const STORE = `export interface Item { id: number; name: string; qty: number }

export class InventoryStore {
  private items = new Map<number, Item>();
  private nextId = 1;

  add(name: string, qty: number): Item {
    if (!name.trim()) throw new Error('name is required');
    if (!Number.isInteger(qty) || qty < 0) throw new Error('qty must be a non-negative integer');
    const item: Item = { id: this.nextId++, name: name.trim(), qty };
    this.items.set(item.id, item);
    return item;
  }
  list(): Item[] { return [...this.items.values()]; }
  get(id: number): Item | undefined { return this.items.get(id); }
  remove(id: number): boolean { return this.items.delete(id); }
  totalQty(): number { return this.list().reduce((n, i) => n + i.qty, 0); }
}
`;

const SERVER = `import http from 'node:http';
import { InventoryStore } from './store';

export const store = new InventoryStore();
const port: number = process.env.PORT || 3187;

function send(res: http.ServerResponse, code: number, body: unknown): void {
  res.writeHead(code, { 'content-type': 'application/json' });
  res.end(JSON.stringify(body));
}

export const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost');
  if (req.method === 'GET' && url.pathname === '/health') return send(res, 200, { ok: true, total: store.totalQty() });
  if (req.method === 'GET' && url.pathname === '/items') return send(res, 200, store.list());
  if (req.method === 'POST' && url.pathname === '/items') {
    let body = '';
    req.on('data', c => { body += c; });
    req.on('end', () => {
      try { const { name, qty } = JSON.parse(body); send(res, 201, store.add(String(name), Number(qty))); }
      catch (e) { send(res, 400, { error: (e as Error).message }); }
    });
    return;
  }
  const m = /^\\/items\\/(\\d+)$/.exec(url.pathname);
  if (m && req.method === 'GET') { const it = store.get(Number(m[1])); return it ? send(res, 200, it) : send(res, 404, { error: 'not found' }); }
  if (m && req.method === 'DELETE') return send(res, store.remove(Number(m[1])) ? 200 : 404, { ok: true });
  send(res, 404, { error: 'no such route' });
});

if (require.main === module) server.listen(port, () => console.log('listening on ' + port));
`;

const TEST = `import { test } from 'node:test';
import assert from 'node:assert/strict';
import { InventoryStore } from './store';

test('adds and lists items', () => {
  const s = new InventoryStore();
  s.add('bolt', 5); s.add('nut', 7);
  assert.equal(s.list().length, 2);
  assert.equal(s.totalQty(), 12);
});
test('rejects bad input', () => {
  const s = new InventoryStore();
  assert.throws(() => s.add('  ', 1), /name is required/);
  assert.throws(() => s.add('x', -1), /non-negative/);
});
test('remove works', () => {
  const s = new InventoryStore();
  const a = s.add('a', 1);
  assert.equal(s.remove(a.id), true);
  assert.equal(s.remove(a.id), false);
});
`;

mission('M2', 'build a complex app: failed dependency → alternative, type error → fix, tests, run, zip, deliver', async () => {
  await resetMission();
  const P = '/workspace/inventory-api';

  const { res } = await ask('ابني لي تطبيق مخزون كامل: API بـ TypeScript على Node فيه مخزن بيانات وتحقق من المدخلات واختبارات وبناء، وشغّله وجرّبه وسلّمهولي ZIP', [
    call('terminal', `mkdir -p ${P}/src && cd ${P} && npm init -y >/dev/null && npm install express-gvr-no-such-package 2>&1 | tail -3`),
    // dependency does not exist → alternative: no framework at all, only Node's built-in http
    call('terminal', `cd ${P} && npm install --save-dev typescript@5 @types/node@22 2>&1 | tail -2 && npx tsc --version`),
    call('edit_file', `${P}/package.json\n<<<<<<< SEARCH\n  "scripts": {\n    "test": "echo \\"Error: no test specified\\" && exit 1"\n  },\n=======\n  "scripts": {\n    "build": "tsc",\n    "test": "npm run build && node --test dist/",\n    "start": "node dist/server.js"\n  },\n>>>>>>> REPLACE`),
    call('write_file', `${P}/tsconfig.json\n{ "compilerOptions": { "target": "ES2022", "module": "commonjs", "strict": true, "outDir": "dist", "rootDir": "src", "esModuleInterop": true, "skipLibCheck": true }, "include": ["src"] }\n`),
    call('write_file', `${P}/src/store.ts\n${STORE}`),
    call('write_file', `${P}/src/server.ts\n${SERVER}`),
    call('write_file', `${P}/src/store.test.ts\n${TEST}`),
    call('terminal', `cd ${P} && npx tsc 2>&1; echo "build-exit=$?"`),
    // type error in the compiler output → fix exactly that line
    (_p, rest) => {
      const out = lastFull();
      assert.match(out, /server\.ts\(\d+,\d+\): error TS2322/, `expected the type error, got:\n${out.slice(0, 400)}`);
      rest.unshift(call('terminal', `cd ${P} && npx tsc 2>&1; echo "build-exit=$?"`));
      return call('edit_file', `${P}/src/server.ts\n<<<<<<< SEARCH\nconst port: number = process.env.PORT || 3187;\n=======\nconst port: number = Number(process.env.PORT) || 3187;\n>>>>>>> REPLACE`);
    },
    call('terminal', `cd ${P} && npm test 2>&1 | tail -14`),
    // 3) Node 22 does not accept a folder after --test (a real gotcha): read the failure, fix the script, run again
    (_p, rest) => {
      const out = lastFull();
      if (/# pass 3/.test(out) && /# fail 0/.test(out)) return call('terminal', 'echo tests-passed-first-time');
      assert.match(out, /Cannot find module|test failed|ERR_TEST_FAILURE|# fail/i, `unexpected test output:\n${out.slice(0, 300)}`);
      rest.unshift(call('terminal', `cd ${P} && npm test 2>&1 | tail -14`));
      return call('edit_file', `${P}/package.json\n<<<<<<< SEARCH\n    "test": "npm run build && node --test dist/",\n=======\n    "test": "npm run build && node --test \\"dist/**/*.test.js\\"",\n>>>>>>> REPLACE`);
    },
    call('terminal', `cd ${P} && (PORT=3187 node dist/server.js & echo $! > /tmp/gvr_srv.pid; sleep 1.5; curl -s -X POST http://127.0.0.1:3187/items -H 'content-type: application/json' -d '{"name":"bolt","qty":5}'; echo; curl -s -X POST http://127.0.0.1:3187/items -d '{"name":"","qty":1}'; echo; curl -s http://127.0.0.1:3187/items; echo; curl -s http://127.0.0.1:3187/health; echo; kill $(cat /tmp/gvr_srv.pid))`),
    call('search_code', `InventoryStore\n${P}\n*.ts`),
    call('terminal', `cd /workspace && zip -r -q inventory-api.zip inventory-api -x 'inventory-api/node_modules/*' && unzip -l inventory-api.zip | tail -12`),
    call('deliver_file', '/workspace/inventory-api.zip'),
    'بنيت التطبيق: لقيت إن الحزمة اللي اخترتها مش موجودة فاستخدمت http المدمج، صلّحت خطأ النوع، الاختبارات نجحت، شغّلته وجرّبته وسلّمتلك الـ ZIP.',
  ]);

  const idx = (t: string, nth = 0) => toolsUsed.map((x, i) => (x === t ? i : -1)).filter(i => i >= 0)[nth];
  // 1) the failed dependency was seen as a failure (npm 404) — through the pipe the exit code is tail's, so check the text
  assert.match(full[0], /\b404\b/);
  // 2) the type error appeared, was fixed, and the second build is clean
  const builds = full.filter(r => /build-exit=/.test(r));
  assert.match(builds[0], /error TS2322/);
  assert.match(builds[0], /build-exit=2/);
  assert.match(builds[1], /^build-exit=0$/m);
  assert.ok(fs.existsSync(path.join(W, 'inventory-api/dist/server.js')));
  // 3) the first test run failed (folder after --test), the fixed run passes the app's 3 tests
  assert.ok(full.some(r => /Cannot find module|# fail [1-9]|ERR_TEST_FAILURE/.test(r)), 'the first `npm test` should have failed');
  const tests = full.filter(r => /# pass \d+/.test(r)).pop()!;
  assert.match(tests, /# pass 3/);
  assert.match(tests, /# fail 0/);
  // 4) the server really ran and answered
  const live = full.find(r => /"name":"bolt"/.test(r))!;
  assert.match(live, /"id":1,"name":"bolt","qty":5/);
  assert.match(live, /"error":"name is required"/);
  assert.match(live, /"ok":true,"total":5/);
  // 5) code search found the class in the project (and not in node_modules)
  const found = full[idx('search_code')];
  assert.match(found, /src\/store\.ts:\d+:.*class InventoryStore/);
  assert.match(found, /src\/server\.ts/);
  assert.doesNotMatch(found, /node_modules/);
  // 6) the zip has the sources, not node_modules, and it was delivered
  const zipList = sh(`unzip -l inventory-api.zip`, W);
  assert.match(zipList, /inventory-api\/src\/server\.ts/);
  assert.match(zipList, /inventory-api\/package\.json/);
  assert.doesNotMatch(zipList, /node_modules/);
  assert.ok(fs.existsSync(path.join(OUT, 'inventory-api.zip')));
  assert.match(res.answer, /بنيت التطبيق/);
  void PKG_FIX; void lastFull; void Terminal; void toolOutcome;
});
