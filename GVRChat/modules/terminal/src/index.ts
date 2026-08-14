import { requireNativeModule } from 'expo-modules-core';

interface TerminalNative {
  isSetupDone(): Promise<boolean>;
  setup(): Promise<string>;
  run(command: string, timeoutSeconds: number): Promise<string>;
  writeFile(path: string, content: string): Promise<string>;
  readFile(path: string): Promise<string>;
  installPackage(pkg: string): Promise<string>;
}

const Native = requireNativeModule<TerminalNative>('Terminal');

export type SetupStatus = 'not_started' | 'in_progress' | 'done' | 'failed';

/** Check if Alpine Linux is already extracted and ready */
export const isSetupDone = () => Native.isSetupDone();

/**
 * First-time setup: downloads proot (~1.5 MB) + Alpine Linux (~5 MB)
 * and extracts them into the app's private storage.
 * Call once — takes 10–30 seconds depending on connection.
 */
export const setupTerminal = () => Native.setup();

/**
 * Run any shell command inside Alpine Linux chroot.
 * Returns combined stdout+stderr as a string.
 *
 * Examples:
 *   run('ls /') → 'bin  etc  home  lib  proc  root  sys  tmp  usr  var'
 *   run('python3 -c "print(2+2)"') → '4'
 *   run('curl -s https://api.ipify.org') → '1.2.3.4'
 */
export const run = (command: string, timeoutSeconds = 30) =>
  Native.run(command, timeoutSeconds);

/**
 * Run a Python script. Installs python3 first if needed.
 */
export async function runPython(code: string, timeoutSeconds = 60): Promise<string> {
  // ensure python3 is available
  const check = await run('which python3 2>/dev/null || echo MISSING', 5);
  if (check.includes('MISSING')) {
    await installPackage('python3');
  }
  // write script to a temp file, then execute
  await writeFile('/tmp/_gvr_script.py', code);
  return run('python3 /tmp/_gvr_script.py', timeoutSeconds);
}

/**
 * Install a package via Alpine's apk package manager.
 * E.g. installPackage('git') or installPackage('nodejs npm')
 */
export const installPackage = (pkg: string) => Native.installPackage(pkg);

/** Write a file inside Alpine at the given absolute path */
export const writeFile = (path: string, content: string) =>
  Native.writeFile(path, content);

/** Read a file from Alpine */
export const readFile = (path: string) => Native.readFile(path);

export default {
  isSetupDone,
  setupTerminal,
  run,
  runPython,
  installPackage,
  writeFile,
  readFile,
};
