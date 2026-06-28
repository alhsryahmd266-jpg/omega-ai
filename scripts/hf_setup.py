import requests, os, json, base64, sys, time

hf = os.environ.get("HF_TOKEN", "")
pat = os.environ.get("GH_PAT", "")
HF_USER = "ahmedxg"

headers = {
    "Authorization": f"Bearer {hf}",
    "Content-Type": "application/json"
}

print(f"Token: {hf[:12]}...")

# اختبار عبر list models
r_check = requests.get(
    f"https://huggingface.co/api/models?author={HF_USER}&limit=1",
    headers={"Authorization": f"Bearer {hf}"}, timeout=15
)
print(f"Token check: {r_check.status_code} {'✅' if r_check.status_code==200 else '❌'}")

if r_check.status_code != 200:
    print("Token invalid!")
    sys.exit(1)

# إنشاء model repo
r1 = requests.post(
    "https://huggingface.co/api/repos/create",
    headers=headers,
    json={"name": "gvr-model", "type": "model", "private": False, "exists_ok": True},
    timeout=30
)
print(f"Model repo: {r1.status_code} -> https://huggingface.co/{HF_USER}/gvr-model")

result = {
    "hf_user": HF_USER,
    "token_valid": True,
    "model_repo": r1.status_code,
    "model_url": f"https://huggingface.co/{HF_USER}/gvr-model",
    "ts": time.strftime("%Y-%m-%d %H:%M:%S")
}
print(json.dumps(result, indent=2))

content = base64.b64encode(json.dumps(result, indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_result.json",
    headers={"Authorization": f"token {pat}"}
)
body = {"message": "HF setup done", "content": content}
if check.status_code == 200:
    body["sha"] = check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_result.json",
    headers={"Authorization": f"token {pat}"},
    json=body
)
print("✅ Setup complete!")
