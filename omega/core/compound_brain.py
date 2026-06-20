"""
AION Compound Brain — الذكاء المركّب
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
الطبقة العليا التي تجمع كل القدرات في نظام واحد متكامل:

    👁  الرؤية (VisionBrain)       → "ايه ده؟"
    🧠  التفكير (ExternalBrain)    → "ليه؟ وإزاي نحله؟"
    🌳  شجرة التفكير (TreeOfThought) → استكشاف عدة مسارات حل
    💾  الذاكرة الدائمة (Memory)   → "متكونش بتنسى" — يتعلم مع كل استخدام

التسلسل:
    صورة + سؤال
        → VisionBrain يصف اللي في الصورة
        → يتحول لمسألة نصية واضحة
        → استرجاع خبرة سابقة مشابهة من الذاكرة
        → TreeOfThought يبحث في عدة مسارات حل بمساعدة النموذج النصي القوي
        → يخزّن أفضل حل في الذاكرة للمرة الجاية
        → إجابة نهائية + درجة الثقة
"""

import time
from typing import Optional

from omega.core.external_brain import ExternalBrain
from omega.core.vision_brain import VisionBrain
from omega.reasoning.tree_of_thought import TreeOfThought, ExternalBrainAdapter


class CompoundBrain:
    """
    الاستخدام:
        compound = CompoundBrain(
            text_brain=ExternalBrain(text_config),      # DeepSeek-14B
            vision_brain=VisionBrain(vision_config),    # Qwen3-VL-8B
            memory=OmegaPersistentMemory('memory/aion.db'),
        )
        result = compound.see_and_solve(
            "/path/to/android_studio_error.png",
            "في إيه الخطأ ده وإزاي أصلحه؟"
        )
        print(result['answer'])
    """

    def __init__(self,
                 text_brain: ExternalBrain,
                 vision_brain: Optional[VisionBrain] = None,
                 memory=None,
                 tot_breadth: int = 3,
                 tot_depth: int = 3):
        self.text_brain = text_brain
        self.vision_brain = vision_brain
        self.memory = memory

        self._tot = TreeOfThought(
            brain=ExternalBrainAdapter(text_brain),
            breadth=tot_breadth,
            keep_top=2,
            max_depth=tot_depth,
            memory=memory,
        )

    def see_and_solve(self, image_path: str, question: str) -> dict:
        """
        الوظيفة الرئيسية: صورة + سؤال → تحليل بصري + تفكير عميق + ذاكرة
        """
        t0 = time.time()

        # ── الخطوة 1: الرؤية ─────────────────────────────────
        if self.vision_brain is None:
            raise RuntimeError(
                "vision_brain غير مُعرَّف. مرّر VisionBrain للـ CompoundBrain أولاً."
            )
        visual_description = self.vision_brain.see(
            image_path,
            "صف بدقة كل التفاصيل المهمة في هذه الصورة: النص، "
            "الأخطاء، الألوان، التخطيط، أي رسائل أو رموز."
        )

        # ── الخطوة 2: تحويلها لمسألة واضحة للتفكير العميق ────
        problem = (
            f"السؤال الأصلي: {question}\n\n"
            f"وصف الصورة (من نظام الرؤية): {visual_description}"
        )

        # ── الخطوة 3+4: شجرة التفكير + الذاكرة (مدمجين في search) ─
        result = self._tot.solve(problem)

        result["visual_description"] = visual_description
        result["elapsed_sec"] = round(time.time() - t0, 1)
        return result

    def think_only(self, question: str) -> dict:
        """تفكير عميق بدون صورة — نفس قوة شجرة التفكير + الذاكرة"""
        return self._tot.solve(question)

    def quick_answer(self, question: str) -> str:
        """إجابة سريعة مباشرة بدون شجرة تفكير (للأسئلة البسيطة)"""
        return self.text_brain.chat(
            "أنت AION، مساعد ذكي متخصص في البرمجة وتطوير Android.",
            question,
        )
