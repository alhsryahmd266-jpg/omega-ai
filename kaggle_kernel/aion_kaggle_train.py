"""
AION GPU Training Kernel — يعمل داخل Kaggle Notebook
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
هذا السكريبت يُدفع (push) لـ Kaggle عبر الـ API ويشتغل فعلياً
على GPU مجاني (T4 ×2 أو P100) لمدة محددة، ثم يحفظ الناتج
في /kaggle/working/ بحيث يمكن سحبه (output) عبر GitHub Actions.

الـ workflow:
1. يسحب آخر checkpoint من GitHub Releases (Kaggle عنده انترنت كامل)
2. يدرّب بإعدادات أكبر (gpu config) مستغلاً GPU الحقيقي
3. يحفظ الناتج في /kaggle/working/checkpoints/
4. GitHub Actions يسحب الناتج ده بعدين ويدمجه في AION-SWARM
"""

import os
import sys
import json
import time
import math
import subprocess
import urllib.request

# ── تثبيت AION من الريبو (Kaggle عنده انترنت كامل، فده هينجح) ──
REPO_URL = "https://github.com/alhsryahmd266-jpg/omega-ai"
WORK_DIR = "/kaggle/working"
REPO_DIR = os.path.join(WORK_DIR, "omega-ai")

if not os.path.exists(REPO_DIR):
    subprocess.run(["git", "clone", "--depth", "1", REPO_URL, REPO_DIR], check=True)

# ── إصلاح توافق CUDA: P100 (sm_60) يحتاج PyTorch 2.0.x ──────
# بعض GPUs على Kaggle (P100) قديمة وتحتاج نسخة PyTorch أقدم
def fix_pytorch_cuda_compat():
    """
    P100 على Kaggle (CUDA sm_60) غير متوافق مع PyTorch 2.x الحديث.
    الحل: نشتغل على CPU — أبطأ لكن يكمل بدل ما يُقتل.
    لو الـ kernel على T4 (sm_75+) هيشتغل GPU تلقائياً.
    """
    import torch
    if not torch.cuda.is_available():
        return
    try:
        cap = torch.cuda.get_device_capability(0)
        major = cap[0]
        if major < 7:
            name = torch.cuda.get_device_name(0)
            print(f"⚠️  {name} (sm_{major*10}) غير متوافق مع PyTorch الحالي.")
            print("   سيتم التشغيل على CPU بدلاً من ذلك.")
            # نخلي cuda.is_available يرجع False بتعطيل CUDA
            os.environ['CUDA_VISIBLE_DEVICES'] = ''
            print("✅ CUDA معطّل — سيعمل الكود على CPU")
    except Exception as e:
        print(f"⚠️  فحص CUDA compat: {e}")

fix_pytorch_cuda_compat()

sys.path.insert(0, REPO_DIR)

import torch
import torch.nn as nn
from torch.utils.data import DataLoader

from omega.model.architecture import get_config, AIONModel, AIONConfig
from omega.tokenizer.bpe import OmegaTokenizer
from omega.trainer.train import ChatDataset, collate_fn, cosine_lr


def download_latest_checkpoint(out_dir: str):
    """يسحب آخر إصدار من GitHub Releases (Kaggle عنده انترنت كامل)"""
    try:
        req = urllib.request.Request(
            "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/releases/latest")
        with urllib.request.urlopen(req, timeout=20) as r:
            release = json.loads(r.read())
    except Exception as e:
        print(f"لا يوجد إصدار سابق ({e}) — تدريب من الصفر")
        return False

    os.makedirs(out_dir, exist_ok=True)
    downloaded = 0
    for asset in release.get('assets', []):
        name = asset['name']
        if name.endswith('.pt') or name in ('config.json', 'tokenizer.json'):
            try:
                urllib.request.urlretrieve(asset['browser_download_url'],
                                           os.path.join(out_dir, name))
                downloaded += 1
                print(f"  تم تحميل: {name}")
            except Exception as e:
                print(f"  فشل تحميل {name}: {e}")
    print(f"تم تحميل {downloaded} ملف من {release.get('tag_name')}")
    return downloaded > 0


def download_deepseek_14b(out_dir: str) -> str:
    """
    يجيب نموذج DeepSeek-R1-Distill-Qwen-14B-Uncensored-GGUF (~9GB).
    Kaggle عنده انترنت كامل فيقدر يوصل لـ Hugging Face عادي
    (بعكس بيئة Claude المقيّدة بقائمة مسموحة).
    """
    model_dir = os.path.join(out_dir, "deepseek_14b")
    os.makedirs(model_dir, exist_ok=True)
    model_file = os.path.join(model_dir, "deepseek-r1-distill-qwen-14b-uncensored.Q4_K_M.gguf")

    if os.path.exists(model_file) and os.path.getsize(model_file) > 1e9:
        print(f"✅ النموذج موجود بالفعل: {model_file}")
        return model_file

    # ملاحظة: اسم الملف/الريبو الدقيق على Hugging Face لازم يتأكد
    # وقت التشغيل لأن أسماء الإصدارات بتتغيّر. هنحاول استخدام
    # huggingface_hub لو متاح، وإلا نطبع تعليمات واضحة.
    try:
        subprocess.run(["pip", "install", "-q", "huggingface_hub"], check=True)
        from huggingface_hub import hf_hub_download
        print("⬇️  جاري تحميل النموذج من Hugging Face (~9GB، يستغرق دقائق)...")
        path = hf_hub_download(
            repo_id=os.environ.get("AION_VISION_REPO_ID",
                "TheDrummer/DeepSeek-R1-Distill-Qwen-14B-Uncensored-GGUF"),
            filename=os.environ.get("AION_MODEL_FILENAME",
                "DeepSeek-R1-Distill-Qwen-14B-Uncensored-Q4_K_M.gguf"),
            local_dir=model_dir,
        )
        print(f"✅ تم التحميل: {path}")
        return path
    except Exception as e:
        print(f"⚠️  تحميل تلقائي فشل ({e})")
        print("   حدّد AION_VISION_REPO_ID / AION_MODEL_FILENAME الصحيحين "
              "كمتغيرات بيئة في الـ workflow، أو نزّله يدوياً على Kaggle "
              "كـ dataset واربطه بالـ kernel.")
        return ""


def download_vision_model(out_dir: str) -> str:
    """
    يجيب MiniCPM-V-4.6-GGUF — أقوى موديل رؤية+فيديو في حدود 2GB
    (الحجم الفعلي ~0.8GB بجودة Q8_0، يسيب مساحة كبيرة من حد الـ2GB).
    يدعم صور وفيديو أصلياً عبر llama.cpp — نفس محرك النموذج النصي 14B.
    """
    model_dir = os.path.join(out_dir, "vision_minicpm")
    os.makedirs(model_dir, exist_ok=True)
    model_file = os.path.join(model_dir, "MiniCPM-V-4.6-Q8_0.gguf")
    mmproj_file = os.path.join(model_dir, "mmproj-MiniCPM-V-4.6-f16.gguf")

    if os.path.exists(model_file) and os.path.getsize(model_file) > 1e8:
        print(f"✅ موديل الرؤية موجود بالفعل: {model_file}")
        return model_file

    try:
        from huggingface_hub import hf_hub_download
        print("⬇️  جاري تحميل موديل الرؤية MiniCPM-V-4.6 (~0.8GB)...")
        path = hf_hub_download(
            repo_id=os.environ.get("AION_VISION_REPO_ID", "ggml-org/MiniCPM-V-4.6-GGUF"),
            filename=os.environ.get("AION_VISION_FILENAME", "Model-4.6-Q8_0.gguf"),
            local_dir=model_dir,
        )
        # ملف الـ mmproj (المحوّل البصري) منفصل وضروري
        try:
            mmproj_path = hf_hub_download(
                repo_id=os.environ.get("AION_VISION_REPO_ID", "ggml-org/MiniCPM-V-4.6-GGUF"),
                filename=os.environ.get("AION_MMPROJ_FILENAME", "mmproj-model-f16.gguf"),
                local_dir=model_dir,
            )
            print(f"✅ mmproj تم تحميله: {mmproj_path}")
        except Exception as e:
            print(f"⚠️  تحميل mmproj فشل ({e}) — راجع اسم الملف الدقيق على صفحة الموديل")
        print(f"✅ موديل الرؤية تم تحميله: {path}")
        return path
    except Exception as e:
        print(f"⚠️  تحميل موديل الرؤية فشل ({e})")
        print("   حدّد AION_VISION_REPO_ID / AION_VISION_FILENAME الصحيحين، "
              "أو راجع huggingface.co/ggml-org/MiniCPM-V-4.6-GGUF للأسماء الدقيقة")
        return ""



def test_compound_brain(gguf_path: str, vision_path: str, out_dir: str):
    """
    يبني الذكاء المركّب الهجين الكامل (Live على Kaggle GPU):
    نموذج نصي 14B + موديل رؤية/فيديو ~0.8GB + شجرة تفكير + ذاكرة دائمة.
    هذا هو النظام الهجين: 9GB + ~1GB = نظام واحد متكامل.
    """
    if not gguf_path or not os.path.exists(gguf_path):
        print("⏭️  تخطي اختبار الذكاء المركّب — النموذج النصي غير متاح")
        return

    try:
        subprocess.run(["pip", "install", "-q", "llama-cpp-python"], check=True)
    except Exception as e:
        print(f"⚠️  تثبيت llama-cpp-python فشل: {e}")
        return

    from omega.core.external_brain import ExternalBrain, ExternalBrainConfig
    from omega.core.vision_brain import VisionBrain, VisionBrainConfig
    from omega.core.compound_brain import CompoundBrain
    from omega.memory.persistent import OmegaPersistentMemory

    print("\n🧠 بناء الذكاء المركّب الهجين: 14B نصي + رؤية/فيديو + شجرة تفكير + ذاكرة...")
    n_gpu_layers = -1 if torch.cuda.is_available() else 0

    text_brain = ExternalBrain(ExternalBrainConfig(
        model_path=gguf_path, n_ctx=4096, n_gpu_layers=n_gpu_layers, max_tokens=300,
    ))

    vision_brain = None
    if vision_path and os.path.exists(vision_path):
        mmproj_guess = os.path.join(os.path.dirname(vision_path), "mmproj-MiniCPM-V-4.6-f16.gguf")
        if os.path.exists(mmproj_guess):
            vision_brain = VisionBrain(VisionBrainConfig(
                model_path=vision_path,
                clip_model_path=mmproj_guess,
                chat_handler_name=os.environ.get("AION_VISION_HANDLER", "MiniCPMv26ChatHandler"),
                n_gpu_layers=n_gpu_layers,
            ))
            print("✅ موديل الرؤية جاهز للدمج")
        else:
            print(f"⚠️  ملف mmproj غير موجود — الرؤية ستكون معطّلة")
    else:
        print("⏭️  موديل الرؤية غير متاح — الاختبار سيكون نصي فقط")

    mem_path = os.path.join(out_dir, "compound_memory.db")
    memory = OmegaPersistentMemory(mem_path)

    compound = CompoundBrain(text_brain=text_brain, vision_brain=vision_brain, memory=memory)

    test_problem = "إزاي أحل مشكلة Gradle sync failed في Android Studio؟"
    print(f"❓ سؤال تجريبي (نصي): {test_problem}")
    result = compound.think_only(test_problem)

    print(f"✅ الإجابة: {result['answer'][:200]}")
    print(f"   الثقة: {result['confidence']}")
    print(f"   إحصائيات: {result['stats']}")

    with open(os.path.join(out_dir, "compound_brain_test.json"), 'w', encoding='utf-8') as f:
        json.dump({"text_only_result": result,
                   "vision_available": vision_brain is not None}, f, ensure_ascii=False, indent=2)

    memory.close()
    print(f"✅ الذكاء المركّب الهجين يعمل | رؤية متاحة: {vision_brain is not None}")


def main():
    # ── إعداد الجهاز: GPU فقط لو متوافق (sm_70+) ──────────────
    use_cuda = False
    if __import__('torch').cuda.is_available():
        try:
            cap_major = __import__('torch').cuda.get_device_capability(0)[0]
            if cap_major >= 7:
                use_cuda = True
                print(f"✅ GPU متوافق (sm_{cap_major*10}) — سيُستخدم CUDA")
            else:
                print(f"⚠️  GPU (sm_{cap_major*10}) قديم — سيتم التشغيل على CPU")
        except Exception:
            pass
    device = __import__('torch').device('cuda' if use_cuda else 'cpu')
    print(f"الجهاز المستخدم: {device}")
    if torch.cuda.is_available():
        print(f"GPU: {torch.cuda.get_device_name(0)}")
        print(f"VRAM: {torch.cuda.get_device_properties(0).total_memory/1e9:.1f}GB")

    out_dir = os.path.join(WORK_DIR, "checkpoints")
    os.makedirs(out_dir, exist_ok=True)

    # ── تحميل آخر checkpoint للاستمرارية ──────────────────────
    download_latest_checkpoint(out_dir)

    # ── إعدادات أكبر لأن عندنا GPU حقيقي دلوقتي ─────────────
    if torch.cuda.is_available():
        cfg = get_config('intensive')   # ممكن نزود الحجم أكتر لاحقاً
        max_minutes = float(os.environ.get('AION_MAX_MINUTES', '240'))  # 4 ساعات
        batch_size = 16
    else:
        cfg = get_config('nano')
        max_minutes = 5.0
        batch_size = 2

    config_path = os.path.join(out_dir, 'config.json')
    if os.path.exists(config_path):
        with open(config_path) as f:
            raw = json.load(f)
        fields = {k: v for k, v in raw.items() if k in AIONConfig.__dataclass_fields__}
        cfg = AIONConfig(**fields)
        print("تم تحميل الإعدادات من checkpoint سابق")

    model = AIONModel(cfg).to(device)

    ckpt_path = os.path.join(out_dir, 'aion_best.pt')
    start_gen = 0
    if os.path.exists(ckpt_path):
        ck = torch.load(ckpt_path, map_location=device)
        model.load_state_dict(ck['model'])
        start_gen = ck.get('meta', {}).get('generation', 0)
        print(f"استمرار من الجيل {start_gen}")

    # ── البيانات ────────────────────────────────────────────
    data_path = os.path.join(REPO_DIR, 'data', 'training_data.json')
    with open(data_path, 'r', encoding='utf-8') as f:
        samples = json.load(f)
    print(f"عدد العينات: {len(samples)}")

    tok_dir = out_dir
    if os.path.exists(os.path.join(tok_dir, 'tokenizer.json')):
        tok = OmegaTokenizer.load(tok_dir)
    else:
        tok = OmegaTokenizer(cfg.vocab_size)
        texts = [s if isinstance(s, str) else json.dumps(s, ensure_ascii=False)
                 for s in samples]
        tok.train(texts, min_freq=1, verbose=False)
        tok.save(tok_dir)

    dataset = ChatDataset(samples, tok, max_len=min(cfg.max_seq_len, 384))
    loader = DataLoader(dataset, batch_size=batch_size, shuffle=True,
                        collate_fn=collate_fn, num_workers=2,
                        pin_memory=torch.cuda.is_available())

    optimizer = torch.optim.AdamW(model.parameters(), lr=2e-4,
                                   betas=(0.9, 0.95), weight_decay=0.05)
    scaler = torch.cuda.amp.GradScaler() if torch.cuda.is_available() else None

    # ── حلقة التدريب الزمنية (نفس فلسفة AION-SWARM) ──────────
    print(f"\nبدء التدريب لمدة {max_minutes} دقيقة على {device}...")
    t_start = time.time()
    max_seconds = max_minutes * 60
    total_loss, n_steps, n_epochs = 0.0, 0, 0
    max_lr, min_lr, warmup_steps, total_est = 2e-4, 1e-5, 100, 20000

    model.train()
    optimizer.zero_grad()

    while time.time() - t_start < max_seconds:
        n_epochs += 1
        for x, y in loader:
            if time.time() - t_start >= max_seconds:
                break
            x, y = x.to(device), y.to(device)

            lr = cosine_lr(n_steps, warmup_steps, total_est, min_lr, max_lr)
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
                print(f"epoch {n_epochs} | step {n_steps} | loss {loss.item():.4f} | "
                      f"avg {avg:.4f} | lr {lr:.2e} | {elapsed/60:.1f}min")

            # حفظ دوري احتياطي كل 10 دقايق
            if n_steps % 500 == 0:
                torch.save({'model': model.state_dict(),
                           'meta': {'generation': start_gen + 1, 'partial': True},
                           'best_loss': total_loss/n_steps, 'step': n_steps},
                          ckpt_path)

    elapsed = time.time() - t_start
    avg_loss = total_loss / max(n_steps, 1)
    print(f"\nانتهى التدريب | خطوات={n_steps} | متوسط الخطأ={avg_loss:.4f} | "
          f"الوقت={elapsed/60:.1f} دقيقة")

    # ── حفظ النتيجة النهائية ──────────────────────────────────
    torch.save({'model': model.state_dict(),
               'meta': {'generation': start_gen + 1, 'partial': False,
                        'device': str(device), 'n_steps': n_steps},
               'best_loss': avg_loss, 'step': n_steps}, ckpt_path)

    cfg_dict = {k: v for k, v in cfg.__dict__.items() if not callable(v)}
    with open(config_path, 'w') as f:
        json.dump(cfg_dict, f, indent=2)

    result = {'device': str(device), 'avg_loss': avg_loss, 'n_steps': n_steps,
              'n_epochs': n_epochs, 'elapsed_min': elapsed/60,
              'generation': start_gen + 1}
    with open(os.path.join(out_dir, 'kaggle_result.json'), 'w') as f:
        json.dump(result, f, indent=2)

    print(f"\nالنتائج محفوظة في {out_dir}")
    print(json.dumps(result, indent=2, ensure_ascii=False))

    # ── المرحلة الإضافية: جلب الـ14B + الذكاء المركّب ────────
    # (يعمل فقط لو فيه GPU كافي ومفعّل عن طريق متغير بيئة)
    if os.environ.get('AION_FETCH_14B', 'true').lower() == 'true':
        print("\n" + "="*55)
        print("  المرحلة الإضافية: نموذج DeepSeek-14B + الذكاء المركّب")
        print("="*55)
        gguf_path = download_deepseek_14b(out_dir)
        vision_path = download_vision_model(out_dir)
        test_compound_brain(gguf_path, vision_path, out_dir)


if __name__ == '__main__':
    main()
