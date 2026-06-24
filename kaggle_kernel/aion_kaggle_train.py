"""
AION Compound Brain Kernel — النظام الهجين الكامل
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
DeepSeek-R1-Distill-Qwen-14B (9GB) + MiniCPM-V-4.6 (0.8GB)
+ شجرة التفكير + التفكير الهرمي + ذاكرة دائمة
= نظام هجين 11GB كامل
"""
import os, sys, json, time, math, subprocess, glob

# ── Clone AION repo ──────────────────────────────────────────
REPO_URL = "https://github.com/alhsryahmd266-jpg/omega-ai"
WORK_DIR = "/kaggle/working"
REPO_DIR = os.path.join(WORK_DIR, "omega-ai")

if not os.path.exists(REPO_DIR):
    print("Cloning AION repo...")
    subprocess.run(["git", "clone", "--depth", "1", REPO_URL, REPO_DIR], check=True)
sys.path.insert(0, REPO_DIR)

# ── CUDA compatibility check ──────────────────────────────────
def get_device():
    import torch
    if not torch.cuda.is_available():
        print("CPU mode")
        return torch.device("cpu"), False
    try:
        cap = torch.cuda.get_device_capability(0)
        name = torch.cuda.get_device_name(0)
        print(f"GPU: {name} (sm_{cap[0]*10})")
        if cap[0] >= 7:
            print("✅ GPU متوافق")
            return torch.device("cuda"), True
        else:
            print(f"⚠️  GPU قديم (sm_{cap[0]*10}<sm_70) — CPU mode")
            return torch.device("cpu"), False
    except Exception as e:
        print(f"GPU error: {e} — CPU mode")
        return torch.device("cpu"), False

# ── البحث عن ملفات GGUF تلقائياً ────────────────────────────
def find_gguf_files():
    """يبحث في كل المسارات الممكنة على Kaggle عن ملفات GGUF"""
    search_paths = [
        "/kaggle/input/**/*.gguf",
        "/kaggle/working/**/*.gguf",
        "/kaggle/input/**/*.GGUF",
        os.path.join(WORK_DIR, "models/**/*.gguf"),
    ]
    found = []
    for pattern in search_paths:
        found.extend(glob.glob(pattern, recursive=True))
    return found

def classify_gguf(path: str):
    """يصنّف الملف: نصي (14B) أم رؤية (vision) أم mmproj"""
    name = os.path.basename(path).lower()
    size = os.path.getsize(path)
    if "mmproj" in name or "projector" in name:
        return "mmproj"
    if size > 3 * 1024**3:  # أكبر من 3GB = نموذج نصي
        return "text"
    if any(k in name for k in ["vision", "minicpm", "moondream", "llava"]):
        return "vision"
    if size < 2 * 1024**3:  # أصغر من 2GB وليس mmproj = vision
        return "vision"
    return "text"

def scan_and_report():
    print("\n🔍 بحث عن ملفات GGUF في /kaggle/input/ ...")
    files = find_gguf_files()
    if not files:
        print("⚠️  لم يتم العثور على ملفات GGUF")
        print("   تأكد إنك أضفت الـ DeepSeek dataset للـ kernel")
        return None, None, None

    print(f"✅ وجدت {len(files)} ملف:")
    text_model = None
    vision_model = None
    mmproj_model = None

    for f in files:
        size_gb = os.path.getsize(f) / 1024**3
        ftype = classify_gguf(f)
        print(f"  [{ftype:7}] {os.path.basename(f)} ({size_gb:.2f}GB)")
        if ftype == "text" and text_model is None:
            text_model = f
        elif ftype == "vision" and vision_model is None:
            vision_model = f
        elif ftype == "mmproj" and mmproj_model is None:
            mmproj_model = f

    return text_model, vision_model, mmproj_model

# ── تدريب AION الخاص ─────────────────────────────────────────
def train_aion(device, use_cuda: bool, max_minutes: float = 120):
    import torch
    import torch.nn as nn
    from torch.utils.data import DataLoader
    from omega.model.architecture import get_config, AIONModel, AIONConfig
    from omega.tokenizer.bpe import OmegaTokenizer
    from omega.trainer.train import ChatDataset, collate_fn, cosine_lr
    from omega.swarm.data_generator import DataGenerator

    out_dir = os.path.join(WORK_DIR, "checkpoints")
    os.makedirs(out_dir, exist_ok=True)

    # تحميل أو توليد البيانات
    data_path = os.path.join(REPO_DIR, "data", "training_data.json")
    with open(data_path, "r", encoding="utf-8") as f:
        samples = json.load(f)

    # توليد بيانات إضافية تلقائياً
    gen = DataGenerator()
    extra = gen.generate_from_templates(100)
    samples = samples + extra
    print(f"📊 بيانات التدريب: {len(samples)} عينة")

    # إعدادات بناءً على الجهاز
    if use_cuda:
        cfg = get_config("intensive")
        batch_size = 16
    else:
        cfg = get_config("small")
        batch_size = 4

    model = AIONModel(cfg).to(device)
    print(f"🧠 AION: {model.count_params()/1e6:.1f}M params على {device}")

    # Tokenizer
    tok_path = out_dir
    if os.path.exists(os.path.join(tok_path, "tokenizer.json")):
        tok = OmegaTokenizer.load(tok_path)
    else:
        tok = OmegaTokenizer(cfg.vocab_size)
        texts = [s if isinstance(s, str) else json.dumps(s, ensure_ascii=False) for s in samples]
        tok.train(texts, min_freq=1, verbose=False)
        tok.save(tok_path)

    # Load checkpoint if exists
    ckpt_path = os.path.join(out_dir, "aion_best.pt")
    start_gen = 0
    if os.path.exists(ckpt_path):
        ck = torch.load(ckpt_path, map_location=device)
        try:
            model.load_state_dict(ck["model"])
            start_gen = ck.get("meta", {}).get("generation", 0)
            print(f"✅ استمرار من الجيل {start_gen}")
        except Exception as e:
            print(f"⚠️  لم يتم تحميل checkpoint: {e} — تدريب جديد")

    dataset = ChatDataset(samples, tok, max_len=min(cfg.max_seq_len, 256))
    loader = DataLoader(dataset, batch_size=batch_size, shuffle=True,
                        collate_fn=collate_fn, num_workers=2,
                        pin_memory=use_cuda)

    optimizer = torch.optim.AdamW(model.parameters(), lr=2e-4,
                                   betas=(0.9, 0.95), weight_decay=0.05)
    scaler = torch.cuda.amp.GradScaler() if use_cuda else None

    # Time-budgeted training loop
    t0 = time.time()
    max_sec = max_minutes * 60
    total_loss, n_steps, n_epochs = 0.0, 0, 0
    max_lr, min_lr, warmup = 2e-4, 1e-5, 100

    print(f"\n🏋️  تدريب لمدة {max_minutes:.0f} دقيقة ...")
    model.train()
    optimizer.zero_grad()
    accum = 4

    while time.time() - t0 < max_sec:
        n_epochs += 1
        for i, (x, y) in enumerate(loader):
            if time.time() - t0 >= max_sec:
                break
            x, y = x.to(device), y.to(device)
            lr = cosine_lr(n_steps, warmup, 20000, min_lr, max_lr)
            for pg in optimizer.param_groups:
                pg["lr"] = lr

            if scaler:
                with torch.cuda.amp.autocast():
                    _, loss = model(x, y)
                scaler.scale(loss).backward()
                if (i+1) % accum == 0:
                    scaler.unscale_(optimizer)
                    nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                    scaler.step(optimizer)
                    scaler.update()
                    optimizer.zero_grad()
            else:
                _, loss = model(x, y)
                loss.backward()
                if (i+1) % accum == 0:
                    nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                    optimizer.step()
                    optimizer.zero_grad()

            total_loss += loss.item()
            n_steps += 1

            if n_steps % 100 == 0:
                el = time.time() - t0
                avg = total_loss / n_steps
                print(f"  epoch {n_epochs} | step {n_steps} | loss {loss.item():.4f} "
                      f"| avg {avg:.4f} | {el/60:.1f}min")

    elapsed = time.time() - t0
    avg_loss = total_loss / max(n_steps, 1)
    print(f"\n✅ التدريب انتهى | epochs={n_epochs} | steps={n_steps} "
          f"| avg_loss={avg_loss:.4f} | {elapsed/60:.1f}min")

    # حفظ checkpoint
    import torch as _torch
    _torch.save({
        "model": model.state_dict(),
        "meta": {"generation": start_gen + 1, "device": str(device),
                 "n_steps": n_steps, "avg_loss": avg_loss},
        "best_loss": avg_loss, "step": n_steps,
    }, ckpt_path)

    # Save config
    cfg_dict = {k: v for k, v in cfg.__dict__.items() if not callable(v)}
    with open(os.path.join(out_dir, "config.json"), "w") as f:
        json.dump(cfg_dict, f, indent=2)

    result = {"device": str(device), "avg_loss": avg_loss, "n_steps": n_steps,
              "n_epochs": n_epochs, "elapsed_min": elapsed/60,
              "generation": start_gen + 1}
    with open(os.path.join(out_dir, "kaggle_result.json"), "w") as f:
        json.dump(result, f, indent=2)

    print(json.dumps(result, indent=2, ensure_ascii=False))
    return ckpt_path

# ── بناء النظام الهجين الكامل ────────────────────────────────
def build_hybrid_system(text_gguf: str, vision_gguf: str, mmproj: str,
                        device, use_cuda: bool):
    """
    يبني النظام الهجين: DeepSeek-14B + MiniCPM-V-4.6 + شجرة تفكير + ذاكرة
    """
    print("\n" + "="*60)
    print("  بناء النظام الهجين الكامل (11GB)")
    print("="*60)

    try:
        subprocess.run(["pip", "install", "-q", "llama-cpp-python"], check=True)
    except Exception as e:
        print(f"⚠️  llama-cpp-python: {e}")
        return

    from omega.core.external_brain import ExternalBrain, ExternalBrainConfig
    from omega.core.vision_brain import VisionBrain, VisionBrainConfig
    from omega.core.compound_brain import CompoundBrain
    from omega.reasoning.hierarchical_thinking import HierarchicalReasoner
    from omega.reasoning.tree_of_thought import ExternalBrainAdapter
    from omega.memory.persistent import OmegaPersistentMemory

    gpu_layers = -1 if use_cuda else 0
    out_dir = os.path.join(WORK_DIR, "checkpoints")
    mem_path = os.path.join(out_dir, "compound_memory.db")
    memory = OmegaPersistentMemory(mem_path)

    # ── النموذج النصي 14B ───────────────────────────────────
    print(f"\n🧠 تحميل النموذج النصي 14B من: {text_gguf}")
    text_brain = ExternalBrain(ExternalBrainConfig(
        model_path=text_gguf,
        n_ctx=4096,
        n_gpu_layers=gpu_layers,
        max_tokens=512,
        temperature=0.7,
    ))

    # ── نموذج الرؤية ────────────────────────────────────────
    vision_brain = None
    if vision_gguf and os.path.exists(vision_gguf):
        if mmproj and os.path.exists(mmproj):
            print(f"\n👁  تحميل نموذج الرؤية من: {vision_gguf}")
            vision_brain = VisionBrain(VisionBrainConfig(
                model_path=vision_gguf,
                clip_model_path=mmproj,
                chat_handler_name="MiniCPMv26ChatHandler",
                n_gpu_layers=gpu_layers,
            ))
        else:
            print("⚠️  ملف mmproj غير موجود — الرؤية معطّلة")
    else:
        print("ℹ️  نموذج الرؤية غير متاح — تعمل بالنص فقط")

    # ── الذكاء المركّب الكامل ────────────────────────────────
    print("\n🌳 بناء شجرة التفكير + التفكير الهرمي + الذاكرة...")
    compound = CompoundBrain(
        text_brain=text_brain,
        vision_brain=vision_brain,
        memory=memory,
        tot_breadth=3,
        tot_depth=3,
    )

    # اختبارات حقيقية
    tests = [
        "اشرح خوارزمية Quick Sort مع كود Python كامل",
        "صمّم نظام تسجيل دخول آمن لتطبيق Android بـ Kotlin",
        "ما الفرق بين Coroutines و Threads في Android؟",
    ]

    results = []
    for i, problem in enumerate(tests, 1):
        print(f"\n❓ اختبار {i}: {problem[:60]}")
        t0 = time.time()
        result = compound.think_only(problem)
        elapsed = time.time() - t0
        print(f"✅ إجابة ({elapsed:.1f}s): {result['answer'][:150]}...")
        print(f"   الثقة: {result['confidence']:.2f}")
        results.append({
            "question": problem,
            "answer": result["answer"][:500],
            "confidence": result["confidence"],
            "elapsed_sec": elapsed,
        })

    # حفظ النتائج
    out = {
        "system": {
            "text_model": os.path.basename(text_gguf) if text_gguf else None,
            "vision_model": os.path.basename(vision_gguf) if vision_gguf else None,
            "vision_available": vision_brain is not None,
            "memory_entries": memory.stats().get("total", 0),
        },
        "tests": results,
    }
    with open(os.path.join(out_dir, "hybrid_system_test.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)

    print(f"\n✅ النظام الهجين يعمل | اختبارات: {len(results)} | "
          f"رؤية: {vision_brain is not None} | "
          f"ذاكرة: {memory.stats().get('total',0)} عنصر")
    memory.close()

# ── fix_pytorch_cuda_compat ───────────────────────────────────
def fix_pytorch_cuda_compat():
    import torch
    if not torch.cuda.is_available():
        return
    try:
        cap = torch.cuda.get_device_capability(0)
        if cap[0] < 7:
            print(f"⚠️  GPU sm_{cap[0]*10} قديم — CPU mode")
            os.environ["CUDA_VISIBLE_DEVICES"] = ""
    except Exception:
        pass

fix_pytorch_cuda_compat()

# ── Main ──────────────────────────────────────────────────────
if __name__ == "__main__":
    print("=" * 60)
    print("  AION Compound Brain — النظام الهجين الكامل")
    print("=" * 60)

    device, use_cuda = get_device()
    print(f"الجهاز: {device}")

    # الخطوة 1: ابحث عن الـ GGUF files
    text_gguf, vision_gguf, mmproj = scan_and_report()

    # الخطوة 2: درّب AION الخاص
    max_train = float(os.environ.get("AION_MAX_MINUTES", "120"))
    aion_ckpt = train_aion(device, use_cuda, max_minutes=max_train)

    # الخطوة 3: لو لقينا الـ 14B — ابني النظام الهجين الكامل
    if text_gguf:
        build_hybrid_system(text_gguf, vision_gguf, mmproj, device, use_cuda)
    else:
        print("\nℹ️  لم يتم العثور على GGUF — أضف DeepSeek كـ dataset للـ kernel")
        print("   أو فعّل AION_FETCH_14B=true لتحميله تلقائياً (يحتاج ~9GB وقت)")
