"""
GVR Architecture - Generate → Verify → Refine
معمارية صفرية ثورية: تولّد، تتحقق، تحسّن
"""
import torch
import torch.nn as nn
import torch.nn.functional as F
import math
from dataclasses import dataclass
from typing import Optional, Tuple

@dataclass
class GVRConfig:
    # مشترك
    vocab_size: int = 32000
    d_model: int = 512
    max_seq_len: int = 2048
    pad_token_id: int = 0
    # Generator
    gen_layers: int = 12
    gen_heads: int = 8
    gen_ff_mult: int = 4
    # Verifier
    ver_layers: int = 12
    ver_heads: int = 8
    # Arbitration
    arb_layers: int = 4
    arb_d_model: int = 256
    # Inference
    max_iterations: int = 6
    output_threshold: float = 0.88
    refine_threshold: float = 0.50
    dropout: float = 0.1
    device: str = "cuda" if torch.cuda.is_available() else "cpu"

# ────────────────────────────────────────────────
# طبقة الانتباه المحسّنة
# ────────────────────────────────────────────────
class EfficientAttention(nn.Module):
    def __init__(self, d_model: int, n_heads: int, causal: bool = True, dropout: float = 0.1):
        super().__init__()
        self.n_heads = n_heads
        self.d_head = d_model // n_heads
        self.causal = causal
        self.scale = self.d_head ** -0.5

        self.qkv = nn.Linear(d_model, d_model * 3, bias=False)
        self.out = nn.Linear(d_model, d_model, bias=False)
        self.dropout = nn.Dropout(dropout)

    def forward(self, x: torch.Tensor, mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        B, L, D = x.shape
        qkv = self.qkv(x).reshape(B, L, 3, self.n_heads, self.d_head)
        q, k, v = qkv.unbind(2)
        q = q.transpose(1, 2)
        k = k.transpose(1, 2)
        v = v.transpose(1, 2)

        attn = torch.matmul(q, k.transpose(-2, -1)) * self.scale

        if self.causal:
            causal_mask = torch.triu(torch.ones(L, L, device=x.device), diagonal=1).bool()
            attn = attn.masked_fill(causal_mask.unsqueeze(0).unsqueeze(0), float('-inf'))

        if mask is not None:
            attn = attn.masked_fill(mask.unsqueeze(1).unsqueeze(2), float('-inf'))

        attn = F.softmax(attn, dim=-1)
        attn = self.dropout(attn)

        out = torch.matmul(attn, v)
        out = out.transpose(1, 2).reshape(B, L, D)
        return self.out(out)

# ────────────────────────────────────────────────
# طبقة FFN بـ GLU
# ────────────────────────────────────────────────
class GatedFFN(nn.Module):
    def __init__(self, d_model: int, ff_mult: int = 4, dropout: float = 0.1):
        super().__init__()
        d_ff = d_model * ff_mult
        self.gate = nn.Linear(d_model, d_ff * 2, bias=False)
        self.out = nn.Linear(d_ff, d_model, bias=False)
        self.dropout = nn.Dropout(dropout)
        self.norm = nn.LayerNorm(d_model)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        g = self.gate(x)
        g1, g2 = g.chunk(2, dim=-1)
        return self.out(self.dropout(F.silu(g1) * g2))

# ────────────────────────────────────────────────
# ① Generator Block (Causal)
# ────────────────────────────────────────────────
class GeneratorBlock(nn.Module):
    def __init__(self, cfg: GVRConfig):
        super().__init__()
        self.norm1 = nn.LayerNorm(cfg.d_model)
        self.attn = EfficientAttention(cfg.d_model, cfg.gen_heads, causal=True, dropout=cfg.dropout)
        self.norm2 = nn.LayerNorm(cfg.d_model)
        self.ffn = GatedFFN(cfg.d_model, cfg.gen_ff_mult, cfg.dropout)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = x + self.attn(self.norm1(x))
        x = x + self.ffn(self.norm2(x))
        return x

class Generator(nn.Module):
    """يولّد إجابة مبدئية بسرعة — مش لازم تكون مثالية"""
    def __init__(self, cfg: GVRConfig):
        super().__init__()
        self.cfg = cfg
        self.embed = nn.Embedding(cfg.vocab_size, cfg.d_model)
        self.pos_embed = nn.Embedding(cfg.max_seq_len, cfg.d_model)
        self.blocks = nn.ModuleList([GeneratorBlock(cfg) for _ in range(cfg.gen_layers)])
        self.norm = nn.LayerNorm(cfg.d_model)
        self.head = nn.Linear(cfg.d_model, cfg.vocab_size, bias=False)
        self.head.weight = self.embed.weight  # weight tying
        self.dropout = nn.Dropout(cfg.dropout)
        self._init_weights()

    def _init_weights(self):
        for p in self.parameters():
            if p.dim() > 1:
                nn.init.xavier_uniform_(p)

    def forward(self, input_ids: torch.Tensor,
                refinement_hint: Optional[torch.Tensor] = None) -> Tuple[torch.Tensor, torch.Tensor]:
        B, L = input_ids.shape
        pos = torch.arange(L, device=input_ids.device)
        x = self.dropout(self.embed(input_ids) + self.pos_embed(pos))

        if refinement_hint is not None:
            x = x + refinement_hint.unsqueeze(1)

        for block in self.blocks:
            x = block(x)

        x = self.norm(x)
        logits = self.head(x)
        return logits, x  # logits + hidden states

# ────────────────────────────────────────────────
# ② Verifier Block (Bidirectional)
# ────────────────────────────────────────────────
class VerifierBlock(nn.Module):
    def __init__(self, cfg: GVRConfig):
        super().__init__()
        self.norm1 = nn.LayerNorm(cfg.d_model)
        self.attn = EfficientAttention(cfg.d_model, cfg.ver_heads, causal=False, dropout=cfg.dropout)
        self.norm2 = nn.LayerNorm(cfg.d_model)
        self.ffn = GatedFFN(cfg.d_model, dropout=cfg.dropout)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = x + self.attn(self.norm1(x))
        x = x + self.ffn(self.norm2(x))
        return x

class Verifier(nn.Module):
    """يتحقق من صحة الإجابة — ويوجّه التحسين"""
    def __init__(self, cfg: GVRConfig):
        super().__init__()
        self.cfg = cfg
        self.embed = nn.Embedding(cfg.vocab_size, cfg.d_model)
        self.pos_embed = nn.Embedding(cfg.max_seq_len, cfg.d_model)
        self.blocks = nn.ModuleList([VerifierBlock(cfg) for _ in range(cfg.ver_layers)])
        self.norm = nn.LayerNorm(cfg.d_model)
        self.dropout = nn.Dropout(cfg.dropout)

        # رؤوس التقييم
        self.logical_head = nn.Sequential(nn.Linear(cfg.d_model, 128), nn.GELU(), nn.Linear(128, 1), nn.Sigmoid())
        self.factual_head = nn.Sequential(nn.Linear(cfg.d_model, 128), nn.GELU(), nn.Linear(128, 1), nn.Sigmoid())
        self.complete_head = nn.Sequential(nn.Linear(cfg.d_model, 128), nn.GELU(), nn.Linear(128, 1), nn.Sigmoid())
        self.refinement_proj = nn.Linear(cfg.d_model, cfg.d_model)
        self._init_weights()

    def _init_weights(self):
        for p in self.parameters():
            if p.dim() > 1:
                nn.init.xavier_uniform_(p)

    def forward(self, question_ids: torch.Tensor,
                answer_ids: torch.Tensor) -> dict:
        B = question_ids.shape[0]
        # دمج السؤال والإجابة
        combined = torch.cat([question_ids, answer_ids], dim=1)
        L = combined.shape[1]
        if L > self.cfg.max_seq_len:
            combined = combined[:, -self.cfg.max_seq_len:]
            L = self.cfg.max_seq_len

        pos = torch.arange(L, device=combined.device)
        x = self.dropout(self.embed(combined) + self.pos_embed(pos))

        for block in self.blocks:
            x = block(x)
        x = self.norm(x)

        # تجميع الـ representation
        cls_repr = x.mean(dim=1)  # average pooling

        logical  = self.logical_head(cls_repr)   # (B, 1)
        factual  = self.factual_head(cls_repr)   # (B, 1)
        complete = self.complete_head(cls_repr)  # (B, 1)
        combined_score = (logical + factual + complete) / 3.0
        refinement_hint = self.refinement_proj(cls_repr)  # (B, d_model)

        return {
            "logical":   logical.squeeze(-1),
            "factual":   factual.squeeze(-1),
            "complete":  complete.squeeze(-1),
            "score":     combined_score.squeeze(-1),
            "hint":      refinement_hint,
        }

# ────────────────────────────────────────────────
# ③ Arbitration Layer
# ────────────────────────────────────────────────
class Arbitration(nn.Module):
    """يقرر: output أو refine أو restart"""
    def __init__(self, cfg: GVRConfig):
        super().__init__()
        # inputs: score(3) + iteration(1) + confidence(1) = 5
        self.net = nn.Sequential(
            nn.Linear(5, cfg.arb_d_model),
            nn.GELU(),
            nn.Dropout(cfg.dropout),
            nn.Linear(cfg.arb_d_model, cfg.arb_d_model // 2),
            nn.GELU(),
            nn.Linear(cfg.arb_d_model // 2, 3),  # output, refine, restart
        )
        self.cfg = cfg

    def forward(self, logical: torch.Tensor, factual: torch.Tensor,
                complete: torch.Tensor, iteration: int, confidence: float) -> torch.Tensor:
        iter_norm = torch.tensor([iteration / self.cfg.max_iterations],
                                  device=logical.device, dtype=logical.dtype)
        conf_t = torch.tensor([confidence], device=logical.device, dtype=logical.dtype)

        inp = torch.stack([logical.mean(), factual.mean(),
                           complete.mean(), iter_norm[0], conf_t[0]], dim=0).unsqueeze(0)
        return F.softmax(self.net(inp), dim=-1)  # (1, 3)

# ────────────────────────────────────────────────
# النظام الكامل GVR
# ────────────────────────────────────────────────
class GVRSystem(nn.Module):
    def __init__(self, cfg: GVRConfig):
        super().__init__()
        self.cfg = cfg
        self.generator   = Generator(cfg)
        self.verifier    = Verifier(cfg)
        self.arbitration = Arbitration(cfg)

    def num_params(self) -> dict:
        gen = sum(p.numel() for p in self.generator.parameters())
        ver = sum(p.numel() for p in self.verifier.parameters())
        arb = sum(p.numel() for p in self.arbitration.parameters())
        total = gen + ver + arb
        return {"generator": gen, "verifier": ver,
                "arbitration": arb, "total": total,
                "total_M": round(total/1e6, 1)}

    @torch.no_grad()
    def generate_tokens(self, input_ids: torch.Tensor, max_new: int = 128,
                        temperature: float = 0.8, hint: Optional[torch.Tensor] = None) -> torch.Tensor:
        device = input_ids.device
        generated = input_ids.clone()

        for _ in range(max_new):
            logits, _ = self.generator(generated, hint)
            next_logits = logits[:, -1, :] / max(temperature, 1e-8)
            probs = F.softmax(next_logits, dim=-1)
            next_token = torch.multinomial(probs, num_samples=1)
            generated = torch.cat([generated, next_token], dim=1)

            if generated.shape[1] >= self.cfg.max_seq_len:
                break

        return generated

    @torch.no_grad()
    def inference(self, input_ids: torch.Tensor, max_new: int = 128) -> dict:
        best_answer = None
        best_score = -1.0
        hint = None

        for iteration in range(self.cfg.max_iterations):
            # توليد
            temp = 0.7 + (iteration * 0.1)
            answer_ids = self.generate_tokens(input_ids, max_new, temp, hint)
            answer_part = answer_ids[:, input_ids.shape[1]:]

            # تحقق
            v = self.verifier(input_ids, answer_part)
            score = v["score"].item()
            hint = v["hint"]

            if score > best_score:
                best_score = score
                best_answer = answer_ids

            # قرار
            conf = score
            decision = self.arbitration(v["logical"], v["factual"],
                                        v["complete"], iteration, conf)
            action = decision.argmax().item()  # 0=output, 1=refine, 2=restart

            if action == 0 or score >= self.cfg.output_threshold:
                break
            elif action == 2:
                hint = None  # restart

        return {
            "answer_ids": best_answer,
            "score": best_score,
            "iterations": iteration + 1,
        }
