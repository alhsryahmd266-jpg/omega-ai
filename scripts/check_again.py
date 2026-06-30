import os, json, requests, base64
HF_TOKEN = os.environ.get("HF_TOKEN","")
GH_PAT = os.environ.get("GH_PAT","")

r = requests.get("https://huggingface.co/api/spaces/ahmedxg/gvr-training-space",
                  headers={"Authorization":f"Bearer {HF_TOKEN}"})
d = r.json()
runtime = d.get("runtime",{})
result = {"stage": runtime.get("stage"), "error": runtime.get("errorMessage","")}

content = base64.b64encode(json.dumps(result,indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/space_check2.json",
    headers={"Authorization":f"token {GH_PAT}"}
)
body = {"message":"check3","content":content}
if check.status_code==200: body["sha"]=check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/space_check2.json",
    headers={"Authorization":f"token {GH_PAT}"},json=body
)
print(result["error"])
