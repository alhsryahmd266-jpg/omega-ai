"""
GVR-Agent Flask API Server
يشتغل في Termux خلفية، الـ APK بيتكلم معاه
"""
from flask import Flask, request, jsonify, Response, stream_with_context
import os, sys, json, re, time, subprocess, tempfile, threading, queue

app = Flask(__name__)

BASE_DIR    = os.path.expanduser("~/gvr-agent")
MODEL_PATH  = os.path.join(BASE_DIR, "model.gguf")
MEMORY_FILE = os.path.join(BASE_DIR, "memory.json")
os.makedirs(BASE_DIR, exist_ok=True)

LLM = None
_mem = {}

def load_model():
    global LLM
    if not os.path.exists(MODEL_PATH):
        print(f"Model not found: {MODEL_PATH}"); return False
    from llama_cpp import Llama
    print("Loading model...")
    LLM = Llama(model_path=MODEL_PATH, n_ctx=8192, n_batch=512,
                n_threads=max(4, os.cpu_count() or 4),
                n_gpu_layers=0, verbose=False)
    print("✅ Model ready")
    if os.path.exists(MEMORY_FILE):
        with open(MEMORY_FILE) as f:
            _mem.update(json.load(f))
    return True

def generate(prompt, temp=0.7, max_t=800):
    if not LLM: return "Model not loaded"
    out = LLM.create_chat_completion(
        messages=[{"role":"user","content":prompt}],
        temperature=temp, max_tokens=max_t, stream=False)
    return out["choices"][0]["message"]["content"].strip()

def generate_stream_gen(prompt, temp=0.7, max_t=800):
    if not LLM: yield "Model not loaded"; return
    for chunk in LLM.create_chat_completion(
        messages=[{"role":"user","content":prompt}],
        temperature=temp, max_tokens=max_t, stream=True):
        delta = chunk["choices"][0]["delta"].get("content","")
        if delta: yield delta

def tool_terminal(cmd):
    deny = ["rm -rf /","dd if","mkfs"]
    for d in deny:
        if d in cmd: return f"Blocked: {d}"
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True,
                           text=True, timeout=15, cwd=BASE_DIR)
        return (r.stdout+(r.stderr if r.returncode else "")).strip()[:2000] or "OK"
    except subprocess.TimeoutExpired: return "Timeout"
    except Exception as e: return str(e)

def tool_python(code):
    code = code.replace("\\n","\n")
    for f in ["os.system","subprocess.Popen"]:
        if f in code: return f"Blocked: {f}"
    try:
        with tempfile.NamedTemporaryFile(mode="w",suffix=".py",delete=False) as f:
            f.write(code); p=f.name
        r = subprocess.run([sys.executable,p],capture_output=True,text=True,timeout=15)
        os.unlink(p)
        return (r.stdout+(r.stderr if r.returncode else "")).strip()[:2000] or "OK"
    except subprocess.TimeoutExpired: return "Timeout"
    except Exception as e: return str(e)

def tool_search(q):
    try:
        import requests as req
        r = req.get("https://html.duckduckgo.com/html/",
                    params={"q":q},headers={"User-Agent":"Mozilla/5.0"},timeout=10)
        clean = lambda s: re.sub(r"<[^>]+>","",s).strip()
        titles   = re.findall(r'class="result__a"[^>]*>(.*?)</a>',r.text)
        snippets = re.findall(r'class="result__snippet"[^>]*>(.*?)</a>',r.text)
        out = [f"• {clean(t)}: {clean(s)[:160]}" for t,s in zip(titles[:4],snippets[:4])]
        return "\n".join(out) or "No results"
    except Exception as e: return str(e)

TOOLS = {"terminal":tool_terminal,"python":tool_python,"search":tool_search}

AGENT_SYS = """You are GVR-Agent on Android. Use tools with:
TOOL: terminal <cmd>
TOOL: python <code>
TOOL: search <query>
Otherwise answer directly."""

def react(task, max_steps=5):
    hist = f"Task: {task}\n"; steps = []
    for i in range(max_steps):
        resp = generate(AGENT_SYS+"\n\n"+hist+"\nYour action:", temp=0.25, max_t=300)
        m = re.search(r"TOOL:\s*(\w+)\s*(.*)", resp, re.DOTALL)
        if not m: return resp, steps
        tool, arg = m.group(1), m.group(2).strip()
        obs = TOOLS.get(tool, lambda a: f"Unknown: {tool}")(arg)
        steps.append({"tool":tool,"arg":arg[:80],"obs":obs[:120]})
        hist += f"\nAction: TOOL: {tool} {arg}\nObservation: {obs}\n"
    return generate(AGENT_SYS+"\n\n"+hist+"\nFinal answer:", temp=0.3, max_t=600), steps

def gvr(question, iterations=3):
    best, bs = "", -1.0; refine=""
    for it in range(iterations):
        ans = generate(refine+question, temp=0.5+it*0.1, max_t=700)
        codes = re.findall(r"```python\n(.*?)```",ans,re.DOTALL)
        score = 0.7
        if codes:
            out = tool_python(codes[0])
            score = 0.4 if ("Error" in out) else 0.9
        if score > bs: bs=score; best=ans
        if bs >= 0.8: break
        if codes and score<0.8:
            out=tool_python(codes[0])
            refine=f"Fix code error: {out[:100]}\n\n"
    return best

# ── API ROUTES ────────────────────────────────────────────────────────────────
@app.route("/health")
def health():
    return jsonify({"status":"ok","model_loaded":LLM is not None,
                    "model_size_gb": round(os.path.getsize(MODEL_PATH)/1e9,1)
                    if os.path.exists(MODEL_PATH) else 0})

@app.route("/chat", methods=["POST"])
def chat():
    data = request.json or {}
    message = data.get("message","")
    mode    = data.get("mode","agent")
    if not message: return jsonify({"error":"No message"}), 400

    t0 = time.time()
    if mode == "agent":
        answer, steps = react(message)
        return jsonify({"answer":answer,"steps":steps,"elapsed":round(time.time()-t0,1)})
    elif mode == "gvr":
        answer = gvr(message)
        return jsonify({"answer":answer,"elapsed":round(time.time()-t0,1)})
    else:
        answer = generate(message)
        return jsonify({"answer":answer,"elapsed":round(time.time()-t0,1)})

@app.route("/chat/stream", methods=["POST"])
def chat_stream():
    data = request.json or {}
    message = data.get("message","")
    if not message: return jsonify({"error":"No message"}), 400

    def generate_sse():
        for token in generate_stream_gen(message):
            yield f"data: {json.dumps({'token':token})}\n\n"
        yield "data: [DONE]\n\n"

    return Response(stream_with_context(generate_sse()),
                    mimetype="text/event-stream",
                    headers={"Cache-Control":"no-cache","X-Accel-Buffering":"no"})

@app.route("/tool", methods=["POST"])
def tool_call():
    data = request.json or {}
    tool = data.get("tool","")
    arg  = data.get("arg","")
    result = TOOLS.get(tool, lambda a: f"Unknown tool: {tool}")(arg)
    return jsonify({"result":result})

@app.route("/memory", methods=["GET"])
def memory_list():
    return jsonify(_mem)

@app.route("/memory", methods=["POST"])
def memory_save():
    data = request.json or {}
    k,v = data.get("key",""), data.get("value","")
    _mem[k] = {"v":v,"ts":time.strftime("%H:%M")}
    with open(MEMORY_FILE,"w") as f: json.dump(_mem,f,indent=2)
    return jsonify({"ok":True})

if __name__ == "__main__":
    if not load_model():
        print("Run install.sh first!"); sys.exit(1)
    print("🚀 GVR-Agent API running on http://0.0.0.0:5000")
    app.run(host="0.0.0.0", port=5000, threaded=False)
