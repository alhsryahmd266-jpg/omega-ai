import os, requests, base64, json

HF_TOKEN = os.environ.get("HF_TOKEN","")
GH_PAT = os.environ.get("GH_PAT","")

r = requests.get(
    "https://huggingface.co/spaces/ahmedxg/gvr-training-space/raw/main/requirements.txt",
    headers={"Authorization":f"Bearer {HF_TOKEN}"}
)
content = r.text
print(f"Status: {r.status_code}")
print(content)

result = {"status":r.status_code, "content":content}
c = base64.b64encode(json.dumps(result).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/req_verify.json",
    headers={"Authorization":f"token {GH_PAT}"}
)
body = {"message":"verify req2","content":c}
if check.status_code==200: body["sha"]=check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/req_verify.json",
    headers={"Authorization":f"token {GH_PAT}"},json=body
)
