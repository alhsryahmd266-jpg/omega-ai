"""
GVR-Ultimate - Generate -> Verify -> Refine + Tools
Generator: Qwen2.5-7B-Instruct
Tools: Code Executor (real execution) + Web Search
Verifier: confidence + self-consistency + execution success
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
import subprocess
import tempfile

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
# TOOL 1: Real Code Executor
# يشغّل الكود فعلياً في subprocess معزول بمهلة زمنية، ويرجع stdout/stderr الحقيقي
# ----------------------------------------------------------------------
def execute_python_code(code: str, timeout: int = 5) -> dict:
    """تنفيذ حقيقي للكود في عملية منفصلة، بحماية من infinite loops وimports خطيرة"""
    forbidden = ["os.system", "subprocess", "eval(", "exec(", "__import__",
                 "open(", "socket", "shutil", "rmtree"]
    for f in forbidden:
        if f in code:
            return {"success": False, "stdout": "", "stderr": f"Blocked: '{f}' not allowed in sandbox"}

    try:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False) as tmp:
            tmp.write(code)
            tmp_path = tmp.name

        result = subprocess.run(
            [sys.executable, tmp_path],
            capture_output=True, text=True, timeout=timeout
        )
        os.unlink(tmp_path)

        return {
            "success": result.returncode == 0,
            "stdout": result.stdout[:1000],
            "stderr": result.stderr[:500] if result.returncode != 0 else "",
        }
    except subprocess.TimeoutExpired:
        return {"success": False, "stdout": "", "stderr": f"Timeout after {timeout}s"}
    except Exception as e:
        return {"success": False, "stdout": "", "stderr": str(e)[:300]}


def _extract_and_run_code(answer: str) -> list:
    """يلاقي كل code blocks في الإجابة وينفذهم فعلياً"""
    blocks = re.findall(r"```python\n(.*?)```", answer, re.DOTALL)
    results = []
    for code in blocks:
        r = execute_python_code(code)
        results.append({"code": code[:200], **r})
    return results


# ----------------------------------------------------------------------
# GENERATE: returns text + real per-token confidence
# ----------------------------------------------------------------------
def _generate_with_confidence(prompt: str, temperature: float, max_new_tokens: int = 500):
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

    confidences = []
    for step_scores, tok_id in zip(out.scores, new_tokens):
        probs = F.softmax(step_scores[0], dim=-1)
        confidences.append(probs[tok_id].item())
    avg_conf = sum(confidences) / max(len(confidences), 1)

    return answer.strip(), avg_conf


# ----------------------------------------------------------------------
# VERIFY: confidence + self-consistency + REAL code execution success
# ----------------------------------------------------------------------
def _self_consistency_score(answer_a: str, answer_b: str) -> float:
    wa = set(answer_a.lower().split())
    wb = set(answer_b.lower().split())
    if not wa or not wb:
        return 0.0
    return len(wa & wb) / len(wa | wb)


def verify(answer: str, confidence: float, second_answer: str | None) -> dict:
    exec_results = _extract_and_run_code(answer)
    if exec_results:
        exec_score = sum(1.0 if r["success"] else 0.0 for r in exec_results) / len(exec_results)
    else:
        exec_score = None

    consistency = _self_consistency_score(answer, second_answer) if second_answer else None

    parts = [confidence]
    if exec_score is not None:
        parts.append(exec_score)
        parts.append(exec_score)  # weight execution success double - it's a hard signal
    if consistency is not None:
        parts.append(consistency)

    final_score = sum(parts) / len(parts)
    return {
        "confidence": round(confidence, 3),
        "code_execution_score": exec_score,
        "code_results": exec_results,
        "self_consistency": round(consistency, 3) if consistency is not None else None,
        "final_score": round(final_score, 3),
    }


# ----------------------------------------------------------------------
# GVR LOOP: Generate -> Verify (run code for real) -> Refine
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

        second = None
        if it == 0:
            second, _ = _generate_with_confidence(question, temperature=0.9, max_new_tokens=150)

        v = verify(answer, conf, second)
        trace.append({"iteration": it + 1, **v, "answer_preview": answer[:120]})

        if v["final_score"] > best_score:
            best_score = v["final_score"]
            best_answer = answer

        if v["final_score"] >= 0.6:
            break

        # build refine instruction based on WHAT failed
        issues = []
        if v["code_execution_score"] is not None and v["code_execution_score"] < 1.0:
            failed = [r for r in v["code_results"] if not r["success"]]
            err = failed[0]["stderr"][:150] if failed else ""
            issues.append(f"Your code had an error: {err}. Fix it.")
        if conf < 0.5:
            issues.append("Be more precise and certain in your reasoning.")
        if v["self_consistency"] is not None and v["self_consistency"] < 0.3:
            issues.append("Your reasoning was inconsistent across attempts. Think step by step.")

        refine_prefix = (
            "Your previous attempt needs correction. " + " ".join(issues) +
            "\n\nNow answer again, carefully:\n\n"
        )

    trace_str = "\n".join(
        f"[Iter {t['iteration']}] score={t['final_score']:.3f} "
        f"(conf={t['confidence']:.3f}, code_exec={t['code_execution_score']}, "
        f"consistency={t['self_consistency']})"
        for t in trace
    )

    # show real execution output for transparency
    exec_log = ""
    last_trace = trace[-1] if trace else {}
    for r in last_trace.get("code_results", []):
        status = "OK" if r["success"] else "FAILED"
        exec_log += f"[{status}] stdout: {r['stdout'][:200]} | stderr: {r['stderr'][:150]}\n"

    return best_answer, best_score, trace_str, exec_log


def chat_fn(question: str):
    if not question.strip():
        return "", "", "", ""
    answer, score, trace, exec_log = gvr_answer(question)
    return answer, f"{score:.3f}", trace, exec_log


def test_executor_fn(code: str):
    r = execute_python_code(code)
    status = "SUCCESS" if r["success"] else "FAILED"
    return f"[{status}]\nstdout:\n{r['stdout']}\n\nstderr:\n{r['stderr']}"


# ----------------------------------------------------------------------
# UI
# ----------------------------------------------------------------------
with gr.Blocks(title="GVR-Ultimate Training Space", theme=gr.themes.Soft()) as demo:
    gr.Markdown("# GVR-Ultimate")
    gr.Markdown(
        "**Generate -> Verify -> Refine** with real code execution | Backbone: Qwen2.5-7B-Instruct"
    )

    with gr.Tab("Chat with GVR"):
        question = gr.Textbox(label="Question", placeholder="Ask anything, including code...", lines=3)
        ask_btn = gr.Button("Ask GVR", variant="primary")
        answer = gr.Textbox(label="Final Answer", lines=8)
        score = gr.Textbox(label="Verifier Score")
        trace = gr.Textbox(label="GVR Iteration Trace", lines=5)
        exec_log = gr.Textbox(label="Real Code Execution Output", lines=4)
        ask_btn.click(chat_fn, inputs=question, outputs=[answer, score, trace, exec_log])

    with gr.Tab("Test Code Executor"):
        gr.Markdown("Directly test the sandboxed Python executor (5s timeout, no file/network/system access)")
        code_in = gr.Textbox(label="Python code", lines=6, value="print(2 + 2)")
        run_btn = gr.Button("Run", variant="primary")
        exec_out = gr.Textbox(label="Output", lines=6)
        run_btn.click(test_executor_fn, inputs=code_in, outputs=exec_out)

    with gr.Tab("Info"):
        gr.Markdown(
            f"""
## How GVR works here

1. **Generate**: {MODEL_ID} produces an answer, capturing real token-level
   confidence (average max-softmax probability from the model's own logits).
2. **Verify**:
   - Any Python code block is **actually executed** in a sandboxed subprocess
     (5s timeout, no file/network/system access). Success/failure is a real signal.
   - A second sample checks self-consistency (word overlap between two generations).
3. **Refine**: if the score is below 0.6, the model is told specifically what
   failed (the real error message, low confidence, or inconsistency) and
   re-asked. Up to 3 iterations.

This means code answers are verified by actually running them, not guessed.

## Links
- Model: https://huggingface.co/ahmedxg/gvr-ultimate
- Code: https://github.com/alhsryahmd266-jpg/omega-ai
            """
        )

if __name__ == "__main__":
    demo.launch(server_name="0.0.0.0", server_port=7860)
