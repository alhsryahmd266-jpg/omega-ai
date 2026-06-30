import os, json, requests
HF_TOKEN = os.environ.get("HF_TOKEN","")
r = requests.get("https://huggingface.co/api/spaces/ahmedxg/gvr-training-space",
                  headers={"Authorization":f"Bearer {HF_TOKEN}"})
d = r.json()
runtime = d.get("runtime",{})
print(f"Stage: {runtime.get('stage')}")
print(f"Error: {runtime.get('errorMessage','')[:300]}")
