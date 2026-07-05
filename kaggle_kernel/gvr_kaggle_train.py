"""
GVR-ULTIMATE v4.0 — GPU T4×2 (30 GB VRAM)
==========================================
Reasoning : DeepSeek-R1-Distill-Qwen-14B  4-bit  (~8 GB)
Vision    : Qwen2-VL-2B-Instruct          fp16   (~4 GB)
Tools     : Code · Terminal · Web-search · Memory
Intel.    : GVR-loop · Tree-of-Thoughts · Self-Consistency · ReAct · CoT
"""

import os, sys, json, re, ast, time, subprocess, tempfile, requests, base64
import torch
import torch.nn.functional as F

OUT   = "/kaggle/working"
HFT   = os.environ.get("HF_TOKEN", "")
GHPAT = os.environ.get("GH_PAT", "")
HFU   = "ahmedxg"

# ── helpers ───────────────────────────────────────────────────────────────────
def sh(cmd):
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if r.stdout: print(r.stdout[-500:])
    if r.stderr and r.returncode: print("ERR:", r.stderr[-300:])
    return r

def push_github(filename, data_str):
    if not GHPAT: return
    content = base64.b64encode(data_str.encode()).decode()
    url = f"https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/{filename}"
    check = requests.get(url, headers={"Authorization": f"token {GHPAT}"})
    body = {"message": f"GVR-Ultimate v4: {filename}", "content": content}
    if check.status_code == 200:
        body["sha"] = check.json()["sha"]
    requests.put(url, headers={"Authorization": f"token {GHPAT}"}, json=body)

# ── hardware ──────────────────────────────────────────────────────────────────
print("="*60)
print("GVR-ULTIMATE v4.0")
print("="*60)

n_gpus    = torch.cuda.device_count()
vram_each = [torch.cuda.get_device_properties(i).total_memory/1e9 for i in range(n_gpus)]
total_vram = sum(vram_each)
sm_ver     = (torch.cuda.get_device_properties(0).major,
              torch.cuda.get_device_properties(0).minor) if n_gpus else (0,0)

for i in range(n_gpus):
    p = torch.cuda.get_device_properties(i)
    print(f"GPU[{i}]: {p.name} | {p.total_memory/1e9:.0f} GB | sm_{p.major}{p.minor}")
print(f"Total VRAM: {total_vram:.0f} GB")

# sm < 70 → bitsandbytes will segfault (P100=sm60). Fallback to fp16 split.
BNB_OK = sm_ver >= (7, 0)
print(f"bitsandbytes compatible: {BNB_OK}")

# ── install ───────────────────────────────────────────────────────────────────
print("\nInstalling deps ...")
sh("pip install transformers>=4.45 accelerate sentencepiece "
   "huggingface_hub>=0.23 Pillow -q")
if BNB_OK:
    sh("pip install bitsandbytes -q")
    print("bitsandbytes installed ✅")

from transformers import (AutoTokenizer, AutoModelForCausalLM,
                           BitsAndBytesConfig,
                           AutoProcessor, Qwen2VLForConditionalGeneration)
from huggingface_hub import HfApi

# ── load reasoning model (DeepSeek-R1-14B) ───────────────────────────────────
R_ID = "deepseek-ai/DeepSeek-R1-Distill-Qwen-14B"
print(f"\nLoading reasoning model: {R_ID}")

if BNB_OK and total_vram < 25:
    # 4-bit when tight on VRAM
    bnb = BitsAndBytesConfig(load_in_4bit=True,
                             bnb_4bit_compute_dtype=torch.float16,
                             bnb_4bit_quant_type="nf4")
    r_tok = AutoTokenizer.from_pretrained(R_ID, trust_remote_code=True)
    r_mdl = AutoModelForCausalLM.from_pretrained(
        R_ID, quantization_config=bnb,
        device_map="auto", trust_remote_code=True)
    print("Loaded in 4-bit ✅")
else:
    # fp16 – fits in 30 GB 2×T4
    r_tok = AutoTokenizer.from_pretrained(R_ID, trust_remote_code=True)
    r_mdl = AutoModelForCausalLM.from_pretrained(
        R_ID, torch_dtype=torch.float16,
        device_map="auto", trust_remote_code=True)
    print("Loaded in fp16 ✅")

r_mdl.eval()
r_params = sum(p.numel() for p in r_mdl.parameters())/1e9
print(f"Params: {r_params:.1f}B")

# ── load vision model (Qwen2-VL-2B) ──────────────────────────────────────────
V_ID = "Qwen/Qwen2-VL-2B-Instruct"
HAS_VIS = False
print(f"\nLoading vision model: {V_ID}")
try:
    v_proc = AutoProcessor.from_pretrained(V_ID, trust_remote_code=True)
    v_mdl  = Qwen2VLForConditionalGeneration.from_pretrained(
        V_ID, torch_dtype=torch.float16,
        device_map="auto", trust_remote_code=True)
    v_mdl.eval()
    HAS_VIS = True
    vp = sum(p.numel() for p in v_mdl.parameters())/1e9
    print(f"Vision model loaded ✅  ({vp:.1f}B)")
except Exception as e:
    print(f"Vision model skipped: {e}")

# ── GENERATE ──────────────────────────────────────────────────────────────────
def generate(prompt, temp=0.7, max_t=800, system="") -> str:
    msgs = []
    if system:
        msgs.append({"role":"system","content":system})
    msgs.append({"role":"user","content":prompt})
    txt = r_tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
    ids = r_tok(txt, return_tensors="pt").to(r_mdl.device)
    with torch.no_grad():
        out = r_mdl.generate(
            **ids, max_new_tokens=max_t,
            temperature=max(temp,0.01), do_sample=temp>0.01,
            repetition_penalty=1.1, pad_token_id=r_tok.eos_token_id)
    new = out[0][len(ids.input_ids[0]):]
    return r_tok.decode(new, skip_special_tokens=True).strip()

def gen_conf(prompt, temp=0.7, max_t=600):
    """Generate + real token-level confidence."""
    msgs = [{"role":"user","content":prompt}]
    txt  = r_tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
    ids  = r_tok(txt, return_tensors="pt").to(r_mdl.device)
    with torch.no_grad():
        out = r_mdl.generate(
            **ids, max_new_tokens=max_t,
            temperature=max(temp,0.01), do_sample=temp>0.01,
            repetition_penalty=1.1, pad_token_id=r_tok.eos_token_id,
            output_scores=True, return_dict_in_generate=True)
    new   = out.sequences[0][len(ids.input_ids[0]):]
    ans   = r_tok.decode(new, skip_special_tokens=True).strip()
    confs = [F.softmax(s[0],dim=-1)[t].item() for s,t in zip(out.scores,new)]
    return ans, sum(confs)/max(len(confs),1)

# ── VISION ────────────────────────────────────────────────────────────────────
def vision_analyze(image_path:str, question="Describe this image in detail") -> str:
    if not HAS_VIS:
        return "Vision model not loaded."
    try:
        from PIL import Image
        img  = Image.open(image_path).convert("RGB")
        msgs = [{"role":"user","content":[
            {"type":"image","image":img},
            {"type":"text","text":question}]}]
        txt  = v_proc.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
        inputs = v_proc(text=[txt], images=[img], return_tensors="pt").to(v_mdl.device)
        with torch.no_grad():
            out = v_mdl.generate(**inputs, max_new_tokens=300)
        return v_proc.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True)
    except Exception as e:
        return f"Vision error: {e}"

# ── TOOLS ─────────────────────────────────────────────────────────────────────
_ALLOWED = {"ls","pwd","echo","cat","wc","grep","head","tail","python3",
            "date","whoami","df","free","uname","sort","uniq","find","stat"}

def tool_terminal(cmd:str) -> str:
    deny = ["rm ","sudo","curl","wget","&&","||",";","|","`","$(",">/"]
    for d in deny:
        if d in cmd: return f"❌ Blocked: '{d}'"
    parts = cmd.strip().split()
    if not parts or parts[0] not in _ALLOWED:
        return f"❌ '{parts[0] if parts else '?'}' not in allowlist"
    try:
        r = subprocess.run(parts, capture_output=True, text=True, timeout=8, cwd="/tmp")
        return (r.stdout + (r.stderr if r.returncode else "")).strip()[:1500] or "✅"
    except subprocess.TimeoutExpired: return "❌ Timeout"
    except Exception as e: return f"❌ {e}"

def tool_python(code:str) -> str:
    code = code.replace("\\n","\n")
    for f in ["os.system","__import__('os')","socket.","rmtree","subprocess.Popen"]:
        if f in code: return f"❌ Blocked: '{f}'"
    try:
        with tempfile.NamedTemporaryFile(mode="w",suffix=".py",delete=False) as t:
            t.write(code); tp=t.name
        r = subprocess.run([sys.executable,tp], capture_output=True, text=True, timeout=10)
        os.unlink(tp)
        return (r.stdout+(r.stderr if r.returncode else "")).strip()[:1200] or "✅ (no output)"
    except subprocess.TimeoutExpired: return "❌ Timeout"
    except Exception as e: return f"❌ {e}"

def tool_search(q:str) -> str:
    try:
        r = requests.get("https://html.duckduckgo.com/html/",
                         params={"q":q},
                         headers={"User-Agent":"Mozilla/5.0"}, timeout=10)
        clean  = lambda s: re.sub(r"<[^>]+>","",s).strip()
        titles = re.findall(r'class="result__a"[^>]*>(.*?)</a>', r.text)
        snips  = re.findall(r'class="result__snippet"[^>]*>(.*?)</a>', r.text)
        out = [f"• {clean(t)}: {clean(s)[:170]}" for t,s in zip(titles[:4],snips[:4])]
        return "\n".join(out) if out else "No results."
    except Exception as e: return f"Search error: {e}"

_mem:dict = {}
def mem_save(k,v): _mem[k]={"v":v,"ts":time.strftime("%H:%M")}
def mem_get(k): return _mem.get(k,{}).get("v",f"No memory for '{k}'")

# ── VERIFY (real signals) ─────────────────────────────────────────────────────
def verify(question:str, answer:str, conf:float, second:str|None=None) -> float:
    sig = [conf]
    # code execution
    codes = re.findall(r"```python\n(.*?)```", answer, re.DOTALL)
    if codes:
        ex = tool_python(codes[0])
        ok = 0.0 if ("Error" in ex or "❌" in ex) else 1.0
        sig += [ok, ok]          # weight ×2
    # self-consistency
    if second:
        wa,wb = set(answer.lower().split()), set(second.lower().split())
        if wa and wb: sig.append(len(wa&wb)/len(wa|wb))
    # no-refusal
    bad = ["i cannot","i can't","i'm unable","as an ai"]
    sig.append(max(0,1-sum(1 for b in bad if b in answer.lower())*0.3))
    return round(sum(sig)/len(sig), 3)

# ── TREE OF THOUGHTS ──────────────────────────────────────────────────────────
def tree_of_thoughts(question:str, n=3, depth=2) -> str:
    print(f"[ToT] {n} branches × depth {depth}")
    branches = []
    for i in range(n):
        ans, conf = gen_conf(
            f"(Approach {i+1}/{n}) Think step by step:\n{question}",
            temp=0.55+i*0.12, max_t=350)
        s = verify(question, ans, conf)
        branches.append({"a":ans,"s":s})
        print(f"  branch {i+1}: {s:.3f}")
    branches.sort(key=lambda x:x["s"], reverse=True)
    branches = branches[:max(1,n//2)]
    for d in range(2, depth+1):
        nb=[]
        for b in branches:
            ans, conf = gen_conf(
                f"Continue and finalize:\n{b['a']}\n\nFinal answer:", temp=0.45, max_t=400)
            s = verify(question, ans, conf)
            nb.append({"a":ans,"s":s})
            print(f"  depth {d}: {s:.3f}")
        branches = sorted(nb, key=lambda x:x["s"], reverse=True)
    return branches[0]["a"]

# ── SELF-CONSISTENCY ──────────────────────────────────────────────────────────
def self_consistency(question:str, n=4) -> str:
    ans_list = [gen_conf(question, temp=0.5+i*0.15, max_t=400)[0] for i in range(n)]
    scores = []
    for i,a in enumerate(ans_list):
        wa=set(a.lower().split())
        sim=[len(wa&set(b.lower().split()))/max(len(wa|set(b.lower().split())),1)
             for j,b in enumerate(ans_list) if j!=i]
        scores.append(sum(sim)/max(len(sim),1))
    best=scores.index(max(scores))
    print(f"[SC] n={n} best={best} score={scores[best]:.3f}")
    return ans_list[best]

# ── REACT AGENT ───────────────────────────────────────────────────────────────
_SYS = """You are GVR-Agent. Use tools when needed:
TOOL: terminal <cmd>
TOOL: python <code (use \\n for newlines)>
TOOL: search <query>
TOOL: vision <image_path>|<question>
TOOL: mem_save <key>|<value>
TOOL: mem_get <key>
Otherwise, write your final answer directly. Be concise."""

def react(task:str, max_steps=5) -> dict:
    hist, steps = f"Task: {task}\n", []
    for step in range(max_steps):
        resp = generate(_SYS+"\n\n"+hist+"\nYour action:", temp=0.25, max_t=280)
        m = re.search(r"TOOL:\s*(terminal|python|search|vision|mem_save|mem_get)\s+(.+)",
                      resp, re.DOTALL)
        if not m:
            steps.append({"s":step+1,"t":"answer","c":resp})
            return {"answer":resp,"steps":steps}
        tool,arg = m.group(1), m.group(2).strip()
        if   tool=="terminal":  obs=tool_terminal(arg)
        elif tool=="python":    obs=tool_python(arg)
        elif tool=="search":    obs=tool_search(arg)
        elif tool=="vision":
            parts=arg.split("|",1)
            obs=vision_analyze(parts[0].strip(),parts[1].strip() if len(parts)>1 else "Describe")
        elif tool=="mem_save":
            p=arg.split("|",1); mem_save(p[0].strip(),p[1].strip() if len(p)>1 else "")
            obs=f"Saved '{p[0].strip()}' to memory."
        elif tool=="mem_get":   obs=mem_get(arg.strip())
        else:                   obs="Unknown tool"
        steps.append({"s":step+1,"t":f"tool:{tool}","action":arg[:80],"obs":obs[:120]})
        print(f"  [{step+1}] {tool}: {arg[:50]} → {obs[:70]}")
        hist += f"\nAction: TOOL: {tool} {arg}\nObservation: {obs}\n"
    fin = generate(_SYS+"\n\n"+hist+"\nFinal answer (no tools):", temp=0.3, max_t=500)
    steps.append({"s":max_steps+1,"t":"final","c":fin})
    return {"answer":fin,"steps":steps}

# ── GVR LOOP ──────────────────────────────────────────────────────────────────
def gvr(question:str, mode="gvr") -> dict:
    best, best_score, trace, refine = "", -1.0, [], ""
    for it in range(3):
        print(f"\n[GVR iter {it+1}]", end=" ")
        if it==0 and mode=="tot":
            ans = tree_of_thoughts(question)
            conf = 0.7
        elif it==0 and mode=="sc":
            ans = self_consistency(question)
            conf = 0.7
        else:
            ans, conf = gen_conf(refine+question, temp=0.5+it*0.1)
        sec = gen_conf(question, temp=0.9, max_t=150)[0] if it==0 else None
        score = verify(question, ans, conf, sec)
        trace.append({"iter":it+1,"score":score,"conf":round(conf,3),"preview":ans[:80]})
        print(f"score={score:.3f} conf={conf:.3f}")
        if score > best_score: best_score,best=score,ans
        if score >= 0.70: break
        issues=[]
        codes=re.findall(r"```python\n(.*?)```",ans,re.DOTALL)
        if codes:
            out=tool_python(codes[0])
            if "Error" in out or "❌" in out:
                issues.append(f"Code error: {out[:100]}. Fix it.")
        refine="Previous answer needs improvement. "+" ".join(issues)+"\n\n"
    return {"question":question,"answer":best,"score":round(best_score,3),"trace":trace}

# ── TESTS ─────────────────────────────────────────────────────────────────────
print("\n"+"="*60)
print("TEST SUITE")
print("="*60)

TESTS = [
    ("gvr",   "Write Python quicksort, test on [5,3,8,1,9,2], print result"),
    ("gvr",   "ما هو الذكاء الاصطناعي؟ اشرح بالعربية ببساطة"),
    ("gvr",   "23 × 47 + 89 = ? Step by step"),
    ("agent", "What is today's date on this server? Show RAM usage"),
    ("agent", "Search: Qwen2-VL model vision capabilities and summarize"),
    ("tot",   "Explain how Transformer attention works with a simple example"),
    ("sc",    "Write Python binary search with docstring and test"),
    ("gvr",   "Write a Python async web scraper using aiohttp (skeleton)"),
]

results=[]
for mode, q in TESTS:
    print(f"\n{'─'*55}")
    print(f"[{mode.upper()}] {q[:65]}")
    t0=time.time()
    if mode in ("gvr","tot","sc"):
        r=gvr(q, mode=mode)
        ans,meta=r["answer"],{"score":r["score"],"trace":r["trace"]}
    else:
        r=react(q)
        ans,meta=r["answer"],{"steps":r["steps"]}
    elapsed=round(time.time()-t0,1)
    print(f"({elapsed}s) {ans[:180]}")
    results.append({"mode":mode,"q":q,"ans":ans[:400],"elapsed_s":elapsed,**meta})

# ── SAVE & UPLOAD ─────────────────────────────────────────────────────────────
cfg={
    "version":"4.0",
    "reasoning":R_ID,
    "reasoning_params":round(r_params,1),
    "vision":V_ID if HAS_VIS else None,
    "hardware":f"{n_gpus}×GPU total {total_vram:.0f}GB sm_{sm_ver[0]}{sm_ver[1]}",
    "tools":["terminal","python","web_search","memory","vision"],
    "intelligence":["gvr_loop","tree_of_thoughts","self_consistency","react","cot","verify"],
    "test_results":results,
    "ts":time.strftime("%Y-%m-%d %H:%M:%S")
}
cfg_path=f"{OUT}/gvr_config.json"
with open(cfg_path,"w") as f: json.dump(cfg,f,indent=2,ensure_ascii=False)

if HFT:
    api=HfApi(token=HFT)
    api.create_repo(f"{HFU}/gvr-ultimate",exist_ok=True,private=False)
    for fp,rp in [(cfg_path,"config_v4.json")]:
        api.upload_file(path_or_fileobj=fp,path_in_repo=rp,
                        repo_id=f"{HFU}/gvr-ultimate",repo_type="model")
    print(f"\n✅ https://huggingface.co/{HFU}/gvr-ultimate")

push_github("gvr_ultimate_result.json",
            json.dumps({"status":"SUCCESS",**{k:v for k,v in cfg.items() if k!="test_results"},
                        "n_tests":len(results)},indent=2,ensure_ascii=False))

print("\n"+"="*60)
print("GVR-ULTIMATE v4.0 — DONE")
print("="*60)
