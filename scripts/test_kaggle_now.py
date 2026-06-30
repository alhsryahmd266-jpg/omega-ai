import os, json, requests, base64, time

GH_PAT = os.environ.get("GH_PAT", "")
KAGGLE_USERNAME = os.environ.get("KAGGLE_USERNAME", "")
KAGGLE_KEY = os.environ.get("KAGGLE_KEY", "")

print(f"Username: '{KAGGLE_USERNAME}' (len={len(KAGGLE_USERNAME)})")
print(f"Key: '{KAGGLE_KEY[:8]}...' (len={len(KAGGLE_KEY)})")

results = {
    "username": KAGGLE_USERNAME,
    "key_len": len(KAGGLE_KEY),
    "ts": time.strftime("%Y-%m-%d %H:%M:%S"),
    "tests": {}
}

# طريقة 1: Basic auth (old style)
r1 = requests.get("https://www.kaggle.com/api/v1/kernels",
                   auth=(KAGGLE_USERNAME, KAGGLE_KEY),
                   params={"mine": True, "pageSize": 3},
                   timeout=15)
results["tests"]["basic_auth"] = {"status": r1.status_code, "preview": r1.text[:200]}
print(f"Basic auth: {r1.status_code}")
print(r1.text[:200])

# طريقة 2: Bearer token (KGAT_ style)
r2 = requests.get("https://www.kaggle.com/api/v1/kernels",
                   headers={"Authorization": f"Bearer {KAGGLE_KEY}"},
                   params={"mine": True, "pageSize": 3},
                   timeout=15)
results["tests"]["bearer"] = {"status": r2.status_code, "preview": r2.text[:200]}
print(f"\nBearer: {r2.status_code}")
print(r2.text[:200])

# طريقة 3: kaggle CLI
import subprocess, sys
r3 = subprocess.run([sys.executable, "-m", "pip", "install", "kaggle", "-q"],
                    capture_output=True, text=True)
import os as _os
_os.makedirs(_os.path.expanduser("~/.kaggle"), exist_ok=True)
with open(_os.path.expanduser("~/.kaggle/kaggle.json"), "w") as f:
    json.dump({"username": KAGGLE_USERNAME, "key": KAGGLE_KEY}, f)
_os.chmod(_os.path.expanduser("~/.kaggle/kaggle.json"), 0o600)

r4 = subprocess.run(["kaggle", "kernels", "list", "--mine"],
                    capture_output=True, text=True, timeout=15)
results["tests"]["kaggle_cli"] = {
    "returncode": r4.returncode,
    "stdout": r4.stdout[:200],
    "stderr": r4.stderr[:200]
}
print(f"\nKaggle CLI: {r4.returncode}")
print(r4.stdout[:200])
print(r4.stderr[:200])

# احفظ على GitHub
content = base64.b64encode(json.dumps(results, indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/kaggle_test_result2.json",
    headers={"Authorization": f"token {GH_PAT}"}
)
body = {"message": "Kaggle test", "content": content}
if check.status_code == 200:
    body["sha"] = check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/kaggle_test_result2.json",
    headers={"Authorization": f"token {GH_PAT}"},
    json=body
)
print("\nResults saved!")
