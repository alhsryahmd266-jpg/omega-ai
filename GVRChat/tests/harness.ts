/** Shared by the integration tests: resets every stub and the module-level state of the real src/ files. */
import './stubs/install';
import AsyncStorage from './stubs/async-storage';
import * as FS from './stubs/expo-file-system';
import * as Sharing from './stubs/expo-sharing';
import * as Intent from './stubs/expo-intent-launcher';
import { __reset as resetTerm, state as term } from './stubs/terminal';
import { __reset as resetModel } from './stubs/localLLM';
import { resetConversation } from '../src/gvrEngine';
import { refreshLinuxState, resetTerminalState, resetToolApprovals, setAutonomy, setToolConfirmHandler } from '../src/tools';

export const WORK = `${FS.documentDirectory}workspace/`;

export async function fresh(opts: { alpine?: boolean } = {}): Promise<void> {
  FS.__reset();
  resetTerm();
  resetModel();
  Sharing.calls.length = 0;
  Intent.calls.length = 0;
  await AsyncStorage.clear();
  term.alpine = opts.alpine ?? true;
  await refreshLinuxState();
  resetConversation();
  await new Promise(r => setTimeout(r, 5));
  resetToolApprovals();
  resetTerminalState();
  setAutonomy({ mode: 'auto', guard: true });
  setToolConfirmHandler(null);
}

export function runner(label: string) {
  let pass = 0;
  let fail = 0;
  return {
    async t(name: string, fn: () => Promise<void> | void): Promise<void> {
      try { await fn(); pass++; } catch (e) { fail++; console.error(`✗ ${name}\n    ${(e as Error).message.split('\n').join('\n    ')}`); }
    },
    finish(): void {
      console.log(`${label}: ${pass} passed, ${fail} failed`);
      if (fail) process.exit(1);
    },
  };
}
