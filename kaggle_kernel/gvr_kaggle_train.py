"""
GVR-ULTIMATE v3.0
=================
Backbone   : DeepSeek-R1-Distill-Qwen-14B Q4_K_M (~8.4 GB) via llama-cpp-python
Vision     : Qwen2-VL-2B-Instruct (float16)
Hardware   : TPU v5e-8 / P100 GPU / CPU  (auto-detect)
Tools      : Code Executor · Terminal · Web Search · Memory
Intelligence: GVR Loop · Tree of Thoughts · Self-Consistency · ReAct · CoT
"""

import os, sys, json, time, re, ast, math, hashlib
import subprocess, tempfile, threading, requests, base64

# ─── paths ────────────────────────────────────────────────────────────────────
OUT_DIR   = "/kaggle/working"
HF_TOKEN  = os.environ.get("HF_TOKEN", "")
GH_PAT    = os.environ.get("GH_PAT", "")
HF_USER   = "ahmedxg"
GGUF_REPO = "bartowski/DeepSeek-R1-Distill-Qwen-14B-GGUF"
GGUF_FILE = "DeepSeek-R1-Distill-Qwen-14B-Q4_K_M.gguf"
VISION_ID = "Qwen/Qwen2-VL-2B-Instruct"


# ─────────────────────────────────────────────────────────────────────────────
# 0. INSTALL DEPENDENCIES
# ─────────────────────────────────────────────────────────────────────────────
def shell(cmd, check=False):
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if r.stdout: print(r.stdout[-400:])
    if r.stderr and r.returncode != 0: print("ERR:", r.stderr[-300:])
    return r

print("="*60)
print("GVR-ULTIMATE v3.0 — Starting")
print("="*60)

# Detect hardware
import torch
try:
    import torch_xla.core.xla_model as xm
    DEVICE = xm.xla_device()
    HW = "TPU"
    print(f"Hardware: TPU (torch_xla)")
except Exception:
    if torch.cuda.is_available():
        DEVICE = torch.device("cuda")
        n_gpus = torch.cuda.device_count()
        total_vram = sum(torch.cuda.get_device_properties(i).total_memory
                         for i in range(n_gpus)) / 1e9
        HW = f"GPU ({n_gpus}x, {total_vram:.0f}GB)"
        print(f"Hardware: {HW}")
        for i in range(n_gpus):
            p = torch.cuda.get_device_properties(i)
            print(f"  GPU[{i}]: {p.name} | {p.total_memory/1e9:.1f}GB | sm_{p.major}{p.minor}")
    else:
        DEVICE = torch.device("cpu")
        HW = "CPU"
        print("Hardware: CPU")

# Install llama-cpp-python with correct backend (no bitsandbytes = no segfault)
print("\nInstalling llama-cpp-python...")
if HW.startswith("GPU"):
    cuda_ver = torch.version.cuda or "12.1"
    shell(
        f"CMAKE_ARGS='-DGGML_CUDA=on' pip install llama-cpp-python "
        f"--extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cu121 -q 2>/dev/null || "
        f"pip install llama-cpp-python -q"
    )
elif HW == "TPU":
    shell("pip install llama-cpp-python -q")  # CPU inference on TPU pod
else:
    shell("pip install llama-cpp-python -q")

shell("pip install transformers accelerate sentencepiece huggingface_hub -q")

from llama_cpp import Llama
from huggingface_hub import HfApi, hf_hub_download

# ─────────────────────────────────────────────────────────────────────────────
# 1. DOWNLOAD GGUF MODEL
# ─────────────────────────────────────────────────────────────────────────────
print(f"\nDownloading {GGUF_FILE} from {GGUF_REPO}...")
gguf_path = f"{OUT_DIR}/{GGUF_FILE}"

if not os.path.exists(gguf_path):
    try:
        gguf_path = hf_hub_download(
            repo_id=GGUF_REPO,
            filename=GGUF_FILE,
            token=HF_TOKEN or None,
            cache_dir=OUT_DIR,
            local_dir=OUT_DIR,
        )
        print(f"✅ Downloaded: {gguf_path}")
    except Exception as e:
        print(f"Primary download failed ({e}), trying wget...")
        url = f"https://huggingface.co/{GGUF_REPO}/resolve/main/{GGUF_FILE}"
        if HF_TOKEN:
            shell(f'wget -q --header="Authorization: Bearer {HF_TOKEN}" -O "{gguf_path}" "{url}"')
        else:
            shell(f'wget -q -O "{gguf_path}" "{url}"')

size_gb = os.path.getsize(gguf_path) / 1e9 if os.path.exists(gguf_path) else 0
print(f"Model size: {size_gb:.2f} GB")

# ─────────────────────────────────────────────────────────────────────────────
# 2. LOAD MODELS
# ─────────────────────────────────────────────────────────────────────────────

# Determine GPU layers for llama.cpp
if HW.startswith("GPU"):
    n_gpu_layers = -1   # offload all layers to GPU
elif HW == "TPU":
    n_gpu_layers = 0    # CPU inference (TPU pod has large RAM)
else:
    n_gpu_layers = 0

print(f"\nLoading DeepSeek-R1-14B GGUF (n_gpu_layers={n_gpu_layers})...")
llm = Llama(
    model_path=gguf_path,
    n_gpu_layers=n_gpu_layers,
    n_ctx=8192,           # 8K context window
    n_batch=512,
    verbose=False,
    flash_attn=True,      # Flash Attention 2 if supported
)
print(f"✅ DeepSeek-R1-14B loaded")

# Vision model (Qwen2-VL-2B) - lightweight, works on any hardware
print(f"\nLoading vision model: {VISION_ID}...")
try:
    from transformers import AutoTokenizer, AutoModelForCausalLM
    v_tok = AutoTokenizer.from_pretrained(VISION_ID, trust_remote_code=True)
    v_mdl = AutoModelForCausalLM.from_pretrained(
        VISION_ID, torch_dtype=torch.float16,
        device_map="auto", trust_remote_code=True
    )
    v_mdl.eval()
    HAS_VISION = True
    print(f"✅ Vision model loaded")
except Exception as e:
    HAS_VISION = False
    print(f"Vision model skipped: {e}")


# ─────────────────────────────────────────────────────────────────────────────
# 3. CORE GENERATION (DeepSeek-R1 style with <think> support)
# ─────────────────────────────────────────────────────────────────────────────

def generate(prompt: str, temp=0.7, max_tokens=1024, stop=None) -> str:
    """Core generation via llama-cpp-python (GGUF, no bitsandbytes)"""
    msgs = [{"role": "user", "content": prompt}]
    out = llm.create_chat_completion(
        messages=msgs,
        temperature=temp,
        max_tokens=max_tokens,
        stop=stop or [],
        stream=False,
    )
    return out["choices"][0]["message"]["content"].strip()


def generate_stream(prompt: str, temp=0.7, max_tokens=512):
    """Streaming generation (shows thinking process)"""
    msgs = [{"role": "user", "content": prompt}]
    full = ""
    for chunk in llm.create_chat_completion(
        messages=msgs, temperature=temp, max_tokens=max_tokens, stream=True
    ):
        delta = chunk["choices"][0]["delta"].get("content", "")
        full += delta
        print(delta, end="", flush=True)
    print()
    return full.strip()


# ─────────────────────────────────────────────────────────────────────────────
# 4. TOOLS
# ─────────────────────────────────────────────────────────────────────────────

ALLOWED_CMDS = {
    "ls", "pwd", "echo", "cat", "wc", "grep", "head", "tail",
    "python3", "date", "whoami", "df", "free", "uname", "sort",
    "uniq", "find", "stat", "du", "env", "printenv", "which",
}

def tool_terminal(cmd: str) -> str:
    cmd = cmd.strip()
    deny = ["rm ", "sudo", "curl", "wget", "chmod 777", "/etc/shadow",
            "/etc/passwd", "&&", "||", ";", "|", "`", "$(", ">{", ">/",
            "dd ", "mkfs", "shutdown", "reboot", "kill -9"]
    for d in deny:
        if d in cmd:
            return f"❌ Blocked: contains '{d}'"
    parts = cmd.split()
    if not parts or parts[0] not in ALLOWED_CMDS:
        return f"❌ '{parts[0] if parts else '?'}' not in allowed commands: {sorted(ALLOWED_CMDS)}"
    try:
        r = subprocess.run(
            parts, capture_output=True, text=True, timeout=8, cwd="/tmp"
        )
        out = (r.stdout + (r.stderr if r.returncode != 0 else "")).strip()
        return out[:1200] or "✅ (no output)"
    except subprocess.TimeoutExpired:
        return "❌ Timeout (8s)"
    except Exception as e:
        return f"❌ {e}"


def tool_python(code: str) -> str:
    code = code.replace("\\n", "\n")
    forbidden = ["os.system", "subprocess.run", "subprocess.Popen",
                 "__import__('os')", "socket.", "rmtree", "shutil.rmtree"]
    for f in forbidden:
        if f in code:
            return f"❌ Blocked: '{f}' not permitted in sandbox"
    try:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False) as tmp:
            tmp.write(code)
            tmp_path = tmp.name
        r = subprocess.run(
            [sys.executable, tmp_path],
            capture_output=True, text=True, timeout=10
        )
        os.unlink(tmp_path)
        out = r.stdout + (r.stderr if r.returncode != 0 else "")
        return out.strip()[:1200] or "✅ ran (no output)"
    except subprocess.TimeoutExpired:
        return "❌ Timeout (10s)"
    except Exception as e:
        return f"❌ {e}"


def tool_search(query: str) -> str:
    try:
        r = requests.get(
            "https://html.duckduckgo.com/html/",
            params={"q": query},
            headers={"User-Agent": "Mozilla/5.0 (compatible; GVR-Agent/3.0)"},
            timeout=10,
        )
        clean = lambda s: re.sub(r"<[^>]+>", "", s).strip()
        titles   = re.findall(r'class="result__a"[^>]*>(.*?)</a>', r.text)
        snippets = re.findall(r'class="result__snippet"[^>]*>(.*?)</a>', r.text)
        results  = []
        for t, s in zip(titles[:4], snippets[:4]):
            results.append(f"• {clean(t)}: {clean(s)[:180]}")
        return "\n".join(results) if results else "No results found."
    except Exception as e:
        return f"Search error: {e}"


def tool_vision(image_path: str, question: str = "Describe this image") -> str:
    if not HAS_VISION:
        return "Vision model not loaded."
    try:
        from PIL import Image
        import torch
        img = Image.open(image_path).convert("RGB")
        msgs = [{"role": "user", "content": [
            {"type": "image", "image": img},
            {"type": "text", "text": question},
        ]}]
        txt = v_tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
        ids = v_tok(txt, return_tensors="pt").to(v_mdl.device)
        with torch.no_grad():
            out = v_mdl.generate(**ids, max_new_tokens=256, temperature=0.7)
        return v_tok.decode(out[0][len(ids.input_ids[0]):], skip_special_tokens=True)
    except Exception as e:
        return f"Vision error: {e}"


# Persistent memory (simple JSON-based)
MEMORY_FILE = f"{OUT_DIR}/gvr_memory.json"
_memory = {}

def mem_save(key: str, value: str):
    _memory[key] = {"value": value, "ts": time.strftime("%Y-%m-%d %H:%M:%S")}
    with open(MEMORY_FILE, "w") as f:
        json.dump(_memory, f, indent=2, ensure_ascii=False)

def mem_get(key: str) -> str:
    return _memory.get(key, {}).get("value", f"No memory for '{key}'")

def mem_list() -> str:
    return "\n".join(f"• {k}: {v['value'][:60]}..." for k, v in _memory.items()) or "Memory is empty."


# ─────────────────────────────────────────────────────────────────────────────
# 5. VERIFY (real signals)
# ─────────────────────────────────────────────────────────────────────────────

def verify(question: str, answer: str, second: str | None = None) -> dict:
    signals = []

    # signal 1: answer completeness (length heuristic — crude but real)
    completeness = min(len(answer.split()) / 60, 1.0)
    signals.append(completeness)

    # signal 2: code execution success (hard signal)
    py_blocks = re.findall(r"```python\n(.*?)```", answer, re.DOTALL)
    if py_blocks:
        ok = 0
        for code in py_blocks:
            out = tool_python(code)
            if "Error" not in out and "❌" not in out:
                ok += 1
        exec_score = ok / len(py_blocks)
        signals.extend([exec_score, exec_score])  # weight double

    # signal 3: self-consistency vs second sample
    if second:
        wa = set(answer.lower().split())
        wb = set(second.lower().split())
        if wa and wb:
            jaccard = len(wa & wb) / len(wa | wb)
            signals.append(jaccard)

    # signal 4: no refusal or apology markers
    bad = ["i cannot", "i can't", "i'm sorry", "i don't know", "as an ai"]
    penalty = sum(1 for b in bad if b in answer.lower())
    signals.append(max(0.0, 1.0 - penalty * 0.25))

    score = sum(signals) / len(signals)
    return {"score": round(score, 3), "n_signals": len(signals)}


# ─────────────────────────────────────────────────────────────────────────────
# 6. TREE OF THOUGHTS (ToT)
# ─────────────────────────────────────────────────────────────────────────────

def tree_of_thoughts(question: str, n_branches=3, depth=2) -> str:
    """
    Real ToT: generates multiple reasoning branches,
    scores each, prunes weak branches, continues best ones.
    """
    print(f"\n[ToT] Branching into {n_branches} thoughts, depth={depth}")

    # Root thoughts
    branches = []
    for i in range(n_branches):
        thought = generate(
            f"Start reasoning about this problem step by step (approach {i+1} of {n_branches}):\n{question}",
            temp=0.6 + i * 0.15, max_tokens=300
        )
        score = verify(question, thought)["score"]
        branches.append({"thought": thought, "score": score, "depth": 1})
        print(f"  Branch {i+1}: score={score:.3f}")

    # Prune: keep top half
    branches.sort(key=lambda x: x["score"], reverse=True)
    branches = branches[: max(1, n_branches // 2)]

    # Deepen
    for d in range(2, depth + 1):
        new_branches = []
        for b in branches:
            continuation = generate(
                f"Continue and complete this reasoning:\n{b['thought']}\n\nFinal answer:",
                temp=0.5, max_tokens=400
            )
            combined = b["thought"] + "\n" + continuation
            score = verify(question, combined)["score"]
            new_branches.append({"thought": combined, "score": score, "depth": d})
            print(f"  Depth {d} score={score:.3f}")
        branches = sorted(new_branches, key=lambda x: x["score"], reverse=True)

    best = branches[0]
    print(f"[ToT] Best score: {best['score']:.3f}")
    return best["thought"]


# ─────────────────────────────────────────────────────────────────────────────
# 7. SELF-CONSISTENCY (SC)
# ─────────────────────────────────────────────────────────────────────────────

def self_consistency(question: str, n=4) -> str:
    """
    Generate n answers at different temperatures,
    find the most common answer by embedding-free word overlap.
    """
    answers = []
    for i in range(n):
        a = generate(question, temp=0.5 + i * 0.15, max_tokens=400)
        answers.append(a)

    # Score each answer against all others (mean pairwise similarity)
    scores = []
    for i, a in enumerate(answers):
        wa = set(a.lower().split())
        sim = []
        for j, b in enumerate(answers):
            if i == j: continue
            wb = set(b.lower().split())
            if wa or wb:
                sim.append(len(wa & wb) / max(len(wa | wb), 1))
        scores.append(sum(sim) / max(len(sim), 1))

    best_idx = scores.index(max(scores))
    print(f"[SC] n={n}, best idx={best_idx}, score={scores[best_idx]:.3f}")
    return answers[best_idx]


# ─────────────────────────────────────────────────────────────────────────────
# 8. REACT AGENT LOOP
# ─────────────────────────────────────────────────────────────────────────────

AGENT_SYS = """You are GVR-Agent, an intelligent assistant with real tools.

To use a tool, output EXACTLY ONE LINE in this format:
TOOL: terminal <shell_command>
TOOL: python <one-line python code, use \\n for newlines>
TOOL: search <search query>
TOOL: memory_save <key>|<value>
TOOL: memory_get <key>

If no tool is needed, output your final answer directly.
Be precise and concise. The tool output is real."""


def react_agent(task: str, max_steps=5) -> dict:
    history = f"Task: {task}\n"
    steps = []

    for step in range(max_steps):
        response = generate(
            AGENT_SYS + "\n\n" + history + "\nYour next action:",
            temp=0.2, max_tokens=300
        )

        # parse tool call
        m = re.search(
            r"TOOL:\s*(terminal|python|search|memory_save|memory_get)\s+(.+)",
            response, re.DOTALL
        )
        if not m:
            steps.append({"step": step+1, "type": "answer", "content": response})
            return {"answer": response, "steps": steps}

        tool, arg = m.group(1), m.group(2).strip()

        if tool == "terminal":
            obs = tool_terminal(arg)
        elif tool == "python":
            obs = tool_python(arg)
        elif tool == "search":
            obs = tool_search(arg)
        elif tool == "memory_save":
            parts = arg.split("|", 1)
            mem_save(parts[0].strip(), parts[1].strip() if len(parts) > 1 else "")
            obs = f"Saved '{parts[0].strip()}' to memory."
        elif tool == "memory_get":
            obs = mem_get(arg.strip())
        else:
            obs = "Unknown tool"

        steps.append({"step": step+1, "type": f"tool:{tool}",
                       "action": arg[:80], "observation": obs[:150]})
        print(f"  [Step {step+1}] {tool}: {arg[:50]} → {obs[:80]}")
        history += f"\nAction: TOOL: {tool} {arg}\nObservation: {obs}\n"

    # forced final answer
    final = generate(
        AGENT_SYS + "\n\n" + history + "\nGive your final answer now (no tools):",
        temp=0.3, max_tokens=500
    )
    steps.append({"step": max_steps+1, "type": "final", "content": final})
    return {"answer": final, "steps": steps}


# ─────────────────────────────────────────────────────────────────────────────
# 9. FULL GVR PIPELINE (Generate → Verify → Refine)
# ─────────────────────────────────────────────────────────────────────────────

def gvr_pipeline(question: str, use_tot=False, use_sc=False) -> dict:
    """
    Complete GVR loop with optional Tree of Thoughts and Self-Consistency.
    """
    best_answer, best_score = "", -1.0
    trace = []
    refine_prefix = ""

    for iteration in range(3):
        print(f"\n[GVR] Iteration {iteration+1}/3")

        # choose generation strategy per iteration
        if iteration == 0 and use_tot:
            answer = tree_of_thoughts(question, n_branches=3, depth=2)
        elif iteration == 0 and use_sc:
            answer = self_consistency(question, n=3)
        else:
            prompt = refine_prefix + question
            answer = generate(prompt, temp=0.5 + iteration * 0.1, max_tokens=600)

        # second sample for consistency check
        second = generate(question, temp=0.9, max_tokens=200) if iteration == 0 else None

        v = verify(question, answer, second)
        score = v["score"]
        trace.append({"iter": iteration+1, "score": score,
                       "n_signals": v["n_signals"], "preview": answer[:80]})
        print(f"  Verify score: {score:.3f} ({v['n_signals']} signals)")

        if score > best_score:
            best_score, best_answer = score, answer

        if score >= 0.70:
            print("  Threshold reached — stopping early")
            break

        # build targeted refinement hint
        issues = []
        py_blocks = re.findall(r"```python\n(.*?)```", answer, re.DOTALL)
        if py_blocks:
            out = tool_python(py_blocks[0])
            if "Error" in out or "❌" in out:
                issues.append(f"Your code has an error: {out[:120]}. Fix it.")
        if not issues and score < 0.45:
            issues.append("Be more thorough and precise in your reasoning.")

        refine_prefix = (
            "Your previous answer needs improvement. " + " ".join(issues) +
            "\n\nRevised answer:\n\n"
        )

    return {
        "question": question,
        "answer": best_answer,
        "score": round(best_score, 3),
        "trace": trace,
    }


# ─────────────────────────────────────────────────────────────────────────────
# 10. TEST SUITE
# ─────────────────────────────────────────────────────────────────────────────

print("\n" + "="*60)
print("RUNNING GVR-ULTIMATE TEST SUITE")
print("="*60)

TEST_CASES = [
    # Code + execution
    {"q": "Write Python quicksort, test it on [5,2,8,1,9,3], print result", "mode": "gvr"},
    # Arabic
    {"q": "ما هو الذكاء الاصطناعي؟ اشرح ببساطة بالعربية", "mode": "gvr"},
    # Math reasoning
    {"q": "What is 23 × 47 + 89 − 15? Show step by step", "mode": "gvr"},
    # Agent with tools
    {"q": "What is today's date on this server? List files in /tmp", "mode": "agent"},
    # Web search
    {"q": "Search: DeepSeek R1 model capabilities and report findings", "mode": "agent"},
    # Tree of Thoughts
    {"q": "Explain transformer attention mechanism clearly with example", "mode": "tot"},
    # Self-Consistency
    {"q": "Write a Python binary search function with docstring", "mode": "sc"},
]

results = []
for tc in TEST_CASES:
    q    = tc["q"]
    mode = tc["mode"]
    print(f"\n{'─'*50}")
    print(f"[{mode.upper()}] {q[:65]}")

    t0 = time.time()
    if mode == "gvr":
        r = gvr_pipeline(q)
        ans  = r["answer"]
        meta = {"score": r["score"], "trace": r["trace"]}
    elif mode == "tot":
        ans  = gvr_pipeline(q, use_tot=True)["answer"]
        meta = {"method": "tree_of_thoughts"}
    elif mode == "sc":
        ans  = gvr_pipeline(q, use_sc=True)["answer"]
        meta = {"method": "self_consistency"}
    elif mode == "agent":
        r    = react_agent(q)
        ans  = r["answer"]
        meta = {"steps": r["steps"]}
    else:
        ans  = generate(q)
        meta = {}

    elapsed = time.time() - t0
    print(f"Answer ({elapsed:.1f}s): {ans[:200]}")
    results.append({"q": q, "mode": mode, "answer": ans[:400],
                    "elapsed_s": round(elapsed, 1), **meta})


# ─────────────────────────────────────────────────────────────────────────────
# 11. SAVE EVERYTHING
# ─────────────────────────────────────────────────────────────────────────────

config = {
    "version": "3.0",
    "reasoning_model": GGUF_REPO + "/" + GGUF_FILE,
    "reasoning_size_gb": round(os.path.getsize(gguf_path) / 1e9, 2) if os.path.exists(gguf_path) else 0,
    "vision_model": VISION_ID if HAS_VISION else None,
    "hardware": HW,
    "tools": ["terminal", "python_executor", "web_search", "memory", "vision"],
    "intelligence": ["gvr_loop", "tree_of_thoughts", "self_consistency",
                     "react_agent", "chain_of_thought"],
    "test_results": results,
    "ts": time.strftime("%Y-%m-%d %H:%M:%S"),
}

config_path = f"{OUT_DIR}/gvr_config.json"
with open(config_path, "w") as f:
    json.dump(config, f, indent=2, ensure_ascii=False)

print("\n" + "="*60)
print("UPLOADING TO HUGGINGFACE")
print("="*60)

if HF_TOKEN:
    api = HfApi(token=HF_TOKEN)
    api.create_repo(f"{HF_USER}/gvr-ultimate", exist_ok=True, private=False)

    # upload config
    api.upload_file(path_or_fileobj=config_path, path_in_repo="config.json",
                    repo_id=f"{HF_USER}/gvr-ultimate", repo_type="model")

    # upload memory if exists
    if os.path.exists(MEMORY_FILE):
        api.upload_file(path_or_fileobj=MEMORY_FILE, path_in_repo="memory.json",
                        repo_id=f"{HF_USER}/gvr-ultimate", repo_type="model")

    print(f"✅ https://huggingface.co/{HF_USER}/gvr-ultimate")
else:
    print("No HF_TOKEN — skipping HF upload")

# save result to GitHub
if GH_PAT:
    content = base64.b64encode(json.dumps(config, indent=2, ensure_ascii=False).encode()).decode()
    check = requests.get(
        "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/gvr_ultimate_result.json",
        headers={"Authorization": f"token {GH_PAT}"}
    )
    body = {"message": "GVR-Ultimate v3 result", "content": content}
    if check.status_code == 200:
        body["sha"] = check.json()["sha"]
    requests.put(
        "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/gvr_ultimate_result.json",
        headers={"Authorization": f"token {GH_PAT}"}, json=body
    )
    print("✅ Result saved to GitHub")

print("\n" + "="*60)
print("GVR-ULTIMATE v3.0 — DONE!")
print(json.dumps({k: v for k, v in config.items() if k != "test_results"}, indent=2))
print("="*60)
