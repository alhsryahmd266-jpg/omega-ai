import os, json, requests, base64, subprocess, sys

GH_PAT = os.environ.get("GH_PAT","")
KAGGLE_USERNAME = os.environ.get("KAGGLE_USERNAME","")
KAGGLE_KEY = os.environ.get("KAGGLE_KEY","")

import os as _os
_os.makedirs(_os.path.expanduser("~/.kaggle"), exist_ok=True)
with open(_os.path.expanduser("~/.kaggle/kaggle.json"), "w") as f:
    json.dump({"username": KAGGLE_USERNAME, "key": KAGGLE_KEY}, f)
_os.chmod(_os.path.expanduser("~/.kaggle/kaggle.json"), 0o600)

# شوف status
r1 = subprocess.run(["kaggle","kernels","status",f"{KAGGLE_USERNAME}/gvr-gpu-training"],
                    capture_output=True, text=True)
print(f"Status: {r1.stdout.strip()}")
print(f"Err: {r1.stderr[:200]}")

# شوف الـ output files
r2 = subprocess.run(["kaggle","kernels","output",f"{KAGGLE_USERNAME}/gvr-gpu-training","-p","/tmp/kout"],
                    capture_output=True, text=True)
print(f"Output download: {r2.returncode}")
print(r2.stdout[:300])
print(r2.stderr[:300])

import glob
files = glob.glob("/tmp/kout/**/*", recursive=True)
print(f"Files found: {files}")

# احفظ النتيجة
result = {
    "status_raw": r1.stdout.strip(),
    "download_rc": r2.returncode,
    "download_out": r2.stdout[:300],
    "download_err": r2.stderr[:300],
    "files": files
}

content = base64.b64encode(json.dumps(result,indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/kaggle_status_check.json",
    headers={"Authorization":f"token {GH_PAT}"}
)
body = {"message":"Kaggle status check","content":content}
if check.status_code==200: body["sha"]=check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/kaggle_status_check.json",
    headers={"Authorization":f"token {GH_PAT}"},json=body
)
print("Saved!")
