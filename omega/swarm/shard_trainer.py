"""
AION-SWARM Shard Trainer v2 — Time-Budgeted Training
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
كل job يدرّب shard مستقل لمدة زمنية محددة (مش عدد خطوات ثابت)
بيستغل الـ 6 ساعات المتاحة في GitHub Actions بالكامل
ويحفظ checkpoints دورياً عشان مفيش فقدان لو حصل timeout
"""
import os, sys, json, math, time
import torch
import torch.nn as nn
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(__file__))))

from omega.model.architecture import get_config, AIONModel, AIONConfig
from omega.tokenizer.bpe import OmegaTokenizer
from omega.trainer.train import ChatDataset, collate_fn, cosine_lr
from torch.utils.data import DataLoader


def get_shard_data(all_samples: list, shard_id: int, n_shards: int) -> list:
    per_shard = math.ceil(len(all_samples) / n_shards)
    start = shard_id * per_shard
    end   = min(start + per_shard, len(all_samples))
    return all_samples[start:end]


def save_weights(model: AIONModel, path: str, meta: dict):
    torch.save({'model': model.state_dict(), 'meta': meta}, path)


def train_shard_timed(shard_id: int, n_shards: int, config_path: str,
                       data_path: str, checkpoint_path: str,
                       out_dir: str, max_minutes: float = 300,
                       checkpoint_every_sec: float = 600):
    """
    تدريب shard لمدة زمنية محددة (مش عدد خطوات ثابت):
    - يكرر على البيانات (epochs متعددة) لحد ما الوقت يخلص
    - يحفظ checkpoint دوري كل N دقايق احتياطاً من الـ timeout
    - يحافظ على نفس الأوزان عبر الأجيال (لا يبدأ من الصفر)
    """
    os.makedirs(out_dir, exist_ok=True)
    max_seconds = max_minutes * 60
    t_start = time.time()

    # ── تحميل الـ config ──────────────────────────────
    if os.path.exists(config_path):
        with open(config_path) as f:
            raw = json.load(f)
        fields = {k: v for k, v in raw.items()
                  if k in AIONConfig.__dataclass_fields__}
        cfg = AIONConfig(**fields)
    else:
        cfg = get_config('intensive')

    print(f"\n{'━'*55}")
    print(f"  AION-SWARM Shard {shard_id}/{n_shards-1} (TIME-BUDGETED)")
    print(f"  Budget: {max_minutes:.0f} minutes | dim={cfg.dim} layers={cfg.n_layers}")
    print(f"{'━'*55}\n")

    device = torch.device('cpu')
    torch.set_num_threads(os.cpu_count() or 2)
    model = AIONModel(cfg).to(device)

    # ── تحميل الـ checkpoint (الاستمرارية الحقيقية) ────
    start_gen = 0
    if os.path.exists(checkpoint_path):
        ck = torch.load(checkpoint_path, map_location=device)
        model.load_state_dict(ck['model'])
        start_gen = ck.get('meta', {}).get('generation', 0)
        print(f"✅ Continuing from checkpoint | generation={start_gen} | "
              f"prev_loss={ck.get('best_loss', '?')}")
    else:
        print("⚠️  No checkpoint found — training from scratch (gen 0)")

    # ── تحميل البيانات ────────────────────────────────
    with open(data_path, 'r', encoding='utf-8') as f:
        all_samples = json.load(f)
    shard_samples = get_shard_data(all_samples, shard_id, n_shards)
    print(f"📊 Shard {shard_id}: {len(shard_samples)}/{len(all_samples)} samples")

    # ── Tokenizer ──────────────────────────────────────
    tok_dir = os.path.dirname(checkpoint_path) or '.'
    if os.path.exists(os.path.join(tok_dir, 'tokenizer.json')):
        tok = OmegaTokenizer.load(tok_dir)
        print(f"✅ Loaded tokenizer | vocab={len(tok.vocab)}")
    else:
        tok = OmegaTokenizer(cfg.vocab_size)
        texts = [s if isinstance(s, str) else json.dumps(s, ensure_ascii=False)
                 for s in all_samples]
        tok.train(texts, min_freq=1, verbose=False)
        tok.save(tok_dir)
        print(f"✅ Trained new tokenizer | vocab={len(tok.vocab)}")

    dataset = ChatDataset(shard_samples, tok, max_len=min(cfg.max_seq_len, 256))
    if len(dataset) == 0:
        print("⚠️  Empty dataset for this shard, skipping training")
        save_weights(model, os.path.join(out_dir, f'weights_{shard_id:02d}.pt'),
                    {'shard_id': shard_id, 'n_shards': n_shards, 'avg_loss': 99.0,
                     'n_steps': 0, 'elapsed': 0, 'generation': start_gen})
        return

    loader = DataLoader(dataset, batch_size=4, shuffle=True,
                        collate_fn=collate_fn, num_workers=0)

    optimizer = torch.optim.AdamW(model.parameters(), lr=2e-4,
                                   betas=(0.9, 0.95), eps=1e-8,
                                   weight_decay=0.05)

    # ── حلقة التدريب الزمنية: تكرار epochs لحد ما الوقت يخلص ──
    model.train()
    total_loss, n_steps, n_epochs = 0.0, 0, 0
    last_ckpt_time = t_start
    max_lr, min_lr, warmup_steps = 2e-4, 1e-5, 50
    estimated_total_steps = 5000  # تقدير لـ cosine schedule

    print(f"\n🏋️  Starting time-budgeted training loop...")
    accum_steps = 4
    optimizer.zero_grad()

    while True:
        elapsed = time.time() - t_start
        if elapsed >= max_seconds:
            print(f"\n⏱️  Time budget reached ({elapsed/60:.1f}min) — stopping")
            break

        n_epochs += 1
        epoch_loss, epoch_steps = 0.0, 0

        for i, (x, y) in enumerate(loader):
            elapsed = time.time() - t_start
            if elapsed >= max_seconds:
                break

            x, y = x.to(device), y.to(device)

            lr = cosine_lr(n_steps, warmup_steps, estimated_total_steps, min_lr, max_lr)
            for pg in optimizer.param_groups:
                pg['lr'] = lr

            _, loss = model(x, y)
            (loss / accum_steps).backward()

            total_loss += loss.item()
            epoch_loss += loss.item()
            n_steps += 1
            epoch_steps += 1

            if (i + 1) % accum_steps == 0:
                nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                optimizer.step()
                optimizer.zero_grad()

            if n_steps % 25 == 0:
                avg = total_loss / n_steps
                print(f"  epoch {n_epochs:3d} | step {n_steps:5d} | "
                      f"loss {loss.item():.4f} | avg {avg:.4f} | "
                      f"lr {lr:.2e} | {elapsed/60:.1f}min")

            # حفظ دوري احتياطي (لو الـ job اتقطع فجأة)
            if time.time() - last_ckpt_time >= checkpoint_every_sec:
                save_weights(model, os.path.join(out_dir, f'weights_{shard_id:02d}.pt'),
                            {'shard_id': shard_id, 'n_shards': n_shards,
                             'avg_loss': total_loss / max(n_steps, 1),
                             'n_steps': n_steps, 'elapsed': elapsed,
                             'generation': start_gen, 'partial': True})
                last_ckpt_time = time.time()
                print(f"  💾 Periodic checkpoint saved ({elapsed/60:.1f}min elapsed)")

        avg_epoch_loss = epoch_loss / max(epoch_steps, 1)
        print(f"  ── Epoch {n_epochs} done | avg_loss={avg_epoch_loss:.4f} | "
              f"steps={epoch_steps} ──")

    total_elapsed = time.time() - t_start
    avg_loss = total_loss / max(n_steps, 1)
    print(f"\n✅ Shard {shard_id} done | epochs={n_epochs} | steps={n_steps} | "
          f"avg_loss={avg_loss:.4f} | time={total_elapsed/60:.1f}min")

    # ── حفظ النتيجة النهائية ───────────────────────────
    save_weights(model, os.path.join(out_dir, f'weights_{shard_id:02d}.pt'), {
        'shard_id': shard_id, 'n_shards': n_shards,
        'avg_loss': avg_loss, 'n_steps': n_steps, 'n_epochs': n_epochs,
        'elapsed': total_elapsed, 'generation': start_gen + 1, 'partial': False,
    })

    result = {
        'shard_id': shard_id, 'avg_loss': avg_loss,
        'n_steps': n_steps, 'n_epochs': n_epochs,
        'elapsed': total_elapsed, 'n_samples': len(shard_samples),
        'generation': start_gen + 1,
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
    p.add_argument('--max-minutes', type=float, default=300)
    args = p.parse_args()

    result = train_shard_timed(
        shard_id=args.shard, n_shards=args.n_shards,
        config_path=args.config, data_path=args.data,
        checkpoint_path=args.checkpoint, out_dir=args.out_dir,
        max_minutes=args.max_minutes,
    )
    print(f"\nFinal result: {result}")
