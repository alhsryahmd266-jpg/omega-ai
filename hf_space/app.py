"""
GVR-Ultimate — Generate -> Verify -> Refine (Real Implementation)
Generator: Qwen2.5-7B-Instruct
Verifier: real signals (confidence + self-consistency + syntax check)
"""
import gradio as gr
import torch
import torch.nn.functional as F
import os
import json
import time
import ast
import re
import sys

try:
    import spaces
    HAS_ZERO_GPU = True
except Exception:
    HAS_ZERO_GPU = False

HF_TOKEN = os.environ.get("HF_TOKEN", "")
MODEL_ID = "Qwen/Qwen2.5-7B-Instruct"

model = None
tokenizer = None


def load_model():
    global model, tokenizer
    if model is not None:
        return "Already loaded"
    from transformers import AutoTokenizer, AutoModelForCausalLM
    print(f"Loading {MODEL_ID}...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_ID,
        torch_dtype=torch.float16,
        device_map="auto",
        trust_remote_code=True,
    )
    model.eval()
    params = sum(p.numel() for p in model.parameters())
    return f"Loaded {params/1e9:.1f}B params"


def _gpu_decorator(duration=120):
    if HAS_ZERO_GPU:
        return spaces.GPU(duration=duration)
    return lambda f: f


# ----------------------------------------------------------------------
# GENERATE: returns text + real per-token confidence (avg log-prob)
# ----------------------------------------------------------------------
def _generate_with_confidence(prompt: str, temperature: float, max_new_tokens: int = 400):
    messages = [{"role": "user", "content": prompt}]
    text = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    inputs = tokenizer(text, return_tensors="pt").to(model.device)

    with torch.no_grad():
        out = model.generate(
            **inputs,
            max_new_tokens=max_new_tokens,
            temperature=max(temperature, 0.01),
            do_sample=temperature > 0.01,
            repetition_penalty=1.1,
            pad_token_id=tokenizer.eos_token_id,
            output_scores=True,
            return_dict_in_generate=True,
        )

    seq = out.sequences[0]
    new_tokens = seq[len(inputs.input_ids[0]):]
    answer = tokenizer.decode(new_tokens, skip_special_tokens=True)

    # real confidence: average max-softmax-prob across generated tokens
    confidences = []
    for step_scores, tok_id in zip(out.scores, new_tokens):
        probs = F.softmax(step_scores[0], dim=-1)
        confidences.append(probs[tok_id].item())
    avg_conf = sum(confidences) / max(len(confidences), 1)

    return answer.strip(), avg_conf


# ----------------------------------------------------------------------
# VERIFY: real signals, not synthetic features
# ----------------------------------------------------------------------
def _check_code_syntax(answer: str) -> float:
    """لو فيه كود بايثون، يتأكد إنه valid syntactically. يرجع 1.0 لو سليم، 0.0 لو فيه syntax error، None لو مفيش كود"""
    blocks = re.findall(r"```python\n(.*?)```", answer, re.DOTALL)
    if not blocks:
        return None
    ok = 0
    for b in blocks:
        try:
            ast.parse(b)
            ok += 1
        except SyntaxError:
            pass
    return ok / len(blocks)


def _self_consistency_score(answer_a: str, answer_b: str) -> float:
    """تشابه بسيط بين إجابتين (Jaccard على الكلمات) كمؤشر اتساق"""
    wa = set(answer_a.lower().split())
    wb = set(answer_b.lower().split())
    if not wa or not wb:
        return 0.0
    return len(wa & wb) / len(wa | wb)


def verify(answer: str, confidence: float, second_answer: str | None) -> dict:
    code_score = _check_code_syntax(answer)
    consistency = _self_consistency_score(answer, second_answer) if second_answer else None

    parts = [confidence]
    if code_score is not None:
        parts.append(code_score)
    if consistency is not None:
        parts.append(consistency)

    final_score = sum(parts) / len(parts)
    return {
        "confidence": round(confidence, 3),
        "code_syntax_ok": code_score,
        "self_consistency": round(consistency, 3) if consistency is not None else None,
        "final_score": round(final_score, 3),
    }


# ----------------------------------------------------------------------
# GVR LOOP: Generate -> Verify -> Refine
# ----------------------------------------------------------------------
@_gpu_decorator(180)
def gvr_answer(question: str, max_iterations: int = 3):
    if model is None:
        load_model()

    trace = []
    best_answer, best_score = "", -1.0
    refine_prefix = ""

    for it in range(max_iterations):
        prompt = refine_prefix + question
        answer, conf = _generate_with_confidence(prompt, temperature=0.5 + it * 0.1)

        # second sample for self-consistency, only on first pass (cheap check)
        second = None
        if it == 0:
            second, _ = _generate_with_confidence(question, temperature=0.9, max_new_tokens=150)

        v = verify(answer, conf, second)
        trace.append({"iteration": it + 1, **v, "answer_preview": answer[:120]})

        if v["final_score"] > best_score:
            best_score = v["final_score"]
            best_answer = answer

        if v["final_score"] >= 0.55:
            break

        refine_prefix = (
            "Your previous attempt was uncertain or inconsistent. "
            "Reconsider carefully, double-check any code or math, and answer precisely.\n\n"
        )

    trace_str = "\n".join(
        f"[Iter {t['iteration']}] score={t['final_score']:.3f} "
        f"(conf={t['confidence']:.3f}, code_ok={t['code_syntax_ok']}, "
        f"consistency={t['self_consistency']})"
        for t in trace
    )

    return best_answer, best_score, trace_str


def chat_fn(question: str):
    if not question.strip():
        return "", "", ""
    answer, score, trace = gvr_answer(question)
    return answer, f"{score:.3f}", trace


# ----------------------------------------------------------------------
# UI
# ----------------------------------------------------------------------
with gr.Blocks(title="GVR-Ultimate Training Space", theme=gr.themes.Soft()) as demo:
    gr.Markdown("# GVR-Ultimate")
    gr.Markdown(
        "**Generate -> Verify -> Refine** (real loop) | Backbone: Qwen2.5-7B-Instruct"
    )

    with gr.Tab("Chat with GVR"):
        question = gr.Textbox(label="Question", placeholder="Ask anything...", lines=3)
        ask_btn = gr.Button("Ask GVR", variant="primary")
        answer = gr.Textbox(label="Final Answer", lines=8)
        score = gr.Textbox(label="Verifier Score")
        trace = gr.Textbox(label="GVR Iteration Trace", lines=6)
        ask_btn.click(chat_fn, inputs=question, outputs=[answer, score, trace])

    with gr.Tab("Info"):
        gr.Markdown(
            f"""
## How GVR works here

1. **Generate**: {MODEL_ID} produces an answer, and we capture the model's
   own token-level confidence (average max-softmax probability).
2. **Verify**: a second sample checks self-consistency (word overlap), and any
   Python code block is parsed with `ast.parse` to check it's syntactically valid.
   These signals are combined into a final score (0-1).
3. **Refine**: if the score is below 0.55, the question is re-asked with an
   explicit instruction to reconsider, and a higher temperature is used to
   escape a bad local generation. Up to 3 iterations.

This is a real, signal-based verifier — not a synthetic classifier.

## Links
- Model: https://huggingface.co/ahmedxg/gvr-ultimate
- Code: https://github.com/alhsryahmd266-jpg/omega-ai
            """
        )

if __name__ == "__main__":
    demo.launch(server_name="0.0.0.0", server_port=7860)
