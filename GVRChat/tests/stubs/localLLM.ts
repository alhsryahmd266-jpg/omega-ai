/** Stand-in for localLLM.ts: a scripted "model". Each completion() call pops the next scripted reply. */
export type Reply = string | { text: string };
export const script: Array<(params: any) => Reply | Promise<Reply>> = [];
export const seen: any[] = [];
export const flags = { vision: false };
export const __reset = () => { script.length = 0; seen.length = 0; flags.vision = false; };
/** queue plain replies */
export const queue = (...replies: Array<string | ((p: any) => Reply | Promise<Reply>)>) =>
  replies.forEach(r => script.push(typeof r === 'function' ? r : () => r));

const ctx = {
  isJinjaSupported: () => false,
  async completion(params: any, onToken?: (d: { token: string }) => void) {
    seen.push(params);
    if (process.env.GVR_DEBUG_MODEL) {
      const last = params.messages[params.messages.length - 1];
      console.error(`[model call #${seen.length}] ${params.messages.length} msgs; last(${last.role}): ${String(typeof last.content === 'string' ? last.content : JSON.stringify(last.content)).replace(/\s+/g, ' ').slice(0, 160)}`);
    }
    const next = script.shift();
    if (!next) throw new Error(`scripted model has no reply left (call #${seen.length})`);
    const r = await next(params);
    const text = typeof r === 'string' ? r : r.text;
    onToken?.({ token: text });
    return { text, content: text, tokens_predicted: 30, timings: { predicted_per_second: 12 }, context_full: false } as any;
  },
  async stopCompletion() { /* nothing is running in the stub */ },
};

export const getContext = () => ctx;
export const getLoadedContextSize = () => 4096;
export const getModelProfile = () => ({ tier: 'large', looksBase: false, paramsB: 14, supportsTools: true, warnings: [] as string[] });
export const isModelLoaded = () => true;
export const isVisionReady = () => flags.vision;
export const isMtpActive = () => false;
export const samplingFor = (_p: unknown) => ({ temperature: 0.7, top_p: 0.9 });
export const STOP_WORDS = ['<|im_end|>'];
export const stripThinking = (s: string) => s;
export const detectLoop = (_s: string) => false;
export const collapseLoop = (s: string) => s;
export const withGenerationLock = <T,>(fn: () => Promise<T>) => fn();
export const analyzeImage = async (_uri: string, _prompt: string, _max?: number) => 'a cat sitting on a laptop';
