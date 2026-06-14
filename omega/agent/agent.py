"""
Omega Agent v2 - وكيل ذاتي متكامل
✦ ذاكرة دائمة
✦ أدوات متعددة
✦ تفكير متعدد الخطوات
"""
import os, sys, json, subprocess, re
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from omega.memory.persistent import OmegaPersistentMemory

SYSTEM = """أنت Omega AI، ذكاء اصطناعي متقدم متخصص في:
- البرمجة (Python, Kotlin, Java, JavaScript)
- تطوير Android
- الذكاء الاصطناعي والتعلم الآلي
- صناعة الفيديو والوسائط
تفكر خطوة بخطوة وتتذكر كل ما تعلمته."""


class OmegaAgent:
    def __init__(self, model=None, tokenizer=None,
                 memory: OmegaPersistentMemory = None,
                 memory_path: str = "memory/omega.db"):
        self.model     = model
        self.tokenizer = tokenizer
        self.memory    = memory or OmegaPersistentMemory(memory_path)
        self.tools = {
            'run_python': self._run_python,
            'run_bash':   self._run_bash,
            'write_file': self._write_file,
            'read_file':  self._read_file,
            'remember':   self._remember,
            'recall':     self._recall,
        }

    def _run_python(self, code: str) -> str:
        try:
            import io
            from contextlib import redirect_stdout, redirect_stderr
            buf = io.StringIO()
            with redirect_stdout(buf), redirect_stderr(buf):
                exec(code, {})
            return buf.getvalue() or "OK"
        except Exception as e:
            return f"Error: {e}"

    def _run_bash(self, cmd: str) -> str:
        try:
            r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=30)
            return (r.stdout + r.stderr)[:2000] or "OK"
        except Exception as e:
            return f"Error: {e}"

    def _write_file(self, args: str) -> str:
        try:
            path, *rest = args.split('\n', 1)
            content = rest[0] if rest else ''
            os.makedirs(os.path.dirname(path.strip()) or '.', exist_ok=True)
            with open(path.strip(), 'w', encoding='utf-8') as f:
                f.write(content)
            return f"Written: {path.strip()}"
        except Exception as e:
            return f"Error: {e}"

    def _read_file(self, path: str) -> str:
        try:
            with open(path.strip(), 'r', encoding='utf-8') as f:
                return f.read()[:3000]
        except Exception as e:
            return f"Error: {e}"

    def _remember(self, content: str) -> str:
        mid = self.memory.remember(content, importance=0.7, source='agent')
        return f"Remembered (id={mid})"

    def _recall(self, query: str) -> str:
        mems = self.memory.recall(query, top_k=5)
        if not mems:
            return "No relevant memories"
        return '\n'.join(f"[{m.mtype}] {m.content[:150]}" for m in mems)

    def _parse_tools(self, text: str) -> str:
        results = []
        for m in re.finditer(r'<tool>(.*?)</tool>\s*<input>(.*?)</input>',
                             text, re.DOTALL):
            name, inp = m.group(1).strip(), m.group(2).strip()
            if name in self.tools:
                out = self.tools[name](inp)
                results.append(f"<result>{out}</result>")
        return '\n'.join(results)

    def _generate(self, prompt: str) -> str:
        if self.model and self.tokenizer:
            import torch
            ids = self.tokenizer.encode(f"{SYSTEM}\nUser: {prompt}\nAssistant:")
            x   = torch.tensor([ids[-512:]])
            out = self.model.generate(x, max_new=300, temperature=0.7)
            resp = self.tokenizer.decode(out[0].tolist())
            if 'Assistant:' in resp:
                resp = resp.split('Assistant:')[-1].strip()
            return resp
        return f"[Omega ready | input='{prompt[:60]}']"

    def chat(self, user_input: str) -> str:
        # Recall relevant memories
        mems = self.memory.recall(user_input, top_k=4)
        ctx  = ""
        if mems:
            ctx = "ذكريات ذات صلة:\n" + \
                  '\n'.join(f"- {m.content[:120]}" for m in mems) + "\n\n"

        response = self._generate(ctx + user_input)

        if '<tool>' in response:
            tool_out = self._parse_tools(response)
            response += '\n' + tool_out

        # Save to memory
        self.memory.remember(
            f"محادثة: {user_input[:100]} → {response[:200]}",
            mtype='conversation', importance=0.4, source='user'
        )
        return response

    def stats(self) -> dict:
        return {
            'memory': self.memory.stats(),
            'model_loaded': self.model is not None,
        }
