"""
GVR Trainer - تدريب النظام الكامل
"""
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader
import json, os, time, math
from gvr.architecture import GVRConfig, GVRSystem

# ──────────────────────────────────────────
# Dataset
# ──────────────────────────────────────────
class GVRDataset(Dataset):
    def __init__(self, data: list, max_len: int = 512):
        self.data = data
        self.max_len = max_len

    def __len__(self): return len(self.data)

    def __getitem__(self, idx):
        item = self.data[idx]
        q = item.get("question", item.get("input", ""))
        a = item.get("answer", item.get("output", ""))
        q_ids = self._tokenize(q)
        a_ids = self._tokenize(a)
        return {"question": q_ids, "answer": a_ids, "label": 1.0}

    def _tokenize(self, text: str) -> torch.Tensor:
        ids = [ord(c) % 32000 for c in text[:self.max_len]]
        if not ids: ids = [0]
        return torch.tensor(ids, dtype=torch.long)

def collate_fn(batch):
    def pad(seqs):
        max_l = max(s.shape[0] for s in seqs)
        return torch.stack([F.pad(s, (0, max_l - s.shape[0])) for s in seqs])
    return {
        "question": pad([b["question"] for b in batch]),
        "answer": pad([b["answer"] for b in batch]),
        "label": torch.tensor([b["label"] for b in batch]),
    }

# ──────────────────────────────────────────
# Data Generator
# ──────────────────────────────────────────
def generate_training_data() -> list:
    data = []

    # برمجة
    code_examples = [
        ("اكتب دالة Python لحساب مجموع قائمة",
         "def sum_list(lst):\n    return sum(lst)\n\nprint(sum_list([1,2,3]))  # 6"),
        ("اكتب class Stack في Python",
         "class Stack:\n    def __init__(self):\n        self.items = []\n    def push(self, item):\n        self.items.append(item)\n    def pop(self):\n        return self.items.pop() if self.items else None\n    def peek(self):\n        return self.items[-1] if self.items else None"),
        ("اكتب دالة Fibonacci بالـ memoization",
         "from functools import lru_cache\n@lru_cache(maxsize=None)\ndef fib(n):\n    if n < 2: return n\n    return fib(n-1) + fib(n-2)"),
        ("اكتب binary search في Python",
         "def binary_search(arr, target):\n    lo, hi = 0, len(arr)-1\n    while lo <= hi:\n        mid = (lo+hi)//2\n        if arr[mid]==target: return mid\n        elif arr[mid]<target: lo=mid+1\n        else: hi=mid-1\n    return -1"),
        ("اكتب merge sort في Python",
         "def merge_sort(arr):\n    if len(arr)<=1: return arr\n    mid=len(arr)//2\n    l=merge_sort(arr[:mid])\n    r=merge_sort(arr[mid:])\n    return merge(l,r)\ndef merge(l,r):\n    res=[]\n    i=j=0\n    while i<len(l) and j<len(r):\n        if l[i]<=r[j]: res.append(l[i]);i+=1\n        else: res.append(r[j]);j+=1\n    return res+l[i:]+r[j:]"),
        ("اكتب decorator لحساب وقت التنفيذ",
         "import time\ndef timer(func):\n    def wrapper(*args,**kwargs):\n        start=time.time()\n        result=func(*args,**kwargs)\n        print(f'{func.__name__}: {time.time()-start:.3f}s')\n        return result\n    return wrapper"),
        ("اكتب context manager لفتح ملف بأمان",
         "class SafeFile:\n    def __init__(self, path, mode='r'):\n        self.path=path;self.mode=mode\n    def __enter__(self):\n        self.f=open(self.path,self.mode)\n        return self.f\n    def __exit__(self,*args):\n        self.f.close()"),
        ("اكتب generator لأرقام أولية",
         "def primes():\n    seen=[]\n    n=2\n    while True:\n        if all(n%p!=0 for p in seen):\n            seen.append(n);yield n\n        n+=1"),
        ("اكتب async function لجلب بيانات من API",
         "import asyncio,aiohttp\nasync def fetch(url):\n    async with aiohttp.ClientSession() as s:\n        async with s.get(url) as r:\n            return await r.json()\nasync def main():\n    data=await fetch('https://api.example.com/data')\n    print(data)"),
        ("كيف تعمل Neural Network في Python بـ PyTorch؟",
         "import torch\nimport torch.nn as nn\nclass Net(nn.Module):\n    def __init__(self):\n        super().__init__()\n        self.fc1=nn.Linear(784,128)\n        self.fc2=nn.Linear(128,10)\n    def forward(self,x):\n        x=torch.relu(self.fc1(x))\n        return self.fc2(x)\nmodel=Net()\noptimizer=torch.optim.Adam(model.parameters())\ncriterion=nn.CrossEntropyLoss()"),
    ]

    # رياضيات
    math_examples = [
        ("ما هو مشتق x^3 + 2x^2؟", "مشتق x^3 = 3x^2، ومشتق 2x^2 = 4x، إذن المشتق الكلي = 3x^2 + 4x"),
        ("احسب تكامل x^2 dx", "∫x^2 dx = x^3/3 + C"),
        ("ما هو الفرق بين المتسلسلة والمتتالية؟",
         "المتتالية هي قائمة منظمة من الأرقام (a1,a2,a3,...). المتسلسلة هي مجموع حدود المتتالية (a1+a2+a3+...)."),
        ("حل المعادلة التفاضلية dy/dx = y",
         "هذه معادلة تفاضلية بسيطة. الحل: dy/y = dx، بالتكامل: ln|y| = x + C، إذن y = Ae^x حيث A ثابت."),
        ("ما هو مفهوم حد النهاية؟",
         "النهاية (limit) تصف قيمة تقترب منها الدالة عندما يقترب المتغير من قيمة معينة. مثال: lim(x→2) x^2 = 4"),
    ]

    # ذكاء اصطناعي
    ai_examples = [
        ("ما الفرق بين supervised و unsupervised learning؟",
         "Supervised: النموذج يتعلم من بيانات مُعلَّمة (مع الإجابات الصحيحة). Unsupervised: النموذج يكتشف الأنماط بنفسه بدون إجابات. أمثلة supervised: تصنيف الصور. أمثلة unsupervised: تجميع البيانات clustering."),
        ("اشرح backpropagation",
         "Backpropagation هو الخوارزمية التي تُدرّب الشبكات العصبية. الخطوات: 1) Forward pass: احسب التنبؤ. 2) احسب الخطأ (loss). 3) Backward pass: احسب التدرجات بقاعدة السلسلة. 4) حدّث الأوزان بـ gradient descent."),
        ("ما هو attention mechanism؟",
         "Attention يسمح للنموذج بالتركيز على أجزاء معينة من المدخل عند توليد كل token. يحسب: Attention(Q,K,V) = softmax(QK^T/√d)V. هو أساس معمارية Transformer."),
    ]

    for q, a in code_examples + math_examples + ai_examples:
        data.append({"question": q, "answer": a})
        # تنويعات
        data.append({"question": f"كيف أكتب {q[:30]}", "answer": a})

    # عربي عام
    arabic_examples = [
        ("ما هو الذكاء الاصطناعي؟",
         "الذكاء الاصطناعي هو مجال علوم الحاسب الذي يهدف لبناء أنظمة قادرة على أداء مهام تتطلب الذكاء البشري كالتعلم والتفكير واتخاذ القرارات."),
        ("ما الفرق بين Machine Learning وDeep Learning؟",
         "Machine Learning: يتعلم النموذج من البيانات باستخدام خوارزميات كـ SVM وRandom Forest. Deep Learning: فرع من ML يستخدم شبكات عصبية عميقة، مناسب للصور والنصوص والصوت."),
    ]

    for q, a in arabic_examples:
        data.append({"question": q, "answer": a})

    print(f"[Data] Generated {len(data)} training examples")
    return data

# ──────────────────────────────────────────
# Trainer
# ──────────────────────────────────────────
class GVRTrainer:
    def __init__(self, cfg: GVRConfig, budget_seconds: int = 5*3600):
        self.cfg = cfg
        self.budget = budget_seconds
        self.device = torch.device(cfg.device)
        self.model = GVRSystem(cfg).to(self.device)
        params = self.model.num_params()
        print(f"[GVR] Model: {params['total_M']}M params | device: {cfg.device}")

        if cfg.device == "cuda":
            try:
                self.model = torch.compile(self.model)
                print("[GVR] torch.compile: ON")
            except Exception as e:
                print(f"[GVR] torch.compile: skipped ({e})")

    def train(self, checkpoint_path: str = "gvr_checkpoint.pt"):
        # تحميل checkpoint لو موجود
        start_epoch = 0
        if os.path.exists(checkpoint_path):
            ckpt = torch.load(checkpoint_path, map_location=self.device)
            self.model.load_state_dict(ckpt["model"], strict=False)
            start_epoch = ckpt.get("epoch", 0)
            print(f"[GVR] Resumed from epoch {start_epoch}")

        # بيانات التدريب
        raw_data = generate_training_data()
        dataset = GVRDataset(raw_data, max_len=256)
        loader = DataLoader(dataset, batch_size=4, shuffle=True,
                            collate_fn=collate_fn, num_workers=0)

        # optimizers
        gen_opt = torch.optim.AdamW(self.model.generator.parameters(), lr=3e-4, weight_decay=0.01)
        ver_opt = torch.optim.AdamW(self.model.verifier.parameters(),  lr=3e-4, weight_decay=0.01)
        arb_opt = torch.optim.AdamW(self.model.arbitration.parameters(), lr=1e-3)

        gen_sched = torch.optim.lr_scheduler.CosineAnnealingWarmRestarts(gen_opt, T_0=10)
        ver_sched = torch.optim.lr_scheduler.CosineAnnealingWarmRestarts(ver_opt, T_0=10)

        # AMP
        scaler = torch.cuda.amp.GradScaler() if self.device.type == "cuda" else None

        print(f"\n[GVR] Training start | budget: {self.budget//3600}h")
        start_time = time.time()
        step = 0
        best_loss = float('inf')

        epoch = start_epoch
        while True:
            epoch += 1
            epoch_gen_loss = 0.0
            epoch_ver_loss = 0.0
            n_batches = 0

            for batch in loader:
                elapsed = time.time() - start_time
                if elapsed >= self.budget - 60:
                    print(f"\n[GVR] Budget reached at step {step}")
                    self._save(checkpoint_path, epoch, step, best_loss)
                    return

                q_ids = batch["question"].to(self.device)
                a_ids = batch["answer"].to(self.device)

                # ── Generator Loss ──
                gen_opt.zero_grad()
                if scaler:
                    with torch.cuda.amp.autocast():
                        logits, _ = self.model.generator(a_ids)
                        targets = torch.roll(a_ids, -1, 1)
                        targets[:, -1] = 0
                        gen_loss = F.cross_entropy(logits.reshape(-1, self.cfg.vocab_size),
                                                    targets.reshape(-1), ignore_index=0,
                                                    label_smoothing=0.1)
                    scaler.scale(gen_loss).backward()
                    scaler.unscale_(gen_opt)
                    nn.utils.clip_grad_norm_(self.model.generator.parameters(), 1.0)
                    scaler.step(gen_opt)
                    scaler.update()
                else:
                    logits, _ = self.model.generator(a_ids)
                    targets = torch.roll(a_ids, -1, 1)
                    targets[:, -1] = 0
                    gen_loss = F.cross_entropy(logits.reshape(-1, self.cfg.vocab_size),
                                                targets.reshape(-1), ignore_index=0,
                                                label_smoothing=0.1)
                    gen_loss.backward()
                    nn.utils.clip_grad_norm_(self.model.generator.parameters(), 1.0)
                    gen_opt.step()

                # ── Verifier Loss ──
                ver_opt.zero_grad()
                with torch.no_grad():
                    answer_gen = self.model.generate_tokens(q_ids, max_new=32)
                    answer_part = answer_gen[:, q_ids.shape[1]:]

                v_out = self.model.verifier(q_ids, a_ids[:, :min(a_ids.shape[1], 64)])
                # الإجابة الصحيحة = score عالي
                ver_loss_correct = F.binary_cross_entropy(
                    v_out["score"], torch.ones_like(v_out["score"]) * 0.9)

                # الإجابة المولّدة = score أقل
                if answer_part.shape[1] > 0:
                    v_gen = self.model.verifier(q_ids, answer_part[:, :min(answer_part.shape[1], 64)])
                    ver_loss_gen = F.binary_cross_entropy(
                        v_gen["score"], torch.ones_like(v_gen["score"]) * 0.3)
                    ver_loss = (ver_loss_correct + ver_loss_gen) / 2
                else:
                    ver_loss = ver_loss_correct

                ver_loss.backward()
                nn.utils.clip_grad_norm_(self.model.verifier.parameters(), 1.0)
                ver_opt.step()

                # Arbitration: بسيط — تعلم يقول "output" لما الـ score عالي
                arb_opt.zero_grad()
                with torch.no_grad():
                    v_final = self.model.verifier(q_ids, a_ids[:, :64])
                fake_action = self.model.arbitration(
                    v_final["logical"], v_final["factual"], v_final["complete"], 0, 0.9)
                # target: action 0 (output) عندما score عالي
                arb_target = torch.zeros(1, 3, device=self.device)
                arb_target[0, 0] = 1.0
                arb_loss = F.cross_entropy(fake_action, arb_target.argmax(dim=1))
                arb_loss.backward()
                arb_opt.step()

                epoch_gen_loss += gen_loss.item()
                epoch_ver_loss += ver_loss.item()
                n_batches += 1
                step += 1

                if step % 50 == 0:
                    avg_gen = epoch_gen_loss / n_batches
                    avg_ver = epoch_ver_loss / n_batches
                    elapsed_m = (time.time() - start_time) / 60
                    print(f"[Step {step:5d}] Gen={avg_gen:.4f} Ver={avg_ver:.4f} "
                          f"| Epoch {epoch} | {elapsed_m:.1f}m elapsed")

                    total_loss = avg_gen + avg_ver
                    if total_loss < best_loss:
                        best_loss = total_loss
                        self._save(checkpoint_path, epoch, step, best_loss)

            gen_sched.step()
            ver_sched.step()
            print(f"[Epoch {epoch}] Gen={epoch_gen_loss/max(n_batches,1):.4f} | steps={step}")

    def _save(self, path: str, epoch: int, step: int, loss: float):
        try:
            state = {k: v.cpu() if hasattr(v, 'cpu') else v
                     for k, v in self.model.state_dict().items()}
            torch.save({"model": state, "epoch": epoch, "step": step, "loss": loss}, path)
            size_mb = os.path.getsize(path) / 1e6
            print(f"[Save] {path} ({size_mb:.1f}MB) | epoch={epoch} loss={loss:.4f}")
        except Exception as e:
            print(f"[Save] Error: {e}")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--budget_hours", type=float, default=2.0)
    parser.add_argument("--checkpoint", type=str, default="gvr_checkpoint.pt")
    parser.add_argument("--d_model", type=int, default=256)
    parser.add_argument("--layers", type=int, default=6)
    args = parser.parse_args()

    cfg = GVRConfig(
        d_model=args.d_model,
        gen_layers=args.layers,
        ver_layers=args.layers,
        max_seq_len=512,
    )
    trainer = GVRTrainer(cfg, budget_seconds=int(args.budget_hours * 3600))
    trainer.train(args.checkpoint)
