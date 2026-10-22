/** Stand-in for the native terminal module: records every command and answers through `state.handler`. */
export const calls: { command: string; wrapped: string; timeout: number }[] = [];
export const state = {
  alpine: true,
  handler: (_cmd: string): string => '',
  installed: [] as string[],
  cancelled: 0,
  jobs: [] as string[],
};
export const __reset = () => {
  calls.length = 0;
  state.alpine = true;
  state.handler = () => '';
  state.installed = [];
  state.cancelled = 0;
  state.jobs = [];
};

const Terminal = {
  async run(wrapped: string, timeout = 30): Promise<string> {
    const m = /^cd '[^']*' 2>\/dev\/null\n\{\n([\s\S]*)\n\}\n__gvr_rc=/.exec(wrapped);
    const command = m ? m[1] : wrapped;
    calls.push({ command, wrapped, timeout });
    return `${state.handler(command)}\n__GVR_CWD__/workspace\n`;
  },
  async runAndroid(_c: string, _t?: number): Promise<string> { return ''; },
  async runPython(_c: string): Promise<string> { return ''; },
  async installPackages(pkgs: string): Promise<string> { state.installed.push(pkgs); return `installed ${pkgs}`; },
  async alpineStatus() { return { installed: state.alpine, prootPresent: true, tallocPresent: true, shmemPresent: true }; },
  async mode() { return state.alpine ? 'alpine' : 'android'; },
  async cancelRunning(): Promise<boolean> { state.cancelled++; return true; },
  async beginJob(l: string): Promise<boolean> { state.jobs.push(`begin:${l}`); return true; },
  async endJob(): Promise<boolean> { state.jobs.push('end'); return true; },
};
export default Terminal;
