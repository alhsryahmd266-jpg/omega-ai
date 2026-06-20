"""
AION Vision Brain — أدوات التعرف على الصور
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
يلف نموذج رؤية (Vision-Language Model) خارجي جاهز ليعطي AION
القدرة على "الرؤية": فهم صور، قراءة screenshots، تحليل أخطاء بصرية.

المُوصى به (الأقوى في فئته حالياً): Qwen3-VL-8B-Instruct-GGUF
  - يحتاج ملفين: النموذج نفسه + ملف mmproj (المُحوّل البصري)
  - يعمل مع llama-cpp-python (قد يحتاج نسخة معدّلة/fork لدعم
    أحدث الـ handlers — راجع التعليق في load() أدناه)
  - حجم تقريبي: 5-6GB عند Q4، بالإضافة لـ ~9GB للنموذج النصي
    (DeepSeek-R1-Distill-Qwen-14B) = ~15GB إجمالي، يفضل البقاء
    ضمن 24GB RAM المتاح على جهازك.

بديل أخف لو الذاكرة ضيقة: Qwen2.5-VL-3B-Instruct-GGUF أو
SmolVLM2-2.2B-Instruct-GGUF (كلاهما مدعوم رسمياً في llama.cpp).

هذا الموديول لا يُحمّل أو يُشغّل الموديل هنا — فقط يوفّر الواجهة
الجاهزة. التحميل الفعلي يحصل على جهازك (Termux/Kaggle) حيث ملفات
الـ GGUF موجودة بالفعل.
"""

import base64
import importlib
import os
from typing import Optional


class VisionBrainConfig:
    def __init__(self,
                 model_path: str,
                 clip_model_path: str,
                 chat_handler_name: str = "Qwen25VLChatHandler",
                 n_ctx: int = 4096,
                 n_threads: int = 8,
                 n_gpu_layers: int = 0,
                 temperature: float = 0.3,
                 max_tokens: int = 512):
        """
        chat_handler_name: اسم الـ handler المناسب لنموذجك. الخيارات الشائعة:
            "Qwen25VLChatHandler"  → Qwen2.5-VL (مدعوم في الإصدار الرسمي)
            "Qwen3VLChatHandler"   → Qwen3-VL (قد يحتاج fork مثل JamePeng's)
            "Llava16ChatHandler"   → LLaVA 1.6
            "MoondreamChatHandler" → Moondream (الأخف والأسرع)
        """
        self.model_path        = model_path
        self.clip_model_path   = clip_model_path
        self.chat_handler_name = chat_handler_name
        self.n_ctx             = n_ctx
        self.n_threads         = n_threads
        self.n_gpu_layers      = n_gpu_layers
        self.temperature       = temperature
        self.max_tokens        = max_tokens


class VisionBrain:
    """
    الاستخدام:
        brain = VisionBrain(VisionBrainConfig(
            model_path="/path/to/Qwen3-VL-8B-Instruct-Q4_K_M.gguf",
            clip_model_path="/path/to/mmproj-Qwen3-VL-8B-F16.gguf",
        ))
        answer = brain.see("/path/to/screenshot.png",
                           "في إيه الخطأ في الصورة دي؟")
    """

    def __init__(self, config: VisionBrainConfig):
        self.config = config
        self._llm = None

    def load(self):
        try:
            from llama_cpp import Llama
            chat_format_mod = importlib.import_module("llama_cpp.llama_chat_format")
            HandlerClass = getattr(chat_format_mod, self.config.chat_handler_name, None)
        except ImportError:
            raise RuntimeError(
                "llama-cpp-python غير مثبَّت. ثبّته بـ:\n"
                "  pip install llama-cpp-python"
            )

        if HandlerClass is None:
            raise RuntimeError(
                f"الـ handler '{self.config.chat_handler_name}' غير موجود في "
                f"نسختك من llama-cpp-python.\n"
                f"النسخة الرسمية من PyPI أحياناً متدعمش أحدث الـ handlers "
                f"(زي Qwen3VLChatHandler). جرّب:\n"
                f"  1. استخدم 'Qwen25VLChatHandler' بدلاً منه (مدعوم رسمياً)\n"
                f"  2. أو ثبّت نسخة معدّلة بتدعم الأحدث، مثل:\n"
                f"     pip install git+https://github.com/TAO71-AI/llama-cpp-python-JamePeng"
            )

        if not os.path.exists(self.config.model_path):
            raise FileNotFoundError(f"ملف النموذج غير موجود: {self.config.model_path}")
        if not os.path.exists(self.config.clip_model_path):
            raise FileNotFoundError(f"ملف mmproj غير موجود: {self.config.clip_model_path}")

        handler = HandlerClass(clip_model_path=self.config.clip_model_path, verbose=False)
        self._llm = Llama(
            model_path=self.config.model_path,
            chat_handler=handler,
            n_ctx=self.config.n_ctx,
            n_threads=self.config.n_threads,
            n_gpu_layers=self.config.n_gpu_layers,
            verbose=False,
        )
        print(f"✅ Vision brain جاهز ({self.config.chat_handler_name})")

    @staticmethod
    def _image_to_data_uri(image_path: str) -> str:
        ext = os.path.splitext(image_path)[1].lower().lstrip('.')
        mime = {'jpg': 'jpeg', 'jpeg': 'jpeg', 'png': 'png', 'webp': 'webp'}.get(ext, 'jpeg')
        with open(image_path, 'rb') as f:
            b64 = base64.b64encode(f.read()).decode('utf-8')
        return f"data:image/{mime};base64,{b64}"

    def see(self, image_path: str, question: str = "صف هذه الصورة بالتفصيل") -> str:
        """يحلّل صورة ويرجع وصف/إجابة نصية"""
        if self._llm is None:
            self.load()

        data_uri = self._image_to_data_uri(image_path)
        messages = [{
            "role": "user",
            "content": [
                {"type": "image_url", "image_url": {"url": data_uri}},
                {"type": "text", "text": question},
            ],
        }]
        result = self._llm.create_chat_completion(
            messages=messages,
            temperature=self.config.temperature,
            max_tokens=self.config.max_tokens,
        )
        return result["choices"][0]["message"]["content"]

    def unload(self):
        self._llm = None
