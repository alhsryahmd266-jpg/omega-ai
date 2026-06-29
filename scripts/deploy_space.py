import os, json, base64, time, requests
from huggingface_hub import HfApi

hf  = os.environ.get("HF_TOKEN","")
pat = os.environ.get("GH_PAT","")
HF_USER = "ahmedxg"
SPACE_ID = f"{HF_USER}/gvr-training-space"

api = HfApi(token=hf)

# إنشاء/تحديث Space
try:
    api.create_repo(SPACE_ID, repo_type="space", space_sdk="gradio",
                    exist_ok=True, private=False)
    print(f"Space ready!")
except Exception as e:
    print(f"Space: {e}")

# رفع الملفات
files = {"README.md":"hf_space/README.md",
         "app.py":"hf_space/app.py",
         "requirements.txt":"hf_space/requirements.txt"}

for dest,src in files.items():
    if os.path.exists(src):
        api.upload_file(src, dest, SPACE_ID, repo_type="space",
                       commit_message=f"Update {dest}")
        print(f"✅ {dest}")

# أضف secrets للـ Space
try:
    api.add_space_secret(SPACE_ID, "HF_TOKEN", hf)
    print("✅ HF_TOKEN secret added")
except Exception as e:
    print(f"Secret: {e}")

result = {"space_url": f"https://huggingface.co/spaces/{SPACE_ID}",
          "ts": time.strftime("%Y-%m-%d %H:%M:%S")}

# احفظ على GitHub
content = base64.b64encode(json.dumps(result,indent=2).encode()).decode()
check = requests.get(
    f"https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/space_status.json",
    headers={"Authorization":f"token {pat}"}
)
body = {"message":"Space deployed","content":content}
if check.status_code==200: body["sha"]=check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/space_status.json",
    headers={"Authorization":f"token {pat}"},json=body
)
print(f"\n🚀 {result['space_url']}")
