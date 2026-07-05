"""
AION Ultimate v3.0 — ملف واحد كامل
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TPU v5e-8 | GPU T4×2 | CPU — auto-detect
DeepSeek-14B GGUF + MiniCPM-V + شجرة التفكير + ذاكرة
إصلاح كامل: IndexError + llama-cpp + TPU metadata
"""
import os, sys, json, time, math, gc, subprocess, glob

# ══ SETUP ═══════════════════════════════════════════════
REPO_URL = "https://github.com/alhsryahmd266-jpg/omega-ai"
WORK_DIR = "/kaggle/working"
REPO_DIR = os.path.join(WORK_DIR, "omega-ai")
OUT_DIR  = os.path.join(WORK_DIR, "checkpoints")
os.makedirs(OUT_DIR, exist_ok=True)

if not os.path.exists(REPO_DIR):
    print("Cloning AION repo...")
    subprocess.run(["git","clone","--depth","1",REPO_URL,REPO_DIR],check=True)
sys.path.insert(0, REPO_DIR)

import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import DataLoader

# ══ HARDWARE AUTO-DETECT ════════════════════════════════
IS_TPU   = False
IS_GPU   = False
N_GPUS   = 0
USE_BF16 = False

def detect_hardware():
    global IS_TPU, IS_GPU, N_GPUS, USE_BF16

    # ── TPU v5e-8 ─────────────────────────────────────
    try:
        import torch_xla.core.xla_model as xm
        device  = xm.xla_device()
        IS_TPU  = True
        USE_BF16 = True
        n_cores = xm.xrt_world_size() if hasattr(xm,'xrt_world_size') else 8
        print(f"✅ TPU v5e-8 | cores={n_cores} | BF16=True")
        print("⚡ 128GB HBM — يكفي DeepSeek-14B بالكامل بدون ضغط!")
        return device
    except Exception:
        pass

    # ── GPU T4×2 ──────────────────────────────────────
    if torch.cuda.is_available():
        N_GPUS = torch.cuda.device_count()
        cap    = torch.cuda.get_device_capability(0)
        name   = torch.cuda.get_device_name(0)
        major  = cap[0]
        vram   = torch.cuda.get_device_properties(0).total_memory/1e9

        if major < 7:
            print(f"⚠️  {name} sm_{major*10} — CPU fallback")
        else:
            IS_GPU   = True
            USE_BF16 = major >= 8
            torch.backends.cudnn.benchmark         = True
            torch.backends.cuda.matmul.allow_tf32  = True
            torch.backends.cudnn.allow_tf32        = True
            total = N_GPUS * vram
            print(f"✅ {N_GPUS}× {name} sm_{major*10} | {vram:.0f}GB each = {total:.0f}GB total")
            if N_GPUS >= 2:
                print(f"⚡ T4×2: {total:.0f}GB VRAM — يكفي DeepSeek + AION معاً!")
            print(f"   cuDNN.benchmark + TF32 + BF16={USE_BF16}")
            return torch.device("cuda")

    print("⚠️  CPU mode")
    return torch.device("cpu")

DEVICE = detect_hardware()
print(f"\nالجهاز: {DEVICE} | TPU={IS_TPU} | GPU={IS_GPU}×{N_GPUS} | BF16={USE_BF16}\n")

# ══ DATASET ═════════════════════════════════════════════
def build_dataset():
    samples = []
    for fname in ["training_data.json","massive_training_data.json"]:
        p = os.path.join(REPO_DIR,"data",fname)
        if os.path.exists(p):
            d = json.load(open(p,encoding="utf-8"))
            samples.extend(d)
            print(f"  ✅ {fname}: +{len(d)}")
    try:
        from omega.swarm.data_generator import DataGenerator
        extra = DataGenerator().generate_from_templates(500)
        samples.extend(extra)
        print(f"  ✅ DataGenerator: +{len(extra)}")
    except Exception as e:
        print(f"  ⚠️  DataGenerator: {e}")
    seen,unique = set(),[]
    for s in samples:
        k = str(s)[:120]
        if k not in seen:
            seen.add(k); unique.append(s)
    print(f"📊 إجمالي: {len(unique)} عينة")
    return unique

# ══ COLLATE — إصلاح IndexError Target -1 ════════════════
def safe_collate(batch):
    """PAD=0, IGNORE=-100 — يحل IndexError: Target -1 out of bounds"""
    xs,ys = zip(*batch)
    L = max(len(x) for x in xs)
    xp = torch.zeros(len(xs),L,dtype=torch.long)
    yp = torch.full((len(ys),L),-100,dtype=torch.long)
    for i,(x,y) in enumerate(zip(xs,ys)):
        n=len(x)
        xp[i,:n] = torch.tensor(x[:n],dtype=torch.long)
        yp[i,:n] = torch.tensor(y[:n],dtype=torch.long)
    return xp, yp

def ce_loss(logits, targets):
    B,T,V = logits.shape
    return F.cross_entropy(
        logits.reshape(B*T,V),
        targets.reshape(B*T),
        ignore_index=-100,
        label_smoothing=0.1,
    )

def cosine_lr(step, warmup=200, total=30000, lo=1e-5, hi=3e-4):
    if step < warmup: return hi * step/warmup
    p = min(1.0,(step-warmup)/(total-warmup))
    return lo + 0.5*(hi-lo)*(1+math.cos(math.pi*p))

# ══ TRAIN AION ══════════════════════════════════════════
def train_aion(max_minutes=120.0):
    from omega.model.architecture import get_config, AIONModel, AIONConfig
    from omega.tokenizer.bpe import OmegaTokenizer
    from omega.trainer.train import ChatDataset

    print("\n"+"━"*50)
    print("  AION Training")
    print("━"*50)

    if IS_TPU:
        cfg=get_config("intensive"); BS=32; ACCUM=1
    elif IS_GPU:
        cfg=get_config("intensive"); BS=16*N_GPUS; ACCUM=max(1,4//N_GPUS)
    else:
        cfg=get_config("small"); BS=4; ACCUM=8

    model = AIONModel(cfg).to(DEVICE)
    print(f"🧠 AION {model.count_params()/1e6:.1f}M | dim={cfg.dim} | layers={cfg.n_layers}")
    print(f"   BS={BS} | accum={ACCUM} | device={DEVICE}")

    if IS_GPU and N_GPUS > 1:
        model = nn.DataParallel(model)
        print(f"⚡ DataParallel: {N_GPUS}× GPU")

    if IS_GPU and hasattr(torch,"compile"):
        try:
            model = torch.compile(model, mode="max-autotune")
            print("⚡ torch.compile(max-autotune)")
        except Exception as e:
            print(f"⚠️  compile: {e}")

    # Tokenizer
    if os.path.exists(os.path.join(OUT_DIR,"tokenizer.json")):
        tok = OmegaTokenizer.load(OUT_DIR)
    else:
        samples = build_dataset()
        tok = OmegaTokenizer(cfg.vocab_size)
        tok.train([s if isinstance(s,str) else json.dumps(s,ensure_ascii=False)
                   for s in samples], min_freq=1, verbose=False)
        tok.save(OUT_DIR)
    print(f"✅ Tokenizer | vocab={len(tok.vocab)}")

    samples = build_dataset()
    dataset = ChatDataset(samples, tok, max_len=min(cfg.max_seq_len,256))
    loader  = DataLoader(dataset, batch_size=BS, shuffle=True,
                         collate_fn=safe_collate, num_workers=2,
                         pin_memory=IS_GPU,
                         prefetch_factor=2 if IS_GPU else None)

    # Load checkpoint
    ckpt = os.path.join(OUT_DIR,"aion_best.pt")
    gen  = 0
    if os.path.exists(ckpt):
        try:
            ck = torch.load(ckpt, map_location=DEVICE)
            raw = (model._orig_mod if hasattr(model,'_orig_mod') else
                   model.module    if hasattr(model,'module')    else model)
            raw.load_state_dict(ck["model"])
            gen = ck.get("meta",{}).get("generation",0)
            print(f"✅ Checkpoint جيل={gen} | loss={ck.get('best_loss','?')}")
        except Exception as e:
            print(f"⚠️  Checkpoint: {e}")

    # EMA
    from omega.model.architecture import AIONModel as _AM
    raw = (model._orig_mod if hasattr(model,'_orig_mod') else
           model.module    if hasattr(model,'module')    else model)
    ema = _AM(cfg).to(DEVICE)
    ema.load_state_dict(raw.state_dict())
    ema.eval()
    print("⚡ EMA decay=0.999")

    def upd_ema():
        s = (model._orig_mod if hasattr(model,'_orig_mod') else
             model.module    if hasattr(model,'module')    else model)
        with torch.no_grad():
            for ep,sp in zip(ema.parameters(),s.parameters()):
                ep.data.mul_(0.999).add_(sp.data,alpha=0.001)

    # Optimizer (fused)
    try:
        opt = torch.optim.AdamW(model.parameters(),lr=3e-4,
              betas=(0.9,0.95),weight_decay=0.1,fused=IS_GPU)
        if IS_GPU: print("⚡ AdamW fused")
    except TypeError:
        opt = torch.optim.AdamW(model.parameters(),lr=3e-4,
              betas=(0.9,0.95),weight_decay=0.1)

    amp_dt = (torch.bfloat16 if USE_BF16 else
              torch.float16  if IS_GPU   else None)
    scaler = torch.cuda.amp.GradScaler() if (IS_GPU and not USE_BF16) else None
    if amp_dt: print(f"⚡ AMP {amp_dt}")

    # ── Training loop ─────────────────────────────────
    model.train(); opt.zero_grad()
    t0=time.time(); ms=max_minutes*60
    total_loss=n_steps=n_epochs=0
    best=float("inf"); last_ckpt=t0

    print(f"\n🏋️  {max_minutes:.0f} دقيقة | hardware={DEVICE}")

    while time.time()-t0 < ms:
        n_epochs += 1
        for i,(x,y) in enumerate(loader):
            if time.time()-t0 >= ms: break
            x,y = x.to(DEVICE), y.to(DEVICE)
            for pg in opt.param_groups: pg["lr"] = cosine_lr(n_steps)

            if IS_TPU:
                import torch_xla.core.xla_model as xm
                logits,_ = model(x)
                loss = ce_loss(logits,y)/ACCUM
                loss.backward()
            elif amp_dt:
                with torch.autocast("cuda",dtype=amp_dt):
                    logits,_ = model(x)
                    loss = ce_loss(logits,y)/ACCUM
                (scaler.scale(loss) if scaler else loss).backward()
            else:
                logits,_ = model(x)
                loss = ce_loss(logits,y)/ACCUM
                loss.backward()

            total_loss += loss.item()*ACCUM
            n_steps    += 1

            if (i+1)%ACCUM == 0:
                if IS_TPU:
                    import torch_xla.core.xla_model as xm
                    nn.utils.clip_grad_norm_(model.parameters(),1.0)
                    opt.step(); xm.mark_step()
                elif scaler:
                    scaler.unscale_(opt)
                    nn.utils.clip_grad_norm_(model.parameters(),1.0)
                    scaler.step(opt); scaler.update()
                else:
                    nn.utils.clip_grad_norm_(model.parameters(),1.0)
                    opt.step()
                opt.zero_grad(); upd_ema()

            if n_steps%50==0:
                el=time.time()-t0; avg=total_loss/n_steps
                print(f"  e{n_epochs} s{n_steps} | loss={loss.item()*ACCUM:.4f}"
                      f" | avg={avg:.4f} | {el/60:.1f}min")

            if time.time()-last_ckpt >= 600:
                avg=total_loss/max(n_steps,1)
                if avg<best: best=avg
                _ckpt(ema,gen,n_steps,avg,best,cfg)
                last_ckpt=time.time(); gc.collect()

        print(f"  ── epoch {n_epochs} done ──")

    elapsed=time.time()-t0
    avg=total_loss/max(n_steps,1)
    if avg<best: best=avg
    _ckpt(ema,gen+1,n_steps,avg,best,cfg)

    result={"device":str(DEVICE),"is_tpu":IS_TPU,"n_gpus":N_GPUS,
            "bf16":USE_BF16,"avg_loss":avg,"best_loss":best,
            "n_steps":n_steps,"n_epochs":n_epochs,
            "elapsed_min":elapsed/60,"generation":gen+1,"ema":True}
    with open(os.path.join(OUT_DIR,"kaggle_result.json"),"w") as f:
        json.dump(result,f,indent=2)
    print(f"\n✅ Training | steps={n_steps} | avg={avg:.4f} | {elapsed/60:.1f}min")
    print(json.dumps(result,indent=2,ensure_ascii=False))

def _ckpt(ema,gen,steps,avg,best,cfg):
    p=os.path.join(OUT_DIR,"aion_best.pt")
    torch.save({"model":ema.state_dict(),
                "meta":{"generation":gen,"ema":True},
                "best_loss":best,"step":steps},p)
    with open(os.path.join(OUT_DIR,"config.json"),"w") as f:
        json.dump({k:v for k,v in cfg.__dict__.items() if not callable(v)},f,indent=2)

# ══ GGUF SCANNER ════════════════════════════════════════
def scan_gguf():
    found=[]
    for p in ["/kaggle/input/**/*.gguf","/kaggle/input/**/*.GGUF",
              "/kaggle/working/models/**/*.gguf"]:
        found.extend(glob.glob(p,recursive=True))

    if not found:
        print("⚠️  لا GGUF في /kaggle/input/ — تأكد إن dataset مربوط بالـ kernel")
        return None,None,None

    text_m=vision_m=mmproj_m=None
    print(f"🔍 وُجد {len(found)} GGUF:")
    for f in found:
        sz=os.path.getsize(f)/1e9
        n=os.path.basename(f).lower()
        if "mmproj" in n or "projector" in n:
            t="mmproj"; mmproj_m=mmproj_m or f
        elif sz>3:
            t="text-14B"; text_m=text_m or f
        elif any(k in n for k in ["vision","minicpm","llava","moondream"]):
            t="vision"; vision_m=vision_m or f
        else:
            t="text"; text_m=text_m or f
        print(f"  [{t:10}] {os.path.basename(f)} ({sz:.2f}GB)")
    return text_m,vision_m,mmproj_m

# ══ COMPOUND BRAIN ══════════════════════════════════════
def install_llama():
    """يثبّت llama-cpp-python بدعم GPU لو متاح"""
    if IS_GPU:
        print("⚡ تثبيت llama-cpp-python+CUDA...")
        r=subprocess.run(
            ["pip","install","-q","--upgrade","llama-cpp-python",
             "--extra-index-url",
             "https://abetlen.github.io/llama-cpp-python/whl/cu121"],
            capture_output=True,text=True)
        if r.returncode==0:
            print("✅ llama-cpp+CUDA"); return True
        print(f"  CUDA build فشل ({r.stderr[-100:]}), جرب CPU...")
    r=subprocess.run(["pip","install","-q","llama-cpp-python"],
                     capture_output=True,text=True)
    if r.returncode==0:
        print("✅ llama-cpp (CPU)"); return True
    print(f"❌ llama-cpp فشل: {r.stderr[-200:]}"); return False

def build_compound(text_gguf,vision_gguf,mmproj):
    if not text_gguf: return

    print("\n"+"═"*50)
    print(f"  الذكاء المركّب | {os.path.basename(text_gguf)}")
    print("═"*50)

    if not install_llama(): return

    from omega.core.external_brain import ExternalBrain, ExternalBrainConfig
    from omega.core.compound_brain import CompoundBrain
    from omega.reasoning.tree_of_thought import TreeOfThought, ExternalBrainAdapter
    from omega.reasoning.hierarchical_thinking import HierarchicalReasoner
    from omega.memory.persistent import OmegaPersistentMemory

    # TPU لا يدعم llama-cpp مباشرة — نشغّل على CPU
    gpu_layers = (-1 if IS_GPU else 0)
    print(f"  gpu_layers={gpu_layers} | {'GPU' if IS_GPU else 'CPU'}")

    brain = ExternalBrain(ExternalBrainConfig(
        model_path=text_gguf, n_ctx=4096,
        n_gpu_layers=gpu_layers, max_tokens=512, temperature=0.7))

    mem = OmegaPersistentMemory(os.path.join(OUT_DIR,"compound_memory.db"))

    # Vision
    vbrain=None
    if vision_gguf and mmproj and os.path.exists(vision_gguf) and os.path.exists(mmproj):
        try:
            from omega.core.vision_brain import VisionBrain, VisionBrainConfig
            vbrain=VisionBrain(VisionBrainConfig(
                model_path=vision_gguf, clip_model_path=mmproj,
                chat_handler_name="MiniCPMv26ChatHandler",
                n_gpu_layers=gpu_layers))
            print("✅ MiniCPM-V (رؤية+فيديو)")
        except Exception as e:
            print(f"⚠️  Vision: {e}")

    tot  = TreeOfThought(
        brain=ExternalBrainAdapter(brain,"أنت محلل برمجي دقيق"),
        breadth=2,keep_top=2,max_depth=2,memory=mem)
    hier = HierarchicalReasoner(
        brain=ExternalBrainAdapter(brain),memory=mem)

    tests=["اشرح Quick Sort مع كود Python كامل",
           "صمّم نظام تسجيل دخول آمن لـ Android بـ Kotlin",
           "الفرق بين Coroutines و Threads في Android"]

    results=[]
    for q in tests:
        print(f"\n❓ {q[:60]}")
        t0=time.time()
        try:
            r=tot.solve(q); dt=time.time()-t0
            print(f"  🌳 ToT ({dt:.1f}s | conf={r['confidence']:.2f}): {r['answer'][:100]}...")
            results.append({"q":q,"answer":r["answer"][:300],"conf":r["confidence"]})
        except Exception as e:
            print(f"  ❌ ToT: {e}")
            results.append({"q":q,"error":str(e)})

    # تفكير هرمي
    try:
        print("\n🔺 التفكير الهرمي (3 مستويات)...")
        hr=hier.solve("صمّم بنية Android متكاملة بـ Clean Architecture + MVVM")
        for l in hr["levels"]:
            print(f"  📍 {l.name}: {l.answer[:80]}...")
        results.append({"hierarchical":True,"levels":len(hr["levels"])})
    except Exception as e:
        print(f"  ⚠️  Hierarchical: {e}")

    mem.close()
    out={"system":{"text_model":os.path.basename(text_gguf),
                   "vision":os.path.basename(vision_gguf) if vision_gguf else None,
                   "device":str(DEVICE),"gpu_layers":gpu_layers,
                   "n_gpus":N_GPUS,"is_tpu":IS_TPU},
         "tests":results}
    with open(os.path.join(OUT_DIR,"hybrid_system_test.json"),"w",encoding="utf-8") as f:
        json.dump(out,f,ensure_ascii=False,indent=2)
    print(f"\n✅ Compound Brain | tests={len(results)} | vision={'✅' if vbrain else '❌'}")

# ══ MAIN ════════════════════════════════════════════════
if __name__ == "__main__":
    hw = ("TPU v5e-8" if IS_TPU else
          f"GPU T4×{N_GPUS}" if IS_GPU else "CPU")
    print("\n"+"═"*50)
    print(f"  AION Ultimate v3.0 | {hw}")
    print("═"*50)

    max_min = float(os.environ.get("AION_MAX_MINUTES","120"))

    text_gguf,vision_gguf,mmproj = scan_gguf()

    # وزّع الوقت
    train_min = max_min*0.6 if text_gguf and (IS_GPU or IS_TPU) else max_min
    if text_gguf: print(f"⚡ تدريب={train_min:.0f}min | inference={max_min-train_min:.0f}min")

    train_aion(max_minutes=train_min)
    build_compound(text_gguf, vision_gguf, mmproj)

    print("\n"+"═"*50)
    print("  AION Ultimate v3.0 — اكتمل ✅")
    print("═"*50)
