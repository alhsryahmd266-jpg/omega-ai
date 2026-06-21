"""
AION Hierarchical Thinking — التفكير الهرمي
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
بدل ما يحل المسألة في خطوة واحدة، يفكر على 3 مستويات من العام
للتفصيل — تماماً زي ما إنسان خبير يفكر في مشروع معقد:

    المستوى 1: الاستراتيجية  →  "إيه أفضل أسلوب عام للحل؟"
    المستوى 2: الخطوات       →  "إزاي أقسّم الأسلوب ده لخطوات؟"
    المستوى 3: التنفيذ       →  "هات الحل التفصيلي/الكود لكل خطوة"

كل مستوى بيستخدم TreeOfThought كامل بداخله (يستكشف عدة أفكار،
يقيّمها، يختار الأفضل) — يعني الهرمية دي مبنية فوق شجرة التفكير
الموجودة، مش بديل لها.

ده "الذكاء المركّب" الكامل: رؤية + ذاكرة + شجرة تفكير + هرمية.
"""

import time
from dataclasses import dataclass, field
from typing import List, Optional

from omega.reasoning.tree_of_thought import TreeOfThought, Brain


@dataclass
class HierarchyLevel:
    name: str
    answer: str
    confidence: float
    full_reasoning: str = ""


class HierarchicalReasoner:
    """
    الاستخدام:
        reasoner = HierarchicalReasoner(brain=ExternalBrainAdapter(text_brain),
                                        memory=memory)
        result = reasoner.solve("صمّم نظام تسجيل دخول آمن لتطبيق Android")
        print(result['final_answer'])       # الحل التفصيلي النهائي
        print(result['levels'][0].answer)   # الاستراتيجية المختارة
    """

    LEVEL_NAMES = ["الاستراتيجية", "الخطوات", "التنفيذ"]

    def __init__(self, brain: Brain, memory=None,
                 tot_breadth: int = 2, tot_keep_top: int = 2,
                 tot_depth_per_level: int = 2):
        self.brain = brain
        self.memory = memory
        self.tot_breadth = tot_breadth
        self.tot_keep_top = tot_keep_top
        self.tot_depth_per_level = tot_depth_per_level
        self.stats = {"levels_completed": 0, "total_thoughts": 0, "elapsed_sec": 0.0}

    def _make_tot(self) -> TreeOfThought:
        """كل مستوى يحصل على شجرة تفكير جديدة (نظيفة) خاصة به"""
        return TreeOfThought(
            brain=self.brain,
            breadth=self.tot_breadth,
            keep_top=self.tot_keep_top,
            max_depth=self.tot_depth_per_level,
            memory=self.memory,
        )

    def solve(self, problem: str, levels: Optional[List[str]] = None) -> dict:
        """
        يحل المسألة عبر المستويات الهرمية بالترتيب.
        كل مستوى يبني على نتيجة المستوى اللي قبله.
        """
        t0 = time.time()
        level_names = levels or self.LEVEL_NAMES
        results: List[HierarchyLevel] = []
        context = ""

        for i, level_name in enumerate(level_names):
            level_prompt = self._build_level_prompt(problem, level_name, i, context)

            tot = self._make_tot()
            tot_result = tot.solve(level_prompt)

            level = HierarchyLevel(
                name=level_name,
                answer=tot_result["answer"],
                confidence=tot_result["confidence"],
                full_reasoning=tot_result["full_reasoning"],
            )
            results.append(level)
            self.stats["total_thoughts"] += tot_result["stats"]["thoughts_generated"]
            self.stats["levels_completed"] += 1

            # نتيجة هذا المستوى تدخل في سياق المستوى اللي بعده
            context += f"\n[{level_name}]: {level.answer}"

        # تخزين الحل الهرمي الكامل في الذاكرة — "متكونش بتنسى"
        if self.memory is not None:
            summary = " → ".join(f"{l.name}: {l.answer[:80]}" for l in results)
            self.memory.remember(
                f"مسألة: {problem[:120]}\nحل هرمي: {summary}",
                mtype="hierarchical_solution",
                importance=results[-1].confidence if results else 0.5,
                source="hierarchical_thinking",
            )

        self.stats["elapsed_sec"] = round(time.time() - t0, 1)

        return {
            "problem": problem,
            "levels": results,
            "final_answer": results[-1].answer if results else "",
            "overall_confidence": sum(l.confidence for l in results) / max(len(results), 1),
            "stats": dict(self.stats),
        }

    @staticmethod
    def _build_level_prompt(problem: str, level_name: str, depth: int, context: str) -> str:
        if depth == 0:
            return (
                f"المسألة: {problem}\n\n"
                f"المستوى المطلوب الآن: {level_name}.\n"
                f"اقترح الأسلوب العام للحل (من غير تفاصيل تنفيذية بعد)."
            )
        return (
            f"المسألة الأصلية: {problem}\n"
            f"السياق من المستويات السابقة:{context}\n\n"
            f"المستوى المطلوب الآن: {level_name}.\n"
            f"بناءً على السياق فوق، طوّر هذا المستوى بالتفصيل المناسب له."
        )
