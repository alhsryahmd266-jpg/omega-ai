import requests, os, json, base64

HF_TOKEN = os.environ.get("HF_TOKEN","")
GH_PAT = os.environ.get("GH_PAT","")

r = requests.get(
    "https://huggingface.co/ahmedxg/gvr-chat-app/resolve/main/GVR-Chat.apk",
    headers={"Authorization": f"Bearer {HF_TOKEN}"},
    allow_redirects=True, timeout=15
)
result = {
    "status": r.status_code,
    "size_mb": round(int(r.headers.get('content-length',0))/1e6, 1) if r.headers.get('content-length') else None,
    "url": "https://huggingface.co/ahmedxg/gvr-chat-app/resolve/main/GVR-Chat.apk"
}
print(json.dumps(result, indent=2))

content = base64.b64encode(json.dumps(result,indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/final_apk_verify.json",
    headers={"Authorization": f"token {GH_PAT}"}
)
body = {"message": "Final APK v2 verify", "content": content}
if check.status_code == 200:
    body["sha"] = check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/final_apk_verify.json",
    headers={"Authorization": f"token {GH_PAT}"}, json=body
)
