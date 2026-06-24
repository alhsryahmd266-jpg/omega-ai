"""
AION Massive Data Builder
يبني آلاف أمثلة التدريب برمجياً من قوالب منظّمة
مصمم ليغطي: خوارزميات + هياكل بيانات + Android + AI + System Design
"""
import json
import random
import os

# ════════════════════════════════════════════════════════
# قوالب خوارزميات قابلة للتوليد المتعدد (متغيرات لكل واحدة)
# ════════════════════════════════════════════════════════

ALGO_FAMILIES = {
    "sorting": {
        "names": ["Bubble Sort", "Selection Sort", "Insertion Sort",
                  "Merge Sort", "Quick Sort", "Heap Sort", "Counting Sort"],
        "code": {
            "Bubble Sort": "def bubble_sort(arr):\n    n = len(arr)\n    for i in range(n):\n        for j in range(n-i-1):\n            if arr[j] > arr[j+1]:\n                arr[j], arr[j+1] = arr[j+1], arr[j]\n    return arr",
            "Selection Sort": "def selection_sort(arr):\n    n = len(arr)\n    for i in range(n):\n        min_idx = i\n        for j in range(i+1, n):\n            if arr[j] < arr[min_idx]:\n                min_idx = j\n        arr[i], arr[min_idx] = arr[min_idx], arr[i]\n    return arr",
            "Insertion Sort": "def insertion_sort(arr):\n    for i in range(1, len(arr)):\n        key = arr[i]\n        j = i - 1\n        while j >= 0 and arr[j] > key:\n            arr[j+1] = arr[j]\n            j -= 1\n        arr[j+1] = key\n    return arr",
            "Merge Sort": "def merge_sort(arr):\n    if len(arr) <= 1: return arr\n    mid = len(arr)//2\n    L, R = merge_sort(arr[:mid]), merge_sort(arr[mid:])\n    result, i, j = [], 0, 0\n    while i < len(L) and j < len(R):\n        if L[i] <= R[j]: result.append(L[i]); i += 1\n        else: result.append(R[j]); j += 1\n    return result + L[i:] + R[j:]",
            "Quick Sort": "def quick_sort(arr):\n    if len(arr) <= 1: return arr\n    pivot = arr[len(arr)//2]\n    left  = [x for x in arr if x < pivot]\n    mid   = [x for x in arr if x == pivot]\n    right = [x for x in arr if x > pivot]\n    return quick_sort(left) + mid + quick_sort(right)",
            "Heap Sort": "import heapq\ndef heap_sort(arr):\n    heapq.heapify(arr)\n    return [heapq.heappop(arr) for _ in range(len(arr))]",
            "Counting Sort": "def counting_sort(arr):\n    if not arr: return arr\n    mx = max(arr)\n    count = [0]*(mx+1)\n    for x in arr: count[x] += 1\n    result = []\n    for i, c in enumerate(count):\n        result.extend([i]*c)\n    return result",
        },
        "complexity": {
            "Bubble Sort": "O(n²) زمن، O(1) ذاكرة",
            "Selection Sort": "O(n²) زمن، O(1) ذاكرة",
            "Insertion Sort": "O(n²) أسوأ حالة، O(n) أفضل حالة",
            "Merge Sort": "O(n log n) دائماً، O(n) ذاكرة إضافية",
            "Quick Sort": "O(n log n) متوسط، O(n²) أسوأ حالة",
            "Heap Sort": "O(n log n) دائماً، O(1) ذاكرة إضافية",
            "Counting Sort": "O(n+k) حيث k النطاق، غير مقارن",
        }
    },
    "searching": {
        "names": ["Linear Search", "Binary Search", "Jump Search", "Exponential Search"],
        "code": {
            "Linear Search": "def linear_search(arr, target):\n    for i, val in enumerate(arr):\n        if val == target: return i\n    return -1",
            "Binary Search": "def binary_search(arr, target):\n    lo, hi = 0, len(arr)-1\n    while lo <= hi:\n        mid = (lo+hi)//2\n        if arr[mid] == target: return mid\n        elif arr[mid] < target: lo = mid+1\n        else: hi = mid-1\n    return -1",
            "Jump Search": "import math\ndef jump_search(arr, target):\n    n = len(arr)\n    step = int(math.sqrt(n))\n    prev = 0\n    while prev < n and arr[min(step,n)-1] < target:\n        prev = step\n        step += int(math.sqrt(n))\n    for i in range(prev, min(step, n)):\n        if arr[i] == target: return i\n    return -1",
            "Exponential Search": "def exponential_search(arr, target):\n    if arr[0] == target: return 0\n    i = 1\n    while i < len(arr) and arr[i] <= target: i *= 2\n    lo, hi = i//2, min(i, len(arr)-1)\n    while lo <= hi:\n        mid = (lo+hi)//2\n        if arr[mid] == target: return mid\n        elif arr[mid] < target: lo = mid+1\n        else: hi = mid-1\n    return -1",
        },
        "complexity": {
            "Linear Search": "O(n) زمن، يعمل على أي مصفوفة",
            "Binary Search": "O(log n) زمن، يحتاج مصفوفة مرتبة",
            "Jump Search": "O(√n) زمن، يحتاج مصفوفة مرتبة",
            "Exponential Search": "O(log n) زمن، جيد للمصفوفات اللانهائية",
        }
    },
    "graph": {
        "names": ["BFS", "DFS", "Dijkstra", "Bellman-Ford", "Kruskal", "Prim", "Floyd-Warshall"],
        "code": {
            "BFS": "from collections import deque\ndef bfs(graph, start):\n    visited, order = {start}, []\n    q = deque([start])\n    while q:\n        node = q.popleft()\n        order.append(node)\n        for nb in graph.get(node, []):\n            if nb not in visited:\n                visited.add(nb); q.append(nb)\n    return order",
            "DFS": "def dfs(graph, start, visited=None):\n    if visited is None: visited = set()\n    visited.add(start)\n    order = [start]\n    for nb in graph.get(start, []):\n        if nb not in visited:\n            order.extend(dfs(graph, nb, visited))\n    return order",
            "Dijkstra": "import heapq\ndef dijkstra(graph, start):\n    dist = {n: float('inf') for n in graph}\n    dist[start] = 0\n    pq = [(0, start)]\n    while pq:\n        d, u = heapq.heappop(pq)\n        if d > dist[u]: continue\n        for v, w in graph[u].items():\n            nd = d + w\n            if nd < dist[v]:\n                dist[v] = nd\n                heapq.heappush(pq, (nd, v))\n    return dist",
            "Bellman-Ford": "def bellman_ford(graph, edges, start):\n    dist = {n: float('inf') for n in graph}\n    dist[start] = 0\n    for _ in range(len(graph)-1):\n        for u, v, w in edges:\n            if dist[u] + w < dist[v]:\n                dist[v] = dist[u] + w\n    return dist",
            "Kruskal": "def kruskal(n, edges):\n    parent = list(range(n))\n    def find(x):\n        while parent[x] != x: x = parent[x]\n        return x\n    mst = []\n    for w, u, v in sorted(edges):\n        ru, rv = find(u), find(v)\n        if ru != rv:\n            parent[ru] = rv\n            mst.append((u, v, w))\n    return mst",
            "Prim": "import heapq\ndef prim(graph, start):\n    visited = {start}\n    edges = [(w, start, v) for v, w in graph[start].items()]\n    heapq.heapify(edges)\n    mst = []\n    while edges:\n        w, u, v = heapq.heappop(edges)\n        if v not in visited:\n            visited.add(v)\n            mst.append((u, v, w))\n            for nb, nw in graph[v].items():\n                if nb not in visited:\n                    heapq.heappush(edges, (nw, v, nb))\n    return mst",
            "Floyd-Warshall": "def floyd_warshall(graph, n):\n    dist = [[graph.get((i,j), float('inf')) for j in range(n)] for i in range(n)]\n    for i in range(n): dist[i][i] = 0\n    for k in range(n):\n        for i in range(n):\n            for j in range(n):\n                if dist[i][k] + dist[k][j] < dist[i][j]:\n                    dist[i][j] = dist[i][k] + dist[k][j]\n    return dist",
        },
        "complexity": {
            "BFS": "O(V+E) زمن، أقصر مسار غير موزون",
            "DFS": "O(V+E) زمن، مفيد لاكتشاف الدورات",
            "Dijkstra": "O((V+E) log V) بـ heap، أقصر مسار موزون موجب",
            "Bellman-Ford": "O(VE)، يدعم أوزان سالبة",
            "Kruskal": "O(E log E)، شجرة ممتدة صغرى",
            "Prim": "O(E log V) بـ heap، شجرة ممتدة صغرى",
            "Floyd-Warshall": "O(V³)، كل الأزواج لأقصر مسار",
        }
    },
    "dp": {
        "names": ["Fibonacci", "Knapsack 0/1", "Longest Common Subsequence",
                  "Longest Increasing Subsequence", "Edit Distance", "Coin Change"],
        "code": {
            "Fibonacci": "def fib(n, memo={}):\n    if n in memo: return memo[n]\n    if n <= 1: return n\n    memo[n] = fib(n-1, memo) + fib(n-2, memo)\n    return memo[n]",
            "Knapsack 0/1": "def knapsack(weights, values, capacity):\n    n = len(weights)\n    dp = [[0]*(capacity+1) for _ in range(n+1)]\n    for i in range(1, n+1):\n        for w in range(capacity+1):\n            if weights[i-1] <= w:\n                dp[i][w] = max(dp[i-1][w], dp[i-1][w-weights[i-1]]+values[i-1])\n            else:\n                dp[i][w] = dp[i-1][w]\n    return dp[n][capacity]",
            "Longest Common Subsequence": "def lcs(a, b):\n    m, n = len(a), len(b)\n    dp = [[0]*(n+1) for _ in range(m+1)]\n    for i in range(1, m+1):\n        for j in range(1, n+1):\n            if a[i-1] == b[j-1]: dp[i][j] = dp[i-1][j-1]+1\n            else: dp[i][j] = max(dp[i-1][j], dp[i][j-1])\n    return dp[m][n]",
            "Longest Increasing Subsequence": "def lis(arr):\n    if not arr: return 0\n    dp = [1]*len(arr)\n    for i in range(1, len(arr)):\n        for j in range(i):\n            if arr[j] < arr[i]:\n                dp[i] = max(dp[i], dp[j]+1)\n    return max(dp)",
            "Edit Distance": "def edit_distance(a, b):\n    m, n = len(a), len(b)\n    dp = [[0]*(n+1) for _ in range(m+1)]\n    for i in range(m+1): dp[i][0] = i\n    for j in range(n+1): dp[0][j] = j\n    for i in range(1, m+1):\n        for j in range(1, n+1):\n            if a[i-1] == b[j-1]: dp[i][j] = dp[i-1][j-1]\n            else: dp[i][j] = 1 + min(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])\n    return dp[m][n]",
            "Coin Change": "def coin_change(coins, amount):\n    dp = [float('inf')]*(amount+1)\n    dp[0] = 0\n    for a in range(1, amount+1):\n        for c in coins:\n            if c <= a: dp[a] = min(dp[a], dp[a-c]+1)\n    return dp[amount] if dp[amount] != float('inf') else -1",
        },
        "complexity": {
            "Fibonacci": "O(n) بالـ memoization بدل O(2ⁿ)",
            "Knapsack 0/1": "O(n×capacity) زمن وذاكرة",
            "Longest Common Subsequence": "O(m×n)",
            "Longest Increasing Subsequence": "O(n²)، يمكن تحسينه لـ O(n log n)",
            "Edit Distance": "O(m×n)",
            "Coin Change": "O(amount × len(coins))",
        }
    },
}

ANDROID_TOPICS = {
    "Lifecycle": "class MainActivity : AppCompatActivity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        // التهيئة الأولى\n    }\n    override fun onStart() { super.onStart() }\n    override fun onResume() { super.onResume() }\n    override fun onPause() { super.onPause() }\n    override fun onStop() { super.onStop() }\n    override fun onDestroy() { super.onDestroy() }\n}",
    "ViewModel + LiveData": "class CounterViewModel : ViewModel() {\n    private val _count = MutableLiveData(0)\n    val count: LiveData<Int> = _count\n    fun increment() { _count.value = (_count.value ?: 0) + 1 }\n}",
    "Coroutines": "class UserRepository {\n    suspend fun fetchUser(id: String): User = withContext(Dispatchers.IO) {\n        api.getUser(id)\n    }\n}\n// في الـ ViewModel\nviewModelScope.launch {\n    val user = repository.fetchUser(\"123\")\n}",
    "RecyclerView": "class ItemAdapter(val items: List<Item>) : RecyclerView.Adapter<ItemAdapter.VH>() {\n    class VH(view: View) : RecyclerView.ViewHolder(view)\n    override fun onCreateViewHolder(parent: ViewGroup, type: Int): VH {\n        val view = LayoutInflater.from(parent.context)\n            .inflate(R.layout.item_layout, parent, false)\n        return VH(view)\n    }\n    override fun onBindViewHolder(holder: VH, position: Int) {\n        holder.itemView.findViewById<TextView>(R.id.title).text = items[position].name\n    }\n    override fun getItemCount() = items.size\n}",
    "Room Database": "@Entity data class User(@PrimaryKey val id: Int, val name: String)\n@Dao interface UserDao {\n    @Query(\"SELECT * FROM User\") fun getAll(): Flow<List<User>>\n    @Insert suspend fun insert(user: User)\n}\n@Database(entities = [User::class], version = 1)\nabstract class AppDb : RoomDatabase() {\n    abstract fun userDao(): UserDao\n}",
    "Navigation Component": "// nav_graph.xml\n<navigation>\n    <fragment android:id=\"@+id/home\" />\n    <fragment android:id=\"@+id/detail\" />\n</navigation>\n\n// في الكود\nfindNavController().navigate(R.id.action_home_to_detail)",
    "Dependency Injection (Hilt)": "@HiltAndroidApp\nclass MyApp : Application()\n\n@AndroidEntryPoint\nclass MainActivity : AppCompatActivity()\n\n@HiltViewModel\nclass MainViewModel @Inject constructor(\n    private val repo: UserRepository\n) : ViewModel()",
    "WorkManager": "class SyncWorker(ctx: Context, params: WorkerParameters) :\n    CoroutineWorker(ctx, params) {\n    override suspend fun doWork(): Result {\n        return try {\n            syncData()\n            Result.success()\n        } catch (e: Exception) {\n            Result.retry()\n        }\n    }\n}",
    "Jetpack Compose State": "@Composable\nfun Counter() {\n    var count by remember { mutableStateOf(0) }\n    Column {\n        Text(\"العدد: $count\")\n        Button(onClick = { count++ }) { Text(\"زيادة\") }\n    }\n}",
    "Retrofit Networking": "interface ApiService {\n    @GET(\"users/{id}\")\n    suspend fun getUser(@Path(\"id\") id: String): User\n}\nval retrofit = Retrofit.Builder()\n    .baseUrl(\"https://api.example.com/\")\n    .addConverterFactory(GsonConverterFactory.create())\n    .build()",
}

AI_TOPICS = {
    "Gradient Descent": "def gradient_descent(X, y, lr=0.01, epochs=100):\n    m, n = X.shape\n    theta = np.zeros(n)\n    for _ in range(epochs):\n        pred = X.dot(theta)\n        grad = X.T.dot(pred - y) / m\n        theta -= lr * grad\n    return theta",
    "Linear Regression": "class LinearRegression:\n    def fit(self, X, y, lr=0.01, epochs=1000):\n        self.w = np.zeros(X.shape[1])\n        self.b = 0\n        for _ in range(epochs):\n            pred = X.dot(self.w) + self.b\n            dw = X.T.dot(pred - y) / len(y)\n            db = np.mean(pred - y)\n            self.w -= lr * dw\n            self.b -= lr * db\n    def predict(self, X):\n        return X.dot(self.w) + self.b",
    "K-Means Clustering": "def kmeans(X, k, iters=100):\n    centers = X[np.random.choice(len(X), k, replace=False)]\n    for _ in range(iters):\n        labels = np.array([np.argmin([np.linalg.norm(x-c) for c in centers]) for x in X])\n        new_centers = np.array([X[labels==i].mean(axis=0) for i in range(k)])\n        if np.allclose(centers, new_centers): break\n        centers = new_centers\n    return labels, centers",
    "Softmax + Cross Entropy": "def softmax(x):\n    e = np.exp(x - np.max(x))\n    return e / e.sum(axis=-1, keepdims=True)\ndef cross_entropy(pred, target):\n    return -np.sum(target * np.log(pred + 1e-9))",
    "Convolution Layer": "class Conv2D(nn.Module):\n    def __init__(self, in_ch, out_ch, k=3):\n        super().__init__()\n        self.conv = nn.Conv2d(in_ch, out_ch, k, padding=k//2)\n        self.bn = nn.BatchNorm2d(out_ch)\n        self.relu = nn.ReLU()\n    def forward(self, x):\n        return self.relu(self.bn(self.conv(x)))",
    "Attention Mechanism": "def attention(Q, K, V, mask=None):\n    scores = Q @ K.transpose(-2,-1) / math.sqrt(Q.shape[-1])\n    if mask is not None:\n        scores = scores.masked_fill(mask==0, float('-inf'))\n    weights = torch.softmax(scores, dim=-1)\n    return weights @ V",
    "Decision Tree (simple)": "class Node:\n    def __init__(self, feature=None, threshold=None, left=None, right=None, value=None):\n        self.feature, self.threshold = feature, threshold\n        self.left, self.right, self.value = left, right, value\ndef gini(y):\n    classes = set(y)\n    return 1 - sum((sum(1 for v in y if v==c)/len(y))**2 for c in classes)",
}

SYSTEM_DESIGN_TOPICS = {
    "Rate Limiter (Token Bucket)": "class TokenBucket:\n    def __init__(self, rate, capacity):\n        self.rate, self.capacity = rate, capacity\n        self.tokens = capacity\n        self.last = time.time()\n    def allow(self):\n        now = time.time()\n        self.tokens = min(self.capacity, self.tokens + (now-self.last)*self.rate)\n        self.last = now\n        if self.tokens >= 1:\n            self.tokens -= 1\n            return True\n        return False",
    "Consistent Hashing": "import hashlib\nclass ConsistentHash:\n    def __init__(self, nodes, replicas=3):\n        self.ring = {}\n        self.sorted_keys = []\n        for node in nodes:\n            for i in range(replicas):\n                h = self._hash(f'{node}:{i}')\n                self.ring[h] = node\n                self.sorted_keys.append(h)\n        self.sorted_keys.sort()\n    def _hash(self, key):\n        return int(hashlib.md5(key.encode()).hexdigest(), 16)\n    def get_node(self, key):\n        h = self._hash(key)\n        for k in self.sorted_keys:\n            if h <= k: return self.ring[k]\n        return self.ring[self.sorted_keys[0]]",
    "LRU Cache": "from collections import OrderedDict\nclass LRUCache:\n    def __init__(self, cap):\n        self.cap, self.cache = cap, OrderedDict()\n    def get(self, k):\n        if k not in self.cache: return -1\n        self.cache.move_to_end(k)\n        return self.cache[k]\n    def put(self, k, v):\n        if k in self.cache: self.cache.move_to_end(k)\n        self.cache[k] = v\n        if len(self.cache) > self.cap: self.cache.popitem(last=False)",
    "Pub/Sub System": "class EventBus:\n    def __init__(self): self.subs = {}\n    def subscribe(self, topic, callback):\n        self.subs.setdefault(topic, []).append(callback)\n    def publish(self, topic, data):\n        for cb in self.subs.get(topic, []): cb(data)",
    "Circuit Breaker": "class CircuitBreaker:\n    def __init__(self, threshold=5, timeout=30):\n        self.threshold, self.timeout = threshold, timeout\n        self.failures, self.state = 0, 'closed'\n        self.last_failure = 0\n    def call(self, func, *args):\n        if self.state == 'open':\n            if time.time() - self.last_failure > self.timeout:\n                self.state = 'half-open'\n            else:\n                raise Exception('Circuit open')\n        try:\n            result = func(*args)\n            self.failures = 0\n            self.state = 'closed'\n            return result\n        except Exception as e:\n            self.failures += 1\n            self.last_failure = time.time()\n            if self.failures >= self.threshold: self.state = 'open'\n            raise e",
}

QUESTION_TEMPLATES = [
    "نفّذ {name} بـ Python مع شرح",
    "اكتب كود {name} كامل",
    "وضّح خوارزمية {name} مع مثال",
    "ما هي خطوات {name}؟ اكتبها بالكود",
    "حل لي مسألة {name} برمجياً",
]

ANDROID_QUESTION_TEMPLATES = [
    "اشرح {name} في Android مع كود Kotlin",
    "كيف أستخدم {name} في تطبيق Android؟",
    "اكتب مثال على {name}",
    "وضّح {name} بالتفصيل مع الكود",
]


def build_algorithm_samples():
    samples = []
    for family, info in ALGO_FAMILIES.items():
        for name in info["names"]:
            code = info["code"].get(name, "")
            complexity = info["complexity"].get(name, "")
            if not code:
                continue
            q_tmpl = random.choice(QUESTION_TEMPLATES)
            question = q_tmpl.format(name=name)
            answer = (f"<think>خوارزمية {name} من فئة {family}</think>\n"
                     f"<python>\n{code}\n</python>\n\n"
                     f"التعقيد الزمني: {complexity}")
            samples.append({"user": question, "assistant": answer, "category": f"algo_{family}"})

            # سؤال تاني عن التعقيد بس
            samples.append({
                "user": f"ما تعقيد خوارزمية {name} الزمني والمكاني؟",
                "assistant": f"خوارزمية {name}: {complexity}",
                "category": f"complexity_{family}"
            })
    return samples


def build_android_samples():
    samples = []
    for name, code in ANDROID_TOPICS.items():
        q_tmpl = random.choice(ANDROID_QUESTION_TEMPLATES)
        question = q_tmpl.format(name=name)
        answer = f"<android><kotlin>\n{code}\n</kotlin></android>"
        samples.append({"user": question, "assistant": answer, "category": "android"})
    return samples


def build_ai_samples():
    samples = []
    for name, code in AI_TOPICS.items():
        question = random.choice([
            f"نفّذ {name} من الصفر",
            f"اكتب كود {name} بـ Python",
            f"اشرح {name} مع التطبيق العملي",
        ])
        answer = f"<think>خوارزمية تعلم آلي: {name}</think>\n<python>\nimport numpy as np\n{code}\n</python>"
        samples.append({"user": question, "assistant": answer, "category": "ai_ml"})
    return samples


def build_system_design_samples():
    samples = []
    for name, code in SYSTEM_DESIGN_TOPICS.items():
        question = random.choice([
            f"صمّم {name} بـ Python",
            f"نفّذ {name} للاستخدام الإنتاجي",
            f"اكتب {name} مع شرح الاستخدام",
        ])
        answer = f"<think>نمط تصميم أنظمة: {name}</think>\n<python>\nimport time\n{code}\n</python>"
        samples.append({"user": question, "assistant": answer, "category": "system_design"})
    return samples


def build_variation_samples(base_samples: list, n_variations: int = 3) -> list:
    """يولّد متغيرات من الأسئلة الأساسية بصياغات مختلفة"""
    variations = []
    prefixes = ["", "ممكن ", "محتاج ", "عايز اعرف ازاي ", "اشرحلي ازاي "]
    suffixes = ["", " مع مثال", " بالتفصيل", " بشكل مبسط", " للمبتدئين"]

    for sample in base_samples:
        for _ in range(n_variations):
            prefix = random.choice(prefixes)
            suffix = random.choice(suffixes)
            new_q = f"{prefix}{sample['user']}{suffix}"
            variations.append({
                "user": new_q,
                "assistant": sample["assistant"],
                "category": sample.get("category", "general")
            })
    return variations


def build_conceptual_qa():
    """أسئلة مفاهيمية عامة عن البرمجة وهندسة البرمجيات"""
    concepts = [
        ("ما الفرق بين Array و LinkedList؟",
         "Array: وصول مباشر O(1) لكن حجم ثابت أو إعادة تخصيص مكلفة. LinkedList: إدراج/حذف O(1) لكن وصول O(n) ولا locality في الذاكرة."),
        ("ما الفرق بين Stack و Queue؟",
         "Stack: LIFO (آخر داخل أول خارج) - push/pop من نفس الطرف. Queue: FIFO (أول داخل أول خارج) - enqueue من طرف وdequeue من الطرف الآخر."),
        ("متى أستخدم HashMap بدل Array؟",
         "استخدم HashMap عندما تحتاج بحث/إدراج/حذف بمتوسط O(1) باستخدام مفاتيح غير متسلسلة. استخدم Array عندما تحتاج ترتيب محفوظ ووصول بالفهرس."),
        ("ما هو Big O Notation؟",
         "مقياس لكيفية نمو وقت أو ذاكرة الخوارزمية مع حجم المدخلات n. O(1) ثابت، O(log n) لوغاريتمي، O(n) خطي، O(n log n)، O(n²) تربيعي، O(2ⁿ) أسي."),
        ("ما الفرق بين Process و Thread؟",
         "Process: وحدة تنفيذ مستقلة بذاكرتها الخاصة. Thread: وحدة تنفيذ أخف داخل process تشارك نفس الذاكرة مع threads أخرى."),
        ("ما هو Race Condition؟",
         "حالة تحدث عندما تتنافس عمليات/threads متعددة على مورد مشترك بدون تزامن صحيح، فتعتمد النتيجة على توقيت التنفيذ بشكل غير متوقع."),
        ("ما الفرق بين == و equals في Java/Kotlin؟",
         "== يقارن المرجع (هل نفس الكائن في الذاكرة)، بينما equals() يقارن القيمة المنطقية للكائنين حسب التعريف المخصص."),
        ("ما هو Dependency Injection؟",
         "نمط تصميم يتم فيه تزويد الكائن باعتمادياته من الخارج بدل إنشائها داخلياً، مما يسهل الاختبار وفصل المسؤوليات."),
        ("ما الفرق بين Synchronous و Asynchronous؟",
         "Synchronous: العمليات تنفذ بالتتابع وتنتظر كل عملية اكتمال السابقة. Asynchronous: العمليات تبدأ وتستمر بالتنفيذ الآخر بدون انتظار، مع callback عند الاكتمال."),
        ("ما هو Garbage Collection؟",
         "آلية تلقائية لإدارة الذاكرة تحذف الكائنات غير المستخدمة (التي لا توجد إليها مراجع) لتحرير الذاكرة دون تدخل المبرمج."),
        ("ما الفرق بين Compile-time و Runtime errors؟",
         "Compile-time: أخطاء تكتشف أثناء التحويل البرمجي قبل التشغيل (أخطاء صياغة). Runtime: أخطاء تحدث أثناء تنفيذ البرنامج (مثل القسمة على صفر)."),
        ("ما هو Polymorphism في البرمجة الكائنية؟",
         "قدرة الكائنات المختلفة على الاستجابة لنفس الرسالة (method call) بطرق مختلفة، عادة عبر inheritance و method overriding."),
        ("ما الفرق بين Abstract Class و Interface؟",
         "Abstract Class يمكن أن تحتوي على implementation جزئي وstate، بينما Interface (في معظم اللغات) يحدد فقط العقد (method signatures) بدون implementation."),
        ("ما هو Memoization؟",
         "تقنية تحسين تخزن نتائج استدعاءات الدوال المكلفة وتعيد استخدامها عند تكرار نفس المدخلات، بدل إعادة الحساب."),
        ("ما الفرق بين Mutable و Immutable؟",
         "Mutable: يمكن تغيير حالة الكائن بعد إنشائه (مثل list في Python). Immutable: لا يمكن تغييره بعد الإنشاء (مثل tuple أو string)."),
        ("ما هو SOLID في هندسة البرمجيات؟",
         "خمسة مبادئ: Single Responsibility، Open/Closed، Liskov Substitution، Interface Segregation، Dependency Inversion - لتصميم كود قابل للصيانة."),
        ("ما الفرق بين REST و GraphQL؟",
         "REST: نقاط نهاية متعددة بهياكل بيانات ثابتة. GraphQL: نقطة نهاية واحدة تتيح للعميل تحديد البيانات المطلوبة بدقة، يقلل over-fetching."),
        ("ما هو الفرق بين TCP و UDP؟",
         "TCP: موثوق، يضمن وصول البيانات بالترتيب، أبطأ. UDP: غير موثوق، أسرع، لا يضمن الترتيب أو الوصول، يستخدم في streaming."),
        ("ما هو ACID في قواعد البيانات؟",
         "Atomicity (الذرية)، Consistency (الاتساق)، Isolation (العزل)، Durability (الديمومة) - خصائص تضمن موثوقية معاملات قاعدة البيانات."),
        ("ما الفرق بين SQL و NoSQL؟",
         "SQL: علائقية، schema ثابت، ACID قوي، جيد للبيانات المنظمة. NoSQL: مرن، schema-less، يتوسع أفقياً بسهولة، جيد للبيانات الضخمة وغير المنظمة."),
    ]
    return [{"user": q, "assistant": a, "category": "concepts"} for q, a in concepts]


def main():
    print("بناء dataset ضخم...")
    all_samples = []

    algo_samples = build_algorithm_samples()
    android_samples = build_android_samples()
    ai_samples = build_ai_samples()
    sysdesign_samples = build_system_design_samples()
    concept_samples = build_conceptual_qa()

    print(f"  Algorithms: {len(algo_samples)}")
    print(f"  Android:    {len(android_samples)}")
    print(f"  AI/ML:      {len(ai_samples)}")
    print(f"  SysDesign:  {len(sysdesign_samples)}")
    print(f"  Concepts:   {len(concept_samples)}")

    all_samples.extend(algo_samples)
    all_samples.extend(android_samples)
    all_samples.extend(ai_samples)
    all_samples.extend(sysdesign_samples)
    all_samples.extend(concept_samples)

    # توليد variations لزيادة التنوع (يضاعف البيانات)
    variations = build_variation_samples(
        algo_samples + android_samples + ai_samples + sysdesign_samples,
        n_variations=2
    )
    print(f"  Variations: {len(variations)}")
    all_samples.extend(variations)

    random.shuffle(all_samples)
    print(f"\nإجمالي العينات: {len(all_samples)}")

    os.makedirs('data', exist_ok=True)
    with open('data/massive_training_data.json', 'w', encoding='utf-8') as f:
        json.dump(all_samples, f, ensure_ascii=False, indent=2)
    print("Saved -> data/massive_training_data.json")
    return all_samples


if __name__ == '__main__':
    main()
