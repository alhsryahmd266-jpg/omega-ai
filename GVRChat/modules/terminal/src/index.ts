import { requireNativeModule } from 'expo-modules-core';
import * as FileSystem from 'expo-file-system';

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

export interface AlpineStatus {
  installed: boolean;
  prootPresent: boolean;
  loaderPresent: boolean;
  tallocPresent: boolean;
  shmemPresent: boolean;
  targetSdk: number;
  androidSdk: number;
  rootDir: string;
}

interface TerminalNative {
  isSetupDone(): Promise<boolean>;
  setupTerminal(): Promise<{ bashPath: string; nativeLibDir: string; rootfsDir: string }>;
  run(command: string, timeoutMs: number): Promise<NativeExecResult>;
  runShell(command: string, timeoutMs: number): Promise<NativeExecResult>;
  alpineStatus(): Promise<AlpineStatus>;
  extractTarGz(archivePath: string, destDir: string): Promise<number>;
  prepareAlpine(): Promise<boolean>;
  runAlpine(command: string, timeoutMs: number): Promise<NativeExecResult>;
  selfTest(): Promise<string>;
  sha256File(path: string): Promise<string>;
  runPython(code: string, timeoutMs: number): Promise<NativeExecResult>;
  installPackage(packageName: string): Promise<NativeExecResult>;
  writeFile(path: string, content: string): Promise<boolean>;
  readFile(path: string): Promise<string>;
}

const Native = requireNativeModule<TerminalNative>('Terminal');

/** Formats a native exec result into the single-string shape tools.ts expects. */
function formatResult(r: NativeExecResult): string {
  if (r.success) {
    const err = r.stderr.trim();
    const out = r.stdout.trim();
    if (out && err) return `${out}\nstderr: ${err}`;
    return out || (err ? `stderr: ${err}` : '(command ran with no output)');
  }
  const parts: string[] = [];
  if (r.stdout.trim()) parts.push(r.stdout.trim());
  if (r.stderr.trim()) parts.push(`stderr: ${r.stderr.trim()}`);
  parts.push(`(exit code ${r.exitCode})`);
  return parts.join('\n');
}

let alpineChecked = false;
let alpineReady = false;
let alpineBroken = false;

const stripScheme = (p: string): string => p.replace(/^file:\/\//, '');

function looksLikeProotFailure(r: NativeExecResult): boolean {
  const s = `${r.stderr} ${r.stdout}`;
  return /proot (error|info: )|CANNOT LINK|libtalloc|libproot|libandroid-shmem|not found in .*nativeLibraryDir|Alpine Linux is not installed/i.test(s);
}

async function isAlpineUsable(): Promise<boolean> {
  if (alpineBroken) return false;
  if (!alpineChecked) {
    try {
      const st = await Native.alpineStatus();
      alpineReady = !!(st.installed && st.prootPresent && st.tallocPresent && st.shmemPresent);
    } catch {
      alpineReady = false;
    }
    alpineChecked = true;
  }
  return alpineReady;
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

  /**
   * Smart shell. If Alpine Linux is installed and proot works, commands run
   * inside Alpine (apk, python3, node, git ...). Otherwise they run in
   * Android's own /system/bin/sh (toybox: ls cat grep sed find tar ps ...).
   */
  async run(command: string, timeoutSeconds = 30): Promise<string> {
    if (await isAlpineUsable()) {
      try {
        const r = await Native.runAlpine(command, timeoutSeconds * 1000);
        const text = formatResult(r);
        if (!r.success && looksLikeProotFailure(r)) {
          alpineBroken = true;
          return `${text}\n\n[proot failed — falling back to the Android shell. Run the self-test in Settings.]\n` +
            (await this.runAndroid(command, timeoutSeconds));
        }
        return text;
      } catch (e: any) {
        alpineBroken = true;
        return `Alpine execution failed (${e.message || e}); using the Android shell instead:\n` +
          (await this.runAndroid(command, timeoutSeconds));
      }
    }
    return this.runAndroid(command, timeoutSeconds);
  },

  /** Always the Android system shell (toybox), never proot. */
  async runAndroid(command: string, timeoutSeconds = 30): Promise<string> {
    try {
      const result = await Native.runShell(command, timeoutSeconds * 1000);
      return formatResult(result);
    } catch (e: any) {
      return `Terminal execution failed: ${e.message || e}`;
    }
  },

  /** Always inside Alpine (fails clearly if it is not installed). */
  async runAlpine(command: string, timeoutSeconds = 30): Promise<string> {
    try {
      const result = await Native.runAlpine(command, timeoutSeconds * 1000);
      return formatResult(result);
    } catch (e: any) {
      return `Alpine execution failed: ${e.message || e}`;
    }
  },

  async alpineStatus(): Promise<AlpineStatus | null> {
    try { return await Native.alpineStatus(); } catch { return null; }
  },

  /** Which shell `run()` will use right now. */
  async mode(): Promise<'alpine' | 'android'> {
    return (await isAlpineUsable()) ? 'alpine' : 'android';
  },

  async selfTest(): Promise<string> {
    try {
      alpineChecked = false;
      alpineBroken = false;
      return await Native.selfTest();
    } catch (e: any) {
      return `Self-test failed: ${e.message || e}`;
    }
  },

  /**
   * Downloads Alpine's official mini root filesystem (aarch64), verifies its
   * SHA-256 against Alpine's published index, unpacks it and runs a test
   * command through proot.
   */
  async installAlpine(onProgress: (message: string, fraction?: number) => void): Promise<void> {
    const st = await Native.alpineStatus();
    const missing = [
      !st.prootPresent && 'libproot.so', !st.tallocPresent && 'libtalloc.so',
      !st.shmemPresent && 'libandroid-shmem.so',
    ].filter(Boolean);
    if (missing.length) {
      throw new Error(`This build is missing: ${missing.join(', ')} (packaging problem).`);
    }

    onProgress('بدوّر على أحدث إصدار من Alpine...');
    const base = 'https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/';
    const idx = await (await fetch(base + 'latest-releases.yaml')).text();
    const m = /file:\s*(alpine-minirootfs-[\w.\-]+-aarch64\.tar\.gz)[\s\S]*?sha256:\s*([0-9a-f]{64})/.exec(idx);
    if (!m) throw new Error('Could not find the Alpine mini rootfs in the release index.');
    const [, file, sha] = m;

    const dest = (FileSystem.cacheDirectory || '') + file;
    const dl = FileSystem.createDownloadResumable(base + file, dest, {}, (p) => {
      const frac = p.totalBytesExpectedToWrite > 0 ? p.totalBytesWritten / p.totalBytesExpectedToWrite : undefined;
      onProgress(`تنزيل ${file}`, frac);
    });
    const res = await dl.downloadAsync();
    if (!res || res.status !== 200) throw new Error(`Download failed (HTTP ${res?.status}).`);

    onProgress('بتحقق من سلامة الملف (SHA-256)...');
    const got = await Native.sha256File(stripScheme(dest));
    if (got !== sha) {
      await FileSystem.deleteAsync(dest, { idempotent: true });
      throw new Error('SHA-256 mismatch — the download is corrupted or tampered with.');
    }

    onProgress('بفك الضغط...');
    const n = await Native.extractTarGz(stripScheme(dest), st.rootDir);
    await Native.prepareAlpine();
    await FileSystem.deleteAsync(dest, { idempotent: true });

    onProgress('بجرّب proot...');
    const t = await Native.runAlpine('cat /etc/alpine-release && uname -m && echo proot_ok', 60000);
    if (!t.success || !t.stdout.includes('proot_ok')) {
      throw new Error(`Alpine unpacked (${n} entries) but proot could not start it:\n${formatResult(t)}`);
    }
    alpineChecked = true;
    alpineBroken = false;
    alpineReady = true;
    onProgress('تم تثبيت Alpine ✔', 1);
  },

  /** apk add <packages> inside Alpine (can take a few minutes). */
  async installPackages(packages: string, onProgress?: (m: string) => void): Promise<string> {
    onProgress?.('بنزّل الحزم...');
    const r = await Native.installPackage(packages);
    return formatResult(r);
  },

  /** Experimental: bash inside proot (builtins only — no coreutils are bundled). */
  async runProot(command: string, timeoutSeconds = 30): Promise<string> {
    try {
      const result = await Native.run(command, timeoutSeconds * 1000);
      return formatResult(result);
    } catch (e: any) {
      return `Proot execution failed: ${e.message || e}`;
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
