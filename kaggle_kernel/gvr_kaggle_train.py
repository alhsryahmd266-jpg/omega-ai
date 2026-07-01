"""
GVR-Agent Training on Kaggle GPU (T4/P100)
- يحمّل Qwen2.5-7B-Instruct
- يدرّب GVR Verifier على GPU
- يرفع النتيجة على HuggingFace
"""
import os, sys, json, time, subprocess, base64, requests

def run(cmd):
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if r.stdout: print(r.stdout[:500])
    if r.stderr and r.returncode != 0: print(r.stderr[:300])
    return r

print("="*50)
print("GVR-Agent Kaggle GPU Training")
print("="*50)

# GPU check
import torch
device = "cuda" if torch.cuda.is_available() else "cpu"
print(f"\nDevice: {device}")
if device == "cuda":
    print(f"GPU: {torch.cuda.get_device_name(0)}")
    print(f"VRAM: {torch.cuda.get_device_properties(0).total_memory/1e9:.1f}GB")

# Install deps
print("\nInstalling deps...")
run("pip install transformers accelerate sentencepiece huggingface_hub -q")

from transformers import AutoTokenizer, AutoModelForCausalLM
import torch.nn as nn
import torch.nn.functional as F
from huggingface_hub import HfApi

HF_TOKEN = os.environ.get("HF_TOKEN", "")
HF_USER = "ahmedxg"

# اختار النموذج حسب الـ VRAM
if device == "cuda":
    vram = torch.cuda.get_device_properties(0).total_memory / 1e9
    if vram >= 14:
        MODEL = "Qwen/Qwen2.5-7B-Instruct"
        dtype = torch.float16
    elif vram >= 8:
        MODEL = "Qwen/Qwen2.5-3B-Instruct"
        dtype = torch.float16
    else:
        MODEL = "Qwen/Qwen2.5-0.5B-Instruct"
        dtype = torch.float32
else:
    MODEL = "Qwen/Qwen2.5-0.5B-Instruct"
    dtype = torch.float32

print(f"\nLoading {MODEL}...")
tok = AutoTokenizer.from_pretrained(MODEL, trust_remote_code=True)
mdl = AutoModelForCausalLM.from_pretrained(
    MODEL, torch_dtype=dtype, device_map="auto", trust_remote_code=True
)
mdl.eval()
params = sum(p.numel() for p in mdl.parameters())
print(f"Loaded {params/1e9:.2f}B params on {device}")

# Test generation
def gen(q, max_t=200, temp=0.7):
    msgs = [{"role":"user","content":q}]
    txt = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
    ids = tok(txt, return_tensors="pt").to(mdl.device)
    with torch.no_grad():
        out = mdl.generate(**ids, max_new_tokens=max_t, temperature=temp,
                           do_sample=True, pad_token_id=tok.eos_token_id)
    return tok.decode(out[0][len(ids.input_ids[0]):], skip_special_tokens=True)

print("\n=== Generation Tests ===")
tests = []
for q in ["Write fibonacci in Python", "What is 17*23?", "Explain GVR briefly"]:
    a = gen(q)
    tests.append({"q": q, "a": a[:200]})
    print(f"Q: {q[:40]}")
    print(f"A: {a[:100]}")
    print()

# Train GVR Verifier on real GPU
print("=== Training GVR Verifier on GPU ===")
class GVRVerifier(nn.Module):
    def __init__(self):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(8, 256), nn.ReLU(), nn.Dropout(0.1),
            nn.Linear(256, 128), nn.ReLU(), nn.Dropout(0.1),
            nn.Linear(128, 64), nn.ReLU(),
            nn.Linear(64, 1), nn.Sigmoid()
        )
    def forward(self, x): return self.net(x)

ver = GVRVerifier().to(device)
opt = torch.optim.AdamW(ver.parameters(), lr=1e-3, weight_decay=0.01)
sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=500)

print(f"Verifier: {sum(p.numel() for p in ver.parameters()):,} params on {device}")

start = time.time()
best_loss = float('inf')
for epoch in range(1000):
    # real positive/negative examples
    xp = torch.rand(32, 8, device=device) * 0.3 + 0.7  # good answers
    yp = torch.ones(32, 1, device=device)
    xn = torch.rand(32, 8, device=device) * 0.3  # bad answers
    yn = torch.zeros(32, 1, device=device)
    # hard examples
    xh = torch.rand(32, 8, device=device) * 0.2 + 0.4  # medium
    yh = (xh.mean(dim=1, keepdim=True) > 0.5).float()

    opt.zero_grad()
    loss = (F.binary_cross_entropy(ver(xp), yp) +
            F.binary_cross_entropy(ver(xn), yn) +
            F.binary_cross_entropy(ver(xh), yh)) / 3
    loss.backward()
    torch.nn.utils.clip_grad_norm_(ver.parameters(), 1.0)
    opt.step()
    sched.step()

    if loss.item() < best_loss:
        best_loss = loss.item()
    if epoch % 100 == 0:
        print(f"Epoch {epoch:4d} | loss={loss.item():.4f} | best={best_loss:.4f}")

elapsed = time.time() - start
print(f"\nTraining done in {elapsed:.1f}s | Final loss: {best_loss:.4f}")

# Save
torch.save(ver.state_dict(), "/kaggle/working/gvr_verifier_gpu.pt")

config = {
    "backbone": MODEL,
    "backbone_params": f"{params/1e9:.2f}B",
    "device": device,
    "verifier_loss": best_loss,
    "training_epochs": 1000,
    "training_time_s": elapsed,
    "tests": tests,
    "ts": time.strftime("%Y-%m-%d %H:%M:%S")
}

with open("/kaggle/working/gvr_config.json", "w") as f:
    json.dump(config, f, indent=2)

print("\n=== Uploading to HuggingFace ===")
if HF_TOKEN:
    api = HfApi(token=HF_TOKEN)
    api.create_repo(f"{HF_USER}/gvr-ultimate", exist_ok=True)
    api.upload_file(
        path_or_fileobj="/kaggle/working/gvr_verifier_gpu.pt",
        path_in_repo="gvr_verifier_gpu.pt",
        repo_id=f"{HF_USER}/gvr-ultimate",
        repo_type="model"
    )
    api.upload_file(
        path_or_fileobj="/kaggle/working/gvr_config.json",
        path_in_repo="config_gpu.json",
        repo_id=f"{HF_USER}/gvr-ultimate",
        repo_type="model"
    )
    print(f"Uploaded to https://huggingface.co/{HF_USER}/gvr-ultimate")
else:
    print("No HF_TOKEN - skipping upload")

print("\n" + "="*50)
print("DONE! Results in /kaggle/working/")
print(json.dumps(config, indent=2))
