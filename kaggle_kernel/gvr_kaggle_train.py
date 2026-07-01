"""
GVR-Ultimate Training on Kaggle GPU (2x T4 / P100)
"""
import os, sys, json, time, subprocess, torch
import torch.nn as nn
import torch.nn.functional as F

def run(cmd):
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if r.stdout: print(r.stdout[:500])
    if r.stderr and r.returncode != 0: print(r.stderr[:300])
    return r

print("="*60)
print("GVR-Ultimate — Kaggle GPU Training")
print("="*60)

device = "cuda" if torch.cuda.is_available() else "cpu"
print(f"\nDevice: {device}")
if device == "cuda":
    n_gpus = torch.cuda.device_count()
    print(f"GPUs available: {n_gpus}")
    for i in range(n_gpus):
        props = torch.cuda.get_device_properties(i)
        print(f"  GPU[{i}]: {props.name} | VRAM: {props.total_memory/1e9:.1f}GB")
    total_vram = sum(torch.cuda.get_device_properties(i).total_memory
                     for i in range(n_gpus)) / 1e9
    print(f"Total VRAM: {total_vram:.1f}GB")

print("\nInstalling deps...")
run("pip install transformers accelerate sentencepiece huggingface_hub -q")

from transformers import AutoTokenizer, AutoModelForCausalLM
from huggingface_hub import HfApi

HF_TOKEN = os.environ.get("HF_TOKEN", "")
HF_USER = "ahmedxg"

# اختار أكبر نموذج يشتغل على الـ VRAM المتاح
if device == "cuda":
    total_vram = sum(torch.cuda.get_device_properties(i).total_memory
                     for i in range(torch.cuda.device_count())) / 1e9
    if total_vram >= 28:   # 2x T4 = 30GB
        MODEL = "Qwen/Qwen2.5-14B-Instruct"
        dtype = torch.float16
    elif total_vram >= 14:  # P100 = 16GB or T4 = 15GB
        MODEL = "Qwen/Qwen2.5-7B-Instruct"
        dtype = torch.float16
    elif total_vram >= 8:
        MODEL = "Qwen/Qwen2.5-3B-Instruct"
        dtype = torch.float16
    else:
        MODEL = "Qwen/Qwen2.5-0.5B-Instruct"
        dtype = torch.float32
else:
    MODEL = "Qwen/Qwen2.5-0.5B-Instruct"
    dtype = torch.float32

print(f"\nSelected model: {MODEL}")
print(f"Loading tokenizer...")
tok = AutoTokenizer.from_pretrained(MODEL, trust_remote_code=True)
print(f"Loading model...")
mdl = AutoModelForCausalLM.from_pretrained(
    MODEL,
    torch_dtype=dtype,
    device_map="auto",
    trust_remote_code=True,
    low_cpu_mem_usage=True,
)
mdl.eval()
params = sum(p.numel() for p in mdl.parameters())
print(f"✅ Loaded {params/1e9:.2f}B params")

# GVR Generation + real confidence
def gen_with_conf(q, temp=0.7, max_t=400):
    msgs = [{"role":"user","content":q}]
    txt = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
    ids = tok(txt, return_tensors="pt").to(mdl.device)
    with torch.no_grad():
        out = mdl.generate(
            **ids, max_new_tokens=max_t, temperature=max(temp,0.01),
            do_sample=temp>0.01, repetition_penalty=1.1,
            pad_token_id=tok.eos_token_id,
            output_scores=True, return_dict_in_generate=True,
        )
    new_toks = out.sequences[0][len(ids.input_ids[0]):]
    answer = tok.decode(new_toks, skip_special_tokens=True)
    confs = [F.softmax(sc[0],dim=-1)[tid].item()
             for sc,tid in zip(out.scores,new_toks)]
    return answer.strip(), sum(confs)/max(len(confs),1)

print("\n=== GVR Generation Tests ===")
test_qs = [
    "Write a Python quicksort with comments",
    "What is 23*47+89? Show work",
    "Explain transformer attention in 3 sentences",
    "Write binary search tree insert in Python",
]
tests = []
for q in test_qs:
    a, conf = gen_with_conf(q)
    tests.append({"q":q, "a":a[:300], "conf":round(conf,3)})
    print(f"Q: {q[:50]}")
    print(f"A: {a[:120]}")
    print(f"Conf: {conf:.3f}\n")

# Train GVR Verifier with hard examples on GPU
print("=== Training GVR Verifier on GPU ===")
class GVRVerifier(nn.Module):
    def __init__(self):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(8, 512), nn.LayerNorm(512), nn.GELU(), nn.Dropout(0.1),
            nn.Linear(512, 256), nn.LayerNorm(256), nn.GELU(), nn.Dropout(0.1),
            nn.Linear(256, 128), nn.GELU(),
            nn.Linear(128, 1), nn.Sigmoid()
        )
    def forward(self, x): return self.net(x)

ver = GVRVerifier().to(device)
opt = torch.optim.AdamW(ver.parameters(), lr=3e-4, weight_decay=0.01)
sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=1e-3, total_steps=2000)
params_v = sum(p.numel() for p in ver.parameters())
print(f"Verifier: {params_v:,} params on {device}")

start_t = time.time()
best_loss = float('inf')
for step in range(2000):
    # positive — high confidence, complete, correct signals
    xp = torch.rand(64, 8, device=device) * 0.25 + 0.75
    yp = torch.ones(64, 1, device=device)
    # negative — low confidence, incomplete
    xn = torch.rand(64, 8, device=device) * 0.25
    yn = torch.zeros(64, 1, device=device)
    # hard negatives — borderline
    xh = torch.rand(64, 8, device=device) * 0.3 + 0.35
    yh = (xh.mean(dim=1, keepdim=True) > 0.55).float()

    opt.zero_grad()
    loss = (F.binary_cross_entropy(ver(xp), yp)
          + F.binary_cross_entropy(ver(xn), yn)
          + 1.5 * F.binary_cross_entropy(ver(xh), yh)) / 3.5
    loss.backward()
    nn.utils.clip_grad_norm_(ver.parameters(), 1.0)
    opt.step()
    sched.step()

    if loss.item() < best_loss:
        best_loss = loss.item()
    if step % 400 == 0:
        print(f"Step {step:4d} | loss={loss.item():.4f} | best={best_loss:.4f}")

elapsed = time.time() - start_t
print(f"\n✅ Training done in {elapsed:.1f}s | Best loss: {best_loss:.4f}")

# Save
out_dir = "/kaggle/working"
torch.save(ver.state_dict(), f"{out_dir}/gvr_verifier_gpu.pt")

config = {
    "backbone": MODEL,
    "backbone_params_B": round(params/1e9, 2),
    "device": device,
    "n_gpus": torch.cuda.device_count() if device=="cuda" else 0,
    "verifier_params": params_v,
    "verifier_best_loss": best_loss,
    "training_steps": 2000,
    "training_time_s": round(elapsed, 1),
    "gen_tests": tests,
    "ts": time.strftime("%Y-%m-%d %H:%M:%S")
}
with open(f"{out_dir}/gvr_config.json", "w") as f:
    json.dump(config, f, indent=2, ensure_ascii=False)

print("\n=== Uploading to HuggingFace ===")
if HF_TOKEN:
    api = HfApi(token=HF_TOKEN)
    api.create_repo(f"{HF_USER}/gvr-ultimate", exist_ok=True, private=False)
    for fname in ["gvr_verifier_gpu.pt", "gvr_config.json"]:
        api.upload_file(
            path_or_fileobj=f"{out_dir}/{fname}",
            path_in_repo=fname,
            repo_id=f"{HF_USER}/gvr-ultimate",
            repo_type="model"
        )
    print(f"✅ Uploaded: https://huggingface.co/{HF_USER}/gvr-ultimate")

print("\n" + "="*60)
print("DONE!")
print(json.dumps(config, indent=2, ensure_ascii=False))
print("="*60)
