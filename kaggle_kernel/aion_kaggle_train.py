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


def main():
    # ── إعداد الجهاز: GPU إذا توفر ──────────────────────────
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
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


if __name__ == '__main__':
    main()
