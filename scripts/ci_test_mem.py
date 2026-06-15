import sys, os
sys.path.insert(0,'.')
os.makedirs('/tmp/test_mem', exist_ok=True)
from omega.memory.persistent import OmegaPersistentMemory
mem = OmegaPersistentMemory('/tmp/test_mem/aion.db')
mem.remember('AION يعيد تعريف نفسه أثناء التفكير', mtype='fact', importance=0.9)
mem.remember('Live LoRA تعدل الأوزان مؤقتاً', mtype='skill', importance=0.85)
mem.remember_code('fibonacci','def fib(n): return n if n<=1 else fib(n-1)+fib(n-2)')
r = mem.recall('AION')
assert len(r) > 0
s = mem.stats()
assert s['total'] >= 3
print(f"Memory OK | total={s['total']} | types={s['by_type']}")
mem.close()
