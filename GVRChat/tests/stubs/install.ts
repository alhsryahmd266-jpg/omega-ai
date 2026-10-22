/**
 * Test harness: lets the REAL src/ files (tools, engine, router, memory, permissions …) run in plain Node by
 * swapping the few modules that need a phone (react-native, expo-*, the native terminal, the llama.rn model)
 * for the stubs in this folder. Import this file FIRST in a test.
 */
import Module from 'node:module';
import path from 'node:path';

const here = __dirname;
const root = path.resolve(here, '..', '..');

/** GVR_TEST_REAL=1 (mission tests): a real folder on disk + a real bash instead of the in-memory stubs. */
const REAL = process.env.GVR_TEST_REAL === '1';

const BY_NAME: Record<string, string> = {
  'react-native': path.join(here, 'react-native.ts'),
  '@react-native-async-storage/async-storage': path.join(here, 'async-storage.ts'),
  'expo-file-system': path.join(here, REAL ? 'expo-file-system-disk.ts' : 'expo-file-system.ts'),
  'expo-clipboard': path.join(here, 'expo-clipboard.ts'),
  'expo-sharing': path.join(here, 'expo-sharing.ts'),
  'expo-intent-launcher': path.join(here, 'expo-intent-launcher.ts'),
};
const BY_PATH: Record<string, string> = {
  [path.join(root, 'src', 'localLLM')]: path.join(here, 'localLLM.ts'),
  [path.join(root, 'modules', 'terminal', 'src')]: path.join(here, REAL ? 'terminal-real.ts' : 'terminal.ts'),
  [path.join(root, 'modules', 'terminal', 'src', 'index')]: path.join(here, REAL ? 'terminal-real.ts' : 'terminal.ts'),
};

const M = Module as unknown as { _resolveFilename: (...a: any[]) => string };
const orig = M._resolveFilename;
M._resolveFilename = function patched(this: unknown, request: string, parent: any, ...rest: any[]) {
  if (BY_NAME[request]) return BY_NAME[request];
  if (request.startsWith('.') && parent?.filename) {
    const abs = path.resolve(path.dirname(parent.filename), request).replace(/\.(?:ts|js)$/, '');
    if (BY_PATH[abs]) return BY_PATH[abs];
  }
  return orig.call(this, request, parent, ...rest);
};
