"""
AION External Brain Adapter
━━━━━━━━━━━━━━━━━━━━━━━━━━━
يوصّل نظام AION (ذاكرة + أدوات + تعلّم ذاتي) بأي نموذج GGUF خارجي
جاهز (مثل DeepSeek-R1-Distill-Qwen-14B) بدل النموذج المُدرَّب من الصفر.

الفكرة: AION ليس النموذج نفسه — هو طبقة الميزات حوله.
هذا الـ adapter يسمح باستخدام أي "عقل" (brain) خارجي مع نفس الميزات:
  - الذاكرة الدائمة (SQLite)
  - التعلّم من الإنترنت
  - التحسين الذاتي
  - استخدام الأدوات (bash/python/files)

يعمل عن طريق llama-cpp-python — يجب أن يكون مُثبَّتاً ومثبتاً عليه ملف
الـ GGUF محلياً على الجهاز الذي يشغّل هذا الكود (مثل Termux بـ 24GB RAM).
"""

import os
import re
from typing import Optional, List, Dict, Any


class ExternalBrainConfig:
    """إعدادات الاتصال بالنموذج الخارجي"""
    def __init__(self,
                 model_path: str,
                 n_ctx: int = 4096,
                 n_threads: int = 8,
                 n_gpu_layers: int = 0,   # 0 = CPU فقط، زوّدها لو فيه GPU
                 temperature: float = 0.7,
                 top_p: float = 0.9,
                 max_tokens: int = 1024,
                 chat_format: str = "qwen"):
        self.model_path   = model_path
        self.n_ctx        = n_ctx
        self.n_threads    = n_threads
        self.n_gpu_layers = n_gpu_layers
        self.temperature  = temperature
        self.top_p        = top_p
        self.max_tokens   = max_tokens
        self.chat_format  = chat_format


class ExternalBrain:
    """
    Wrapper حول llama-cpp-python يعطي نفس الواجهة (interface)
    اللي AIONAgent يتوقعها من النموذج الداخلي.

    الاستخدام:
        brain = ExternalBrain(ExternalBrainConfig(
            model_path="/path/to/deepseek-r1-distill-qwen-14b.Q4_K_M.gguf"
        ))
        agent = OmegaAgent(model=None, tokenizer=None, memory=mem)
        agent.external_brain = brain   # AION يستخدمه بدل النموذج الداخلي
    """

    def __init__(self, config: ExternalBrainConfig):
        self.config = config
        self._llm = None
        self._loaded = False

    def load(self):
        """تحميل النموذج فعلياً (يستهلك ذاكرة، نادي مرة واحدة)"""
        try:
            from llama_cpp import Llama
        except ImportError:
            raise RuntimeError(
                "llama-cpp-python غير مثبَّت. ثبّته بـ:\n"
                "  pip install llama-cpp-python\n"
                "أو لو عايز تسريع على ARM/Termux:\n"
                "  CMAKE_ARGS='-DLLAMA_BLAS=ON' pip install llama-cpp-python"
            )

        if not os.path.exists(self.config.model_path):
            raise FileNotFoundError(
                f"ملف الـ GGUF غير موجود: {self.config.model_path}\n"
                f"نزّله أولاً من المصدر الذي اخترته (Hugging Face وغيره)."
            )

        print(f"🧠 تحميل النموذج الخارجي: {self.config.model_path}")
        print(f"   n_ctx={self.config.n_ctx} | threads={self.config.n_threads} | "
              f"gpu_layers={self.config.n_gpu_layers}")

        self._llm = Llama(
            model_path=self.config.model_path,
            n_ctx=self.config.n_ctx,
            n_threads=self.config.n_threads,
            n_gpu_layers=self.config.n_gpu_layers,
            verbose=False,
        )
        self._loaded = True
        print("✅ النموذج جاهز")

    def is_loaded(self) -> bool:
        return self._loaded

    def chat(self, system_prompt: str, user_message: str,
              history: Optional[List[Dict[str, str]]] = None) -> str:
        """
        محادثة كاملة بصيغة chat (الأفضل مع النماذج المُدرَّبة على instruct/chat)
        """
        if not self._loaded:
            self.load()

        messages = [{"role": "system", "content": system_prompt}]
        if history:
            messages.extend(history)
        messages.append({"role": "user", "content": user_message})

        result = self._llm.create_chat_completion(
            messages=messages,
            temperature=self.config.temperature,
            top_p=self.config.top_p,
            max_tokens=self.config.max_tokens,
        )
        return result["choices"][0]["message"]["content"]

    def complete(self, prompt: str) -> str:
        """توليد نصي مباشر (completion) بدون قالب chat"""
        if not self._loaded:
            self.load()

        result = self._llm(
            prompt,
            temperature=self.config.temperature,
            top_p=self.config.top_p,
            max_tokens=self.config.max_tokens,
            stop=["</s>", "<|im_end|>"],
        )
        return result["choices"][0]["text"]

    def unload(self):
        """تفريغ الذاكرة - مفيد لو عايز تبدّل بين نماذج"""
        self._llm = None
        self._loaded = False


# ════════════════════════════════════════════════════════════
# دمج مباشر مع OmegaAgent — استبدال النموذج الداخلي بالخارجي
# ════════════════════════════════════════════════════════════

def attach_external_brain(agent, brain: ExternalBrain):
    """
    يربط العقل الخارجي بوكيل AION الموجود (agent.py) من غير تعديل
    باقي النظام (الذاكرة + الأدوات + التعلّم الذاتي تفضل شغّالة زي ما هي).

    بعد الاستدعاء، agent.chat() هيستخدم النموذج الخارجي تلقائياً.
    """
    agent._external_brain = brain

    original_generate = agent._generate

    def patched_generate(prompt: str) -> str:
        if hasattr(agent, '_external_brain') and agent._external_brain:
            system = (
                "أنت Omega AI (AION)، ذكاء اصطناعي متقدم متخصص في "
                "البرمجة وتطوير Android والذكاء الاصطناعي. "
                "تتذكر كل المحادثات السابقة وتتعلم من تجربتك."
            )
            return agent._external_brain.chat(system, prompt)
        return original_generate(prompt)

    agent._generate_response = patched_generate
    print("✅ العقل الخارجي مربوط بنظام AION — الذاكرة والأدوات تعمل كما هي")
    return agent
