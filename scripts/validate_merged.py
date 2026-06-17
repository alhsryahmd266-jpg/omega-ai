"""
Validate the aggregated AION-SWARM model after Model-Soup merging.
Ensures the merged checkpoint is loadable and produces correct-shaped output
before it gets released.
"""
import sys
import json
import torch

sys.path.insert(0, '.')
from omega.model.architecture import AIONModel, AIONConfig


def main():
    with open('checkpoints/config.json') as f:
        raw = json.load(f)
    fields = {k: v for k, v in raw.items()
              if k in AIONConfig.__dataclass_fields__}
    cfg = AIONConfig(**fields)
    model = AIONModel(cfg)

    ckpt = torch.load('checkpoints/aion_best.pt', map_location='cpu')
    model.load_state_dict(ckpt['model'])
    model.eval()

    meta = ckpt.get('meta', {})
    print(f"Merged model valid | meta: {meta}")

    x = torch.randint(0, cfg.vocab_size, (1, 16))
    logits, _ = model(x)
    assert logits.shape == (1, 16, cfg.vocab_size), f"Bad shape: {logits.shape}"
    print(f"Forward pass OK: {logits.shape}")

    out = model.generate(x, max_new=5, enable_metacog=True)
    assert out.shape[1] > 16
    print(f"Generation OK: {out.shape}")
    print("All validation checks passed")


if __name__ == '__main__':
    main()
