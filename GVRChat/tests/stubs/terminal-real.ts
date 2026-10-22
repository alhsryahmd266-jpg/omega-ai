/**
 * Real shell behind the app's Terminal API, for the MISSION tests. Commands the app would send to Alpine
 * (/workspace, /usr/local/bin, /opt/gvr, /sdcard, /root) run in bash on this machine with those paths mapped into
 * the temp ROOT; output paths are mapped back, so the model sees exactly what it would see on the phone.
 */
import { spawn, type ChildProcess } from 'node:child_process';
import path from 'node:path';
import { ROOT } from './expo-file-system-disk';

export const calls: { command: string; wrapped: string; timeout: number }[] = [];
export const state = { alpine: true, installed: [] as string[], cancelled: 0, jobs: [] as string[], handler: (_c: string): string => '' };
export const __reset = () => { calls.length = 0; state.installed = []; state.cancelled = 0; state.jobs = []; };

const SEG = String.raw`(?<![\w./-])`;
const END = String.raw`(?=[/'"\s:;)]|$)`;
const VMAP: [RegExp, string, string][] = [
  [new RegExp(`${SEG}/workspace${END}`, 'g'), `${ROOT}/files/workspace`, '/workspace'],
  [new RegExp(`${SEG}/usr/local/bin${END}`, 'g'), `${ROOT}/bin`, '/usr/local/bin'],
  [new RegExp(`${SEG}/opt/gvr${END}`, 'g'), `${ROOT}/opt`, '/opt/gvr'],
  [new RegExp(`${SEG}/sdcard${END}`, 'g'), `${ROOT}/sdcard`, '/sdcard'],
  [new RegExp(`${SEG}/root${END}`, 'g'), `${ROOT}/root`, '/root'],
];
const toReal = (s: string) => VMAP.reduce((a, [re, to]) => a.replace(re, to), s);
const toVirtual = (s: string) => VMAP.reduce((a, [, real, virt]) => a.split(real).join(virt), s);

let current: ChildProcess | null = null;
const killTree = (c: ChildProcess): void => { try { process.kill(-c.pid!, 'SIGKILL'); } catch { try { c.kill('SIGKILL'); } catch { /* gone */ } } };

const Terminal = {
  run(wrapped: string, timeout = 30): Promise<string> {
    const m = /^cd '[^']*' 2>\/dev\/null\n(?:[\s\S]*?\n)?\{\n([\s\S]*)\n\}\n__gvr_rc=/.exec(wrapped);
    calls.push({ command: m ? m[1] : wrapped, wrapped, timeout });
    return new Promise(resolve => {
      const child = spawn('bash', ['-c', toReal(wrapped)], {
        cwd: path.join(ROOT, 'files', 'workspace'),
        env: { ...process.env, PATH: `${ROOT}/bin:${process.env.PATH}`, HOME: `${ROOT}/root`, GVR_TOOLS: `${ROOT}/opt` },
        detached: true,                                  // own process group, so a kill takes the children too (like proot --kill-on-exit)
      });
      current = child;
      let out = '';
      let timedOut = false;
      child.stdout.on('data', d => { out += d; });
      child.stderr.on('data', d => { out += d; });
      const timer = setTimeout(() => { timedOut = true; killTree(child); }, timeout * 1000);
      child.on('close', code => {
        clearTimeout(timer);
        if (current === child) current = null;
        let text = toVirtual(out).replace(/\s+$/, '');
        if (timedOut) text += `\n(timed out after ${timeout}s)`;
        else if (code !== 0) text += `\n(exit code ${code})`;
        resolve(text);
      });
    });
  },
  async runAndroid(): Promise<string> { return ''; },
  async runPython(): Promise<string> { return ''; },
  async installPackages(pkgs: string): Promise<string> { state.installed.push(pkgs); return `simulated: apk add ${pkgs}`; },
  async alpineStatus() { return { installed: state.alpine, prootPresent: true, tallocPresent: true, shmemPresent: true }; },
  async mode() { return 'alpine'; },
  async cancelRunning(): Promise<boolean> { state.cancelled++; if (current) { killTree(current); return true; } return false; },
  async beginJob(l: string): Promise<boolean> { state.jobs.push(`begin:${l}`); return true; },
  async endJob(): Promise<boolean> { state.jobs.push('end'); return true; },
};
export default Terminal;
