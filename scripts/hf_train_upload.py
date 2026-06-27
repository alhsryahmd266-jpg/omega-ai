import requests, os, json, base64, sys, time

hf_token = os.environ.get("HF_TOKEN", "")
pat      = os.environ.get("GH_PAT", "")
headers  = {"Authorization": f"Bearer {hf_token}"}

# التحقق
me = requests.get("https://huggingface.co/api/whoami", headers=headers, timeout=30)
hf_user = me.json().get("name", "ahmedxg") if me.status_code == 200 else "ahmedxg"
print(f"Training as: {hf_user}")

# تدريب GVR
sys.path.insert(0, ".")
from gvr.architecture import GVRConfig
from gvr.trainer import GVRTrainer

cfg = GVRConfig(d_model=256, gen_layers=6, ver_layers=6, max_seq_len=256)
trainer = GVRTrainer(cfg, budget_seconds=18*60)
trainer.train("/tmp/gvr_ckpt.pt")

result = {"status": "no_checkpoint", "ts": time.strftime("%Y-%m-%d %H:%M")}

if os.path.exists("/tmp/gvr_ckpt.pt"):
    size_mb = os.path.getsize("/tmp/gvr_ckpt.pt") / 1e6
    print(f"Checkpoint: {size_mb:.1f}MB - uploading to HF Hub...")
    
    # رفع الـ checkpoint على HF Hub عبر API
    with open("/tmp/gvr_ckpt.pt", "rb") as f:
        ckpt_data = f.read()
    
    upload_r = requests.put(
        f"https://huggingface.co/api/models/{hf_user}/gvr-model/upload/main/gvr_checkpoint.pt",
        headers={
            "Authorization": f"Bearer {hf_token}",
            "Content-Type": "application/octet-stream",
            "X-Linked-Filename": "gvr_checkpoint.pt"
        },
        data=ckpt_data,
        timeout=300
    )
    print(f"Upload: {upload_r.status_code}")
    
    # رفع config
    config = {"d_model": 256, "gen_layers": 6, "ts": time.strftime("%Y-%m-%d %H:%M")}
    config_r = requests.put(
        f"https://huggingface.co/api/models/{hf_user}/gvr-model/upload/main/config.json",
        headers={
            "Authorization": f"Bearer {hf_token}",
            "Content-Type": "application/json"
        },
        data=json.dumps(config).encode(),
        timeout=60
    )
    print(f"Config: {config_r.status_code}")
    
    result = {
        "status": "success",
        "size_mb": round(size_mb, 1),
        "upload": upload_r.status_code,
        "url": f"https://huggingface.co/{hf_user}/gvr-model",
        "ts": time.strftime("%Y-%m-%d %H:%M")
    }

print(json.dumps(result, indent=2))

# حفظ على GitHub
content = base64.b64encode(json.dumps(result, indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_train_result.json",
    headers={"Authorization": f"token {pat}"}, timeout=30
)
body = {"message": "GVR: HF training done", "content": content}
if check.status_code == 200:
    body["sha"] = check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_train_result.json",
    headers={"Authorization": f"token {pat}"},
    json=body, timeout=30
)
