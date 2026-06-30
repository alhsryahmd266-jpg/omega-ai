import os, requests
HF_TOKEN = os.environ.get("HF_TOKEN","")
r = requests.get("https://huggingface.co/api/spaces/ahmedxg/gvr-training-space/raw/main/runtime.txt",
                  headers={"Authorization":f"Bearer {HF_TOKEN}"})
print(f"runtime.txt exists: {r.status_code}")

from huggingface_hub import HfApi
api = HfApi(token=HF_TOKEN)
try:
    api.delete_file("runtime.txt", "ahmedxg/gvr-training-space", repo_type="space")
    print("Deleted runtime.txt")
except Exception as e:
    print(f"Delete note: {e}")
