"""
Omega Self-Improver
━━━━━━━━━━━━━━━━━━━
✦ يحلل أخطاءه ويتعلم منها
✦ يقيّم إجاباته ذاتياً
✦ يعدّل أسلوبه بناءً على النتائج
✦ يولد بيانات تدريب جديدة من تجربته
✦ يطور نفسه بلا توقف
"""

import os, sys, json, time, re, math
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from omega.memory.persistent import OmegaPersistentMemory


class OmegaSelfImprover:
    """
    وحدة التطوير الذاتي:
    - تتابع جودة الإجابات
    - تتعلم من الأخطاء
    - تولد بيانات تدريب جديدة
    - تقترح تحسينات على نفسها
    """

    def __init__(self, memory: OmegaPersistentMemory,
                 data_dir: str = "data/self_generated"):
        self.memory   = memory
        self.data_dir = data_dir
        os.makedirs(data_dir, exist_ok=True)
        self.session_evals = []

    # ── Self-Evaluation ───────────────────────────────────────────────────
    def evaluate_response(self, question: str, response: str,
                          context: str = "") -> dict:
        """تقييم جودة الإجابة ذاتياً"""
        score = 1.0
        issues = []

        # طول الإجابة
        words = len(response.split())
        if words < 5:
            score -= 0.4; issues.append("الإجابة قصيرة جداً")
        elif words > 500:
            score -= 0.1; issues.append("الإجابة طويلة جداً")

        # هل تحتوي على معلومات ذات صلة؟
        q_words = set(re.findall(r'\w{3,}', question.lower()))
        r_words = set(re.findall(r'\w{3,}', response.lower()))
        overlap = len(q_words & r_words) / max(len(q_words), 1)
        if overlap < 0.1:
            score -= 0.3; issues.append("الإجابة لا تتعلق بالسؤال")

        # هل يحتوي كود على syntax أساسي؟
        if 'def ' in question.lower() or 'code' in question.lower():
            if not any(kw in response for kw in ['def ', 'class ', 'return', '=', 'print']):
                score -= 0.2; issues.append("السؤال يطلب كوداً لكن الإجابة لا تحتوي عليه")

        # هل الإجابة باللغة المطلوبة؟
        if re.search(r'[\u0600-\u06FF]', question):  # سؤال عربي
            if not re.search(r'[\u0600-\u06FF]', response):
                score -= 0.2; issues.append("السؤال عربي لكن الإجابة إنجليزية")

        score = max(0.0, min(1.0, score))
        result = {
            'score': score,
            'issues': issues,
            'question_len': len(question),
            'response_len': len(response),
            'q_r_overlap': overlap,
            'timestamp': time.time(),
        }
        self.session_evals.append(result)
        return result

    # ── Learn from Mistake ────────────────────────────────────────────────
    def learn_from_mistake(self, question: str, bad_response: str,
                           good_response: str, reason: str = ""):
        """تعلّم من خطأ: حفظ السؤال الصحيح مع الإجابة الصح"""
        entry = {
            'user': question,
            'assistant': good_response,
            'system': f'تعلمت هذا لأن إجابتي السابقة كانت خاطئة: {reason}',
        }
        # Save to memory
        self.memory.remember(
            f"تعلمت: السؤال='{question[:80]}' → الإجابة الصحيحة='{good_response[:200]}'",
            mtype='self_improvement',
            tags=['mistake', 'corrected', 'self_learn'],
            importance=0.9,
            source='self'
        )
        # Save to training file
        self._append_training(entry)
        self.memory.log_self_event('learned_from_mistake', {
            'question': question[:100],
            'reason': reason,
        })
        print(f"  📝 Learned from mistake: {question[:50]}")

    # ── Generate Training Data ─────────────────────────────────────────────
    def generate_training_sample(self, topic: str,
                                 question: str, answer: str) -> dict:
        """توليد عينة تدريب وحفظها"""
        sample = {
            'user': question,
            'assistant': f"<think>موضوع: {topic}</think>\n{answer}",
            'topic': topic,
            'generated_at': time.time(),
            'source': 'self_generated',
        }
        self._append_training(sample)
        self.memory.remember(
            f"مثال تدريبي [{topic}]: {question[:60]}",
            mtype='self_improvement',
            tags=['training_data', topic],
            importance=0.7,
            source='self'
        )
        return sample

    def _append_training(self, sample: dict):
        path = os.path.join(self.data_dir, 'self_generated.jsonl')
        with open(path, 'a', encoding='utf-8') as f:
            f.write(json.dumps(sample, ensure_ascii=False) + '\n')

    # ── Synthesize New QA from Memory ─────────────────────────────────────
    def synthesize_qa_from_memory(self, n: int = 20) -> list:
        """توليد أسئلة وإجابات من الذكريات المحفوظة"""
        web_mems = self.memory.recall_by_type('web', limit=100)
        generated = []

        question_templates = [
            "ما هو {}؟",
            "اشرح لي {}",
            "كيف يعمل {}؟",
            "ما أهمية {}؟",
            "ما الفرق بين {} و{}؟",
        ]

        for mem in web_mems[:n]:
            # Extract key terms (nouns > 4 chars)
            words = re.findall(r'\b[A-Za-z\u0600-\u06FF]{4,}\b', mem.content)
            if not words:
                continue
            key = words[0]
            template = question_templates[len(generated) % len(question_templates)]

            if '{}و{}' in template and len(words) > 1:
                q = f"ما الفرق بين {key} و{words[1]}؟"
            else:
                q = template.format(key)

            sample = self.generate_training_sample(
                topic=mem.tags[0] if mem.tags else 'general',
                question=q,
                answer=mem.content
            )
            generated.append(sample)

        print(f"✨ Generated {len(generated)} new training samples from memory")
        self.memory.log_self_event('synthesize_qa', {'count': len(generated)})
        return generated

    # ── Self Reflection ───────────────────────────────────────────────────
    def reflect(self) -> str:
        """تأمل ذاتي: ماذا تعلمت؟ ما نقاط ضعفي؟"""
        stats = self.memory.stats()
        logs  = self.memory.get_self_log(20)
        evals = self.session_evals

        avg_score = sum(e['score'] for e in evals) / max(len(evals), 1) if evals else 0
        all_issues = []
        for e in evals:
            all_issues.extend(e.get('issues', []))

        issue_counts = {}
        for issue in all_issues:
            issue_counts[issue] = issue_counts.get(issue, 0) + 1

        top_issues = sorted(issue_counts.items(), key=lambda x: x[1], reverse=True)[:3]

        reflection = f"""
🧠 تأمل ذاتي - Omega AI
━━━━━━━━━━━━━━━━━━━━━━━
📊 الذاكرة:
  • إجمالي الذكريات: {stats['total']}
  • من الإنترنت: {stats['by_type'].get('web', 0)}
  • مهارات: {stats['by_type'].get('skill', 0)}
  • تحسينات ذاتية: {stats['by_type'].get('self_improvement', 0)}
  • مواقع تعلمت منها: {stats['learned_urls']}

📈 التقييم:
  • متوسط جودة الإجابات: {avg_score:.2%}
  • عدد التقييمات: {len(evals)}

⚠️ نقاط تحتاج تحسين:
{chr(10).join(f'  • {issue} ({count}x)' for issue, count in top_issues) if top_issues else '  • لا مشاكل مكتشفة'}

📅 آخر الأنشطة:
{chr(10).join(f'  • {l["type"]}: {json.dumps(l["data"], ensure_ascii=False)[:60]}' for l in logs[:5])}
"""
        self.memory.remember(
            f"تأمل ذاتي: متوسط الجودة={avg_score:.2%}, ذكريات={stats['total']}",
            mtype='self_improvement', tags=['reflection', 'self'],
            importance=0.6, source='self'
        )
        return reflection.strip()

    # ── Continuous Improvement Loop ────────────────────────────────────────
    def improvement_cycle(self, web_learner=None, n_synth: int = 30):
        """دورة تحسين كاملة"""
        print("\n🔄 Starting improvement cycle...")

        # 1. تعلم من الإنترنت
        if web_learner:
            print("  1️⃣  Web learning...")
            web_learner.learn_all()

        # 2. توليد بيانات تدريب من الذاكرة
        print(f"  2️⃣  Synthesizing {n_synth} QA pairs...")
        self.synthesize_qa_from_memory(n_synth)

        # 3. تأمل ذاتي
        print("  3️⃣  Self reflection...")
        reflection = self.reflect()
        print(reflection)

        # 4. حفظ الحالة
        self.memory.log_self_event('improvement_cycle', {
            'memory_stats': self.memory.stats(),
            'avg_eval_score': sum(e['score'] for e in self.session_evals) / max(len(self.session_evals), 1),
        })

        print("\n✅ Improvement cycle complete!")
        return reflection
