"""
Omega Agent - وكيل ذاتي متكامل
يفكر → يخطط → ينفذ → يتعلم
"""

import os
import sys
import json
import subprocess
import re
from typing import List, Dict, Any, Optional
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(__file__))))

from omega.memory.memory import OmegaMemory


SYSTEM_PROMPT = """أنت Omega AI، ذكاء اصطناعي متقدم متخصص في:
- البرمجة (Python, Kotlin, Java, JavaScript, C++)
- تطوير تطبيقات Android
- صناعة الفيديو والوسائط
- حل المشكلات المعقدة

تفكر خطوة بخطوة، وتستخدم الأدوات المتاحة، وتتذكر ما تعلمته.

الأدوات المتاحة:
<tools>
  run_python: تشغيل كود Python
  run_bash: تشغيل أوامر bash
  write_file: كتابة ملف
  read_file: قراءة ملف
  search_memory: البحث في الذاكرة
  remember: حفظ معلومة
</tools>

للاستخدام: <tool>اسم_الأداة</tool><input>المدخل</input>
"""


class OmegaAgent:
    def __init__(self, model=None, tokenizer=None, memory_path: str = "memory"):
        self.model = model
        self.tokenizer = tokenizer
        self.memory = OmegaMemory(memory_path)
        self.conversation: List[Dict] = []
        self.tools = {
            'run_python': self._run_python,
            'run_bash':   self._run_bash,
            'write_file': self._write_file,
            'read_file':  self._read_file,
            'search_memory': self._search_memory,
            'remember':   self._remember_tool,
        }

    # ─── Tools ───────────────────────────────────────────────────────────────

    def _run_python(self, code: str) -> str:
        try:
            import io
            from contextlib import redirect_stdout, redirect_stderr
            stdout_capture = io.StringIO()
            stderr_capture = io.StringIO()
            local_ns = {}
            with redirect_stdout(stdout_capture), redirect_stderr(stderr_capture):
                exec(code, local_ns)
            out = stdout_capture.getvalue()
            err = stderr_capture.getvalue()
            result = out
            if err:
                result += f"\nSTDERR: {err}"
            return result or "تم التنفيذ بنجاح (لا مخرجات)"
        except Exception as e:
            return f"خطأ: {str(e)}"

    def _run_bash(self, cmd: str) -> str:
        try:
            result = subprocess.run(
                cmd, shell=True, capture_output=True,
                text=True, timeout=30
            )
            out = result.stdout + result.stderr
            return out[:2000] if out else "تم التنفيذ"
        except subprocess.TimeoutExpired:
            return "انتهت المهلة (30s)"
        except Exception as e:
            return f"خطأ: {str(e)}"

    def _write_file(self, args: str) -> str:
        try:
            parts = args.split('\n', 1)
            path, content = parts[0].strip(), parts[1] if len(parts) > 1 else ''
            os.makedirs(os.path.dirname(path) if os.path.dirname(path) else '.', exist_ok=True)
            with open(path, 'w', encoding='utf-8') as f:
                f.write(content)
            return f"كُتب الملف: {path} ({len(content)} حرف)"
        except Exception as e:
            return f"خطأ: {e}"

    def _read_file(self, path: str) -> str:
        try:
            with open(path.strip(), 'r', encoding='utf-8') as f:
                content = f.read()
            return content[:3000]
        except Exception as e:
            return f"خطأ: {e}"

    def _search_memory(self, query: str) -> str:
        memories = self.memory.recall(query, top_k=5)
        if not memories:
            return "لا توجد ذكريات ذات صلة"
        return "\n".join([f"[{m.memory_type}] {m.content}" for m in memories])

    def _remember_tool(self, content: str) -> str:
        mem_id = self.memory.remember(content, importance=0.7)
        return f"تم الحفظ (ID: {mem_id})"

    # ─── Tool Parser ─────────────────────────────────────────────────────────

    def _parse_and_run_tools(self, text: str) -> str:
        pattern = r'<tool>(.*?)</tool>\s*<input>(.*?)</input>'
        results = []

        for match in re.finditer(pattern, text, re.DOTALL):
            tool_name = match.group(1).strip()
            tool_input = match.group(2).strip()

            if tool_name in self.tools:
                print(f"  → Running tool: {tool_name}")
                result = self.tools[tool_name](tool_input)
                results.append(f"<tool_result tool='{tool_name}'>\n{result}\n</tool_result>")
            else:
                results.append(f"<tool_result>أداة غير معروفة: {tool_name}</tool_result>")

        return '\n'.join(results)

    # ─── Inference ───────────────────────────────────────────────────────────

    def _generate_response(self, prompt: str) -> str:
        """توليد رد - إما من النموذج أو fallback"""
        if self.model and self.tokenizer:
            import torch
            ctx = f"{SYSTEM_PROMPT}\n\nUser: {prompt}\nAssistant:"
            ids = self.tokenizer.encode(ctx)
            x = torch.tensor([ids])
            out_ids = self.model.generate(x, max_new=500, temperature=0.8)
            response = self.tokenizer.decode(out_ids[0].tolist())
            # Extract assistant part
            if 'Assistant:' in response:
                response = response.split('Assistant:')[-1].strip()
            return response
        else:
            return f"[Omega AI جاهز — النموذج لم يُحمَّل بعد. المدخل: '{prompt}']"

    def chat(self, user_input: str, use_tools: bool = True) -> str:
        # Add to memory
        self.memory.stm.append({'type': 'user', 'content': user_input})

        # Get relevant memories
        relevant = self.memory.recall(user_input, top_k=3)
        memory_ctx = ""
        if relevant:
            memory_ctx = "\nذكريات ذات صلة:\n" + \
                         "\n".join([f"- {m.content}" for m in relevant])

        full_prompt = f"{memory_ctx}\n{user_input}" if memory_ctx else user_input

        # Generate response
        response = self._generate_response(full_prompt)

        # Execute tools if present
        if use_tools and '<tool>' in response:
            tool_results = self._parse_and_run_tools(response)
            response = response + "\n" + tool_results

            # Learn from tool results
            self.memory.remember(
                f"Tool use: {user_input[:100]} → {tool_results[:200]}",
                memory_type='skill', importance=0.6
            )

        # Save to memory
        self.memory.stm.append({'type': 'assistant', 'content': response})

        return response

    def run_autonomous_task(self, goal: str, max_steps: int = 10) -> str:
        """تشغيل مهمة تلقائية متعددة الخطوات"""
        print(f"\n🎯 Goal: {goal}")
        print("="*50)

        results = []
        current_goal = goal

        for step in range(max_steps):
            print(f"\nStep {step+1}/{max_steps}")
            response = self.chat(current_goal)
            results.append(response)
            print(f"Response: {response[:200]}...")

            # Check if done
            if any(word in response.lower() for word in
                   ['تم', 'انتهى', 'اكتمل', 'done', 'complete', 'finished']):
                print("\n✅ Task completed!")
                break

        self.memory.save()
        return "\n\n".join(results)

    def stats(self) -> Dict:
        return {
            'memory': self.memory.stats(),
            'conversation_turns': len(self.conversation),
            'model_loaded': self.model is not None,
        }
