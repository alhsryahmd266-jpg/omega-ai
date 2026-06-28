import os, sys, json, base64, time, requests, subprocess

HF_TOKEN = os.environ.get("HF_TOKEN", "")
GH_PAT   = os.environ.get("GH_PAT", "")

def save_result(data):
    content = base64.b64encode(json.dumps(data, indent=2, ensure_ascii=False).encode()).decode()
    check = requests.get(
        "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/gvr_pretrained_test.json",
        headers={"Authorization": f"token {GH_PAT}"}
    )
    body = {"message": "GVR-Pretrained results", "content": content}
    if check.status_code == 200:
        body["sha"] = check.json()["sha"]
    requests.put(
        "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/gvr_pretrained_test.json",
        headers={"Authorization": f"token {GH_PAT}"},
        json=body
    )

print("Installing transformers...")
subprocess.run([sys.executable, "-m", "pip", "install",
    "transformers", "accelerate", "sentencepiece", "-q"],
    capture_output=True)

print("\n=== Downloading Qwen2.5-1.5B-Instruct ===")
print("متدرب على 18 تريليون token من Alibaba")

from transformers import AutoTokenizer, AutoModelForCausalLM
import torch

MODEL_ID = "Qwen/Qwen2.5-1.5B-Instruct"
# بديل أصغر لو الذاكرة محدودة
BACKUP_ID = "Qwen/Qwen2.5-0.5B-Instruct"

result = {
    "strategy": "download_pretrained",
    "target_model": MODEL_ID,
    "status": "starting",
    "ts": time.strftime("%Y-%m-%d %H:%M:%S")
}
save_result(result)

try:
    # تحميل الـ tokenizer أول
    print("Loading tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained(
        MODEL_ID,
        token=HF_TOKEN,
        trust_remote_code=True
    )
    print("Tokenizer ✅")

    # تحميل النموذج بـ float16 لتوفير الذاكرة
    print("Loading model (1.5B params)...")
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_ID,
        token=HF_TOKEN,
        torch_dtype=torch.float16,
        device_map="auto",
        trust_remote_code=True,
        low_cpu_mem_usage=True
    )
    model.eval()
    params = sum(p.numel() for p in model.parameters())
    print(f"Model loaded! {params/1e9:.2f}B params ✅")

    # GVR Verifier صغير
    import torch.nn as nn
    class QuickVerifier(nn.Module):
        def __init__(self):
            super().__init__()
            self.fc = nn.Sequential(
                nn.Linear(100, 64), nn.ReLU(),
                nn.Linear(64, 1), nn.Sigmoid()
            )
        def score(self, q: str, a: str) -> float:
            if not a or len(a) < 5: return 0.0
            # features بسيطة
            features = [
                len(a)/500, len(a.split())/100,
                a.count('.')/10, a.count('\n')/10,
                1.0 if any(w in a.lower() for w in ['because','therefore','result']) else 0.0,
            ] + [0.0] * 95
            x = torch.tensor(features[:100], dtype=torch.float).unsqueeze(0)
            with torch.no_grad(): return self.fc(x).item()

    verifier = QuickVerifier()

    def generate(prompt: str, temperature: float = 0.7) -> str:
        messages = [{"role": "user", "content": prompt}]
        text = tokenizer.apply_chat_template(
            messages, tokenize=False, add_generation_prompt=True
        )
        inputs = tokenizer(text, return_tensors="pt").to(model.device)
        with torch.no_grad():
            outputs = model.generate(
                **inputs,
                max_new_tokens=200,
                temperature=temperature,
                do_sample=temperature > 0,
                pad_token_id=tokenizer.eos_token_id,
            )
        output_ids = outputs[0][len(inputs.input_ids[0]):]
        return tokenizer.decode(output_ids, skip_special_tokens=True)

    # GVR Loop
    print("\n=== GVR Loop Test ===")
    test_qs = [
        "Write a Python function to reverse a string",
        "What is machine learning? Explain simply",
        "What is 15 * 7?"
    ]

    gvr_results = []
    for q in test_qs:
        print(f"\nQ: {q}")
        best, best_score = "", -1.0
        for i in range(2):
            a = generate(q, 0.7 + i*0.1)
            score = verifier.score(q, a)
            print(f"  [{i+1}] score={score:.3f} | {a[:60]}...")
            if score > best_score:
                best_score, best = score, a
            if score >= 0.6: break
        gvr_results.append({
            "q": q, "a": best[:200], "score": round(best_score, 3)
        })

    result = {
        "status": "success",
        "model": MODEL_ID,
        "params": f"{params/1e9:.2f}B",
        "training_data": "18 trillion tokens (Alibaba)",
        "gvr_results": gvr_results,
        "ts": time.strftime("%Y-%m-%d %H:%M:%S")
    }
    print("\n✅ GVR + Qwen2.5-1.5B working!")

    # رفع النموذج على HF Hub
    from huggingface_hub import HfApi
    api = HfApi(token=HF_TOKEN)
    try:
        api.create_repo("ahmedxg/gvr-qwen-pretrained", exist_ok=True, private=False)
        # رفع الـ Verifier فقط (النموذج الكبير موجود على HF أصلاً)
        torch.save(verifier.state_dict(), "/tmp/gvr_verifier.pt")
        api.upload_file(
            path_or_fileobj="/tmp/gvr_verifier.pt",
            path_in_repo="gvr_verifier.pt",
            repo_id="ahmedxg/gvr-qwen-pretrained"
        )
        result["hf_url"] = "https://huggingface.co/ahmedxg/gvr-qwen-pretrained"
        print(f"✅ Verifier uploaded to HF!")
    except Exception as e:
        print(f"HF upload note: {e}")

except Exception as e:
    import traceback
    result = {
        "status": "error",
        "error": str(e)[:200],
        "tb": traceback.format_exc()[-300:],
        "ts": time.strftime("%Y-%m-%d %H:%M:%S")
    }
    print(f"Error: {e}")

save_result(result)
print("\nDone! Results saved.")
