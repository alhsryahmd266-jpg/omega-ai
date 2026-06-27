import requests, os, json, base64, sys, traceback, time

hf_token = os.environ.get("HF_TOKEN", "")
pat      = os.environ.get("GH_PAT", "")

output = []
result = {"error": None, "steps": {}}

try:
    output.append(f"HF_TOKEN set: {bool(hf_token)} len={len(hf_token)}")
    output.append(f"Token prefix: {hf_token[:12] if hf_token else 'EMPTY'}")
    
    headers = {"Authorization": f"Bearer {hf_token}"}
    
    # Test connection
    r = requests.get("https://huggingface.co/api/whoami", headers=headers, timeout=30)
    output.append(f"Auth status: {r.status_code}")
    output.append(f"Auth response: {r.text[:200]}")
    result["steps"]["auth"] = r.status_code
    
    if r.status_code == 200:
        user = r.json().get("name", "unknown")
        output.append(f"User: {user}")
        result["steps"]["user"] = user
        
        # Create repo
        r2 = requests.post(
            "https://huggingface.co/api/repos/create",
            headers={**headers, "Content-Type": "application/json"},
            json={"name": "gvr-model", "type": "model", "private": False, "exists_ok": True},
            timeout=30
        )
        output.append(f"Repo create: {r2.status_code}")
        output.append(f"Repo response: {r2.text[:200]}")
        result["steps"]["repo"] = r2.status_code
        result["model_url"] = f"https://huggingface.co/{user}/gvr-model"
    
    result["success"] = True

except Exception as e:
    tb = traceback.format_exc()
    output.append(f"ERROR: {e}")
    output.append(f"Traceback: {tb}")
    result["error"] = str(e)

output_str = "\n".join(output)
print(output_str)
result["log"] = output_str
result["ts"] = time.strftime("%Y-%m-%d %H:%M:%S")

# احفظ على GitHub دايماً حتى لو فيه error
content = base64.b64encode(json.dumps(result, indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_debug_result.json",
    headers={"Authorization": f"token {pat}"}, timeout=30
)
body = {"message": "GVR: HF debug", "content": content}
if check.status_code == 200:
    body["sha"] = check.json()["sha"]
save = requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_debug_result.json",
    headers={"Authorization": f"token {pat}"},
    json=body, timeout=30
)
print(f"\nSaved debug: {save.status_code}")

# لا تفشل بـ exit code حتى لو فيه مشكلة
sys.exit(0)
