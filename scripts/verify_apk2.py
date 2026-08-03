import requests, os, json, base64

HF_TOKEN = os.environ.get("HF_TOKEN","")
GH_PAT = os.environ.get("GH_PAT","")

r = requests.get(
    "https://huggingface.co/api/models/ahmedxg/gvr-chat-app",
    headers={"Authorization": f"Bearer {HF_TOKEN}"}
)
result = {"status": r.status_code}
if r.status_code == 200:
    d = r.json()
    files = [f['rfilename'] for f in d.get('siblings',[])]
    result["files"] = files
    if "GVR-Chat.apk" in files:
        r2 = requests.head(
            "https://huggingface.co/ahmedxg/gvr-chat-app/resolve/main/GVR-Chat.apk",
            headers={"Authorization": f"Bearer {HF_TOKEN}"},
            allow_redirects=True, timeout=15
        )
        size = r2.headers.get('content-length')
        result["apk_size_mb"] = round(int(size)/1e6,1) if size else None
        result["download_status"] = r2.status_code
        result["download_url"] = "https://huggingface.co/ahmedxg/gvr-chat-app/resolve/main/GVR-Chat.apk"

print(json.dumps(result, indent=2))

content = base64.b64encode(json.dumps(result,indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/apk_verify.json",
    headers={"Authorization":f"token {GH_PAT}"}
)
body = {"message":"APK verify","content":content}
if check.status_code==200: body["sha"]=check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/apk_verify.json",
    headers={"Authorization":f"token {GH_PAT}"}, json=body
)
