"""
Omega Web Learner - يتعلم من الإنترنت تلقائياً
"""
import re, time, json, html, os, sys
import urllib.request, urllib.parse, urllib.error
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from omega.memory.persistent import OmegaPersistentMemory

LEARNING_SOURCES = {
    'ai_ml': [
        "https://en.wikipedia.org/wiki/Artificial_intelligence",
        "https://en.wikipedia.org/wiki/Machine_learning",
        "https://en.wikipedia.org/wiki/Deep_learning",
        "https://en.wikipedia.org/wiki/Transformer_(deep_learning_architecture)",
        "https://en.wikipedia.org/wiki/Large_language_model",
    ],
    'programming': [
        "https://en.wikipedia.org/wiki/Python_(programming_language)",
        "https://en.wikipedia.org/wiki/Kotlin_(programming_language)",
        "https://en.wikipedia.org/wiki/Algorithm",
        "https://en.wikipedia.org/wiki/Data_structure",
    ],
    'android': [
        "https://en.wikipedia.org/wiki/Android_(operating_system)",
        "https://en.wikipedia.org/wiki/Android_application_package",
        "https://en.wikipedia.org/wiki/Jetpack_Compose",
    ],
    'arabic_ai': [
        "https://ar.wikipedia.org/wiki/%D8%B0%D9%83%D8%A7%D8%A1_%D8%A7%D8%B5%D8%B7%D9%86%D8%A7%D8%B9%D9%8A",
        "https://ar.wikipedia.org/wiki/%D8%AA%D8%B9%D9%84%D9%85_%D8%A2%D9%84%D9%8A",
    ],
}

class OmegaWebLearner:
    def __init__(self, memory: OmegaPersistentMemory, delay: float = 1.2, timeout: int = 20):
        self.memory  = memory
        self.delay   = delay
        self.timeout = timeout
        self.headers = {
            'User-Agent': 'OmegaAI/2.0 Educational Bot',
            'Accept-Language': 'ar,en;q=0.9',
        }
        self.session_facts = 0

    def fetch(self, url: str):
        try:
            req = urllib.request.Request(url, headers=self.headers)
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                raw = r.read()
                enc = r.headers.get_content_charset('utf-8')
                return raw.decode(enc, errors='replace')
        except Exception as e:
            print(f"  ⚠ {url[:50]}: {e}")
            return None

    def clean(self, text: str) -> str:
        for tag in ['script','style','nav','footer','header']:
            text = re.sub(f'<{tag}[^>]*>.*?</{tag}>', ' ', text, flags=re.DOTALL|re.I)
        text = re.sub(r'<[^>]+>', ' ', text)
        text = html.unescape(text)
        return re.sub(r'\s+', ' ', text).strip()

    def extract_facts(self, text: str) -> list:
        facts = []
        for sent in re.split(r'[.!\n]+', text):
            sent = sent.strip()
            if 15 < len(sent) < 350:
                words = re.findall(r'\b\w{3,}\b', sent)
                if len(words) >= 5:
                    facts.append(sent)
        return facts[:60]

    def learn_url(self, url: str, category: str = 'general') -> int:
        # Skip already learned
        learned = {u['url'] for u in self.memory.get_learned_urls()}
        if url in learned:
            return 0

        print(f"  📖 {url[:65]}")
        content = self.fetch(url)
        if not content or len(content) < 200:
            return 0

        text = self.clean(content)
        title_m = re.search(r'<title[^>]*>(.*?)</title>', content, re.I|re.DOTALL)
        title = html.unescape(title_m.group(1)).strip() if title_m else url[:60]

        facts = self.extract_facts(text)
        if not facts:
            return 0

        imp = {'ai_ml':0.8,'programming':0.75,'android':0.85,'arabic_ai':0.75}.get(category,0.6)
        self.memory.remember_url(url, title, facts)
        for f in facts:
            tags = [category, 'web', 'auto']
            if re.search(r'[\u0600-\u06FF]', f):
                tags.append('arabic')
            self.memory.remember(f, mtype='web', tags=tags,
                                 importance=imp, source=url, summary=f[:100])

        self.session_facts += len(facts)
        self.memory.log_self_event('web_learn', {'url': url, 'title': title, 'facts': len(facts)})
        print(f"  ✅ {len(facts)} facts ← {title[:45]}")
        time.sleep(self.delay)
        return len(facts)

    def learn_category(self, cat: str) -> int:
        urls = LEARNING_SOURCES.get(cat, [])
        print(f"\n📚 Category: {cat}")
        return sum(self.learn_url(u, cat) for u in urls)

    def learn_all(self, cats: list = None) -> dict:
        cats = cats or list(LEARNING_SOURCES.keys())
        print(f"\n🌐 Web learning: {len(cats)} categories")
        results = {cat: self.learn_category(cat) for cat in cats}
        total = sum(results.values())
        print(f"\n🎓 Total learned: {total} facts | Memory: {self.memory.stats()}")
        self.memory.log_self_event('full_learn_session', {'results': results, 'total': total})
        return results

    def search_and_learn(self, query: str) -> int:
        encoded = urllib.parse.quote(query)
        search_url = f"https://en.wikipedia.org/w/api.php?action=opensearch&search={encoded}&limit=3&format=json"
        try:
            req = urllib.request.Request(search_url, headers=self.headers)
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                data = json.loads(r.read())
            urls = data[3] if len(data) > 3 else []
            return sum(self.learn_url(u, 'search') for u in urls[:3])
        except Exception as e:
            print(f"  Search error: {e}")
            return 0
