import sys, torch
sys.path.insert(0,'.')
from omega.model.architecture import get_config, OmegaModel

cfg = get_config('nano')
m = OmegaModel(cfg)
x = torch.randint(0, cfg.vocab_size, (2, 32))
logits, _ = m(x)
assert logits.shape == (2, 32, cfg.vocab_size), f"Bad shape: {logits.shape}"
_, loss = m(x, x)
assert loss is not None and loss.item() > 0
out = m.generate(x[:1], max_new=5)
assert out.shape[1] > 32
print(f"NOVA Architecture OK | params={m.count_params()/1e6:.2f}M | logits={logits.shape}")

# Print large config stats
cfg_l = get_config('large')
print(f"LARGE config: ~{cfg_l.total_params_estimate:.1f}B total, ~{cfg_l.active_params_estimate:.1f}B active")
print(f"Disk @ 4bit: ~{cfg_l.total_params_estimate*0.5:.1f}GB | VRAM: ~{cfg_l.active_params_estimate*0.5:.1f}GB")
