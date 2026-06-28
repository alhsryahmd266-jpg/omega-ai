import requests, os, json, base64, sys, time

hf = os.environ.get("HF_TOKEN", "")
pat = os.environ.get("GH_PAT", "")
HF_USER = "ahmedxg"

print("=== GVR Train + Upload ===")

# تدريب
sys.path.insert(0, ".")
from gvr.architecture import GVRConfig
from gvr.trainer import GVRTrainer
import torch

device = "cuda" if torch.cuda.is_available() else "cpu"
cfg = GVRConfig(d_model=256, gen_layers=6, ver_layers=6, max_seq_len=256)
trainer = GVRTrainer(cfg, budget_seconds=18*60)
trainer.train("/tmp/gvr_ckpt.pt")

result = {"status": "no_checkpoint"}

if os.path.exists("/tmp/gvr_ckpt.pt"):
    size_mb = os.path.getsize("/tmp/gvr_ckpt.pt") / 1e6
    print(f"Checkpoint: {size_mb:.1f}MB")

    # استخدام huggingface_hub library
    try:
        from huggingface_hub import HfApi
        api = HfApi(token=hf)

        # إنشاء الـ repo
        try:
            api.create_repo(
                repo_id=f"{HF_USER}/gvr-model",
                repo_type="model",
                exist_ok=True,
                private=False
            )
            print("Repo ready!")
        except Exception as e:
            print(f"Repo note: {e}")

        # رفع الـ checkpoint
        url = api.upload_file(
            path_or_fileobj="/tmp/gvr_ckpt.pt",
            path_in_repo="gvr_checkpoint.pt",
            repo_id=f"{HF_USER}/gvr-model",
            repo_type="model",
            commit_message=f"GVR checkpoint {time.strftime('%Y-%m-%d %H:%M')}"
        )
        print(f"✅ Uploaded! {url}")

        # رفع config
        config = {
            "architecture": "GVR - Generate Verify Refine",
            "d_model": 256, "gen_layers": 6, "ver_layers": 6,
            "size_mb": round(size_mb, 1),
            "ts": time.strftime("%Y-%m-%d %H:%M:%S")
        }
        with open("/tmp/config.json", "w") as f:
            json.dump(config, f, indent=2)

        api.upload_file(
            path_or_fileobj="/tmp/config.json",
            path_in_repo="config.json",
            repo_id=f"{HF_USER}/gvr-model",
            repo_type="model"
        )

        result = {
            "status": "success",
            "size_mb": round(size_mb, 1),
            "upload": "huggingface_hub OK",
            "url": f"https://huggingface.co/{HF_USER}/gvr-model",
            "ts": time.strftime("%Y-%m-%d %H:%M")
        }
        print(f"✅ Model: https://huggingface.co/{HF_USER}/gvr-model")

    except Exception as e:
        print(f"Upload error: {e}")
        result = {"status": "upload_error", "error": str(e)[:200]}

print(json.dumps(result, indent=2))

# حفظ على GitHub
content = base64.b64encode(json.dumps(result, indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_train_result.json",
    headers={"Authorization": f"token {pat}"}
)
body = {"message": "GVR: Upload fixed", "content": content}
if check.status_code == 200:
    body["sha"] = check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_train_result.json",
    headers={"Authorization": f"token {pat}"},
    json=body
)
