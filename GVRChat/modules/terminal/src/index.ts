import { requireNativeModule } from 'expo-modules-core';

/**
 * Real embedded terminal — proot + termux-exec + bash, bundled inside this
 * app's own APK. No Termux app, no server, no network setup required.
 *
 * Built via the OFFICIAL termux-packages Docker builder (ghcr.io/termux/
 * package-builder — the exact toolchain Termux itself uses), NOT a
 * from-scratch reimplementation and NOT a runtime download (proot's own
 * GitHub repo has zero release assets — a runtime-download approach would
 * 404 on every install, which is why the binary is built once and
 * committed instead).
 *
 * Honest, stated limits:
 *  - The proot+termux-exec+SELinux interaction is known (from Termux's own
 *    open issues) to vary across Android versions/OEMs. This has been
 *    verified to build successfully, but not yet confirmed working on any
 *    specific physical device.
 *  - `-0` inside proot fakes root only within this sandboxed rootfs — it
 *    does not and cannot root the real device.
 *  - No package manager bundled: installPackage() and any command needing
 *    python3 will return a clear "not available" message, never a fake
 *    success.
 */

interface NativeExecResult {
  stdout: string;
  stderr: string;
  exitCode: number;
  success: boolean;
}

interface TerminalNative {
  isSetupDone(): Promise<boolean>;
  setupTerminal(): Promise<{ bashPath: string; nativeLibDir: string; rootfsDir: string }>;
  run(command: string, timeoutMs: number): Promise<NativeExecResult>;
  runPython(code: string, timeoutMs: number): Promise<NativeExecResult>;
  installPackage(packageName: string): Promise<NativeExecResult>;
  writeFile(path: string, content: string): Promise<boolean>;
  readFile(path: string): Promise<string>;
}

const Native = requireNativeModule<TerminalNative>('Terminal');

/** Formats a native exec result into the single-string shape tools.ts expects. */
function formatResult(r: NativeExecResult): string {
  if (r.success) {
    return r.stdout.trim() || '(command ran with no output)';
  }
  const parts: string[] = [];
  if (r.stdout.trim()) parts.push(r.stdout.trim());
  if (r.stderr.trim()) parts.push(`stderr: ${r.stderr.trim()}`);
  parts.push(`(exit code ${r.exitCode})`);
  return parts.join('\n');
}

const Terminal = {
  async isSetupDone(): Promise<boolean> {
    try {
      return await Native.isSetupDone();
    } catch {
      return false;
    }
  },

  async setupTerminal(): Promise<{ bashPath: string; nativeLibDir: string; rootfsDir: string }> {
    return Native.setupTerminal();
  },

  async run(command: string, timeoutSeconds = 30): Promise<string> {
    try {
      const result = await Native.run(command, timeoutSeconds * 1000);
      return formatResult(result);
    } catch (e: any) {
      return `Terminal execution failed: ${e.message || e}`;
    }
  },

  async runPython(code: string, timeoutSeconds = 60): Promise<string> {
    try {
      const result = await Native.runPython(code, timeoutSeconds * 1000);
      return formatResult(result);
    } catch (e: any) {
      return `Python execution failed: ${e.message || e}`;
    }
  },

  async installPackage(packageName: string): Promise<string> {
    try {
      const result = await Native.installPackage(packageName);
      return formatResult(result);
    } catch (e: any) {
      return `Package install failed: ${e.message || e}`;
    }
  },

  async writeFile(path: string, content: string): Promise<boolean> {
    return Native.writeFile(path, content);
  },

  async readFile(path: string): Promise<string> {
    return Native.readFile(path);
  },
};

export default Terminal;
