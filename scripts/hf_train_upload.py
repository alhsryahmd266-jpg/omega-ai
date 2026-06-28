import requests, os, json, base64, sys, time

hf = os.environ.get("HF_TOKEN", "")
pat = os.environ.get("GH_PAT", "")
HF_USER = "ahmedxg"

headers_hf  = {"Authorization": f"Bearer {hf}"}
headers_pat = {"Authorization": f"token {pat}"}

print(f"=== GVR Training + HF Upload ===")
print(f"User: {HF_USER}")

# تدريب GVR
sys.path.insert(0, ".")
from gvr.architecture import GVRConfig
from gvr.trainer import GVRTrainer
import torch

device = "cuda" if torch.cuda.is_available() else "cpu"
print(f"Device: {device}")

cfg = GVRConfig(d_model=256, gen_layers=6, ver_layers=6, max_seq_len=256)
trainer = GVRTrainer(cfg, budget_seconds=18*60)
trainer.train("/tmp/gvr_ckpt.pt")

result = {"status": "no_checkpoint", "ts": time.strftime("%Y-%m-%d %H:%M")}

if os.path.exists("/tmp/gvr_ckpt.pt"):
    size_mb = os.path.getsize("/tmp/gvr_ckpt.pt") / 1e6
    print(f"\nCheckpoint: {size_mb:.1f}MB — uploading to HF...")

    with open("/tmp/gvr_ckpt.pt", "rb") as f:
        ckpt_data = f.read()

    # رفع عبر HF Hub API
    upload_r = requests.put(
        f"https://huggingface.co/api/models/{HF_USER}/gvr-model/upload/main/gvr_checkpoint.pt",
        headers={
            "Authorization": f"Bearer {hf}",
            "Content-Type": "application/octet-stream",
        },
        data=ckpt_data,
        timeout=300
    )
    print(f"Upload checkpoint: {upload_r.status_code}")
    if upload_r.status_code not in [200, 201]:
        print(f"Response: {upload_r.text[:200]}")

    # رفع config
    config = {
        "architecture": "GVR",
        "d_model": cfg.d_model,
        "gen_layers": cfg.gen_layers,
        "ver_layers": cfg.ver_layers,
        "device": device,
        "size_mb": round(size_mb, 1),
        "ts": time.strftime("%Y-%m-%d %H:%M:%S")
    }
    config_r = requests.put(
        f"https://huggingface.co/api/models/{HF_USER}/gvr-model/upload/main/config.json",
        headers={"Authorization": f"Bearer {hf}", "Content-Type": "application/json"},
        data=json.dumps(config, indent=2).encode(),
        timeout=60
    )
    print(f"Upload config: {config_r.status_code}")

    result = {
        "status": "success",
        "device": device,
        "size_mb": round(size_mb, 1),
        "upload": upload_r.status_code,
        "url": f"https://huggingface.co/{HF_USER}/gvr-model",
        "ts": time.strftime("%Y-%m-%d %H:%M")
    }
    print(f"\n✅ Model at: https://huggingface.co/{HF_USER}/gvr-model")

print(json.dumps(result, indent=2))

# احفظ على GitHub
content = base64.b64encode(json.dumps(result, indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_train_result.json",
    headers=headers_pat
)
body = {"message": "GVR: Training done", "content": content}
if check.status_code == 200:
    body["sha"] = check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_train_result.json",
    headers=headers_pat, json=body
)
