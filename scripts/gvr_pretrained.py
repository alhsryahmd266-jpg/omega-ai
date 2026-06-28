import requests, os, json, base64, sys, time, torch
import torch.nn as nn, torch.nn.functional as F

HF_TOKEN = os.environ.get("HF_TOKEN", "")
GH_PAT   = os.environ.get("GH_PAT", "")

MODELS = [
    "Qwen/Qwen2.5-7B-Instruct",
    "microsoft/Phi-3.5-mini-instruct",
    "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
]

def call_hf_api(model_id: str, prompt: str, token: str) -> str:
    """استدعاء HF Inference API مع handling صح للـ response"""
    url = f"https://api-inference.huggingface.co/models/{model_id}"
    headers = {"Authorization": f"Bearer {token}"}

    # جرب الـ text-generation endpoint
    payload = {
        "inputs": prompt,
        "parameters": {
            "max_new_tokens": 300,
            "temperature": 0.7,
            "do_sample": True,
            "return_full_text": False,
        },
        "options": {"wait_for_model": True}
    }

    r = requests.post(url, headers=headers, json=payload, timeout=120)
    print(f"  API Status: {r.status_code}")

    if r.status_code != 200:
        print(f"  Error: {r.text[:200]}")
        # جرب chat completions endpoint
        chat_url = f"https://api-inference.huggingface.co/models/{model_id}/v1/chat/completions"
        chat_payload = {
            "model": model_id,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 300
        }
        r2 = requests.post(chat_url, headers=headers, json=chat_payload, timeout=120)
        print(f"  Chat API Status: {r2.status_code}")
        if r2.status_code == 200:
            d = r2.json()
            return d.get("choices", [{}])[0].get("message", {}).get("content", "")
        return ""

    # Parse response
    raw = r.json()
    print(f"  Response type: {type(raw)}")
    if isinstance(raw, list) and raw:
        item = raw[0]
        if isinstance(item, dict):
            text = item.get("generated_text", item.get("text", ""))
            print(f"  Got text: {len(text)} chars")
            return text
    elif isinstance(raw, dict):
        text = raw.get("generated_text", raw.get("text", ""))
        return text
    print(f"  Raw: {str(raw)[:200]}")
    return ""

class SmallVerifier(nn.Module):
    def __init__(self):
        super().__init__()
        self.embed = nn.Embedding(32000, 128)
        self.pool = nn.AdaptiveAvgPool1d(1)
        self.fc = nn.Sequential(nn.Linear(128, 32), nn.ReLU(), nn.Linear(32, 1), nn.Sigmoid())

    def score(self, question: str, answer: str) -> float:
        if not answer: return 0.0
        combined = f"{question} {answer}"
        ids = torch.tensor([ord(c) % 32000 for c in combined[:200]], dtype=torch.long).unsqueeze(0)
        with torch.no_grad():
            x = self.embed(ids).transpose(1, 2)
            x = self.pool(x).squeeze(-1)
            return self.fc(x).item()

# ── Main Test ──
print("=== GVR + HF Inference API ===\n")
verifier = SmallVerifier()
results = {}

test_questions = [
    "What is 2 + 2?",
    "Write a Python function to reverse a string",
    "What is machine learning?"
]

working_model = None
for model_id in MODELS:
    print(f"\nTrying: {model_id}")
    prompt = "What is 2 + 2? Give a direct answer."
    answer = call_hf_api(model_id, prompt, HF_TOKEN)
    if answer and len(answer) > 5:
        print(f"  ✅ Works! Answer: {answer[:80]}")
        working_model = model_id
        results["working_model"] = model_id
        results["test_answer"] = answer[:200]
        break
    else:
        print(f"  ❌ No answer")
        results[model_id] = "no_response"

if working_model:
    print(f"\n=== GVR Loop with {working_model} ===")
    gvr_results = []
    for q in test_questions[:2]:
        print(f"\nQ: {q}")
        best_answer = ""
        best_score = -1.0
        for i in range(2):
            answer = call_hf_api(working_model, q, HF_TOKEN)
            score = verifier.score(q, answer)
            print(f"  Iter {i+1}: score={score:.3f} | {answer[:60]}...")
            if score > best_score:
                best_score = score
                best_answer = answer
            if score >= 0.7:
                break
        gvr_results.append({
            "question": q,
            "answer": best_answer[:150],
            "score": best_score
        })
    results["gvr_results"] = gvr_results

print("\n=== FINAL ===")
print(json.dumps(results, indent=2, ensure_ascii=False)[:500])

content = base64.b64encode(json.dumps(results, indent=2, ensure_ascii=False).encode()).decode()
check = requests.get(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/gvr_pretrained_test.json",
    headers={"Authorization": f"token {GH_PAT}"}
)
body = {"message": "GVR-Pretrained v2", "content": content}
if check.status_code == 200:
    body["sha"] = check.json()["sha"]
requests.put(
    "https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/gvr_pretrained_test.json",
    headers={"Authorization": f"token {GH_PAT}"},
    json=body
)
print("Saved!")
