"""
Omega Trainer v2 - تدريب متقدم
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✦ AdamW + Cosine LR + Warmup
✦ Gradient Accumulation
✦ Mixed Precision (AMP)
✦ Gradient Clipping
✦ MoE Aux Loss
✦ Checkpoint resume
✦ Perplexity tracking
"""

import os, sys, math, time, json
import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader
from typing import List, Optional

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from omega.model.architecture import OmegaModel, OmegaConfig
from omega.tokenizer.bpe import OmegaTokenizer


class ChatDataset(Dataset):
    """Dataset يدعم تنسيق المحادثة"""
    def __init__(self, samples: List[dict], tokenizer: OmegaTokenizer,
                 max_len: int = 1024):
        self.tokenizer = tokenizer
        self.max_len   = max_len
        self.data: List[List[int]] = []

        for sample in samples:
            if isinstance(sample, str):
                ids = tokenizer.encode(sample, max_len=max_len+1)
            elif isinstance(sample, dict):
                text = self._format_chat(sample)
                ids  = tokenizer.encode(text, max_len=max_len+1)
            else:
                continue
            if len(ids) > 2:
                self.data.append(ids)

    def _format_chat(self, sample: dict) -> str:
        parts = []
        if 'system' in sample:
            parts.append(f"<system>{sample['system']}</system>")
        if 'user' in sample:
            parts.append(f"<user>{sample['user']}</user>")
        if 'assistant' in sample:
            parts.append(f"<assistant>{sample['assistant']}</assistant>")
        if 'text' in sample:
            parts.append(sample['text'])
        return '\n'.join(parts)

    def __len__(self): return len(self.data)

    def __getitem__(self, i):
        ids = self.data[i]
        L   = min(len(ids)-1, self.max_len)
        x   = torch.tensor(ids[:L],   dtype=torch.long)
        y   = torch.tensor(ids[1:L+1], dtype=torch.long)
        return x, y


def collate_fn(batch):
    xs, ys = zip(*batch)
    max_l  = max(x.size(0) for x in xs)
    xp = torch.zeros(len(xs), max_l, dtype=torch.long)
    yp = torch.full((len(ys), max_l), -1, dtype=torch.long)
    for i, (x, y) in enumerate(zip(xs, ys)):
        xp[i, :x.size(0)] = x
        yp[i, :y.size(0)] = y
    return xp, yp


def cosine_lr(step, warmup, total, min_lr, max_lr):
    if step < warmup:
        return max_lr * (step+1) / warmup
    if step >= total:
        return min_lr
    p = (step - warmup) / (total - warmup)
    return min_lr + 0.5*(max_lr-min_lr)*(1 + math.cos(math.pi*p))


class OmegaTrainer:
    def __init__(self, config: OmegaConfig, tc: dict):
        self.cfg = config
        self.tc  = tc
        self.device = torch.device(
            'cuda' if torch.cuda.is_available() else 'cpu')
        print(f"Device: {self.device}")

        self.model     = OmegaModel(config).to(self.device)
        self.tokenizer: Optional[OmegaTokenizer] = None
        self.step      = 0
        self.best_loss = float('inf')
        self.scaler    = (torch.cuda.amp.GradScaler()
                          if self.device.type == 'cuda' else None)

        # Optimizer
        decay   = [p for n,p in self.model.named_parameters()
                   if p.requires_grad and p.dim() >= 2]
        no_decay= [p for n,p in self.model.named_parameters()
                   if p.requires_grad and p.dim() < 2]
        self.optimizer = torch.optim.AdamW(
            [{'params': decay,    'weight_decay': tc.get('wd', 0.1)},
             {'params': no_decay, 'weight_decay': 0.0}],
            lr=tc.get('max_lr', 3e-4), betas=(0.9, 0.95), eps=1e-8,
            fused=(self.device.type=='cuda'))

        os.makedirs(tc.get('out_dir', 'checkpoints'), exist_ok=True)

    # ── Data ─────────────────────────────────────────────────────────────
    def prepare(self, samples: List) -> DataLoader:
        if self.tokenizer is None:
            texts = [s if isinstance(s, str) else str(s) for s in samples]
            self.tokenizer = OmegaTokenizer(self.cfg.vocab_size)
            self.tokenizer.train(texts, min_freq=1)
            self.tokenizer.save(self.tc.get('out_dir', 'checkpoints'))
        else:
            print("Using existing tokenizer")

        ds = ChatDataset(samples, self.tokenizer,
                         max_len=self.tc.get('seq_len', 512))
        print(f"Dataset: {len(ds)} samples")
        return DataLoader(ds, batch_size=self.tc.get('batch_size', 4),
                          shuffle=True, collate_fn=collate_fn,
                          num_workers=0,
                          pin_memory=self.device.type=='cuda')

    # ── Train step ───────────────────────────────────────────────────────
    def train_epoch(self, loader: DataLoader) -> float:
        self.model.train()
        tc   = self.tc
        accum= tc.get('grad_accum', 4)
        clip = tc.get('grad_clip', 1.0)
        total_loss = 0.0
        n_steps    = 0

        self.optimizer.zero_grad()
        t0 = time.time()

        for i, (x, y) in enumerate(loader):
            x, y = x.to(self.device), y.to(self.device)

            # LR schedule
            lr = cosine_lr(self.step,
                           tc.get('warmup', 100),
                           tc.get('total_steps', 5000),
                           tc.get('min_lr', 1e-5),
                           tc.get('max_lr', 3e-4))
            for pg in self.optimizer.param_groups:
                pg['lr'] = lr

            # Forward
            if self.scaler:
                with torch.cuda.amp.autocast():
                    _, loss = self.model(x, y)
                loss = loss / accum
                self.scaler.scale(loss).backward()
            else:
                _, loss = self.model(x, y)
                (loss / accum).backward()

            total_loss += loss.item() * accum
            n_steps    += 1

            if (i+1) % accum == 0:
                if self.scaler:
                    self.scaler.unscale_(self.optimizer)
                nn.utils.clip_grad_norm_(self.model.parameters(), clip)
                if self.scaler:
                    self.scaler.step(self.optimizer)
                    self.scaler.update()
                else:
                    self.optimizer.step()
                self.optimizer.zero_grad()
                self.step += 1

                if self.step % tc.get('log_every', 25) == 0:
                    avg  = total_loss / n_steps
                    ppl  = math.exp(min(avg, 20))
                    elapsed = time.time() - t0
                    print(f"step {self.step:5d} | loss {avg:.4f} | "
                          f"ppl {ppl:7.1f} | lr {lr:.2e} | {elapsed:.0f}s")

        return total_loss / max(n_steps, 1)

    # ── Save/Load ─────────────────────────────────────────────────────────
    def save(self, name='omega'):
        out  = self.tc.get('out_dir', 'checkpoints')
        path = os.path.join(out, f'{name}.pt')
        torch.save({'step': self.step, 'best_loss': self.best_loss,
                    'model': self.model.state_dict(),
                    'optimizer': self.optimizer.state_dict(),
                    'config': self.cfg.__dict__}, path)
        print(f"💾 Saved: {path}")

    def load(self, path):
        ck = torch.load(path, map_location=self.device)
        self.model.load_state_dict(ck['model'])
        self.optimizer.load_state_dict(ck['optimizer'])
        self.step = ck['step']; self.best_loss = ck['best_loss']
        print(f"📂 Loaded step {self.step}")

    # ── Run ──────────────────────────────────────────────────────────────
    def run(self, samples: List, epochs: int = 3):
        print(f"\n{'━'*55}")
        print(f"  OMEGA AI v2 — TRAINING")
        print(f"  params: {self.model.count_params()/1e6:.1f}M")
        print(f"  device: {self.device}")
        print(f"{'━'*55}\n")

        loader = self.prepare(samples)

        for ep in range(1, epochs+1):
            print(f"\n── Epoch {ep}/{epochs} ──────────────")
            t = time.time()
            loss = self.train_epoch(loader)
            print(f"Epoch {ep} | loss {loss:.4f} | {time.time()-t:.0f}s")
            if loss < self.best_loss:
                self.best_loss = loss
                self.save('omega_best')
            self.save(f'omega_ep{ep}')

        print(f"\n✅ Done | best loss: {self.best_loss:.4f}")
        with open(os.path.join(self.tc.get('out_dir','checkpoints'),
                               'train_result.json'), 'w') as f:
            json.dump({'best_loss': self.best_loss,
                       'steps': self.step,
                       'epochs': epochs}, f)
