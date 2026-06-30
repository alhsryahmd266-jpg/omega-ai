import os, json, base64, requests

HF_TOKEN = os.environ.get("HF_TOKEN","")
GH_PAT = os.environ.get("GH_PAT","")
HF_USER = "ahmedxg"

result = {}

# تحقق من model repo
r1 = requests.get(f"https://huggingface.co/api/models/{HF_USER}/gvr-ultimate",
                   headers={"Authorization":f"Bearer {HF_TOKEN}"})
print(f"Model repo: {r1.status_code}")
if r1.status_code == 200:
    d = r1.json()
    print(f"  Files: {[f['rfilename'] for f in d.get('siblings',[])]}")
    result["model_files"] = [f['rfilename'] for f in d.get('siblings',[])]

# تحقق من Space
r2 = requests.get(f"https://huggingface.co/api/spaces/{HF_USER}/gvr-training-space",
                   headers={"Authorization":f"Bearer {HF_TOKEN}"})
print(f"Space repo: {r2.status_code}")
if r2.status_code == 200:
    d = r2.json()
    print(f"  Files: {[f['rfilename'] for f in d.get('siblings',[])]}")
    print(f"  Runtime: {d.get('runtime',{}).get('stage','?')}")
    result["space_files"] = [f['rfilename'] for f in d.get('siblings',[])]
    result["space_stage"] = d.get('runtime',{}).get('stage','?')

result["model_check"] = r1.status_code
result["space_check"] = r2.status_code

content = base64.b64encode(json.dumps(result,indent=2).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_verify_result.json",
    headers={"Authorization":f"token {GH_PAT}"}
)
body = {"message":"HF verify","content":content}
if check.status_code==200: body["sha"]=check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/hf_verify_result.json",
    headers={"Authorization":f"token {GH_PAT}"},json=body
)
print("Verification saved!")
