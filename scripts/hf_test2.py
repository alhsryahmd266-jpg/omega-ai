import requests, os, json, base64

hf = os.environ.get("HF_TOKEN", "")
pat = os.environ.get("GH_PAT", "")
headers = {"Authorization": f"Bearer {hf}"}

print(f"Token: {hf[:12]}... len={len(hf)}")
results = {}

# جرب endpoints مختلفة
tests = [
    ("whoami",    "GET", "https://huggingface.co/api/whoami"),
    ("user",      "GET", "https://huggingface.co/api/user"),
    ("models",    "GET", "https://huggingface.co/api/models?author=ahmedxg&limit=3"),
    ("create_repo","POST", None),
]

for name, method, url in tests:
    if url is None:
        r = requests.post(
            "https://huggingface.co/api/repos/create",
            headers={**headers, "Content-Type": "application/json"},
            json={"name": "gvr-model", "type": "model", "private": False, "exists_ok": True},
            timeout=15
        )
    elif method == "GET":
        r = requests.get(url, headers=headers, timeout=15)
    
    results[name] = r.status_code
    print(f"{name}: {r.status_code} | {r.text[:150]}")
    print()

# احفظ
content = base64.b64encode(json.dumps(results, indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_test2.json",
    headers={"Authorization": f"token {pat}"}
)
body = {"message": "HF test2", "content": content}
if check.status_code == 200:
    body["sha"] = check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_test2.json",
    headers={"Authorization": f"token {pat}"},
    json=body
)
print(f"\nResults: {json.dumps(results)}")
