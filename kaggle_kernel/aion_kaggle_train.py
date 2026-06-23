"""
AION Kaggle GPU Training Kernel v3
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
يشتغل على Kaggle ويدرّب AION ثم يحمّل النموذج الهجين:
  - DeepSeek-R1-Distill-Qwen-14B (العقل النصي، ~9GB)
  - MiniCPM-V-4.6 (الرؤية والفيديو، ~0.8GB)
  - شجرة التفكير + التفكير الهرمي + الذاكرة الدائمة
"""

import os
import sys
import json
import time
import subprocess
import urllib.request

# ── استنساخ الريبو من GitHub ──────────────────────────────
REPO_URL = "https://github.com/alhsryahmd266-jpg/omega-ai"
WORK_DIR = "/kaggle/working"
REPO_DIR = os.path.join(WORK_DIR, "omega-ai")

if not os.path.exists(REPO_DIR):
    subprocess.run(["git", "clone", "--depth", "1", REPO_URL, REPO_DIR], check=True)

sys.path.insert(0, REPO_DIR)

import torch
import torch.nn as nn
from torch.utils.data import DataLoader

from omega.model.architecture import get_config, AIONModel, AIONConfig
from omega.tokenizer.bpe import OmegaTokenizer
from omega.trainer.train import ChatDataset, collate_fn, cosine_lr


# ═══════════════════════════════════════════════════════════
# الجهاز: P100 (sm_60) غير متوافق مع PyTorch الحديث — CPU أأمن
# ═══════════════════════════════════════════════════════════
def get_device():
    if not torch.cuda.is_available():
        return torch.device('cpu'), False
    try:
        cap_major = torch.cuda.get_device_capability(0)[0]
        if cap_major >= 7:
            print(f"✅ GPU متوافق sm_{cap_major*10} — CUDA")
            return torch.device('cuda'), True
        else:
            print(f"⚠️  GPU sm_{cap_major*10} < sm_70 — CPU fallback")
            return torch.device('cpu'), False
    except Exception as e:
        print(f"⚠️  CUDA check failed ({e}) — CPU")
        return torch.device('cpu'), False


# ═══════════════════════════════════════════════════════════
# تحميل آخر checkpoint من GitHub Releases
# ═══════════════════════════════════════════════════════════
def download_latest_checkpoint(out_dir: str) -> str:
    """يسحب آخر checkpoint من Releases ويرجع الـ tag_name"""
    try:
        req = urllib.request.Request(
            "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/releases/latest")
        with urllib.request.urlopen(req, timeout=15) as r:
            release = json.loads(r.read())
    except Exception as e:
        print(f"لا يوجد release سابق ({e})")
        return ""

    os.makedirs(out_dir, exist_ok=True)
    tag = release.get('tag_name', '')
    downloaded = 0
    for asset in release.get('assets', []):
        name = asset['name']
        if name.endswith('.pt') or name in ('config.json', 'tokenizer.json'):
            try:
                urllib.request.urlretrieve(
                    asset['browser_download_url'],
                    os.path.join(out_dir, name))
                downloaded += 1
                print(f"  تم تحميل: {name}")
            except Exception as e:
                print(f"  فشل: {name} — {e}")
    print(f"تم تحميل {downloaded} ملف من {tag}")
    return tag


# ═══════════════════════════════════════════════════════════
# تحميل نموذج DeepSeek-R1-Distill-Qwen-14B (~9GB)
# ═══════════════════════════════════════════════════════════
def download_deepseek_14b(out_dir: str) -> str:
    model_dir = os.path.join(out_dir, "deepseek_14b")
    os.makedirs(model_dir, exist_ok=True)

    # ابحث عن أي ملف GGUF موجود فعلاً (ممكن المستخدم رفعه كـ dataset)
    for root, _, files in os.walk("/kaggle/input"):
        for f in files:
            if "deepseek" in f.lower() and f.endswith(".gguf"):
                path = os.path.join(root, f)
                print(f"✅ وجدت DeepSeek GGUF كـ Kaggle dataset: {path}")
                return path

    try:
        subprocess.run(["pip", "install", "-q", "huggingface_hub"], check=True)
        from huggingface_hub import hf_hub_download
        print("⬇️  جاري تحميل DeepSeek-R1-Distill-Qwen-14B (~9GB)...")
        path = hf_hub_download(
            repo_id=os.environ.get(
                "AION_LLM_REPO",
                "TheDrummer/DeepSeek-R1-Distill-Qwen-14B-Uncensored-GGUF"),
            filename=os.environ.get(
                "AION_LLM_FILE",
                "DeepSeek-R1-Distill-Qwen-14B-Uncensored-Q4_K_M.gguf"),
            local_dir=model_dir,
        )
        print(f"✅ DeepSeek جاهز: {path}")
        return path
    except Exception as e:
        print(f"⚠️  تحميل DeepSeek فشل: {e}")
        return ""


# ═══════════════════════════════════════════════════════════
# تحميل موديل الرؤية/الفيديو MiniCPM-V-4.6 (~0.8GB)
# ═══════════════════════════════════════════════════════════
def download_vision_model(out_dir: str) -> tuple:
    model_dir = os.path.join(out_dir, "vision_minicpm")
    os.makedirs(model_dir, exist_ok=True)

    # ابحث في Kaggle datasets أولاً
    for root, _, files in os.walk("/kaggle/input"):
        model_f, mmproj_f = None, None
        for f in files:
            fl = f.lower()
            if "minicpm" in fl and f.endswith(".gguf") and "mmproj" not in fl:
                model_f = os.path.join(root, f)
            if "mmproj" in fl and f.endswith(".gguf"):
                mmproj_f = os.path.join(root, f)
        if model_f:
            print(f"✅ وجدت MiniCPM كـ dataset: {model_f}")
            return model_f, mmproj_f or ""

    try:
        from huggingface_hub import hf_hub_download
        print("⬇️  جاري تحميل MiniCPM-V-4.6 (~0.8GB)...")
        model_path = hf_hub_download(
            repo_id=os.environ.get("AION_VIS_REPO", "ggml-org/MiniCPM-V-4.6-GGUF"),
            filename=os.environ.get("AION_VIS_FILE", "Model-4.6-Q8_0.gguf"),
            local_dir=model_dir,
        )
        mmproj_path = ""
        try:
            mmproj_path = hf_hub_download(
                repo_id=os.environ.get("AION_VIS_REPO", "ggml-org/MiniCPM-V-4.6-GGUF"),
                filename=os.environ.get("AION_MMPROJ_FILE", "mmproj-model-f16.gguf"),
                local_dir=model_dir,
            )
        except Exception as e:
            print(f"⚠️  mmproj فشل: {e}")
        print(f"✅ MiniCPM جاهز: {model_path}")
        return model_path, mmproj_path
    except Exception as e:
        print(f"⚠️  تحميل MiniCPM فشل: {e}")
        return "", ""


# ═══════════════════════════════════════════════════════════
# اختبار الذكاء المركّب الهجين (14B + رؤية + شجرة + ذاكرة)
# ═══════════════════════════════════════════════════════════
def test_compound_brain(gguf_path: str, vision_path: str, out_dir: str):
    if not gguf_path or not os.path.exists(gguf_path):
        print("⏭️  تخطي الذكاء المركّب — النموذج النصي غير متاح")
        return

    try:
        subprocess.run(["pip", "install", "-q", "llama-cpp-python"], check=True)
    except Exception as e:
        print(f"⚠️  تثبيت llama-cpp-python فشل: {e}")
        return

    from omega.core.external_brain import ExternalBrain, ExternalBrainConfig
    from omega.core.compound_brain import CompoundBrain
    from omega.memory.persistent import OmegaPersistentMemory

    device, use_gpu = get_device()
    n_gpu_layers = -1 if use_gpu else 0

    print("\n🧠 بناء الذكاء المركّب الهجين: 14B + شجرة تفكير + ذاكرة...")
    text_brain = ExternalBrain(ExternalBrainConfig(
        model_path=gguf_path,
        n_ctx=4096,
        n_gpu_layers=n_gpu_layers,
        max_tokens=300,
    ))

    vision_brain = None
    if vision_path and os.path.exists(vision_path):
        from omega.core.vision_brain import VisionBrain, VisionBrainConfig
        mmproj_path = os.path.join(os.path.dirname(vision_path), "mmproj-model-f16.gguf")
        if os.path.exists(mmproj_path):
            try:
                vision_brain = VisionBrain(VisionBrainConfig(
                    model_path=vision_path,
                    clip_model_path=mmproj_path,
                    n_gpu_layers=n_gpu_layers,
                ))
                print("✅ موديل الرؤية/الفيديو جاهز")
            except Exception as e:
                print(f"⚠️  تحميل موديل الرؤية فشل: {e}")

    memory = OmegaPersistentMemory(os.path.join(out_dir, "compound_memory.db"))
    compound = CompoundBrain(text_brain=text_brain, vision_brain=vision_brain,
                             memory=memory)

    result = compound.think_only("إزاي أحل مشكلة Gradle sync failed في Android Studio؟")
    print(f"✅ إجابة: {result['answer'][:200]}")

    with open(os.path.join(out_dir, "compound_brain_test.json"), 'w', encoding='utf-8') as f:
        json.dump({"answer": result['answer'], "confidence": result['confidence'],
                   "vision_available": vision_brain is not None}, f, ensure_ascii=False, indent=2)
    memory.close()
    print(f"✅ الذكاء المركّب يعمل | رؤية: {vision_brain is not None}")


# ═══════════════════════════════════════════════════════════
# الدالة الرئيسية
# ═══════════════════════════════════════════════════════════
def main():
    device, use_gpu = get_device()
    print(f"الجهاز: {device}")
    if use_gpu:
        print(f"GPU: {torch.cuda.get_device_name(0)}")
        print(f"VRAM: {torch.cuda.get_device_properties(0).total_memory/1e9:.1f}GB")

    out_dir = os.path.join(WORK_DIR, "checkpoints")
    os.makedirs(out_dir, exist_ok=True)

    # ── تحميل آخر checkpoint (الاستمرارية) ──────────────────
    last_tag = download_latest_checkpoint(out_dir)

    # ── Config: دائماً nano لأن الـ checkpoint محفوظ بيه ────
    # (لو مستقبلاً عايز تكبّر، تدرّب epoch كامل بـ nano الأول
    #  ثم احفظ release جديد، وبعدها يمكن تغيير الـ config)
    cfg = get_config('nano')
    config_path = os.path.join(out_dir, 'config.json')

    if os.path.exists(config_path):
        try:
            with open(config_path) as f:
                raw = json.load(f)
            fields = {k: v for k, v in raw.items()
                      if k in AIONConfig.__dataclass_fields__}
            cfg = AIONConfig(**fields)
            print(f"✅ Config محمّل: dim={cfg.dim} layers={cfg.n_layers}")
        except Exception as e:
            print(f"⚠️  فشل تحميل config ({e}) — nano افتراضي")
            cfg = get_config('nano')

    model = AIONModel(cfg).to(device)

    # ── تحميل الـ checkpoint مع حماية من shape mismatch ─────
    ckpt_path = os.path.join(out_dir, 'aion_best.pt')
    start_gen = 0
    if os.path.exists(ckpt_path):
        try:
            ck = torch.load(ckpt_path, map_location=device)
            model.load_state_dict(ck['model'])
            start_gen = ck.get('meta', {}).get('generation', 0)
            print(f"✅ Checkpoint محمّل | جيل={start_gen} | خطأ={ck.get('best_loss', '?'):.4f}")
        except RuntimeError as e:
            print(f"⚠️  Checkpoint غير متوافق ({str(e)[:80]}) — تدريب من الصفر")
            model = AIONModel(cfg).to(device)
            start_gen = 0
        except Exception as e:
            print(f"⚠️  فشل تحميل checkpoint ({e}) — تدريب من الصفر")
            model = AIONModel(cfg).to(device)
            start_gen = 0
    else:
        print("لا يوجد checkpoint — تدريب من الصفر")

    # ── البيانات ─────────────────────────────────────────────
    data_path = os.path.join(REPO_DIR, 'data', 'training_data.json')
    with open(data_path, 'r', encoding='utf-8') as f:
        samples = json.load(f)
    print(f"عدد العينات: {len(samples)}")

    # ── Tokenizer ─────────────────────────────────────────────
    if os.path.exists(os.path.join(out_dir, 'tokenizer.json')):
        tok = OmegaTokenizer.load(out_dir)
        print(f"✅ Tokenizer محمّل | vocab={len(tok.vocab)}")
    else:
        tok = OmegaTokenizer(cfg.vocab_size)
        texts = [s if isinstance(s, str) else json.dumps(s, ensure_ascii=False)
                 for s in samples]
        tok.train(texts, min_freq=1, verbose=False)
        tok.save(out_dir)
        print(f"✅ Tokenizer مدرَّب | vocab={len(tok.vocab)}")

    dataset = ChatDataset(samples, tok, max_len=min(cfg.max_seq_len, 256))
    batch_size = 8 if use_gpu else 4
    loader = DataLoader(dataset, batch_size=batch_size, shuffle=True,
                        collate_fn=collate_fn, num_workers=2,
                        pin_memory=use_gpu)

    optimizer = torch.optim.AdamW(model.parameters(), lr=2e-4,
                                   betas=(0.9, 0.95), weight_decay=0.05)
    scaler = torch.cuda.amp.GradScaler() if use_gpu else None

    # ── حلقة التدريب الزمنية ────────────────────────────────
    max_minutes = float(os.environ.get('AION_MAX_MINUTES', '120'))
    max_seconds = max_minutes * 60
    t_start = time.time()
    total_loss, n_steps, n_epochs = 0.0, 0, 0
    max_lr, min_lr, warmup, total_est = 2e-4, 1e-5, 50, 10000
    print(f"\n🏋️  بدء التدريب لمدة {max_minutes:.0f} دقيقة على {device}...")

    model.train()
    optimizer.zero_grad()

    while time.time() - t_start < max_seconds:
        n_epochs += 1
        for x, y in loader:
            if time.time() - t_start >= max_seconds:
                break
            x, y = x.to(device), y.to(device)
            lr = cosine_lr(n_steps, warmup, total_est, min_lr, max_lr)
            for pg in optimizer.param_groups:
                pg['lr'] = lr

            if scaler:
                with torch.cuda.amp.autocast():
                    _, loss = model(x, y)
                scaler.scale(loss).backward()
                scaler.unscale_(optimizer)
                nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                scaler.step(optimizer)
                scaler.update()
            else:
                _, loss = model(x, y)
                loss.backward()
                nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                optimizer.step()
            optimizer.zero_grad()

            total_loss += loss.item()
            n_steps += 1

            if n_steps % 50 == 0:
                elapsed = time.time() - t_start
                avg = total_loss / n_steps
                print(f"epoch {n_epochs} | step {n_steps} | "
                      f"loss {loss.item():.4f} | avg {avg:.4f} | {elapsed/60:.1f}min")

    elapsed = time.time() - t_start
    avg_loss = total_loss / max(n_steps, 1)
    print(f"\n✅ انتهى | epochs={n_epochs} | steps={n_steps} | "
          f"avg_loss={avg_loss:.4f} | {elapsed/60:.1f}min")

    # ── حفظ النتيجة ──────────────────────────────────────────
    torch.save({
        'model': model.state_dict(),
        'meta': {'generation': start_gen + 1, 'device': str(device),
                 'n_steps': n_steps, 'partial': False},
        'best_loss': avg_loss,
        'step': n_steps,
    }, ckpt_path)

    cfg_dict = {k: v for k, v in cfg.__dict__.items() if not callable(v)}
    with open(config_path, 'w') as f:
        json.dump(cfg_dict, f, indent=2)

    tok.save(out_dir)

    result = {'device': str(device), 'avg_loss': avg_loss, 'n_steps': n_steps,
              'n_epochs': n_epochs, 'elapsed_min': elapsed/60,
              'generation': start_gen + 1}
    with open(os.path.join(out_dir, 'kaggle_result.json'), 'w') as f:
        json.dump(result, f, indent=2)

    print(json.dumps(result, indent=2, ensure_ascii=False))

    # ── المرحلة الإضافية: النظام الهجين الكامل ───────────────
    if os.environ.get('AION_FETCH_14B', 'false').lower() == 'true':
        print("\n" + "="*55)
        print("  المرحلة الإضافية: نموذج 14B + الذكاء المركّب")
        print("="*55)
        gguf_path = download_deepseek_14b(out_dir)
        vision_path, _ = download_vision_model(out_dir)
        test_compound_brain(gguf_path, vision_path, out_dir)


if __name__ == '__main__':
    main()
