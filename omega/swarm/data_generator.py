"""
AION-SWARM Data Generator
━━━━━━━━━━━━━━━━━━━━━━━━
النموذج يولّد بياناته التدريبية بنفسه
ثم يقيّمها ويحتفظ بالجيدة فقط
"""
import os, sys, json, re, time, random
import torch
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from omega.model.architecture import get_config, AIONModel, AIONConfig
from omega.tokenizer.bpe import OmegaTokenizer
from omega.memory.persistent import OmegaPersistentMemory


# قوالب توليد البيانات
GENERATION_PROMPTS = [
    # برمجة
    "اكتب خوارزمية {topic} بـ Python مع شرح كامل",
    "حل مسألة {topic} بـ Kotlin لـ Android",
    "نفّذ {topic} من الصفر بدون مكتبات خارجية",
    "اشرح {topic} مع مثال عملي كامل",
    "ما الفرق بين {topic} و {topic2}؟ مع أمثلة كود",
    # ذكاء اصطناعي
    "كيف يعمل {topic} في الشبكات العصبية؟",
    "نفّذ {topic} بـ PyTorch من الصفر",
    "ما أفضل طريقة لـ {topic} في نماذج اللغة؟",
    # Android
    "كيف أبني {topic} في Jetpack Compose؟",
    "نفّذ {topic} بـ Clean Architecture في Android",
]

TOPICS = [
    "Quick Sort", "Binary Search", "Dynamic Programming",
    "Graph BFS/DFS", "Dijkstra", "LRU Cache", "Trie",
    "Transformer", "Attention Mechanism", "LoRA Fine-tuning",
    "Backpropagation", "Gradient Descent", "Batch Normalization",
    "Room Database", "ViewModel", "Coroutines", "Retrofit",
    "RecyclerView", "Navigation Component", "Hilt DI",
    "HashMap", "LinkedList", "Binary Tree", "Heap",
    "REST API", "WebSocket", "GraphQL", "gRPC",
    "Docker", "Git branching", "CI/CD Pipeline",
]

CODE_TEMPLATES = {
    "Quick Sort": '''def quicksort(arr: list) -> list:
    if len(arr) <= 1: return arr
    pivot = arr[len(arr)//2]
    left   = [x for x in arr if x < pivot]
    middle = [x for x in arr if x == pivot]
    right  = [x for x in arr if x > pivot]
    return quicksort(left) + middle + quicksort(right)

print(quicksort([3,6,8,10,1,2,1]))  # [1,1,2,3,6,8,10]''',

    "Binary Search": '''def binary_search(arr: list, target: int) -> int:
    lo, hi = 0, len(arr) - 1
    while lo <= hi:
        mid = (lo + hi) // 2
        if arr[mid] == target:   return mid
        elif arr[mid] < target:  lo = mid + 1
        else:                    hi = mid - 1
    return -1

arr = [1,3,5,7,9,11,13]
print(binary_search(arr, 7))   # 3
print(binary_search(arr, 6))   # -1''',

    "LRU Cache": '''from collections import OrderedDict

class LRUCache:
    def __init__(self, capacity: int):
        self.cap   = capacity
        self.cache = OrderedDict()

    def get(self, key: int) -> int:
        if key not in self.cache: return -1
        self.cache.move_to_end(key)
        return self.cache[key]

    def put(self, key: int, val: int):
        if key in self.cache:
            self.cache.move_to_end(key)
        self.cache[key] = val
        if len(self.cache) > self.cap:
            self.cache.popitem(last=False)''',
}


class DataGenerator:
    def __init__(self, model: AIONModel = None,
                 tokenizer: OmegaTokenizer = None,
                 memory: OmegaPersistentMemory = None):
        self.model     = model
        self.tokenizer = tokenizer
        self.memory    = memory
        self.generated = []

    def _quality_score(self, sample: dict) -> float:
        """تقييم جودة العينة المولّدة"""
        score = 0.5
        answer = sample.get('assistant', '')

        # طول مناسب
        words = len(answer.split())
        if 30 < words < 500: score += 0.2
        if words < 10:        score -= 0.3

        # يحتوي على كود
        if any(kw in answer for kw in ['def ', 'class ', 'return', '```', '<python>']):
            score += 0.2

        # يحتوي على عربية
        if re.search(r'[\u0600-\u06FF]', answer):
            score += 0.1

        # لا تكرار
        q = sample.get('user', '')
        if len(q) > 10 and answer[:50] not in str(self.generated[-10:]):
            score += 0.1

        return min(1.0, max(0.0, score))

    def generate_from_templates(self, n: int = 100) -> list:
        """توليد عينات من القوالب المحددة"""
        samples = []
        topics_list = list(TOPICS)
        random.shuffle(topics_list)

        for i in range(n):
            topic  = topics_list[i % len(topics_list)]
            topic2 = topics_list[(i + 1) % len(topics_list)]
            prompt_tmpl = GENERATION_PROMPTS[i % len(GENERATION_PROMPTS)]
            question = prompt_tmpl.format(topic=topic, topic2=topic2)

            # إجابة من القوالب المحددة أو generic
            if topic in CODE_TEMPLATES:
                answer = f"<python>\n{CODE_TEMPLATES[topic]}\n</python>"
            else:
                answer = (
                    f"<think>سؤال عن {topic}</think>\n"
                    f"{topic} هو مفهوم أساسي في علوم الحاسوب.\n\n"
                    f"```python\n# مثال على {topic}\nprint('مثال {topic}')\n```"
                )

            sample = {'user': question, 'assistant': answer, 'topic': topic}
            score  = self._quality_score(sample)

            if score >= 0.5:
                samples.append(sample)
                if self.memory:
                    self.memory.remember(
                        f"Generated: {question[:80]}",
                        mtype='self_improvement',
                        importance=score, source='generator'
                    )

        print(f"✨ Generated {len(samples)}/{n} quality samples")
        self.generated.extend(samples)
        return samples

    def generate_from_memory(self, n: int = 50) -> list:
        """توليد عينات مبنية على ما في الذاكرة"""
        if not self.memory:
            return []

        samples = []
        web_mems = self.memory.recall_by_type('web', limit=200)

        for mem in web_mems[:n]:
            words = re.findall(r'\b[A-Za-z\u0600-\u06FF]{4,}\b', mem.content)
            if len(words) < 3:
                continue
            key = words[0]
            question = random.choice([
                f"اشرح {key} بمثال برمجي",
                f"ما هو {key} وكيف يعمل؟",
                f"كيف أستخدم {key} في Python؟",
            ])
            answer = f"<think>من المصادر المتاحة</think>\n{mem.content}"
            sample = {'user': question, 'assistant': answer}
            if self._quality_score(sample) >= 0.4:
                samples.append(sample)

        print(f"🧠 Memory-based samples: {len(samples)}")
        return samples

    def save(self, path: str, existing_path: str = None):
        """دمج البيانات الجديدة مع الموجودة وحفظها"""
        existing = []
        if existing_path and os.path.exists(existing_path):
            with open(existing_path, 'r', encoding='utf-8') as f:
                existing = json.load(f)

        combined = existing + self.generated
        # إزالة التكرار
        seen = set()
        unique = []
        for s in combined:
            key = str(s)[:100]
            if key not in seen:
                seen.add(key)
                unique.append(s)

        with open(path, 'w', encoding='utf-8') as f:
            json.dump(unique, f, ensure_ascii=False, indent=2)

        print(f"💾 Saved {len(unique)} samples ({len(unique)-len(existing)} new)")
        return len(unique)


if __name__ == '__main__':
    import argparse
    p = argparse.ArgumentParser()
    p.add_argument('--n',        type=int, default=100)
    p.add_argument('--out',      default='data/training_data.json')
    p.add_argument('--existing', default='data/training_data.json')
    p.add_argument('--memory',   default='memory/aion.db')
    args = p.parse_args()

    os.makedirs('memory', exist_ok=True)
    os.makedirs('data', exist_ok=True)

    mem = OmegaPersistentMemory(args.memory)
    gen = DataGenerator(memory=mem)

    samples1 = gen.generate_from_templates(args.n)
    samples2 = gen.generate_from_memory(args.n // 2)
    gen.generated = samples1 + samples2

    total = gen.save(args.out, args.existing)
    mem.log_self_event('data_generation', {
        'new_samples': len(gen.generated),
        'total': total,
    })
    mem.close()
    print(f"\n✅ Data generation complete: {total} total samples")
