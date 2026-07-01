import os, json, requests, base64, subprocess, sys

GH_PAT = os.environ.get("GH_PAT", "")
KAGGLE_USERNAME = os.environ.get("KAGGLE_USERNAME", "")
KAGGLE_KEY = os.environ.get("KAGGLE_KEY", "")

print(f"Username: '{KAGGLE_USERNAME}'")
print(f"Key: '{KAGGLE_KEY[:8]}...' len={len(KAGGLE_KEY)}")
print()

results = {"username": KAGGLE_USERNAME, "tests": {}}

# install kaggle properly
subprocess.run([sys.executable, "-m", "pip", "install", "kaggle==1.6.17", "-q"],
               capture_output=True)

# setup kaggle.json
import os as _os
_os.makedirs(_os.path.expanduser("~/.kaggle"), exist_ok=True)
with open(_os.path.expanduser("~/.kaggle/kaggle.json"), "w") as f:
    json.dump({"username": KAGGLE_USERNAME, "key": KAGGLE_KEY}, f)
_os.chmod(_os.path.expanduser("~/.kaggle/kaggle.json"), 0o600)

# Test 1: kaggle competitions list (simplest test)
r1 = subprocess.run(["kaggle", "competitions", "list", "--page", "1"],
                    capture_output=True, text=True, timeout=20)
print(f"kaggle competitions list: {r1.returncode}")
print(r1.stdout[:200])
print(r1.stderr[:200])
results["tests"]["competitions_list"] = {
    "rc": r1.returncode, "out": r1.stdout[:200], "err": r1.stderr[:200]
}

# Test 2: API v1 datasets (different endpoint)
r2 = requests.get("https://www.kaggle.com/api/v1/datasets",
                   auth=(KAGGLE_USERNAME, KAGGLE_KEY),
                   params={"mine": True}, timeout=15)
print(f"\nAPI v1 datasets: {r2.status_code}")
print(r2.text[:200])
results["tests"]["api_datasets"] = {"status": r2.status_code, "out": r2.text[:200]}

# Test 3: profile endpoint
r3 = requests.get(f"https://www.kaggle.com/api/v1/users/{KAGGLE_USERNAME}",
                   auth=(KAGGLE_USERNAME, KAGGLE_KEY), timeout=15)
print(f"\nUser profile: {r3.status_code}")
print(r3.text[:200])
results["tests"]["user_profile"] = {"status": r3.status_code, "out": r3.text[:200]}

# Test 4: check if GPU is enabled
r4 = subprocess.run(["kaggle", "kernels", "list", "--mine", "--page", "1"],
                    capture_output=True, text=True, timeout=20)
print(f"\nkaggle kernels list: {r4.returncode}")
print(r4.stdout[:200])
print(r4.stderr[:300])
results["tests"]["kernels_list"] = {
    "rc": r4.returncode, "out": r4.stdout[:200], "err": r4.stderr[:300]
}

# Save
content = base64.b64encode(json.dumps(results, indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/kaggle_deep_test.json",
    headers={"Authorization": f"token {GH_PAT}"}
)
body = {"message": "Kaggle deep test", "content": content}
if check.status_code == 200:
    body["sha"] = check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/kaggle_deep_test.json",
    headers={"Authorization": f"token {GH_PAT}"}, json=body
)
print("\nSaved!")
