"""
Omega Data v2 - بيانات تدريب غنية ومتنوعة
"""
import os, json


def get_training_data() -> list:
    data = []

    # ── Arabic NLP + Reasoning ─────────────────────────────────────────
    ar_reasoning = [
        {"user": "ما هو الذكاء الاصطناعي؟",
         "assistant": "<think>سؤال تعريفي أساسي</think>\nالذكاء الاصطناعي هو مجال علم الحاسوب الذي يهدف إلى بناء أنظمة قادرة على تنفيذ مهام تتطلب ذكاءً بشرياً كالتعلم والاستنتاج والتخطيط وفهم اللغة الطبيعية."},
        {"user": "كيف تعمل الشبكات العصبية؟",
         "assistant": "<think>شرح مبسط مع تفاصيل تقنية</think>\nالشبكات العصبية تتكون من طبقات من الخلايا الاصطناعية (Neurons). كل خلية تحسب: output = activation(Σ wᵢxᵢ + b). تتعلم عبر backpropagation: تحسب الخطأ وتحدّث الأوزان بالتدريج. طبقات متعددة تتعلم تمثيلات متزايدة التجريد."},
        {"user": "ما الفرق بين التعلم الخاضع للإشراف وغير الخاضع له؟",
         "assistant": "التعلم الخاضع للإشراف (Supervised): البيانات مصنّفة مسبقاً. النموذج يتعلم X→Y. مثال: تصنيف الصور.\nالتعلم غير الخاضع (Unsupervised): بيانات بدون تصنيف. النموذج يكتشف أنماطاً. مثال: التجميع، تقليل الأبعاد."},
        {"user": "اشرح لي Transformer بالتفصيل",
         "assistant": "<think>شرح Transformer من الأساس</think>\nTransformer هي معمارية تعتمد على Attention بالكامل:\n\n1. **Embedding**: تحويل الكلمات لمتجهات\n2. **Positional Encoding**: إضافة معلومات الموضع\n3. **Self-Attention**: كل كلمة 'تنظر' لكل الكلمات الأخرى\n   - Q (Query), K (Key), V (Value)\n   - Attention(Q,K,V) = softmax(QKᵀ/√d)·V\n4. **Feed Forward**: تحويل غير خطي\n5. **Layer Norm + Residuals**: استقرار التدريب\n\nالقوة: معالجة متوازية + سياق غير محدود نظرياً."},
        {"user": "كيف أبني تطبيق Android من الصفر؟",
         "assistant": "<android><think>خطوات بناء تطبيق Android</think>\n1. تثبيت Android Studio\n2. إنشاء مشروع جديد (Empty Activity)\n3. اختيار Kotlin (الأحدث والأفضل)\n4. هيكل المشروع:\n   - app/src/main/java: كود Kotlin\n   - app/src/main/res: موارد (layouts, strings)\n   - AndroidManifest.xml: إعدادات التطبيق\n5. بناء الـ UI بـ Jetpack Compose أو XML\n6. إضافة منطق العمل في ViewModel\n7. التجميع والتوقيع والنشر</android>"},
    ]

    # ── Code Generation ────────────────────────────────────────────────
    code_samples = [
        {"user": "اكتب خوارزمية ترتيب سريع QuickSort في Python",
         "assistant": "<python>\ndef quicksort(arr: list) -> list:\n    if len(arr) <= 1:\n        return arr\n    pivot = arr[len(arr) // 2]\n    left   = [x for x in arr if x < pivot]\n    middle = [x for x in arr if x == pivot]\n    right  = [x for x in arr if x > pivot]\n    return quicksort(left) + middle + quicksort(right)\n\n# اختبار\nnums = [3, 6, 8, 10, 1, 2, 1]\nprint(quicksort(nums))  # [1, 1, 2, 3, 6, 8, 10]\n</python>"},
        {"user": "اكتب Activity بسيطة في Kotlin مع ViewModel",
         "assistant": "<kotlin>\n// ViewModel\nclass MainViewModel : ViewModel() {\n    private val _count = MutableLiveData(0)\n    val count: LiveData<Int> = _count\n    \n    fun increment() { _count.value = (_count.value ?: 0) + 1 }\n}\n\n// Activity\nclass MainActivity : AppCompatActivity() {\n    private val vm: MainViewModel by viewModels()\n    \n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        setContentView(R.layout.activity_main)\n        \n        vm.count.observe(this) { count ->\n            textView.text = \"العداد: $count\"\n        }\n        button.setOnClickListener { vm.increment() }\n    }\n}\n</kotlin>"},
        {"user": "اكتب نموذج شبكة عصبية بسيط بـ PyTorch",
         "assistant": "<python>\nimport torch\nimport torch.nn as nn\nimport torch.optim as optim\n\nclass SimpleNet(nn.Module):\n    def __init__(self, in_dim, hidden, out_dim):\n        super().__init__()\n        self.net = nn.Sequential(\n            nn.Linear(in_dim, hidden),\n            nn.ReLU(),\n            nn.Dropout(0.2),\n            nn.Linear(hidden, hidden),\n            nn.ReLU(),\n            nn.Linear(hidden, out_dim)\n        )\n    \n    def forward(self, x):\n        return self.net(x)\n\n# التدريب\nmodel = SimpleNet(784, 256, 10)\noptimizer = optim.AdamW(model.parameters(), lr=3e-4)\ncriterion = nn.CrossEntropyLoss()\n\nfor epoch in range(10):\n    optimizer.zero_grad()\n    out = model(X_train)\n    loss = criterion(out, y_train)\n    loss.backward()\n    optimizer.step()\n    print(f'Epoch {epoch}: loss={loss.item():.4f}')\n</python>"},
        {"user": "كيف أبني REST API بـ FastAPI؟",
         "assistant": "<python>\nfrom fastapi import FastAPI, HTTPException\nfrom pydantic import BaseModel\nfrom typing import List, Optional\n\napp = FastAPI(title='Omega API')\n\nclass Item(BaseModel):\n    name: str\n    price: float\n    description: Optional[str] = None\n\nitems_db = {}\n\n@app.get('/')\ndef root():\n    return {'message': 'Omega AI API يعمل!'}\n\n@app.post('/items/{item_id}')\ndef create_item(item_id: int, item: Item):\n    if item_id in items_db:\n        raise HTTPException(400, 'العنصر موجود')\n    items_db[item_id] = item\n    return {'id': item_id, **item.dict()}\n\n@app.get('/items/')\ndef list_items() -> List[dict]:\n    return [{'id': k, **v.dict()} for k, v in items_db.items()]\n\n# uvicorn main:app --reload\n</python>"},
        {"user": "اكتب Compose UI بسيطة لتطبيق مهام",
         "assistant": "<kotlin>\n@Composable\nfun TaskApp() {\n    var tasks by remember { mutableStateListOf<String>() }\n    var input by remember { mutableStateOf('') }\n    \n    Column(\n        modifier = Modifier\n            .fillMaxSize()\n            .padding(16.dp)\n    ) {\n        Text('مهامي', style = MaterialTheme.typography.headlineMedium)\n        Spacer(Modifier.height(16.dp))\n        \n        Row {\n            OutlinedTextField(\n                value = input,\n                onValueChange = { input = it },\n                label = { Text('مهمة جديدة') },\n                modifier = Modifier.weight(1f)\n            )\n            Spacer(Modifier.width(8.dp))\n            Button(onClick = {\n                if (input.isNotBlank()) {\n                    tasks.add(input)\n                    input = ''\n                }\n            }) { Text('إضافة') }\n        }\n        \n        LazyColumn {\n            items(tasks) { task ->\n                ListItem(\n                    headlineContent = { Text(task) },\n                    trailingContent = {\n                        IconButton(onClick = { tasks.remove(task) }) {\n                            Icon(Icons.Default.Delete, null)\n                        }\n                    }\n                )\n            }\n        }\n    }\n}\n</kotlin>"},
    ]

    # ── Algorithms & Problem Solving ────────────────────────────────────
    algorithms = [
        {"user": "حل مشكلة Two Sum",
         "assistant": "<python>\ndef two_sum(nums: list, target: int) -> list:\n    \"\"\"\n    أجد زوجين يجمعهما target\n    الحل: HashMap بـ O(n) بدل O(n²)\n    \"\"\"\n    seen = {}  # value -> index\n    for i, num in enumerate(nums):\n        complement = target - num\n        if complement in seen:\n            return [seen[complement], i]\n        seen[num] = i\n    return []\n\n# اختبار\nprint(two_sum([2,7,11,15], 9))  # [0,1]\nprint(two_sum([3,2,4], 6))      # [1,2]\n</python>"},
        {"user": "اشرح Dynamic Programming وحل مسألة العملات",
         "assistant": "<think>DP = تقسيم المشكلة لمشاكل أصغر + حفظ النتائج</think>\n<python>\ndef coin_change(coins: list, amount: int) -> int:\n    \"\"\"\n    أقل عدد من العملات للوصول لـ amount\n    DP bottom-up\n    \"\"\"\n    dp = [float('inf')] * (amount + 1)\n    dp[0] = 0  # 0 عملات للوصول لـ 0\n    \n    for a in range(1, amount + 1):\n        for coin in coins:\n            if coin <= a:\n                dp[a] = min(dp[a], dp[a - coin] + 1)\n    \n    return dp[amount] if dp[amount] != float('inf') else -1\n\nprint(coin_change([1,5,10,25], 36))  # 3: 25+10+1\n</python>"},
    ]

    # ── Video/Media Processing ─────────────────────────────────────────
    video_samples = [
        {"user": "كيف أعالج الفيديو بـ Python؟",
         "assistant": "<video><python>\nimport cv2\nimport numpy as np\n\ndef process_video(input_path: str, output_path: str):\n    cap = cv2.VideoCapture(input_path)\n    fps = int(cap.get(cv2.CAP_PROP_FPS))\n    w   = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))\n    h   = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))\n    \n    out = cv2.VideoWriter(\n        output_path,\n        cv2.VideoWriter_fourcc(*'mp4v'),\n        fps, (w, h)\n    )\n    \n    while cap.isOpened():\n        ret, frame = cap.read()\n        if not ret: break\n        \n        # تحويل للتدرج الرمادي ثم معالجة\n        gray  = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)\n        edges = cv2.Canny(gray, 100, 200)\n        colored = cv2.cvtColor(edges, cv2.COLOR_GRAY2BGR)\n        \n        out.write(colored)\n    \n    cap.release()\n    out.release()\n    print(f'تم الحفظ: {output_path}')\n\nprocess_video('input.mp4', 'output.mp4')\n</python></video>"},
    ]

    # ── Build full corpus ──────────────────────────────────────────────
    for item in ar_reasoning + code_samples + algorithms + video_samples:
        data.append(item)

    # Plain text augmentation
    plain = [
        "الذكاء الاصطناعي يتطور بسرعة كبيرة في العقد الأخير.",
        "Python من أكثر لغات البرمجة شعبية في العالم.",
        "Android يمتلك أكثر من 3 مليار جهاز نشط حول العالم.",
        "التعلم العميق Deep Learning ثورة في مجال الذكاء الاصطناعي.",
        "Transformer غيّرت عالم معالجة اللغة الطبيعية منذ 2017.",
        "Kotlin الآن اللغة الرسمية الأولى لتطوير Android.",
        "GitHub يستضيف أكثر من 100 مليون مستودع كود.",
        "Docker يبسّط عملية نشر التطبيقات في الحاويات.",
        "Git نظام التحكم في الإصدارات الأكثر استخداماً في العالم.",
        "البرمجة مهارة أساسية في القرن الحادي والعشرين.",
    ]
    data.extend(plain)

    print(f"✅ Training data: {len(data)} samples")
    return data


if __name__ == '__main__':
    samples = get_training_data()
    os.makedirs('data', exist_ok=True)
    with open('data/training_data.json', 'w', encoding='utf-8') as f:
        json.dump(samples, f, ensure_ascii=False, indent=2)
    print("Saved → data/training_data.json")
