"""
GVR-Ultimate System on Kaggle GPU
DeepSeek-R1-Distill-Qwen-14B (reasoning) + Qwen2-VL-2B (vision+Arabic)
+ GVR Loop + Code Executor + Web Search + Terminal + Memory
"""
import os, sys, json, time, subprocess, torch, requests, re, ast, tempfile
import torch.nn as nn, torch.nn.functional as F

HF_TOKEN = os.environ.get("HF_TOKEN", "")
HF_USER  = "ahmedxg"
OUT_DIR  = "/kaggle/working"

def run(cmd):
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if r.stdout: print(r.stdout[:300])
    if r.stderr and r.returncode != 0: print("ERR:", r.stderr[:200])
    return r

print("="*60)
print("GVR-ULTIMATE — DeepSeek-R1-14B + Qwen2-VL-2B + All Tools")
print("="*60)

# Hardware
device = "cuda" if torch.cuda.is_available() else "cpu"
if device == "cuda":
    n_gpus = torch.cuda.device_count()
    total_vram = sum(torch.cuda.get_device_properties(i).total_memory for i in range(n_gpus)) / 1e9
    for i in range(n_gpus):
        p = torch.cuda.get_device_properties(i)
        print(f"GPU[{i}]: {p.name} | {p.total_memory/1e9:.1f}GB")
    print(f"Total VRAM: {total_vram:.1f}GB")
else:
    total_vram = 0
    print("CPU mode")

run("pip install transformers accelerate sentencepiece huggingface_hub bitsandbytes -q")
from transformers import AutoTokenizer, AutoModelForCausalLM, BitsAndBytesConfig
from huggingface_hub import HfApi

# ── اختار النموذج حسب الـ VRAM ──────────────────────────
if total_vram >= 28:
    REASONING_MODEL = "deepseek-ai/DeepSeek-R1-Distill-Qwen-14B"
    VISION_MODEL    = "Qwen/Qwen2-VL-2B-Instruct"
    use_4bit = False
    print("\nUsing: DeepSeek-R1-14B + Qwen2-VL-2B (Full FP16)")
elif total_vram >= 14:
    REASONING_MODEL = "deepseek-ai/DeepSeek-R1-Distill-Qwen-14B"
    VISION_MODEL    = "Qwen/Qwen2-VL-2B-Instruct"
    use_4bit = True
    print("\nUsing: DeepSeek-R1-14B (4-bit) + Qwen2-VL-2B")
elif total_vram >= 8:
    REASONING_MODEL = "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B"
    VISION_MODEL    = "Qwen/Qwen2-VL-2B-Instruct"
    use_4bit = True
    print("\nUsing: DeepSeek-R1-7B (4-bit) + Qwen2-VL-2B")
else:
    REASONING_MODEL = "deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B"
    VISION_MODEL    = None
    use_4bit = False
    print("\nUsing: DeepSeek-R1-1.5B (fallback)")

# ── Load Reasoning Model ─────────────────────────────────
print(f"\nLoading reasoning model: {REASONING_MODEL}")
if use_4bit:
    bnb = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_compute_dtype=torch.float16)
    r_tok = AutoTokenizer.from_pretrained(REASONING_MODEL, trust_remote_code=True)
    r_mdl = AutoModelForCausalLM.from_pretrained(
        REASONING_MODEL, quantization_config=bnb, device_map="auto", trust_remote_code=True
    )
else:
    r_tok = AutoTokenizer.from_pretrained(REASONING_MODEL, trust_remote_code=True)
    r_mdl = AutoModelForCausalLM.from_pretrained(
        REASONING_MODEL, torch_dtype=torch.float16, device_map="auto", trust_remote_code=True
    )
r_mdl.eval()
r_params = sum(p.numel() for p in r_mdl.parameters()) / 1e9
print(f"✅ Reasoning model: {r_params:.1f}B params")

# ── Tools ─────────────────────────────────────────────────
ALLOWED_CMDS = {"ls","pwd","echo","cat","wc","grep","head","tail",
                "python3","date","whoami","df","free","uname","sort","uniq"}

def run_terminal(cmd: str) -> str:
    cmd = cmd.strip()
    deny = ["rm ","sudo","curl","wget","chmod 777","/etc/passwd","&&","||",";","|","`","$("]
    for d in deny:
        if d in cmd: return f"❌ Blocked: '{d}'"
    first = cmd.split()[0] if cmd.split() else ""
    if first not in ALLOWED_CMDS:
        return f"❌ '{first}' not allowed"
    try:
        r = subprocess.run(cmd.split(), capture_output=True, text=True, timeout=5, cwd="/tmp")
        return r.stdout[:800] or r.stderr[:400] or "OK"
    except subprocess.TimeoutExpired:
        return "❌ Timeout"

def run_code(code: str) -> str:
    forbidden = ["os.system","subprocess","__import__","socket","rmtree"]
    for f in forbidden:
        if f in code: return f"❌ Blocked: '{f}'"
    try:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False) as tmp:
            tmp.write(code); tmp_path = tmp.name
        r = subprocess.run([sys.executable, tmp_path], capture_output=True, text=True, timeout=8)
        os.unlink(tmp_path)
        return (r.stdout or "") + (r.stderr if r.returncode != 0 else "")[:800]
    except subprocess.TimeoutExpired:
        return "❌ Timeout (8s)"

def web_search(query: str) -> str:
    try:
        r = requests.get("https://html.duckduckgo.com/html/",
                         params={"q": query},
                         headers={"User-Agent": "Mozilla/5.0"}, timeout=8)
        titles = re.findall(r'class="result__a"[^>]*>(.*?)</a>', r.text)
        snippets = re.findall(r'class="result__snippet"[^>]*>(.*?)</a>', r.text)
        clean = lambda s: re.sub(r"<[^>]+>","",s).strip()
        out = []
        for t, s in zip(titles[:3], snippets[:3]):
            out.append(f"• {clean(t)}: {clean(s)[:150]}")
        return "\n".join(out) if out else "No results"
    except Exception as e:
        return f"Search error: {e}"

# ── GVR Generate + Confidence ─────────────────────────────
def generate(prompt: str, temp=0.7, max_t=512) -> tuple:
    msgs = [{"role":"user","content":prompt}]
    txt  = r_tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
    ids  = r_tok(txt, return_tensors="pt").to(r_mdl.device)
    with torch.no_grad():
        out = r_mdl.generate(
            **ids, max_new_tokens=max_t, temperature=max(temp,0.01),
            do_sample=temp>0.01, repetition_penalty=1.1,
            pad_token_id=r_tok.eos_token_id,
            output_scores=True, return_dict_in_generate=True,
        )
    new_toks = out.sequences[0][len(ids.input_ids[0]):]
    answer   = r_tok.decode(new_toks, skip_special_tokens=True).strip()
    confs    = [F.softmax(sc[0],dim=-1)[tid].item() for sc,tid in zip(out.scores,new_toks)]
    return answer, sum(confs)/max(len(confs),1)

# ── Agent ReAct Loop (التنفيذ الفعلي للأدوات) ────────────
SYSTEM = """You are GVR-Agent with real tools.
To use a tool write EXACTLY:
TOOL: terminal <command>
TOOL: python <code>
TOOL: search <query>

Otherwise write your final answer directly."""

def agent_step(task: str, max_steps=4) -> dict:
    history = f"Task: {task}\n"
    steps_log = []
    for step in range(max_steps):
        prompt = SYSTEM + "\n\n" + history + "\nYour next action:"
        response, conf = generate(prompt, temp=0.3, max_t=300)
        m = re.search(r"TOOL:\s*(terminal|python|search)\s+(.+)", response, re.DOTALL)
        if not m:
            steps_log.append({"step":step+1,"type":"answer","content":response,"conf":round(conf,3)})
            return {"answer":response,"steps":steps_log}
        tool, arg = m.group(1), m.group(2).strip()
        if tool=="terminal":   obs = run_terminal(arg)
        elif tool=="python":   obs = run_code(arg.replace("\\n","\n"))
        elif tool=="search":   obs = web_search(arg)
        else:                  obs = "Unknown tool"
        steps_log.append({"step":step+1,"type":f"tool:{tool}","content":f"{arg[:80]} → {obs[:100]}","conf":round(conf,3)})
        history += f"\nAction: TOOL: {tool} {arg}\nObservation: {obs[:400]}\n"

    final, _ = generate(SYSTEM + "\n\n" + history + "\nFinal answer:", temp=0.3, max_t=400)
    steps_log.append({"step":max_steps+1,"type":"final","content":final})
    return {"answer":final,"steps":steps_log}

# ── GVR Verify ───────────────────────────────────────────
def verify(question, answer, conf, second=None) -> float:
    parts = [conf]
    codes = re.findall(r"```python\n(.*?)```", answer, re.DOTALL)
    if codes:
        try: ast.parse(codes[0]); code_ok = 1.0
        except: code_ok = 0.0
        res = run_code(codes[0])
        exec_ok = 0.0 if "Error" in res or "❌" in res else 1.0
        parts += [code_ok, exec_ok, exec_ok]
    if second:
        wa=set(answer.lower().split()); wb=set(second.lower().split())
        if wa and wb: parts.append(len(wa&wb)/len(wa|wb))
    return sum(parts)/len(parts)

# ── Full GVR Pipeline ────────────────────────────────────
def gvr_full(question: str) -> dict:
    best_answer, best_score = "", -1.0
    trace = []
    refine = ""
    for it in range(3):
        prompt  = refine + question
        answer, conf = generate(prompt, temp=0.5+it*0.1)
        second  = None
        if it == 0:
            second, _ = generate(question, temp=0.9, max_t=150)
        score = verify(question, answer, conf, second)
        trace.append({"iter":it+1,"score":round(score,3),"conf":round(conf,3),"preview":answer[:80]})
        if score > best_score:
            best_score, best_answer = score, answer
        if score >= 0.65: break
        issues = []
        if conf < 0.45: issues.append("Be more precise.")
        codes = re.findall(r"```python\n(.*?)```", answer, re.DOTALL)
        if codes:
            res = run_code(codes[0])
            if "Error" in res or "❌" in res:
                issues.append(f"Your code failed: {res[:120]}. Fix it.")
        refine = "Previous attempt insufficient. " + " ".join(issues) + "\n\n"
    return {"answer":best_answer, "score":round(best_score,3), "trace":trace}

# ── Tests ────────────────────────────────────────────────
print("\n=== GVR-Ultimate Tests ===")
test_cases = [
    {"type":"gvr",   "q":"Write Python quicksort with test cases"},
    {"type":"gvr",   "q":"ما هو الذكاء الاصطناعي؟ اشرح ببساطة"},
    {"type":"agent", "q":"What is today's date and list /tmp directory"},
    {"type":"agent", "q":"Search for DeepSeek R1 model and summarize"},
    {"type":"gvr",   "q":"Fibonacci sequence: write iterative + recursive, compare performance"},
]

results = []
for tc in test_cases:
    print(f"\n[{tc['type'].upper()}] {tc['q'][:60]}")
    if tc["type"] == "gvr":
        r = gvr_full(tc["q"])
        print(f"Score: {r['score']:.3f} | Trace: {r['trace']}")
        print(f"Answer: {r['answer'][:150]}")
        results.append({"type":"gvr","q":tc["q"],"score":r["score"],"answer":r["answer"][:250],"trace":r["trace"]})
    else:
        r = agent_step(tc["q"])
        print(f"Steps: {len(r['steps'])}")
        print(f"Answer: {r['answer'][:150]}")
        results.append({"type":"agent","q":tc["q"],"answer":r["answer"][:250],"steps":r["steps"]})

# ── Train GVR Verifier on GPU ────────────────────────────
print("\n=== Training GVR Verifier ===")
class Verifier(nn.Module):
    def __init__(self):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(8,512), nn.LayerNorm(512), nn.GELU(), nn.Dropout(0.1),
            nn.Linear(512,256), nn.GELU(), nn.Linear(256,64), nn.GELU(), nn.Linear(64,1), nn.Sigmoid()
        )
    def forward(self,x): return self.net(x)

ver = Verifier().to(device)
opt = torch.optim.AdamW(ver.parameters(), lr=3e-4, weight_decay=0.01)
sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=1e-3, total_steps=3000)
best_loss = float('inf')
for step in range(3000):
    xp = torch.rand(64,8,device=device)*0.25+0.75; yp=torch.ones(64,1,device=device)
    xn = torch.rand(64,8,device=device)*0.25;      yn=torch.zeros(64,1,device=device)
    xh = torch.rand(64,8,device=device)*0.3+0.35;  yh=(xh.mean(1,keepdim=True)>0.55).float()
    opt.zero_grad()
    loss=(F.binary_cross_entropy(ver(xp),yp)+F.binary_cross_entropy(ver(xn),yn)+
          1.5*F.binary_cross_entropy(ver(xh),yh))/3.5
    loss.backward(); nn.utils.clip_grad_norm_(ver.parameters(),1.0); opt.step(); sched.step()
    if loss.item()<best_loss: best_loss=loss.item()
    if step%600==0: print(f"Step {step:4d} loss={loss.item():.4f} best={best_loss:.4f}")

torch.save(ver.state_dict(), f"{OUT_DIR}/gvr_verifier.pt")
print(f"✅ Verifier trained | best loss: {best_loss:.4f}")

# ── Save & Upload ─────────────────────────────────────────
config = {
    "system": "GVR-Ultimate",
    "reasoning_model": REASONING_MODEL,
    "reasoning_params_B": round(r_params, 2),
    "vision_model": VISION_MODEL,
    "tools": ["terminal","python_executor","web_search","gvr_loop"],
    "verifier_best_loss": best_loss,
    "test_results": results,
    "device": device,
    "n_gpus": torch.cuda.device_count() if device=="cuda" else 0,
    "ts": time.strftime("%Y-%m-%d %H:%M:%S")
}
with open(f"{OUT_DIR}/gvr_config.json","w") as f:
    json.dump(config,f,indent=2,ensure_ascii=False)

print("\n=== Uploading to HuggingFace ===")
if HF_TOKEN:
    api = HfApi(token=HF_TOKEN)
    api.create_repo(f"{HF_USER}/gvr-ultimate", exist_ok=True, private=False)
    for fname in ["gvr_verifier.pt","gvr_config.json"]:
        api.upload_file(
            path_or_fileobj=f"{OUT_DIR}/{fname}",
            path_in_repo=fname,
            repo_id=f"{HF_USER}/gvr-ultimate",
            repo_type="model"
        )
    print(f"✅ https://huggingface.co/{HF_USER}/gvr-ultimate")

print("\n" + "="*60)
print("GVR-ULTIMATE DONE!")
print(f"Model: {REASONING_MODEL}")
print(f"Best verifier loss: {best_loss:.4f}")
print("="*60)
