import os, subprocess, requests, base64, json

GH_PAT = os.environ.get("GH_PAT","")
KAGGLE_USERNAME = os.environ.get("KAGGLE_USERNAME","")
KAGGLE_KEY = os.environ.get("KAGGLE_KEY","")

import os as _os
_os.makedirs(_os.path.expanduser("~/.kaggle"), exist_ok=True)
with open(_os.path.expanduser("~/.kaggle/kaggle.json"), "w") as f:
    json.dump({"username": KAGGLE_USERNAME, "key": KAGGLE_KEY}, f)
_os.chmod(_os.path.expanduser("~/.kaggle/kaggle.json"), 0o600)

r = subprocess.run(
    ["kaggle","kernels","output",f"{KAGGLE_USERNAME}/gvr-gpu-training","-p","/tmp/klog"],
    capture_output=True, text=True
)
log_path = "/tmp/klog/gvr-gpu-training.log"
if os.path.exists(log_path):
    with open(log_path) as f:
        log = f.read()
    print("=== KERNEL LOG (last 100 lines) ===")
    lines = log.split('\n')
    for l in lines[-100:]: print(l)
    
    # احفظ على GitHub
    content = base64.b64encode(log[-5000:].encode()).decode()
    check = requests.get(
        "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/kaggle_error_log.txt",
        headers={"Authorization":f"token {GH_PAT}"}
    )
    body = {"message":"Kaggle error log","content":content}
    if check.status_code==200: body["sha"]=check.json()["sha"]
    requests.put(
        "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/kaggle_error_log.txt",
        headers={"Authorization":f"token {GH_PAT}"},json=body
    )
    print("\nLog saved!")
else:
    print("Log file not found")
