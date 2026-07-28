"""
GVR-Agent v4 — Phone Edition (Termux)
======================================
Model  : DeepSeek-R1-Distill-Qwen-14B Q4_K_M (local GGUF)
Tools  : Terminal · Web Search · Code · Files · Memory
Intel  : GVR Loop · ReAct · CoT
"""
import os,sys,json,re,time,subprocess,tempfile

try:
    from rich.console import Console
    from rich.prompt import Prompt
    RICH=True; console=Console()
except ImportError:
    RICH=False; console=None

BASE_DIR    = os.path.expanduser("~/gvr-agent")
MODEL_PATH  = os.path.join(BASE_DIR,"model.gguf")
MEMORY_FILE = os.path.join(BASE_DIR,"memory.json")
HISTORY_FILE= os.path.join(BASE_DIR,"history.json")
os.makedirs(BASE_DIR,exist_ok=True)

def pr(msg,style=""):
    if RICH and style: console.print(msg,style=style)
    else: print(msg)

def header():
    pr("""
╔══════════════════════════════════════╗
║     GVR-Agent  v4  Phone Edition     ║
║  DeepSeek-R1-14B · Tools · Local    ║
╚══════════════════════════════════════╝""","bold cyan")

# ── LOAD MODEL ────────────────────────────────────────────────────────────────
LLM=None
def load_model():
    global LLM
    if not os.path.exists(MODEL_PATH):
        pr(f"Model not found: {MODEL_PATH}","red")
        pr("Run install.sh first","yellow"); sys.exit(1)
    pr(f"\nLoading {os.path.getsize(MODEL_PATH)/1e9:.1f}GB model...","yellow")
    from llama_cpp import Llama
    LLM=Llama(model_path=MODEL_PATH,n_ctx=8192,n_batch=512,
              n_threads=max(4,os.cpu_count() or 4),
              n_gpu_layers=0,verbose=False)
    pr("Model loaded ✅","green")

# ── GENERATE ──────────────────────────────────────────────────────────────────
def generate(prompt,temp=0.7,max_t=800,system=""):
    msgs=[]
    if system: msgs.append({"role":"system","content":system})
    msgs.append({"role":"user","content":prompt})
    out=LLM.create_chat_completion(messages=msgs,temperature=temp,
                                    max_tokens=max_t,stream=False)
    return out["choices"][0]["message"]["content"].strip()

def generate_stream(prompt,temp=0.7,max_t=800,system=""):
    msgs=[]
    if system: msgs.append({"role":"system","content":system})
    msgs.append({"role":"user","content":prompt})
    full=""
    print("\n",end="",flush=True)
    for chunk in LLM.create_chat_completion(messages=msgs,temperature=temp,
                                             max_tokens=max_t,stream=True):
        delta=chunk["choices"][0]["delta"].get("content","")
        if delta: print(delta,end="",flush=True); full+=delta
    print("\n",flush=True)
    return full.strip()

# ── TOOLS ─────────────────────────────────────────────────────────────────────
def tool_terminal(cmd):
    deny=["rm -rf /","dd if","mkfs",":(){ :|:& };:"]
    for d in deny:
        if d in cmd: return f"Blocked: '{d}'"
    try:
        r=subprocess.run(cmd,shell=True,capture_output=True,text=True,
                          timeout=15,cwd=BASE_DIR)
        return (r.stdout+(r.stderr if r.returncode else "")).strip()[:2000] or "OK"
    except subprocess.TimeoutExpired: return "Timeout (15s)"
    except Exception as e: return f"Error: {e}"

def tool_python(code):
    code=code.replace("\\n","\n")
    for f in ["os.system","subprocess.Popen"]:
        if f in code: return f"Blocked: '{f}'"
    try:
        with tempfile.NamedTemporaryFile(mode="w",suffix=".py",delete=False) as f:
            f.write(code); p=f.name
        r=subprocess.run([sys.executable,p],capture_output=True,text=True,timeout=15)
        os.unlink(p)
        return (r.stdout+(r.stderr if r.returncode else "")).strip()[:2000] or "OK"
    except subprocess.TimeoutExpired: return "Timeout"
    except Exception as e: return f"Error: {e}"

def tool_search(q):
    try:
        import requests
        r=requests.get("https://html.duckduckgo.com/html/",
                        params={"q":q},headers={"User-Agent":"Mozilla/5.0"},timeout=10)
        clean=lambda s: re.sub(r"<[^>]+>","",s).strip()
        titles  =re.findall(r'class="result__a"[^>]*>(.*?)</a>',r.text)
        snippets=re.findall(r'class="result__snippet"[^>]*>(.*?)</a>',r.text)
        out=[f"• {clean(t)}: {clean(s)[:170]}" for t,s in zip(titles[:4],snippets[:4])]
        return "\n".join(out) if out else "No results"
    except Exception as e: return f"Search error: {e}"

def tool_file_read(path):
    path=os.path.expanduser(path.strip())
    if not os.path.exists(path): return f"Not found: {path}"
    try:
        with open(path) as f: return f.read(3000)
    except Exception as e: return f"Error: {e}"

def tool_file_write(args):
    parts=args.split("|",1)
    if len(parts)<2: return "Format: path|content"
    path=os.path.expanduser(parts[0].strip())
    content=parts[1].replace("\\n","\n")
    try:
        with open(path,"w") as f: f.write(content)
        return f"Written to {path}"
    except Exception as e: return f"Error: {e}"

_mem={}
def load_memory():
    global _mem
    if os.path.exists(MEMORY_FILE):
        with open(MEMORY_FILE) as f: _mem=json.load(f)

def save_memory_disk():
    with open(MEMORY_FILE,"w") as f: json.dump(_mem,f,indent=2,ensure_ascii=False)

def mem_save(args):
    p=args.split("|",1); k=p[0].strip(); v=p[1].strip() if len(p)>1 else ""
    _mem[k]={"v":v,"ts":time.strftime("%H:%M")}; save_memory_disk()
    return f"Saved '{k}'"

def mem_get(k):
    k=k.strip(); x=_mem.get(k); return x["v"] if x else f"No memory for '{k}'"

def mem_list():
    return "\n".join(f"• {k}: {v['v'][:60]}" for k,v in _mem.items()) or "Empty"

TOOLS={
    "terminal":tool_terminal,"python":tool_python,"search":tool_search,
    "file_read":tool_file_read,"file_write":tool_file_write,
    "mem_save":mem_save,"mem_get":mem_get,"mem_list":lambda _:mem_list(),
}

AGENT_SYS="""You are GVR-Agent on an Android phone (Termux). You have real tools.
Use a tool with EXACTLY ONE LINE:
TOOL: terminal <cmd>
TOOL: python <code (\\n for newlines)>
TOOL: search <query>
TOOL: file_read <path>
TOOL: file_write <path>|<content>
TOOL: mem_save <key>|<value>
TOOL: mem_get <key>
TOOL: mem_list
Otherwise write your final answer. Be concise."""

def react(task,max_steps=6,stream=True):
    hist=f"Task: {task}\n"
    for step in range(max_steps):
        prompt=AGENT_SYS+"\n\n"+hist+"\nYour action:"
        resp=generate_stream(prompt,temp=0.25,max_t=300) if (stream and step==0) \
             else generate(prompt,temp=0.25,max_t=300)
        m=re.search(r"TOOL:\s*(\w+)\s*(.*)",resp,re.DOTALL)
        if not m: return resp
        tool,arg=m.group(1),m.group(2).strip()
        pr(f"\n  [{tool}] {arg[:60]}","dim cyan")
        obs=TOOLS.get(tool,lambda a:f"Unknown tool: {tool}")(arg)
        pr(f"  → {obs[:100]}","dim green")
        hist+=f"\nAction: TOOL: {tool} {arg}\nObservation: {obs}\n"
    return generate(AGENT_SYS+"\n\n"+hist+"\nFinal answer (no tools):",temp=0.3,max_t=600)

def verify(q,a):
    sigs=[min(len(a.split())/50,1.0)]
    codes=re.findall(r"```python\n(.*?)```",a,re.DOTALL)
    if codes:
        out=tool_python(codes[0])
        ok=0.0 if ("Error" in out) else 1.0
        sigs+=[ok,ok]
    bad=["i cannot","i can't","i'm unable"]
    sigs.append(max(0,1-sum(1 for b in bad if b in a.lower())*0.3))
    return round(sum(sigs)/len(sigs),3)

def gvr(question,stream=True):
    best,bs,refine="","",""
    for it in range(3):
        pr(f"  [iter {it+1}/3]","dim")
        ans=generate_stream(refine+question,temp=0.5+it*0.1,max_t=700) if (stream and it==0) \
            else generate(refine+question,temp=0.5+it*0.1,max_t=700)
        sc=verify(question,ans)
        pr(f"  score={sc:.3f}","dim")
        if sc>bs: bs=sc; best=ans
        if sc>=0.68: break
        codes=re.findall(r"```python\n(.*?)```",ans,re.DOTALL)
        issues=[]
        if codes:
            out=tool_python(codes[0])
            if "Error" in out: issues.append(f"Code error: {out[:80]}. Fix it.")
        if not issues: issues.append("Be more complete.")
        refine="Improve previous answer. "+" ".join(issues)+"\n\n"
    return best

# ── MAIN ──────────────────────────────────────────────────────────────────────
HELP="""
/help     show this
/memory   show memory
/clear    clear screen
/mode agent|gvr|chat   switch mode
/exit     quit
"""

def main():
    header()
    load_memory()
    load_model()
    mode="agent"
    pr(f"\nMode:[{mode}]  /help for commands\n","bold")
    hist=[]
    while True:
        try:
            user=(Prompt.ask("\n[bold green]You[/bold green]") if RICH
                  else input("\nYou: ")).strip()
        except (KeyboardInterrupt,EOFError):
            pr("\nGoodbye!","cyan"); break
        if not user: continue
        if user.startswith("/"):
            cmd=user.lower()
            if cmd=="/exit": pr("Goodbye!","cyan"); break
            elif cmd=="/help": pr(HELP,"dim")
            elif cmd=="/memory": pr(mem_list(),"dim")
            elif cmd=="/clear": os.system("clear"); header()
            elif cmd.startswith("/mode "):
                mode=cmd.split("/mode ")[1].strip()
                pr(f"Mode: [{mode}]","yellow")
            continue
        pr("\n[bold blue]GVR-Agent[/bold blue]" if RICH else "\nGVR-Agent:","")
        t0=time.time()
        if mode=="agent":   resp=react(user,stream=True)
        elif mode=="gvr":   resp=gvr(user,stream=True)
        else:               resp=generate_stream(user,temp=0.7,max_t=800)
        pr(f"\n({round(time.time()-t0,1)}s)","dim")
        hist.append({"u":user,"a":resp,"mode":mode})
        if len(hist)>100: hist=hist[-100:]
        with open(HISTORY_FILE,"w") as f: json.dump(hist,f,indent=2,ensure_ascii=False)

if __name__=="__main__":
    main()
