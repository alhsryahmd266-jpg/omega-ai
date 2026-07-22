import os, sys, json, time, re, sqlite3, subprocess, base64, glob
from pathlib import Path

WORK_DIR  = "/kaggle/working"
OUT_DIR   = WORK_DIR + "/checkpoints"
os.makedirs(OUT_DIR, exist_ok=True)

# ══ SETUP ════════════════════════════════════════════════════
REPO_URL = "https://github.com/alhsryahmd266-jpg/omega-ai"
REPO_DIR = WORK_DIR + "/omega-ai"

if not os.path.exists(REPO_DIR):
    subprocess.run(["git","clone","--depth","1",REPO_URL,REPO_DIR],check=True)
sys.path.insert(0, REPO_DIR)

# ══ 1. GPU DETECT ════════════════════════════════════════════
def setup_hardware():
    import torch
    if not torch.cuda.is_available():
        print("CPU mode")
        return False, 0
    n = torch.cuda.device_count()
    cap = torch.cuda.get_device_capability(0)
    name = torch.cuda.get_device_name(0)
    vram = torch.cuda.get_device_properties(0).total_memory/1e9
    total = n*vram
    print(f"✅ {n}x {name} sm_{cap[0]*10} | {total:.0f}GB total VRAM")
    if n >= 2: print("⚡ T4x2: 30GB VRAM يكفي DeepSeek-14B كامل على GPU!")
    torch.backends.cudnn.benchmark = True
    torch.backends.cuda.matmul.allow_tf32 = True
    return cap[0] >= 7, n

USE_GPU, N_GPUS = setup_hardware()

# ══ 2. FIND MODEL ════════════════════════════════════════════
def find_model():
    for p in ["/kaggle/input/**/*.gguf","/kaggle/input/**/*.GGUF"]:
        for f in glob.glob(p, recursive=True):
            if os.path.getsize(f)/1e9 > 3:
                print(f"✅ {os.path.basename(f)} ({os.path.getsize(f)/1e9:.1f}GB)")
                return f
    print("❌ لم يُعثر على GGUF > 3GB")
    return None

# ══ 3. INSTALL LLAMA-CPP ═════════════════════════════════════
def install_llama():
    if USE_GPU:
        for whl in ["cu121","cu118"]:
            r=subprocess.run(["pip","install","-q","--upgrade","llama-cpp-python",
                              "--extra-index-url",
                              f"https://abetlen.github.io/llama-cpp-python/whl/{whl}"],
                             capture_output=True, text=True)
            if r.returncode==0:
                print(f"✅ llama-cpp+{whl}")
                return True
    r=subprocess.run(["pip","install","-q","llama-cpp-python"],
                     capture_output=True, text=True)
    return r.returncode==0

# ══ 4. SMART TOOLS ═══════════════════════════════════════════
class Tools:
    def __init__(self):
        import math, statistics
        self._math = math
        self._stats = statistics
        self.db = sqlite3.connect(OUT_DIR+"/memory.db")
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS mem(k TEXT PRIMARY KEY, v TEXT, ts DATETIME DEFAULT CURRENT_TIMESTAMP)")
        self.db.commit()
        print("✅ SmartTools جاهز")

    def calc(self, expr):
        try:
            ns = {"math":self._math,"statistics":self._stats,
                  "abs":abs,"round":round,"sum":sum,"min":min,"max":max,
                  "sqrt":self._math.sqrt,"pi":self._math.pi,"e":self._math.e,
                  "__builtins__":{}}
            return str(eval(expr.strip(), ns))
        except Exception as ex:
            return f"خطأ: {ex}"

    def run_code(self, code):
        try:
            r=subprocess.run(["python3","-c",code],
                             capture_output=True,text=True,timeout=15)
            return r.stdout.strip() or r.stderr.strip() or "OK (no output)"
        except subprocess.TimeoutExpired:
            return "Timeout (15s)"

    def analyze_image(self, path, question="صف الصورة"):
        try:
            from PIL import Image
            img=Image.open(path)
            w,h=img.size
            info=[f"الحجم: {w}x{h}", f"الوضع: {img.mode}",
                  f"الحجم: {os.path.getsize(path)/1024:.1f}KB"]
            if img.mode in ("RGB","RGBA"):
                sm=img.resize((50,50)).convert("RGB")
                px=list(sm.getdata())
                r=sum(p[0] for p in px)//len(px)
                g=sum(p[1] for p in px)//len(px)
                b=sum(p[2] for p in px)//len(px)
                color=("دافئة/أحمر" if r>g and r>b else
                       "خضراء" if g>r and g>b else
                       "باردة/أزرق" if b>r and b>g else "محايدة")
                info.append(f"الألوان: {color}")
            return " | ".join(info)
        except Exception as ex:
            return f"خطأ: {ex}"

    def remember(self, key, value):
        self.db.execute("INSERT OR REPLACE INTO mem(k,v) VALUES(?,?)",(key,value))
        self.db.commit()
        return f"✅ حُفظ: {key}"

    def recall(self, query):
        rows=self.db.execute(
            "SELECT k,v FROM mem WHERE k LIKE ? OR v LIKE ? LIMIT 5",
            (f"%{query}%",f"%{query}%")).fetchall()
        return "\n".join(f"[{k}]: {v}" for k,v in rows) if rows else "لا توجد"

    def search(self, query):
        try:
            import urllib.request, urllib.parse
            url="https://api.duckduckgo.com/?q="+urllib.parse.quote(query)+"&format=json"
            with urllib.request.urlopen(url, timeout=8) as r:
                d=json.loads(r.read())
            return d.get("AbstractText") or (d.get("RelatedTopics",[""])[0] or {}).get("Text","لا نتائج")[:400]
        except:
            return "البحث غير متاح"

TOOL_RE = {
    "CALC":        r"\[CALC:\s*(.+?)\]",
    "CODE":        r"\[CODE:\s*(.+?)\]",
    "IMAGE":       r"\[IMAGE:\s*(.+?)\|(.+?)\]",
    "SAVE":        r"\[MEMORY_SAVE:\s*(.+?)\|(.+?)\]",
    "GET":         r"\[MEMORY_GET:\s*(.+?)\]",
    "SEARCH":      r"\[SEARCH:\s*(.+?)\]",
}

def execute_tools(text: str, tools: Tools) -> str:
    result = text
    for tool, pattern in TOOL_RE.items():
        for m in re.finditer(pattern, result, re.DOTALL):
            raw = m.group(0)
            try:
                if tool=="CALC":   out=tools.calc(m.group(1))
                elif tool=="CODE": out=tools.run_code(m.group(1))
                elif tool=="IMAGE":out=tools.analyze_image(m.group(1).strip(),m.group(2).strip())
                elif tool=="SAVE": out=tools.remember(m.group(1).strip(),m.group(2).strip())
                elif tool=="GET":  out=tools.recall(m.group(1).strip())
                elif tool=="SEARCH":out=tools.search(m.group(1).strip())
                else: continue
                result=result.replace(raw,f"\n→ نتيجة {tool}: {out}\n",1)
                print(f"  🔧 {tool}: {out[:80]}")
            except Exception as ex:
                result=result.replace(raw,f"\n→ خطأ {tool}: {ex}\n",1)
    return result

# ══ 5. ARABIC LORA ═══════════════════════════════════════════
ARABIC_DATA = [
    {"ins":"ما هو الذكاء الاصطناعي؟",
     "res":"الذكاء الاصطناعي علم يُمكّن الحواسيب من محاكاة القدرات البشرية كالتعلم والاستنتاج. يُستخدم في التعرف على الصور والكلام والترجمة وغيرها."},
    {"ins":"احسب مساحة دائرة نصف قطرها 5",
     "res":"[CALC: math.pi * 5**2]\nمساحة الدائرة = 78.54 سم²"},
    {"ins":"اكتب كود أعداد فيبوناتشي حتى 100",
     "res":"[CODE: a,b=0,1\nwhile a<=100: print(a,end=' '); a,b=b,a+b]"},
    {"ins":"ما الفرق بين Python و JavaScript؟",
     "res":"Python: ذكاء اصطناعي وعلوم وسكريبتات. JavaScript: ويب ومتصفح. كلاهما ممتاز بحسب الهدف."},
    {"ins":"احسب 15% من 2500",
     "res":"[CALC: 2500 * 0.15]\nالناتج = 375"},
    {"ins":"اشرح Neural Networks بالعربي",
     "res":"الشبكات العصبية نماذج مستوحاة من الدماغ، تتكون من طبقات تتعلم الأنماط تلقائياً من البيانات."},
    {"ins":"كيف أتعلم البرمجة؟",
     "res":"1. اختر Python 2. تعلم الأساسيات 3. حل مسائل يومياً 4. ابنِ مشاريع حقيقية 5. استمر"},
    {"ins":"صف الكوانتم كومبيوتينج",
     "res":"ثورة تقنية تستخدم الكيوبت (0 و1 في آنٍ واحد) مما يجعلها أسرع بشكل هائل في مسائل بعينها."},
]

def lora_arabic(use_gpu):
    if not use_gpu:
        print("⚠️  تخطي LoRA — لا GPU")
        return None
    print("\n📦 تثبيت Unsloth + PEFT...")
    r=subprocess.run(
        ["pip","install","-q","transformers","peft","trl","accelerate",
         "bitsandbytes","datasets"],
        capture_output=True, text=True)
    try:
        import torch, datasets as ds
        from transformers import AutoTokenizer,AutoModelForCausalLM,TrainingArguments
        from peft import LoraConfig,get_peft_model,TaskType
        from trl import SFTTrainer

        MODEL_ID = "Qwen/Qwen2.5-1.5B-Instruct"
        print(f"📥 {MODEL_ID}...")
        tok = AutoTokenizer.from_pretrained(MODEL_ID, trust_remote_code=True)
        model = AutoModelForCausalLM.from_pretrained(
            MODEL_ID, torch_dtype=torch.float16,
            device_map="auto", load_in_4bit=True)

        cfg = LoraConfig(r=16, lora_alpha=32,
            target_modules=["q_proj","v_proj","k_proj","o_proj"],
            lora_dropout=0.05, task_type=TaskType.CAUSAL_LM)
        model = get_peft_model(model, cfg)
        model.print_trainable_parameters()

        texts=[f"<|im_start|>user\n{s['ins']}<|im_end|>\n<|im_start|>assistant\n{s['res']}<|im_end|>"
               for s in ARABIC_DATA]
        dataset = ds.Dataset.from_dict({"text": texts})

        trainer = SFTTrainer(
            model=model, tokenizer=tok,
            train_dataset=dataset, dataset_text_field="text",
            max_seq_length=512,
            args=TrainingArguments(
                output_dir=OUT_DIR+"/lora",
                num_train_epochs=3, per_device_train_batch_size=4,
                gradient_accumulation_steps=2, learning_rate=2e-4,
                fp16=True, logging_steps=5, report_to="none",
                warmup_ratio=0.1, lr_scheduler_type="cosine"))

        print("🏋️  LoRA Arabic training...")
        trainer.train()

        lora_out = OUT_DIR+"/lora_arabic"
        model.save_pretrained(lora_out)
        tok.save_pretrained(lora_out)
        print(f"✅ LoRA saved: {lora_out}")
        return lora_out
    except Exception as ex:
        print(f"⚠️  LoRA: {ex}")
        return None

# ══ 6. SYSTEM PROMPT ═════════════════════════════════════════
SYS = """أنت AION، مساعد ذكاء اصطناعي يتحدث العربية بطلاقة.
تستطيع استخدام هذه الأدوات بالشكل الدقيق:
[CALC: تعبير رياضي]  — حاسبة دقيقة 100%
[CODE: كود python]   — تنفيذ آمن
[IMAGE: مسار|سؤال]  — تحليل صورة
[MEMORY_SAVE: مفتاح|قيمة]  — حفظ
[MEMORY_GET: موضوع]        — استرجاع
[SEARCH: استعلام]           — بحث ويب
قواعد: استخدم الأدوات عند الضرورة فقط. أجب بالعربي. لا تفتح terminal."""

# ══ 7. RUN TESTS ═════════════════════════════════════════════
def run_tests(llm, tools, gpu):
    TESTS = [
        "احسب: (2**32) + sqrt(144) + pi*100",
        "اكتب كود Python للأعداد الأولية من 1 إلى 50",
        "ما الفرق بين الذكاء الاصطناعي والتعلم العميق؟",
        "احفظ في ذاكرتك: المطور=أحمد، المشروع=AION",
        "من هو المطور؟ ابحث في ذاكرتك",
        "ما هي عاصمة مصر؟",
    ]
    results=[]
    history=[]
    for i,q in enumerate(TESTS,1):
        print(f"\n🧪 {i}. {q[:60]}")
        t0=time.time()
        try:
            msgs=[{"role":"system","content":SYS}]+history[-4:]+[{"role":"user","content":q}]
            r=llm.create_chat_completion(msgs, temperature=0.7, max_tokens=512)
            ans=r["choices"][0]["message"]["content"]
            if any(f"[{t}:" in ans for t in ["CALC","CODE","IMAGE","MEMORY","SEARCH"]):
                ans=execute_tools(ans, tools)
            dt=time.time()-t0
            print(f"✅ ({dt:.1f}s) {ans[:150]}...")
            history.extend([{"role":"user","content":q},{"role":"assistant","content":ans}])
            results.append({"q":q,"ok":True,"time":dt,"ans":ans[:400]})
        except Exception as ex:
            print(f"❌ {ex}")
            results.append({"q":q,"ok":False,"err":str(ex)})
    return results

# ══ MAIN ═════════════════════════════════════════════════════
if __name__ == "__main__":
    print("="*55)
    print("  AION Arabic Tool-Use System v1.0")
    print(f"  GPU: {USE_GPU} x{N_GPUS} | Dataset: DeepSeek-14B GGUF")
    print("="*55)

    model_path = find_model()
    if not model_path:
        print("❌ لم يُعثر على GGUF — تأكد ربط dataset بالـ kernel")
        raise SystemExit(1)

    ok = install_llama()
    if not ok:
        raise SystemExit("❌ llama-cpp-python فشل")

    lora_path = lora_arabic(USE_GPU)
    tools = Tools()

    from llama_cpp import Llama
    gpu_layers = -1 if USE_GPU else 0
    print(f"\n🧠 Loading DeepSeek-14B | gpu_layers={gpu_layers}")
    llm = Llama(model_path=model_path, n_gpu_layers=gpu_layers,
                n_ctx=8192 if USE_GPU else 4096, n_threads=8, verbose=False)
    print("✅ Model ready!")

    results = run_tests(llm, tools, USE_GPU)
    passed = sum(1 for r in results if r.get("ok"))

    out = {"system":"AION Arabic Tool-Use v1.0",
           "model":os.path.basename(model_path),
           "gpu":f"T4x{N_GPUS}" if USE_GPU else "CPU",
           "lora":lora_path is not None,
           "passed":passed, "total":len(results), "results":results}

    with open(OUT_DIR+"/aion_arabic_results.json","w",encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)

    print(f"\n{'='*55}")
    print(f"  ✅ {passed}/{len(results)} اختبار نجح")
    print(f"{'='*55}")