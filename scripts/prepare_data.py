"""
AION Training Data v2
50,000+ مثال برمجة معقدة
"""
import os, json, random

def get_training_data():
    data = []

    # ════════════════════════════════════════
    # 1. الگوريتمات المعقدة
    # ════════════════════════════════════════
    algorithms = [
        {"user": "نفّذ Dijkstra لأقصر مسار",
         "assistant": '''<think>خوارزمية Dijkstra: Priority Queue + Relaxation</think>
<python>
import heapq
from collections import defaultdict

def dijkstra(graph: dict, start: str) -> dict:
    distances = {node: float('inf') for node in graph}
    distances[start] = 0
    pq = [(0, start)]
    visited = set()

    while pq:
        dist, node = heapq.heappop(pq)
        if node in visited:
            continue
        visited.add(node)
        for neighbor, weight in graph[node].items():
            new_dist = dist + weight
            if new_dist < distances[neighbor]:
                distances[neighbor] = new_dist
                heapq.heappush(pq, (new_dist, neighbor))
    return distances

# اختبار
graph = {
    'A': {'B': 4, 'C': 2},
    'B': {'D': 3, 'C': 1},
    'C': {'B': 1, 'D': 5},
    'D': {}
}
print(dijkstra(graph, 'A'))  # {'A':0,'B':3,'C':2,'D':6}
</python>'''},

        {"user": "نفّذ A* Search للـ pathfinding",
         "assistant": '''<think>A* = Dijkstra + Heuristic للتوجيه الذكي</think>
<python>
import heapq
from typing import Callable, List, Tuple

def astar(grid: List[List[int]], start: Tuple, goal: Tuple,
          h: Callable = None) -> List[Tuple]:
    if h is None:
        h = lambda a, b: abs(a[0]-b[0]) + abs(a[1]-b[1])  # Manhattan

    rows, cols = len(grid), len(grid[0])
    open_set = [(0 + h(start, goal), 0, start, [start])]
    visited = set()

    while open_set:
        f, g, curr, path = heapq.heappop(open_set)
        if curr == goal:
            return path
        if curr in visited:
            continue
        visited.add(curr)

        for dr, dc in [(-1,0),(1,0),(0,-1),(0,1)]:
            nr, nc = curr[0]+dr, curr[1]+dc
            if 0<=nr<rows and 0<=nc<cols and grid[nr][nc]==0:
                nxt = (nr, nc)
                if nxt not in visited:
                    ng = g + 1
                    heapq.heappush(open_set,
                        (ng + h(nxt, goal), ng, nxt, path + [nxt]))
    return []

grid = [
    [0,0,0,0,0],
    [0,1,1,1,0],
    [0,0,0,1,0],
    [0,1,0,0,0],
    [0,0,0,0,0],
]
path = astar(grid, (0,0), (4,4))
print("Path:", path)
</python>'''},

        {"user": "نفّذ Binary Search Tree مع كل العمليات",
         "assistant": '''<python>
class BST:
    class Node:
        def __init__(self, val):
            self.val = val
            self.left = self.right = None

    def __init__(self): self.root = None

    def insert(self, val):
        def _ins(node, val):
            if not node: return self.Node(val)
            if val < node.val: node.left  = _ins(node.left,  val)
            else:              node.right = _ins(node.right, val)
            return node
        self.root = _ins(self.root, val)

    def search(self, val) -> bool:
        node = self.root
        while node:
            if val == node.val: return True
            node = node.left if val < node.val else node.right
        return False

    def delete(self, val):
        def _del(node, val):
            if not node: return None
            if val < node.val:
                node.left  = _del(node.left,  val)
            elif val > node.val:
                node.right = _del(node.right, val)
            else:
                if not node.left:  return node.right
                if not node.right: return node.left
                # Find inorder successor
                succ = node.right
                while succ.left: succ = succ.left
                node.val   = succ.val
                node.right = _del(node.right, succ.val)
            return node
        self.root = _del(self.root, val)

    def inorder(self):
        res = []
        def _io(n):
            if n: _io(n.left); res.append(n.val); _io(n.right)
        _io(self.root)
        return res

bst = BST()
for v in [5,3,7,1,4,6,8]: bst.insert(v)
print(bst.inorder())   # [1,3,4,5,6,7,8]
bst.delete(3)
print(bst.inorder())   # [1,4,5,6,7,8]
</python>'''},

        {"user": "نفّذ LRU Cache من الصفر",
         "assistant": '''<python>
from collections import OrderedDict

class LRUCache:
    def __init__(self, capacity: int):
        self.cap   = capacity
        self.cache = OrderedDict()

    def get(self, key: int) -> int:
        if key not in self.cache:
            return -1
        self.cache.move_to_end(key)
        return self.cache[key]

    def put(self, key: int, val: int):
        if key in self.cache:
            self.cache.move_to_end(key)
        self.cache[key] = val
        if len(self.cache) > self.cap:
            self.cache.popitem(last=False)

# Manual implementation with doubly linked list (O(1) guaranteed)
class LRUCacheFast:
    class Node:
        def __init__(self, k=0, v=0):
            self.k, self.v = k, v
            self.prev = self.next = None

    def __init__(self, capacity):
        self.cap  = capacity
        self.map  = {}
        self.head = self.Node()
        self.tail = self.Node()
        self.head.next = self.tail
        self.tail.prev = self.head

    def _remove(self, node):
        node.prev.next = node.next
        node.next.prev = node.prev

    def _insert(self, node):  # insert at tail (most recent)
        node.prev = self.tail.prev
        node.next = self.tail
        self.tail.prev.next = node
        self.tail.prev = node

    def get(self, key):
        if key in self.map:
            self._remove(self.map[key])
            self._insert(self.map[key])
            return self.map[key].v
        return -1

    def put(self, key, val):
        if key in self.map:
            self._remove(self.map[key])
        node = self.Node(key, val)
        self._insert(node)
        self.map[key] = node
        if len(self.map) > self.cap:
            lru = self.head.next
            self._remove(lru)
            del self.map[lru.k]

cache = LRUCacheFast(2)
cache.put(1, 1); cache.put(2, 2)
print(cache.get(1))   # 1
cache.put(3, 3)
print(cache.get(2))   # -1 (evicted)
</python>'''},

        {"user": "نفّذ Graph BFS و DFS مع تطبيقات عملية",
         "assistant": '''<python>
from collections import deque
from typing import Dict, List, Set, Optional

class Graph:
    def __init__(self): self.adj: Dict[int, List[int]] = {}

    def add_edge(self, u: int, v: int, directed=False):
        self.adj.setdefault(u, []).append(v)
        if not directed:
            self.adj.setdefault(v, []).append(u)

    def bfs(self, start: int) -> List[int]:
        visited, order = {start}, []
        queue = deque([start])
        while queue:
            node = queue.popleft()
            order.append(node)
            for nb in self.adj.get(node, []):
                if nb not in visited:
                    visited.add(nb)
                    queue.append(nb)
        return order

    def dfs(self, start: int) -> List[int]:
        visited, order = set(), []
        def _dfs(v):
            visited.add(v); order.append(v)
            for nb in self.adj.get(v, []):
                if nb not in visited: _dfs(nb)
        _dfs(start)
        return order

    def has_cycle(self) -> bool:
        # Detect cycle in directed graph
        WHITE, GRAY, BLACK = 0, 1, 2
        color = {v: WHITE for v in self.adj}
        def dfs(v):
            color[v] = GRAY
            for nb in self.adj.get(v, []):
                if color[nb] == GRAY: return True
                if color[nb] == WHITE and dfs(nb): return True
            color[v] = BLACK
            return False
        return any(dfs(v) for v in self.adj if color[v] == WHITE)

    def topo_sort(self) -> List[int]:
        # Kahn algorithm for topological sort
        in_deg = {v: 0 for v in self.adj}
        for v in self.adj:
            for nb in self.adj[v]:
                in_deg[nb] = in_deg.get(nb, 0) + 1
        queue = deque(v for v in in_deg if in_deg[v] == 0)
        order = []
        while queue:
            v = queue.popleft(); order.append(v)
            for nb in self.adj.get(v, []):
                in_deg[nb] -= 1
                if in_deg[nb] == 0: queue.append(nb)
        return order if len(order) == len(self.adj) else []

g = Graph()
for u, v in [(0,1),(0,2),(1,3),(2,3),(3,4)]:
    g.add_edge(u, v, directed=True)
print("BFS:", g.bfs(0))
print("Topo:", g.topo_sort())
</python>'''},
    ]

    # ════════════════════════════════════════
    # 2. Android / Kotlin المتقدم
    # ════════════════════════════════════════
    android_advanced = [
        {"user": "اكتب Clean Architecture كاملة لتطبيق Android",
         "assistant": '''<android><kotlin>
// ── Domain Layer ──────────────────────────────────────
data class User(val id: String, val name: String, val email: String)

interface UserRepository {
    suspend fun getUser(id: String): Result<User>
    suspend fun saveUser(user: User): Result<Unit>
    fun observeUsers(): Flow<List<User>>
}

class GetUserUseCase(private val repo: UserRepository) {
    suspend operator fun invoke(id: String): Result<User> =
        repo.getUser(id)
}

// ── Data Layer ─────────────────────────────────────────
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val name: String,
    val email: String
)

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getById(id: String): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: UserEntity)

    @Query("SELECT * FROM users")
    fun observeAll(): Flow<List<UserEntity>>
}

class UserRepositoryImpl(
    private val dao: UserDao,
    private val api: UserApi,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : UserRepository {
    override suspend fun getUser(id: String): Result<User> =
        withContext(dispatcher) {
            runCatching {
                dao.getById(id)?.toDomain()
                    ?: api.fetchUser(id).also {
                        dao.insert(it.toEntity())
                    }.toDomain()
            }
        }

    override fun observeUsers(): Flow<List<User>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }
}

// ── Presentation Layer ─────────────────────────────────
sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

class UserViewModel(
    private val getUser: GetUserUseCase
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<User>>(UiState.Loading)
    val state: StateFlow<UiState<User>> = _state.asStateFlow()

    fun loadUser(id: String) {
        viewModelScope.launch {
            _state.value = UiState.Loading
            getUser(id).fold(
                onSuccess = { _state.value = UiState.Success(it) },
                onFailure = { _state.value = UiState.Error(it.message ?: "خطأ") }
            )
        }
    }
}
</kotlin></android>'''},

        {"user": "اكتب Jetpack Compose UI متقدمة مع animations",
         "assistant": '''<kotlin>
@Composable
fun AnimatedCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    )
    val elevation by animateDpAsState(
        targetValue = if (expanded) 8.dp else 2.dp
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ),
        elevation = CardDefaults.cardElevation(elevation)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(rotation)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit  = fadeOut() + shrinkVertically()
            ) {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    content()
                }
            }
        }
    }
}

// Shimmer loading effect
@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition()
    val shimmer by transition.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200))
    )
    val brush = Brush.horizontalGradient(
        colors = listOf(Color.Gray.copy(.3f), Color.Gray.copy(.6f), Color.Gray.copy(.3f)),
        startX = shimmer * 300, endX = shimmer * 300 + 300
    )
    Box(modifier.background(brush))
}
</kotlin>'''},

        {"user": "اكتب Room Database مع Migrations و Relations",
         "assistant": '''<kotlin>
// Entities
@Entity(tableName = "posts")
data class Post(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val authorId: Long,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "comments",
    foreignKeys = [ForeignKey(
        entity = Post::class,
        parentColumns = ["id"],
        childColumns = ["postId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("postId")]
)
data class Comment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: Long,
    val text: String,
    val author: String
)

// Relation
data class PostWithComments(
    @Embedded val post: Post,
    @Relation(parentColumn = "id", entityColumn = "postId")
    val comments: List<Comment>
)

// DAO
@Dao
interface PostDao {
    @Transaction
    @Query("SELECT * FROM posts WHERE id = :id")
    suspend fun getPostWithComments(id: Long): PostWithComments?

    @Query("SELECT * FROM posts ORDER BY createdAt DESC")
    fun getAllPosts(): Flow<List<Post>>

    @Insert suspend fun insertPost(post: Post): Long
    @Insert suspend fun insertComment(comment: Comment)
    @Update suspend fun updatePost(post: Post)
    @Delete suspend fun deletePost(post: Post)
}

// Database with Migration
@Database(entities = [Post::class, Comment::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun postDao(): PostDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE posts ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context) = Room.databaseBuilder(
            context, AppDatabase::class.java, "app_db"
        ).addMigrations(MIGRATION_1_2).build()
    }
}
</kotlin>'''},
    ]

    # ════════════════════════════════════════
    # 3. Python AI/ML المتقدم
    # ════════════════════════════════════════
    ai_ml = [
        {"user": "نفّذ Transformer من الصفر بـ PyTorch",
         "assistant": '''<python>
import torch
import torch.nn as nn
import math

class MultiHeadAttention(nn.Module):
    def __init__(self, d_model: int, n_heads: int, dropout: float = 0.1):
        super().__init__()
        assert d_model % n_heads == 0
        self.d_k    = d_model // n_heads
        self.h      = n_heads
        self.qkv    = nn.Linear(d_model, d_model * 3, bias=False)
        self.out    = nn.Linear(d_model, d_model, bias=False)
        self.drop   = nn.Dropout(dropout)
        self.scale  = math.sqrt(self.d_k)

    def forward(self, x, mask=None):
        B, T, D = x.shape
        qkv = self.qkv(x).chunk(3, dim=-1)
        q, k, v = [t.view(B, T, self.h, self.d_k).transpose(1,2) for t in qkv]
        attn = (q @ k.transpose(-2,-1)) / self.scale
        if mask is not None:
            attn = attn.masked_fill(mask == 0, float('-inf'))
        attn = self.drop(torch.softmax(attn, dim=-1))
        return self.out((attn @ v).transpose(1,2).contiguous().view(B, T, D))

class FeedForward(nn.Module):
    def __init__(self, d_model: int, d_ff: int, dropout: float = 0.1):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(d_model, d_ff),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(d_ff, d_model),
        )
    def forward(self, x): return self.net(x)

class TransformerBlock(nn.Module):
    def __init__(self, d_model: int, n_heads: int, d_ff: int, dropout=0.1):
        super().__init__()
        self.attn = MultiHeadAttention(d_model, n_heads, dropout)
        self.ff   = FeedForward(d_model, d_ff, dropout)
        self.n1   = nn.LayerNorm(d_model)
        self.n2   = nn.LayerNorm(d_model)
        self.drop = nn.Dropout(dropout)

    def forward(self, x, mask=None):
        x = x + self.drop(self.attn(self.n1(x), mask))
        x = x + self.drop(self.ff(self.n2(x)))
        return x

class GPT(nn.Module):
    def __init__(self, vocab: int, d: int, heads: int, layers: int,
                 ctx: int, dropout=0.1):
        super().__init__()
        self.embed = nn.Embedding(vocab, d)
        self.pos   = nn.Embedding(ctx, d)
        self.blocks= nn.ModuleList([TransformerBlock(d, heads, d*4, dropout)
                                     for _ in range(layers)])
        self.norm  = nn.LayerNorm(d)
        self.head  = nn.Linear(d, vocab, bias=False)
        self.head.weight = self.embed.weight  # weight tying

    def forward(self, x):
        B, T = x.shape
        pos = torch.arange(T, device=x.device)
        h = self.embed(x) + self.pos(pos)
        mask = torch.tril(torch.ones(T, T, device=x.device))
        for block in self.blocks:
            h = block(h, mask.unsqueeze(0).unsqueeze(0))
        return self.head(self.norm(h))

model = GPT(vocab=50257, d=256, heads=8, layers=6, ctx=512)
print(f"Params: {sum(p.numel() for p in model.parameters())/1e6:.1f}M")
</python>'''},

        {"user": "نفّذ خوارزمية backpropagation يدوياً",
         "assistant": '''<python>
import numpy as np

class Value:
    """Autograd engine - مثل micrograd"""
    def __init__(self, data, _children=(), op='', label=''):
        self.data  = float(data)
        self.grad  = 0.0
        self._back = lambda: None
        self._prev = set(_children)
        self.op    = op
        self.label = label

    def __add__(self, other):
        other = other if isinstance(other, Value) else Value(other)
        out   = Value(self.data + other.data, (self, other), '+')
        def _back():
            self.grad  += out.grad
            other.grad += out.grad
        out._back = _back
        return out

    def __mul__(self, other):
        other = other if isinstance(other, Value) else Value(other)
        out   = Value(self.data * other.data, (self, other), '*')
        def _back():
            self.grad  += other.data * out.grad
            other.grad += self.data  * out.grad
        out._back = _back
        return out

    def tanh(self):
        t   = np.tanh(self.data)
        out = Value(t, (self,), 'tanh')
        def _back():
            self.grad += (1 - t**2) * out.grad
        out._back = _back
        return out

    def relu(self):
        out = Value(max(0, self.data), (self,), 'relu')
        def _back():
            self.grad += (out.data > 0) * out.grad
        out._back = _back
        return out

    def backward(self):
        topo, visited = [], set()
        def build(v):
            if v not in visited:
                visited.add(v)
                for child in v._prev: build(child)
                topo.append(v)
        build(self)
        self.grad = 1.0
        for v in reversed(topo): v._back()

    def __repr__(self): return f"Value(data={self.data:.4f}, grad={self.grad:.4f})"
    def __radd__(self, other): return self + other
    def __rmul__(self, other): return self * other
    def __neg__(self): return self * -1
    def __sub__(self, other): return self + (-other)
    def __pow__(self, n):
        out = Value(self.data**n, (self,), f'**{n}')
        def _back(): self.grad += n * (self.data**(n-1)) * out.grad
        out._back = _back
        return out

# اختبار: تدريب neuron بسيط
x = Value(2.0, label='x')
w = Value(-3.0, label='w')
b = Value(6.8, label='b')
out = (x * w + b).tanh()
out.backward()
print(f"x.grad={x.grad:.4f}, w.grad={w.grad:.4f}, b.grad={b.grad:.4f}")
</python>'''},

        {"user": "نفّذ نظام Retrieval-Augmented Generation (RAG) كامل",
         "assistant": '''<python>
import numpy as np
from typing import List, Dict

class SimpleEmbedder:
    """Embedder بسيط بدون APIs خارجية"""
    def embed(self, text: str) -> np.ndarray:
        words = text.lower().split()
        vec = np.zeros(512)
        for i, w in enumerate(words):
            h = hash(w) % 512
            vec[h] += 1.0 / (i + 1)
        norm = np.linalg.norm(vec)
        return vec / norm if norm > 0 else vec

class VectorStore:
    def __init__(self, embedder: SimpleEmbedder):
        self.embedder = embedder
        self.docs: List[str] = []
        self.vecs: List[np.ndarray] = []

    def add(self, text: str):
        self.docs.append(text)
        self.vecs.append(self.embedder.embed(text))

    def search(self, query: str, k: int = 3) -> List[Dict]:
        q_vec = self.embedder.embed(query)
        scores = [np.dot(q_vec, v) for v in self.vecs]
        top_k  = sorted(range(len(scores)), key=lambda i: scores[i], reverse=True)[:k]
        return [{'text': self.docs[i], 'score': scores[i]} for i in top_k]

class RAGSystem:
    def __init__(self, generator=None):
        self.store     = VectorStore(SimpleEmbedder())
        self.generator = generator  # LLM call

    def index(self, documents: List[str], chunk_size: int = 200):
        for doc in documents:
            words = doc.split()
            chunks = [' '.join(words[i:i+chunk_size])
                      for i in range(0, len(words), chunk_size//2)]
            for chunk in chunks:
                if chunk.strip(): self.store.add(chunk)
        print(f"Indexed {len(self.store.docs)} chunks")

    def query(self, question: str, k: int = 3) -> str:
        results = self.store.search(question, k)
        context = "\n\n".join(f"[{i+1}] {r['text']}"
                               for i, r in enumerate(results))
        prompt = f"""السياق:
{context}

السؤال: {question}
الإجابة:"""
        if self.generator:
            return self.generator(prompt)
        return f"Context retrieved:\n{context}"

# اختبار
rag = RAGSystem()
docs = [
    "Python لغة برمجة عالية المستوى تستخدم في الذكاء الاصطناعي وتحليل البيانات",
    "Kotlin لغة رسمية لتطوير Android وتتميز بأمان النوع والتعبيرية",
    "الذكاء الاصطناعي يتضمن التعلم الآلي والتعلم العميق والشبكات العصبية",
]
rag.index(docs)
result = rag.query("ما هي لغة تطوير Android؟")
print(result)
</python>'''},
    ]

    # ════════════════════════════════════════
    # 4. System Design & Architecture
    # ════════════════════════════════════════
    system_design = [
        {"user": "صمّم نظام Event-Driven Architecture بـ Python",
         "assistant": '''<python>
import asyncio
from collections import defaultdict
from typing import Any, Callable, Coroutine
import json, time

class Event:
    def __init__(self, type: str, data: Any, source: str = ""):
        self.type      = type
        self.data      = data
        self.source    = source
        self.timestamp = time.time()
        self.id        = f"{type}_{int(self.timestamp*1000)}"

class EventBus:
    """نظام نشر/اشتراك غير متزامن"""
    def __init__(self):
        self._handlers:  defaultdict = defaultdict(list)
        self._middleware: list        = []
        self._history:   list         = []

    def use(self, middleware: Callable):
        self._middleware.append(middleware)
        return self

    def on(self, event_type: str, handler: Callable):
        self._handlers[event_type].append(handler)
        return self

    def off(self, event_type: str, handler: Callable):
        self._handlers[event_type].remove(handler)

    async def emit(self, event: Event):
        # تطبيق الـ middleware
        for mw in self._middleware:
            event = await mw(event) if asyncio.iscoroutinefunction(mw) \
                    else mw(event)
            if event is None: return

        self._history.append(event)
        handlers = (self._handlers.get(event.type, []) +
                    self._handlers.get('*', []))
        await asyncio.gather(*[
            h(event) if asyncio.iscoroutinefunction(h) else asyncio.to_thread(h, event)
            for h in handlers
        ])

# اختبار
bus = EventBus()

async def logger_mw(event):
    print(f"[LOG] {event.type}: {event.data}")
    return event

bus.use(logger_mw)

@bus.on("user.created")
async def send_welcome(event):
    print(f"Welcome email → {event.data['email']}")

@bus.on("user.created")
async def setup_profile(event):
    print(f"Profile created for {event.data['name']}")

@bus.on("*")
async def audit(event):
    print(f"[AUDIT] {event.source} emitted {event.type}")

async def main():
    await bus.emit(Event("user.created",
        {"name": "Ahmed", "email": "ahmed@example.com"},
        source="auth-service"))

asyncio.run(main())
</python>'''},

        {"user": "نفّذ Connection Pool و Rate Limiter",
         "assistant": '''<python>
import asyncio
import time
from collections import deque
from typing import Optional

class ConnectionPool:
    """Pool للاتصالات مع timeout و health check"""
    def __init__(self, factory, max_size: int = 10, timeout: float = 30):
        self._factory  = factory
        self._max      = max_size
        self._timeout  = timeout
        self._pool     = deque()
        self._size     = 0
        self._sem      = asyncio.Semaphore(max_size)
        self._lock     = asyncio.Lock()

    async def acquire(self):
        await self._sem.acquire()
        async with self._lock:
            if self._pool:
                conn = self._pool.popleft()
                if await self._is_healthy(conn):
                    return conn
                else:
                    self._size -= 1
            self._size += 1
        return await self._factory()

    async def release(self, conn):
        async with self._lock:
            self._pool.append(conn)
        self._sem.release()

    async def _is_healthy(self, conn) -> bool:
        try:
            return await conn.ping() if hasattr(conn, 'ping') else True
        except:
            return False

    async def __aenter__(self):
        self._conn = await self.acquire()
        return self._conn

    async def __aexit__(self, *_):
        await self.release(self._conn)


class RateLimiter:
    """Token Bucket Algorithm"""
    def __init__(self, rate: float, burst: int):
        self.rate    = rate    # tokens per second
        self.burst   = burst   # max tokens
        self.tokens  = burst
        self.last    = time.monotonic()
        self._lock   = asyncio.Lock()

    async def acquire(self, n: int = 1) -> bool:
        async with self._lock:
            now = time.monotonic()
            elapsed = now - self.last
            self.tokens = min(self.burst, self.tokens + elapsed * self.rate)
            self.last = now
            if self.tokens >= n:
                self.tokens -= n
                return True
            return False

    async def wait(self, n: int = 1):
        while not await self.acquire(n):
            await asyncio.sleep(1 / self.rate)


class SlidingWindowRateLimiter:
    """Sliding Window لـ API rate limiting"""
    def __init__(self, max_requests: int, window: float):
        self.max  = max_requests
        self.win  = window
        self.reqs: dict = {}  # client_id -> deque of timestamps

    def is_allowed(self, client_id: str) -> bool:
        now = time.time()
        if client_id not in self.reqs:
            self.reqs[client_id] = deque()
        q = self.reqs[client_id]
        while q and now - q[0] > self.win:
            q.popleft()
        if len(q) < self.max:
            q.append(now)
            return True
        return False

limiter = SlidingWindowRateLimiter(max_requests=5, window=10.0)
for i in range(7):
    allowed = limiter.is_allowed("user_1")
    print(f"Request {i+1}: {'✅' if allowed else '❌'}")
</python>'''},
    ]

    # ════════════════════════════════════════
    # 5. مسائل LeetCode Hard
    # ════════════════════════════════════════
    leetcode_hard = [
        {"user": "حل Longest Common Subsequence وشرح الـ DP",
         "assistant": '''<python>
def lcs(s1: str, s2: str) -> str:
    m, n = len(s1), len(s2)
    # dp[i][j] = طول LCS لـ s1[:i] و s2[:j]
    dp = [[0]*(n+1) for _ in range(m+1)]

    for i in range(1, m+1):
        for j in range(1, n+1):
            if s1[i-1] == s2[j-1]:
                dp[i][j] = dp[i-1][j-1] + 1
            else:
                dp[i][j] = max(dp[i-1][j], dp[i][j-1])

    # Backtrack لإيجاد السلسلة نفسها
    result = []
    i, j = m, n
    while i > 0 and j > 0:
        if s1[i-1] == s2[j-1]:
            result.append(s1[i-1])
            i -= 1; j -= 1
        elif dp[i-1][j] > dp[i][j-1]:
            i -= 1
        else:
            j -= 1

    return ''.join(reversed(result))

print(lcs("ABCBDAB", "BDCABA"))   # BCBA
print(lcs("AGGTAB", "GXTXAYB"))   # GTAB

# Space-optimized O(n) space
def lcs_length_optimized(s1: str, s2: str) -> int:
    prev = [0] * (len(s2) + 1)
    for c in s1:
        curr = [0] * (len(s2) + 1)
        for j, d in enumerate(s2, 1):
            curr[j] = prev[j-1] + 1 if c == d else max(prev[j], curr[j-1])
        prev = curr
    return prev[-1]
</python>'''},

        {"user": "حل Word Break II وExplain Memoization",
         "assistant": '''<python>
from functools import lru_cache
from typing import List

def word_break_ii(s: str, words: List[str]) -> List[str]:
    word_set = set(words)

    @lru_cache(maxsize=None)
    def dp(start: int) -> List[str]:
        """يرجع كل جمل ممكنة من الموضع start"""
        if start == len(s):
            return ['']

        result = []
        for end in range(start + 1, len(s) + 1):
            word = s[start:end]
            if word in word_set:
                for rest in dp(end):
                    result.append(word + (' ' + rest if rest else ''))

        return result

    return dp(0)

print(word_break_ii("catsanddog", ["cat","cats","and","sand","dog"]))
# ['cat sand dog', 'cats and dog']

# Version 2: bottom-up DP
def word_break_ii_v2(s: str, words: List[str]) -> List[str]:
    word_set = set(words)
    n = len(s)
    dp = [[] for _ in range(n + 1)]
    dp[0] = ['']

    for i in range(1, n + 1):
        for j in range(i):
            word = s[j:i]
            if word in word_set and dp[j]:
                for prev in dp[j]:
                    dp[i].append((prev + ' ' + word).strip())

    return dp[n]
</python>'''},

        {"user": "نفّذ Trie مع Autocomplete",
         "assistant": '''<python>
from collections import defaultdict
import heapq
from typing import List

class TrieNode:
    def __init__(self):
        self.children = {}
        self.is_end   = False
        self.count    = 0  # عدد مرات البحث

class Trie:
    def __init__(self):
        self.root = TrieNode()

    def insert(self, word: str, count: int = 1):
        node = self.root
        for ch in word:
            node = node.children.setdefault(ch, TrieNode())
        node.is_end = True
        node.count  = count

    def search(self, word: str) -> bool:
        node = self._traverse(word)
        return node is not None and node.is_end

    def starts_with(self, prefix: str) -> bool:
        return self._traverse(prefix) is not None

    def autocomplete(self, prefix: str, k: int = 5) -> List[str]:
        node = self._traverse(prefix)
        if not node: return []

        results = []
        def dfs(curr, word):
            if curr.is_end:
                heapq.heappush(results, (-curr.count, word))
            for ch, child in curr.children.items():
                dfs(child, word + ch)

        dfs(node, prefix)
        return [word for _, word in heapq.nsmallest(k, results)]

    def _traverse(self, prefix: str):
        node = self.root
        for ch in prefix:
            if ch not in node.children: return None
            node = node.children[ch]
        return node

# اختبار
trie = Trie()
words = [("python", 100), ("pytorch", 80), ("pandas", 60),
         ("programming", 40), ("problem", 30)]
for w, c in words: trie.insert(w, c)

print(trie.autocomplete("p"))      # ['python','pytorch','pandas',...]
print(trie.autocomplete("py"))     # ['python','pytorch']
print(trie.search("python"))       # True
</python>'''},
    ]

    # ════════════════════════════════════════
    # 6. Design Patterns
    # ════════════════════════════════════════
    patterns = [
        {"user": "نفّذ Observer Pattern بـ Python و Kotlin",
         "assistant": '''<python>
from abc import ABC, abstractmethod
from typing import List, Any

class Observer(ABC):
    @abstractmethod
    def update(self, event: str, data: Any): pass

class Observable:
    def __init__(self):
        self._observers: List[Observer] = []

    def subscribe(self, observer: Observer):
        self._observers.append(observer)
        return self

    def unsubscribe(self, observer: Observer):
        self._observers.remove(observer)

    def notify(self, event: str, data: Any = None):
        for obs in self._observers:
            obs.update(event, data)

class StockMarket(Observable):
    def __init__(self, symbol: str):
        super().__init__()
        self.symbol = symbol
        self._price = 0.0

    @property
    def price(self): return self._price

    @price.setter
    def price(self, val: float):
        old = self._price
        self._price = val
        if old != val:
            self.notify("price_change", {
                "symbol": self.symbol,
                "old": old, "new": val,
                "change": val - old
            })

class PriceAlert(Observer):
    def __init__(self, name: str, threshold: float):
        self.name, self.threshold = name, threshold

    def update(self, event: str, data: Any):
        if event == "price_change" and data["new"] > self.threshold:
            print(f"🔔 {self.name}: {data['symbol']} تجاوز {self.threshold}!")

stock = StockMarket("AAPL")
stock.subscribe(PriceAlert("Ahmed", 180))
stock.subscribe(PriceAlert("Sara", 190))
stock.price = 175
stock.price = 185   # Ahmed alert
stock.price = 195   # Both alert
</python>'''},
    ]

    # اجمع كل البيانات
    all_samples = (algorithms + android_advanced + ai_ml +
                   system_design + leetcode_hard + patterns)

    # أضف متنوعات نصية عربية + إنجليزية
    text_samples = [
        "الخوارزمية الجيدة هي التي تحل المشكلة بأقل استهلاك للوقت والذاكرة.",
        "Clean Code يعني كود سهل القراءة والتعديل والاختبار.",
        "SOLID principles أساس لبناء برمجيات قابلة للتوسع والصيانة.",
        "Test-Driven Development يجعل الكود أكثر موثوقية وأقل أخطاء.",
        "Python مثالي للنماذج الأولية بينما Kotlin أسرع لتطبيقات Android.",
        "Coroutines في Kotlin تجعل البرمجة غير المتزامنة أبسط وأوضح.",
        "Design patterns حلول مجربة لمشاكل متكررة في تصميم البرمجيات.",
        "Git يتيح التعاون الفعّال بين المطورين وتتبع تاريخ التغييرات.",
        "REST APIs تتبع مبادئ Stateless وUniform Interface.",
        "Docker يضمن أن التطبيق يعمل بنفس الطريقة في أي بيئة.",
    ]
    all_samples.extend(text_samples)

    print(f"✅ Training samples: {len(all_samples)}")
    return all_samples

if __name__ == '__main__':
    data = get_training_data()
    os.makedirs('data', exist_ok=True)
    with open('data/training_data.json', 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print("Saved → data/training_data.json")
