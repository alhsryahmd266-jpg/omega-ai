import os, json, base64, requests, time

HF_TOKEN = os.environ.get("HF_TOKEN","")
GH_PAT = os.environ.get("GH_PAT","")
HF_USER = "ahmedxg"
sid = f"{HF_USER}/gvr-training-space"

from huggingface_hub import HfApi
api = HfApi(token=HF_TOKEN)

# رفع كل الملفات المحدّثة
for fn in ["app.py", "requirements.txt", "runtime.txt", "README.md"]:
    fp = f"hf_space/{fn}"
    if os.path.exists(fp):
        api.upload_file(
            path_or_fileobj=fp,
            path_in_repo=fn,
            repo_id=sid,
            repo_type="space",
        )
        print(f"{fn} re-uploaded")

# Factory reboot عشان يبني من جديد
try:
    api.restart_space(sid, factory_reboot=True)
    print("Factory reboot triggered!")
except Exception as e:
    print(f"Restart note: {e}")

time.sleep(20)

# تحقق
r = requests.get(f"https://huggingface.co/api/spaces/{sid}",
                  headers={"Authorization":f"Bearer {HF_TOKEN}"})
data = r.json()
runtime = data.get("runtime",{})
result = {"stage": runtime.get("stage"), "error": runtime.get("errorMessage","")[:200]}
print(json.dumps(result, indent=2))

content = base64.b64encode(json.dumps(result,indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/space_redeploy.json",
    headers={"Authorization":f"token {GH_PAT}"}
)
body = {"message":"Redeploy result","content":content}
if check.status_code==200: body["sha"]=check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/space_redeploy.json",
    headers={"Authorization":f"token {GH_PAT}"},json=body
)
