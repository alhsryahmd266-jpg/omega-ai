import requests, os, json, base64, sys, time, subprocess
import torch

hf_token = os.environ.get("HF_TOKEN", "")
pat      = os.environ.get("GH_PAT", "")
headers  = {"Authorization": f"Bearer {hf_token}"}

me = requests.get("https://huggingface.co/api/whoami", headers=headers)
hf_user = me.json().get("name", "ahmedxg") if me.status_code == 200 else "ahmedxg"
print(f"Training as: {hf_user}")

sys.path.insert(0, ".")
from gvr.architecture import GVRConfig
from gvr.trainer import GVRTrainer

cfg = GVRConfig(d_model=256, gen_layers=6, ver_layers=6, max_seq_len=256)
trainer = GVRTrainer(cfg, budget_seconds=18*60)
trainer.train("/tmp/gvr_ckpt.pt")

if os.path.exists("/tmp/gvr_ckpt.pt"):
    size_mb = os.path.getsize("/tmp/gvr_ckpt.pt") / 1e6
    print(f"Checkpoint: {size_mb:.1f}MB")

    # رفع على HF Hub
    with open("/tmp/gvr_ckpt.pt", "rb") as f:
        data = f.read()

    # LFS upload للـ HF
    r = requests.put(
        f"https://huggingface.co/{hf_user}/gvr-model/upload/main",
        headers={**headers, "Content-Type": "application/octet-stream"},
        params={"filename": "gvr_checkpoint.pt"},
        data=data
    )
    print(f"Upload: {r.status_code}")
    print(r.text[:200])

    result = {
        "status": "done",
        "size_mb": size_mb,
        "upload": r.status_code,
        "url": f"https://huggingface.co/{hf_user}/gvr-model",
        "ts": time.strftime("%Y-%m-%d %H:%M")
    }
else:
    result = {"status": "no_checkpoint"}

content = base64.b64encode(json.dumps(result, indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_train_result.json",
    headers={"Authorization": f"token {pat}"}
)
body = {"message": "GVR: HF training done", "content": content}
if check.status_code == 200:
    body["sha"] = check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_train_result.json",
    headers={"Authorization": f"token {pat}"},
    json=body
)
print(json.dumps(result, indent=2))
