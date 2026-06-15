import sys, torch
sys.path.insert(0,'.')
from omega.model.architecture import get_config, AIONModel

cfg = get_config('nano')
m = AIONModel(cfg)
x = torch.randint(0, cfg.vocab_size, (1, 32))
logits, _ = m(x)
assert logits.shape == (1, 32, cfg.vocab_size)
_, loss = m(x, x)
assert loss.item() > 0
out = m.generate(x, max_new=5, enable_metacog=True)
assert out.shape[1] > 32
print(f"AION OK | params={m.count_params()/1e6:.2f}M | metacog=True")
