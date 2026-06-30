import os, requests, json, base64, time

HF_TOKEN = os.environ.get("HF_TOKEN","")
GH_PAT = os.environ.get("GH_PAT","")

from huggingface_hub import HfApi
api = HfApi(token=HF_TOKEN)
sid = "ahmedxg/gvr-training-space"

print("Factory rebooting (clears all cache)...")
try:
    result = api.restart_space(sid, factory_reboot=True)
    print(f"Restart triggered: {result}")
except Exception as e:
    print(f"Error: {e}")

# سجل النتيجة
r = {"action":"factory_reboot","ts":time.strftime("%Y-%m-%d %H:%M:%S")}
c = base64.b64encode(json.dumps(r).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/reboot_log.json",
    headers={"Authorization":f"token {GH_PAT}"}
)
body = {"message":"factory reboot","content":c}
if check.status_code==200: body["sha"]=check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/reboot_log.json",
    headers={"Authorization":f"token {GH_PAT}"},json=body
)
print("Done!")
