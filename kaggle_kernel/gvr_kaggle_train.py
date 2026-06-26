"""
GVR Kaggle GPU Training Kernel
يشتغل على Kaggle GPU ويحفظ الـ checkpoint
"""
import os, sys, time, subprocess, json, shutil

def run(cmd, check=True):
    print(f"$ {cmd}")
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if r.stdout: print(r.stdout[:2000])
    if r.stderr: print(r.stderr[:1000])
    if check and r.returncode != 0:
        print(f"[WARN] Command returned {r.returncode}")
    return r

# ─── Setup ───────────────────────────────────
print("=" * 60)
print("GVR TRAINING KERNEL")
print("=" * 60)

# GPU check
run("nvidia-smi --query-gpu=name,memory.total --format=csv,noheader 2>/dev/null || echo 'No GPU'", check=False)
import torch
device = "cuda" if torch.cuda.is_available() else "cpu"
print(f"Device: {device}")
if device == "cuda":
    print(f"GPU: {torch.cuda.get_device_name(0)}")
    print(f"VRAM: {torch.cuda.get_device_properties(0).total_memory/1e9:.1f}GB")

# ─── Clone repo ──────────────────────────────
REPO = "alhsryahmd266-jpg/omega-ai"
TOKEN = os.environ.get("GITHUB_TOKEN", "")

if TOKEN:
    clone_url = f"https://{TOKEN}@github.com/{REPO}.git"
else:
    clone_url = f"https://github.com/{REPO}.git"

work_dir = "/kaggle/working/gvr"
if os.path.exists(work_dir):
    shutil.rmtree(work_dir)

run(f"git clone --depth 1 {clone_url} {work_dir}")
os.chdir(work_dir)
sys.path.insert(0, work_dir)

# ─── Load checkpoint if exists ───────────────
checkpoint_path = "/kaggle/working/gvr_checkpoint.pt"
if os.path.exists("/kaggle/input/gvr-checkpoints/gvr_checkpoint.pt"):
    shutil.copy("/kaggle/input/gvr-checkpoints/gvr_checkpoint.pt", checkpoint_path)
    print("[Resume] Loaded checkpoint from dataset")

# ─── Install deps ────────────────────────────
run("pip install torch --upgrade -q 2>/dev/null || true", check=False)

# ─── Import and train ────────────────────────
from gvr.architecture import GVRConfig
from gvr.trainer import GVRTrainer

# Config حسب الـ GPU المتاح
if device == "cuda":
    vram = torch.cuda.get_device_properties(0).total_memory / 1e9
    if vram >= 14:
        cfg = GVRConfig(d_model=512, gen_layers=12, ver_layers=12,
                        max_seq_len=1024, dropout=0.1)
        budget_h = 1.8
    elif vram >= 8:
        cfg = GVRConfig(d_model=384, gen_layers=8, ver_layers=8,
                        max_seq_len=512, dropout=0.1)
        budget_h = 1.8
    else:
        cfg = GVRConfig(d_model=256, gen_layers=6, ver_layers=6,
                        max_seq_len=512, dropout=0.1)
        budget_h = 1.8
else:
    # CPU fallback
    cfg = GVRConfig(d_model=256, gen_layers=6, ver_layers=6,
                    max_seq_len=256, dropout=0.1)
    budget_h = 1.5

print(f"\n[Config] d_model={cfg.d_model} layers={cfg.gen_layers} device={device}")
trainer = GVRTrainer(cfg, budget_seconds=int(budget_h * 3600))
trainer.train(checkpoint_path)

# ─── Save results ────────────────────────────
results = {
    "status": "completed",
    "device": device,
    "d_model": cfg.d_model,
    "layers": cfg.gen_layers,
    "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
}
if os.path.exists(checkpoint_path):
    results["checkpoint_size_mb"] = round(os.path.getsize(checkpoint_path)/1e6, 1)

with open("/kaggle/working/gvr_results.json", "w") as f:
    json.dump(results, f, indent=2)

print("\n" + "="*60)
print("TRAINING COMPLETE")
print(json.dumps(results, indent=2, ensure_ascii=False))
print("="*60)

# ─── Push checkpoint back to GitHub ──────────
if TOKEN and os.path.exists(checkpoint_path):
    os.chdir(work_dir)
    shutil.copy(checkpoint_path, os.path.join(work_dir, "checkpoints", "gvr_checkpoint.pt"))

    run('git config user.email "gvr@kaggle.com"', check=False)
    run('git config user.name "GVR Kaggle"', check=False)
    run("git add checkpoints/gvr_checkpoint.pt gvr_results.json 2>/dev/null || true", check=False)
    run(f'git commit -m "GVR checkpoint: {results.get(\"checkpoint_size_mb\", 0)}MB" 2>/dev/null || true', check=False)
    run(f"git push {clone_url} main 2>/dev/null || true", check=False)
    print("[GitHub] Checkpoint pushed!")
