import requests, os, json, base64, sys

hf_token = os.environ.get("HF_TOKEN", "")
pat      = os.environ.get("GH_PAT", "")
headers  = {"Authorization": f"Bearer {hf_token}"}

# اختبر الـ token
me = requests.get("https://huggingface.co/api/whoami", headers=headers)
print(f"Auth: {me.status_code}")
if me.status_code != 200:
    print("Token invalid!")
    sys.exit(1)

hf_user = me.json().get("name", "ahmedxg")
print(f"User: {hf_user}")

# إنشاء model repo
r1 = requests.post("https://huggingface.co/api/repos/create", headers={
    **headers, "Content-Type": "application/json"
}, json={"name": "gvr-model", "type": "model", "private": False, "exists_ok": True})
print(f"Model repo: {r1.status_code} -> https://huggingface.co/{hf_user}/gvr-model")

result = {
    "hf_user": hf_user,
    "auth": me.status_code,
    "model_repo": r1.status_code,
    "model_url": f"https://huggingface.co/{hf_user}/gvr-model"
}

# احفظ على GitHub
content = base64.b64encode(json.dumps(result, indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_result.json",
    headers={"Authorization": f"token {pat}"}
)
body = {"message": "GVR: HF setup", "content": content}
if check.status_code == 200:
    body["sha"] = check.json()["sha"]
save = requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_result.json",
    headers={"Authorization": f"token {pat}"},
    json=body
)
print(f"Saved: {save.status_code}")
print(json.dumps(result, indent=2))
