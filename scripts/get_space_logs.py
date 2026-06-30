import os, json, base64, requests

HF_TOKEN = os.environ.get("HF_TOKEN","")
GH_PAT = os.environ.get("GH_PAT","")
HF_USER = "ahmedxg"

# جيب الـ Space info كامل
r = requests.get(f"https://huggingface.co/api/spaces/{HF_USER}/gvr-training-space",
                  headers={"Authorization":f"Bearer {HF_TOKEN}"})
data = r.json()

runtime = data.get("runtime",{})
result = {
    "stage": runtime.get("stage"),
    "errorMessage": runtime.get("errorMessage",""),
    "hardware": runtime.get("hardware",{}),
    "raw_runtime": runtime
}
print(json.dumps(result, indent=2))

content = base64.b64encode(json.dumps(result,indent=2,default=str).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/space_error.json",
    headers={"Authorization":f"token {GH_PAT}"}
)
body = {"message":"Space error details","content":content}
if check.status_code==200: body["sha"]=check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/space_error.json",
    headers={"Authorization":f"token {GH_PAT}"},json=body
)
