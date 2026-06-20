"""
AION Tree-of-Thought Reasoning Engine
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
بدل ما النموذج يفكر في مسار واحد، بيستكشف عدة "أفكار" في كل خطوة،
يقيّم كل واحدة، يكمل في الأفضل منها، ويرجع للخلف (backtrack) لو
المسار طلع غلط. ده مش تدريب جديد — ده طريقة استدلال (inference)
أذكى تتحط فوق أي نموذج، صغير أو كبير.

الفكرة الأساسية (Yao et al. — Tree of Thoughts):
    المشكلة
       ├── فكرة 1 (تقييم: 0.8) ──┬── فكرة 1.1 (تقييم: 0.9) ✓ يُكمل هنا
       │                          └── فكرة 1.2 (تقييم: 0.4) ✗ يُهجر
       ├── فكرة 2 (تقييم: 0.3) ✗ يُهجر بدري (مش هيتفرّع)
       └── فكرة 3 (تقييم: 0.6) ──── فكرة 3.1 (تقييم: 0.5) ...

يعمل مع أي "عقل" (brain) — النموذج الداخلي لـ AION، أو نموذج خارجي
جاهز زي DeepSeek-R1-Distill-Qwen-14B عن طريق omega/core/external_brain.py.
"""

import re
import time
from dataclasses import dataclass, field
from typing import Callable, List, Optional, Protocol


# ════════════════════════════════════════════════════════════
# واجهة العقل (Brain Interface) — أي نموذج يطبّقها يشتغل مع ToT
# ════════════════════════════════════════════════════════════

class Brain(Protocol):
    """أي نموذج (داخلي أو خارجي) لازم يوفّر دالة توليد نص بسيطة"""
    def complete(self, prompt: str) -> str: ...


class InternalBrainAdapter:
    """يلف نموذج AION الداخلي (PyTorch) بنفس واجهة الـ Brain"""
    def __init__(self, model, tokenizer, device='cpu', max_new=256):
        self.model, self.tok, self.device, self.max_new = model, tokenizer, device, max_new

    def complete(self, prompt: str) -> str:
        import torch
        ids = self.tok.encode(prompt, add_special=True)
        x = torch.tensor([ids], device=self.device)
        out = self.model.generate(x, max_new=self.max_new, enable_metacog=True)
        return self.tok.decode(out[0].tolist()[len(ids):])


class ExternalBrainAdapter:
    """يلف ExternalBrain (GGUF خارجي مثل DeepSeek-14B) بنفس الواجهة"""
    def __init__(self, external_brain, system_prompt: str = ""):
        self.brain = external_brain
        self.system_prompt = system_prompt or (
            "أنت محرك تفكير منطقي صارم. أعط أفكاراً مختصرة ومحددة."
        )

    def complete(self, prompt: str) -> str:
        return self.brain.chat(self.system_prompt, prompt)


# ════════════════════════════════════════════════════════════
# عقدة الفكرة الواحدة في الشجرة
# ════════════════════════════════════════════════════════════

@dataclass
class ThoughtNode:
    content: str
    depth: int = 0
    score: float = 0.0
    parent: Optional["ThoughtNode"] = None
    children: List["ThoughtNode"] = field(default_factory=list)

    def path(self) -> List[str]:
        """المسار الكامل من الجذر لهذه العقدة"""
        node, trail = self, []
        while node is not None:
            trail.append(node.content)
            node = node.parent
        return list(reversed(trail))

    def path_text(self) -> str:
        return "\n".join(f"خطوة {i+1}: {s}" for i, s in enumerate(self.path()) if s)


# ════════════════════════════════════════════════════════════
# محرك شجرة التفكير
# ════════════════════════════════════════════════════════════

class TreeOfThought:
    """
    بحث Beam-Search في شجرة الأفكار:
      - في كل عمق (depth)، يولّد `breadth` أفكار محتملة لكل عقدة نشطة
      - يقيّم كل فكرة (0..1) عن طريق سؤال العقل نفسه يحكم على جودتها
      - يحتفظ بأفضل `keep_top` فروع فقط ويهجر الباقي (تقليم/pruning)
      - يستمر لحد `max_depth` أو لحد ما فكرة توصل لثقة عالية (early-stop)
    """

    def __init__(self, brain: Brain, breadth: int = 3, keep_top: int = 2,
                 max_depth: int = 3, confidence_threshold: float = 0.85,
                 memory=None):
        self.brain = brain
        self.breadth = breadth
        self.keep_top = keep_top
        self.max_depth = max_depth
        self.confidence_threshold = confidence_threshold
        self.memory = memory   # OmegaPersistentMemory اختياري — "الذكاء المركّب"
        self.stats = {"thoughts_generated": 0, "thoughts_evaluated": 0, "backtracks": 0}

    # ── توليد أفكار متعددة لخطوة واحدة ─────────────────────
    def _generate_thoughts(self, problem: str, node: ThoughtNode) -> List[str]:
        history = node.path_text() if node.depth > 0 else ""
        prompt = (
            f"المسألة: {problem}\n\n"
            f"{'الخطوات السابقة:\n' + history if history else ''}\n\n"
            f"اقترح {self.breadth} أفكار مختلفة ومحددة للخطوة التالية فقط "
            f"(كل فكرة جملة أو جملتين بحد أقصى). رقّمها 1، 2، 3..."
        )
        raw = self.brain.complete(prompt)
        self.stats["thoughts_generated"] += self.breadth

        # استخرج الأفكار المرقّمة من الرد
        thoughts = re.findall(r'(?:^|\n)\s*\d+[.\-:)]\s*(.+)', raw)
        if not thoughts:
            # fallback: قسّم على الأسطر لو الترقيم مش واضح
            thoughts = [l.strip() for l in raw.split('\n') if l.strip()][:self.breadth]
        return thoughts[:self.breadth] or [raw.strip()[:200]]

    # ── تقييم فكرة واحدة (هل تستحق المتابعة؟) ──────────────
    def _evaluate_thought(self, problem: str, node: ThoughtNode) -> float:
        prompt = (
            f"المسألة: {problem}\n\n"
            f"مسار التفكير المقترح:\n{node.path_text()}\n\n"
            f"قيّم جودة هذا المسار من 0 إلى 10 (رقم واحد فقط، "
            f"بناءً على: منطقي؟ يقرّب من الحل؟ بدون أخطاء؟)."
        )
        raw = self.brain.complete(prompt)
        self.stats["thoughts_evaluated"] += 1
        match = re.search(r'\d+(\.\d+)?', raw)
        score = float(match.group()) if match else 5.0
        return min(score / 10.0, 1.0)

    # ── البحث الكامل ────────────────────────────────────────
    def search(self, problem: str) -> ThoughtNode:
        t0 = time.time()

        # "ذكاء مركّب": استرجاع خبرة سابقة قبل البدء (متكونش بتنسي)
        recalled = ""
        if self.memory is not None:
            mems = self.memory.recall(problem, top_k=3)
            if mems:
                recalled = "\n".join(f"- {m.content}" for m in mems)
                problem = f"{problem}\n\n[خبرة سابقة ذات صلة]:\n{recalled}"

        root = ThoughtNode(content="", depth=0, score=1.0)
        frontier = [root]
        best_leaf = root

        for depth in range(1, self.max_depth + 1):
            candidates: List[ThoughtNode] = []
            for node in frontier:
                thoughts = self._generate_thoughts(problem, node)
                for t in thoughts:
                    child = ThoughtNode(content=t, depth=depth, parent=node)
                    child.score = self._evaluate_thought(problem, child)
                    node.children.append(child)
                    candidates.append(child)

            if not candidates:
                break

            # تقليم: احتفظ بأفضل keep_top فروع فقط
            candidates.sort(key=lambda n: n.score, reverse=True)
            pruned = len(candidates) - min(self.keep_top, len(candidates))
            self.stats["backtracks"] += max(pruned, 0)
            frontier = candidates[:self.keep_top]
            best_leaf = frontier[0]

            # توقف مبكر لو وصلنا لثقة عالية
            if best_leaf.score >= self.confidence_threshold:
                break

        elapsed = time.time() - t0
        self.stats["elapsed_sec"] = round(elapsed, 1)
        self.stats["final_score"] = round(best_leaf.score, 3)
        self.stats["depth_reached"] = best_leaf.depth

        # "متكونش بتنسي": خزّن أفضل مسار في الذاكرة الدائمة
        if self.memory is not None and best_leaf.score >= 0.6:
            self.memory.remember(
                f"مسألة: {problem[:150]}\nأفضل حل: {best_leaf.path_text()[:400]}",
                mtype="reasoning_path",
                importance=best_leaf.score,
                source="tree_of_thought",
            )

        return best_leaf

    def solve(self, problem: str) -> dict:
        """واجهة مبسّطة: تحل المسألة وترجع الإجابة + معلومات الثقة"""
        leaf = self.search(problem)
        return {
            "answer": leaf.content,
            "full_reasoning": leaf.path_text(),
            "confidence": leaf.score,
            "stats": dict(self.stats),
        }
