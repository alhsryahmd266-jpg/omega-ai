"""
AION-SWARM Shard Trainer
━━━━━━━━━━━━━━━━━━━━━━━
كل job يدرّب shard واحد من النموذج بشكل مستقل
ثم يحفظ الـ gradients للدمج
"""
import os, sys, json, math, time, hashlib
import torch
import torch.nn as nn
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(__file__))))

from omega.model.architecture import get_config, AIONModel, AIONConfig
from omega.tokenizer.bpe import OmegaTokenizer
from omega.trainer.train import OmegaTrainer, ChatDataset, collate_fn
from torch.utils.data import DataLoader


def get_shard_data(all_samples: list, shard_id: int, n_shards: int) -> list:
    """تقسيم البيانات على الـ shards"""
    per_shard = math.ceil(len(all_samples) / n_shards)
    start = shard_id * per_shard
    end   = min(start + per_shard, len(all_samples))
    return all_samples[start:end]


def save_gradients(model: AIONModel, path: str):
    """حفظ الـ gradients للدمج لاحقاً"""
    grads = {}
    for name, param in model.named_parameters():
        if param.grad is not None:
            grads[name] = param.grad.cpu().clone()
    torch.save(grads, path)
    size_mb = os.path.getsize(path) / 1024 / 1024
    print(f"💾 Gradients saved: {path} ({size_mb:.1f}MB)")
    return len(grads)


def save_weights(model: AIONModel, path: str, meta: dict):
    """حفظ الـ weights مع metadata"""
    torch.save({
        'model': model.state_dict(),
        'meta': meta,
    }, path)
    print(f"💾 Weights saved: {path}")


def train_shard(shard_id: int, n_shards: int, config_path: str,
                data_path: str, checkpoint_path: str,
                out_dir: str, max_steps: int = 200):
    """
    تدريب shard واحد:
    1. تحميل الـ checkpoint الأخير
    2. تدريب على البيانات المخصصة لهذا الـ shard
    3. حفظ الـ gradients
    """
    os.makedirs(out_dir, exist_ok=True)

    # تحميل الـ config
    if os.path.exists(config_path):
        with open(config_path) as f:
            raw = json.load(f)
        fields = {k: v for k, v in raw.items()
                  if k in AIONConfig.__dataclass_fields__}
        cfg = AIONConfig(**fields)
    else:
        cfg = get_config('nano')

    print(f"\n{'━'*50}")
    print(f"  AION-SWARM Shard {shard_id}/{n_shards-1}")
    print(f"  Config: dim={cfg.dim}, layers={cfg.n_layers}")
    print(f"{'━'*50}\n")

    device = torch.device('cpu')
    model  = AIONModel(cfg).to(device)

    # تحميل الـ checkpoint
    if os.path.exists(checkpoint_path):
        ck = torch.load(checkpoint_path, map_location=device)
        model.load_state_dict(ck['model'])
        print(f"✅ Loaded checkpoint | meta: {ck.get('meta', {})}")
    else:
        print("⚠️  No checkpoint — starting fresh")

    # تحميل البيانات
    with open(data_path, 'r', encoding='utf-8') as f:
        all_samples = json.load(f)

    shard_samples = get_shard_data(all_samples, shard_id, n_shards)
    print(f"📊 Shard {shard_id}: {len(shard_samples)}/{len(all_samples)} samples")

    # Tokenizer
    tok_path = os.path.join(os.path.dirname(checkpoint_path), '')
    tok_path = checkpoint_path.replace('aion_best.pt', '').replace('omega_best.pt', '')
    if os.path.exists(os.path.join(tok_path, 'tokenizer.json')):
        tok = OmegaTokenizer.load(tok_path)
    else:
        tok = OmegaTokenizer(cfg.vocab_size)
        texts = [s if isinstance(s, str) else str(s) for s in all_samples]
        tok.train(texts, min_freq=1, verbose=False)

    # Dataset
    dataset = ChatDataset(shard_samples, tok, max_len=min(cfg.max_seq_len, 256))
    if len(dataset) == 0:
        print("⚠️  Empty dataset for this shard")
        save_gradients(model, os.path.join(out_dir, f'grad_{shard_id:02d}.pt'))
        return

    loader = DataLoader(dataset, batch_size=2, shuffle=True,
                        collate_fn=collate_fn, num_workers=0)

    # Optimizer
    optimizer = torch.optim.AdamW(model.parameters(), lr=3e-4,
                                   betas=(0.9, 0.95), eps=1e-8)

    # Training loop
    model.train()
    total_loss = 0.0
    n_steps = 0
    t0 = time.time()

    for step, (x, y) in enumerate(loader):
        if step >= max_steps:
            break
        x, y = x.to(device), y.to(device)
        _, loss = model(x, y)
        loss.backward()
        total_loss += loss.item()
        n_steps += 1

        if (step + 1) % 4 == 0:
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            optimizer.zero_grad()

        if step % 50 == 0:
            elapsed = time.time() - t0
            print(f"  step {step:4d} | loss {loss.item():.4f} | {elapsed:.0f}s")

    avg_loss = total_loss / max(n_steps, 1)
    elapsed  = time.time() - t0
    print(f"\n✅ Shard {shard_id} done | avg_loss={avg_loss:.4f} | {elapsed:.0f}s")

    # حفظ الـ gradients
    n_grads = save_gradients(
        model, os.path.join(out_dir, f'grad_{shard_id:02d}.pt'))

    # حفظ الـ weights لهذا الـ shard
    save_weights(model, os.path.join(out_dir, f'weights_{shard_id:02d}.pt'), {
        'shard_id': shard_id,
        'n_shards': n_shards,
        'avg_loss': avg_loss,
        'n_steps': n_steps,
        'n_grads': n_grads,
    })

    # حفظ نتيجة الـ shard
    result = {
        'shard_id': shard_id,
        'avg_loss': avg_loss,
        'n_steps': n_steps,
        'elapsed': elapsed,
        'n_samples': len(shard_samples),
    }
    with open(os.path.join(out_dir, f'result_{shard_id:02d}.json'), 'w') as f:
        json.dump(result, f, indent=2)

    return result


if __name__ == '__main__':
    import argparse
    p = argparse.ArgumentParser()
    p.add_argument('--shard',       type=int, default=0)
    p.add_argument('--n-shards',    type=int, default=4)
    p.add_argument('--config',      default='checkpoints/config.json')
    p.add_argument('--data',        default='data/training_data.json')
    p.add_argument('--checkpoint',  default='checkpoints/aion_best.pt')
    p.add_argument('--out-dir',     default='swarm_out')
    p.add_argument('--max-steps',   type=int, default=200)
    args = p.parse_args()

    result = train_shard(
        shard_id=args.shard,
        n_shards=args.n_shards,
        config_path=args.config,
        data_path=args.data,
        checkpoint_path=args.checkpoint,
        out_dir=args.out_dir,
        max_steps=args.max_steps,
    )
    print(f"\nFinal result: {result}")
