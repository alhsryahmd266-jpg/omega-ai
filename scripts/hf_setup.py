import requests, os, json, base64, sys, time

hf_token = os.environ.get("HF_TOKEN", "")
pat      = os.environ.get("GH_PAT", "")

if not hf_token:
    print("ERROR: HF_TOKEN not set!")
    sys.exit(1)

headers = {
    "Authorization": f"Bearer {hf_token}",
    "Content-Type": "application/json"
}

# 1. التحقق من الـ token
me = requests.get("https://huggingface.co/api/whoami", headers=headers, timeout=30)
print(f"Auth: {me.status_code}")
if me.status_code != 200:
    print(f"ERROR: {me.text[:200]}")
    sys.exit(1)

hf_user = me.json().get("name", "ahmedxg")
print(f"HF User: {hf_user}")

# 2. إنشاء model repo
r1 = requests.post(
    "https://huggingface.co/api/repos/create",
    headers=headers,
    json={"name": "gvr-model", "type": "model", "private": False, "exists_ok": True},
    timeout=30
)
print(f"Model repo [{r1.status_code}]: https://huggingface.co/{hf_user}/gvr-model")
if r1.status_code not in [200, 201]:
    print(f"Response: {r1.text[:200]}")

result = {
    "hf_user": hf_user,
    "auth": me.status_code,
    "model_repo": r1.status_code,
    "model_url": f"https://huggingface.co/{hf_user}/gvr-model",
    "ts": time.strftime("%Y-%m-%d %H:%M:%S")
}

print(json.dumps(result, indent=2))

# 3. حفظ على GitHub
content = base64.b64encode(json.dumps(result, indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_result.json",
    headers={"Authorization": f"token {pat}"}, timeout=30
)
body = {"message": "GVR: HF setup done", "content": content}
if check.status_code == 200:
    body["sha"] = check.json()["sha"]
save = requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_result.json",
    headers={"Authorization": f"token {pat}"},
    json=body, timeout=30
)
print(f"Saved to GitHub: {save.status_code}")
