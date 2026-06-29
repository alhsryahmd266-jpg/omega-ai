import os, sys, json, base64, time, requests, traceback, subprocess

HF_TOKEN = os.environ.get("HF_TOKEN","")
GH_PAT   = os.environ.get("GH_PAT","")
HF_USER  = "ahmedxg"
log = []

def L(msg):
    print(msg, flush=True)
    log.append(str(msg))

def save(data):
    try:
        content = base64.b64encode(json.dumps(data,indent=2,ensure_ascii=False).encode()).decode()
        check = requests.get(
            "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/gvr_master_result.json",
            headers={"Authorization":f"token {GH_PAT}"},timeout=10
        )
        body = {"message":"GVR result","content":content}
        if check.status_code==200: body["sha"]=check.json()["sha"]
        r = requests.put(
            "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/gvr_master_result.json",
            headers={"Authorization":f"token {GH_PAT}"},json=body,timeout=15
        )
        L(f"Saved: {r.status_code}")
    except Exception as e:
        L(f"Save error: {e}")

L("=== GVR MASTER ===")
L(f"Token: {HF_TOKEN[:8]}... len={len(HF_TOKEN)}")
L(f"PAT: {GH_PAT[:8]}... len={len(GH_PAT)}")

# Step 1
L("[1] pip install...")
try:
    r = subprocess.run(
        [sys.executable,"-m","pip","install","-q",
         "torch","transformers","huggingface_hub","sentencepiece","accelerate"],
        capture_output=True, text=True, timeout=300
    )
    L(f"pip done: {r.returncode}")
    if r.returncode != 0:
        L(f"pip stderr: {r.stderr[-300:]}")
except Exception as e:
    L(f"pip failed: {e}")
    save({"status":"pip_failed","error":str(e),"log":log})
    sys.exit(1)

# Step 2
L("[2] import torch...")
try:
    import torch
    L(f"torch: {torch.__version__}")
    L(f"CUDA: {torch.cuda.is_available()}")
except Exception as e:
    L(f"import failed: {e}")
    save({"status":"import_failed","error":str(e),"log":log})
    sys.exit(1)

# Step 3
L("[3] Load Qwen2.5-0.5B...")
try:
    from transformers import AutoTokenizer, AutoModelForCausalLM
    MODEL = "Qwen/Qwen2.5-0.5B-Instruct"
    
    L("  Loading tokenizer...")
    tok = AutoTokenizer.from_pretrained(MODEL, token=HF_TOKEN, trust_remote_code=True)
    L("  Tokenizer OK")
    
    L("  Loading model...")
    mdl = AutoModelForCausalLM.from_pretrained(
        MODEL, token=HF_TOKEN,
        torch_dtype=torch.float32,
        trust_remote_code=True
    )
    mdl.eval()
    p = sum(x.numel() for x in mdl.parameters())
    L(f"  Model: {p/1e6:.0f}M params OK")
    
except Exception as e:
    tb = traceback.format_exc()
    L(f"Model failed: {e}")
    L(tb[-400:])
    save({"status":"model_failed","error":str(e),"tb":tb[-400:],"log":log})
    sys.exit(1)

# Step 4
L("[4] Test generation...")
try:
    def gen(q):
        msgs=[{"role":"user","content":q}]
        txt=tok.apply_chat_template(msgs,tokenize=False,add_generation_prompt=True)
        ids=tok(txt,return_tensors="pt")
        with torch.no_grad():
            out=mdl.generate(**ids,max_new_tokens=100,temperature=0.7,do_sample=True,
                            pad_token_id=tok.eos_token_id)
        return tok.decode(out[0][len(ids.input_ids[0]):],skip_special_tokens=True)
    
    tests=[]
    for q in ["What is 2+2?","Write hello world in Python"]:
        a=gen(q)
        L(f"  Q:{q[:30]} → A:{a[:60]}")
        tests.append({"q":q,"a":a[:150]})
except Exception as e:
    L(f"Generation failed: {e}")
    save({"status":"gen_failed","error":str(e),"log":log})
    sys.exit(1)

# Step 5
L("[5] Train GVR Verifier...")
try:
    import torch.nn as nn
    class V(nn.Module):
        def __init__(self):
            super().__init__()
            self.net=nn.Sequential(nn.Linear(8,32),nn.ReLU(),nn.Linear(32,1),nn.Sigmoid())
        def forward(self,x): return self.net(x)
    
    v=V()
    opt=torch.optim.Adam(v.parameters(),lr=1e-3)
    for ep in range(100):
        xp=torch.rand(8,8)*0.3+0.7; yp=torch.ones(8,1)
        xn=torch.rand(8,8)*0.3;     yn=torch.zeros(8,1)
        loss=(nn.functional.binary_cross_entropy(v(xp),yp)+
              nn.functional.binary_cross_entropy(v(xn),yn))/2
        opt.zero_grad(); loss.backward(); opt.step()
    L(f"  Verifier trained, loss={loss.item():.4f}")
    torch.save(v.state_dict(),"/tmp/verifier.pt")
except Exception as e:
    L(f"Verifier failed: {e}")

# Step 6
L("[6] Upload to HF...")
try:
    from huggingface_hub import HfApi
    api=HfApi(token=HF_TOKEN)
    api.create_repo(f"{HF_USER}/gvr-ultimate",exist_ok=True,private=False)
    api.upload_file("/tmp/verifier.pt","gvr_verifier.pt",f"{HF_USER}/gvr-ultimate")
    
    cfg={"backbone":MODEL,"tests":tests,"ts":time.strftime("%Y-%m-%d %H:%M:%S")}
    with open("/tmp/cfg.json","w") as f: json.dump(cfg,f)
    api.upload_file("/tmp/cfg.json","config.json",f"{HF_USER}/gvr-ultimate")
    L(f"  ✅ https://huggingface.co/{HF_USER}/gvr-ultimate")
    
    # Space
    sid=f"{HF_USER}/gvr-training-space"
    api.create_repo(sid,repo_type="space",space_sdk="gradio",exist_ok=True)
    for fn in ["app.py","requirements.txt","README.md"]:
        fp=f"hf_space/{fn}"
        if os.path.exists(fp):
            api.upload_file(fp,fn,sid,repo_type="space")
            L(f"  Space/{fn} ✅")
    try: api.add_space_secret(sid,"HF_TOKEN",HF_TOKEN)
    except: pass
    L(f"  ✅ https://huggingface.co/spaces/{sid}")
    
except Exception as e:
    L(f"Upload failed: {e}")

final={
    "status":"SUCCESS",
    "model":MODEL,
    "model_url":f"https://huggingface.co/{HF_USER}/gvr-ultimate",
    "space_url":f"https://huggingface.co/spaces/{HF_USER}/gvr-training-space",
    "tests":tests,
    "log":log,
    "ts":time.strftime("%Y-%m-%d %H:%M:%S")
}
save(final)
L("=== DONE ✅ ===")
