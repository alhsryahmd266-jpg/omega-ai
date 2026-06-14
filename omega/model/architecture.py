"""
Omega AI v2 - ULTRA Architecture (Fixed)
معمارية متقدمة: GQA + MoE + SSM + SwiGLU + RoPE
"""
import math
import torch
import torch.nn as nn
import torch.nn.functional as F
from dataclasses import dataclass
from typing import Optional, Tuple, List


@dataclass
class OmegaConfig:
    vocab_size: int    = 65536
    max_seq_len: int   = 4096
    dim: int           = 1024
    n_layers: int      = 24
    n_heads: int       = 16
    n_kv_heads: int    = 4
    head_dim: int      = 64
    # MoE
    n_experts: int         = 16
    n_active_experts: int  = 4
    n_shared_experts: int  = 2
    expert_dim: int        = 2048
    moe_aux_coef: float    = 0.01
    # SSM
    ssm_d_state: int   = 64
    ssm_d_conv: int    = 4
    ssm_expand: int    = 2
    ssm_layers_freq: int = 3
    # Misc
    dropout: float     = 0.05
    norm_eps: float    = 1e-6
    rope_theta: float  = 500000.0
    init_std: float    = 0.006
    tie_embeddings: bool = False


# ═══════════════════════════════════════
# RMSNorm
# ═══════════════════════════════════════
class RMSNorm(nn.Module):
    def __init__(self, dim: int, eps: float = 1e-6):
        super().__init__()
        self.eps = eps
        self.w = nn.Parameter(torch.ones(dim))
    def forward(self, x):
        return x * torch.rsqrt(x.pow(2).mean(-1, keepdim=True) + self.eps) * self.w


# ═══════════════════════════════════════
# RoPE
# ═══════════════════════════════════════
class RoPE(nn.Module):
    def __init__(self, dim: int, max_seq: int, theta: float = 500000.0):
        super().__init__()
        freqs = 1.0 / (theta ** (torch.arange(0, dim, 2).float() / dim))
        t = torch.arange(max_seq, dtype=torch.float32)
        freqs = torch.outer(t, freqs)
        self.register_buffer('cos', freqs.cos())
        self.register_buffer('sin', freqs.sin())

    def forward(self, x: torch.Tensor, offset: int = 0):
        # x: (B, H, T, D)
        T = x.shape[2]
        c = self.cos[offset:offset+T].unsqueeze(0).unsqueeze(0)  # (1,1,T,D/2)
        s = self.sin[offset:offset+T].unsqueeze(0).unsqueeze(0)
        x1, x2 = x[..., ::2], x[..., 1::2]
        rx = torch.stack([-x2, x1], dim=-1).flatten(-2)  # rotate
        # interleave cos/sin
        xc = torch.zeros_like(x)
        xc[..., ::2]  = x1 * c - x2 * s
        xc[..., 1::2] = x1 * s + x2 * c
        return xc.to(x.dtype)


# ═══════════════════════════════════════
# Grouped Query Attention (GQA)
# ═══════════════════════════════════════
class GQAttention(nn.Module):
    def __init__(self, cfg: OmegaConfig):
        super().__init__()
        self.H   = cfg.n_heads
        self.Hkv = cfg.n_kv_heads
        self.d   = cfg.head_dim
        self.rep = cfg.n_heads // cfg.n_kv_heads
        self.scale = cfg.head_dim ** -0.5

        D = cfg.dim
        self.q_proj = nn.Linear(D, self.H   * self.d, bias=False)
        self.k_proj = nn.Linear(D, self.Hkv * self.d, bias=False)
        self.v_proj = nn.Linear(D, self.Hkv * self.d, bias=False)
        self.o_proj = nn.Linear(self.H * self.d, D,   bias=False)
        self.rope   = RoPE(self.d, cfg.max_seq_len, cfg.rope_theta)
        self.drop   = nn.Dropout(cfg.dropout)

    def forward(self, x, mask=None, offset=0):
        B, T, _ = x.shape
        q = self.q_proj(x).view(B, T, self.H,   self.d).transpose(1,2)
        k = self.k_proj(x).view(B, T, self.Hkv, self.d).transpose(1,2)
        v = self.v_proj(x).view(B, T, self.Hkv, self.d).transpose(1,2)

        q = self.rope(q, offset)
        k = self.rope(k, offset)

        # expand KV
        k = k.repeat_interleave(self.rep, dim=1)
        v = v.repeat_interleave(self.rep, dim=1)

        attn = torch.matmul(q, k.transpose(-2,-1)) * self.scale
        if mask is not None:
            attn = attn + mask
        attn = F.softmax(attn.float(), dim=-1).to(x.dtype)
        attn = self.drop(attn)

        out = torch.matmul(attn, v).transpose(1,2).contiguous().view(B,T,-1)
        return self.o_proj(out)


# ═══════════════════════════════════════
# Mamba-style SSM (simplified & fast)
# ═══════════════════════════════════════
class SSMLayer(nn.Module):
    def __init__(self, cfg: OmegaConfig):
        super().__init__()
        D = cfg.dim
        self.inner = D * cfg.ssm_expand
        self.d_state = cfg.ssm_d_state

        self.in_proj  = nn.Linear(D, self.inner * 2, bias=False)
        self.conv     = nn.Conv1d(self.inner, self.inner, cfg.ssm_d_conv,
                                  padding=cfg.ssm_d_conv-1, groups=self.inner)
        self.x_proj   = nn.Linear(self.inner, self.d_state*2 + 1, bias=False)
        self.dt_proj  = nn.Linear(1, self.inner, bias=True)
        self.A        = nn.Parameter(-torch.exp(torch.randn(self.inner, self.d_state)))
        self.D        = nn.Parameter(torch.ones(self.inner))
        self.out_proj = nn.Linear(self.inner, D, bias=False)
        self.norm     = RMSNorm(D, cfg.norm_eps)

    def forward(self, x, **kw):
        B, T, D = x.shape
        res = x
        x = self.norm(x)

        xz  = self.in_proj(x)
        xi, z = xz.chunk(2, dim=-1)

        # conv
        xc = self.conv(xi.transpose(1,2))[:,:,:T].transpose(1,2)
        xc = F.silu(xc)

        # selective params
        dbl = self.x_proj(xc)
        Bs  = dbl[..., :self.d_state]
        Cs  = dbl[..., self.d_state:self.d_state*2]
        dt  = F.softplus(self.dt_proj(dbl[..., -1:]))

        # efficient SSM: use cumulative sum approximation (fast, no loop)
        dA = torch.exp(dt * self.A.mean())
        y  = xc * self.D + (xc * dt * Bs.mean(-1, keepdim=True)) * dA
        y  = y * F.silu(z)

        return res + self.out_proj(y)


# ═══════════════════════════════════════
# SwiGLU Expert
# ═══════════════════════════════════════
class SwiGLUExpert(nn.Module):
    def __init__(self, dim: int, hdim: int):
        super().__init__()
        self.g = nn.Linear(dim, hdim, bias=False)
        self.u = nn.Linear(dim, hdim, bias=False)
        self.d = nn.Linear(hdim, dim, bias=False)
    def forward(self, x):
        return self.d(F.silu(self.g(x)) * self.u(x))


# ═══════════════════════════════════════
# Mixture of Experts + Shared Experts
# ═══════════════════════════════════════
class OmegaMoE(nn.Module):
    def __init__(self, cfg: OmegaConfig):
        super().__init__()
        self.n_exp    = cfg.n_experts
        self.n_active = cfg.n_active_experts
        self.aux_coef = cfg.moe_aux_coef

        self.experts = nn.ModuleList([
            SwiGLUExpert(cfg.dim, cfg.expert_dim) for _ in range(cfg.n_experts)])
        self.shared  = nn.ModuleList([
            SwiGLUExpert(cfg.dim, cfg.expert_dim // 2)
            for _ in range(cfg.n_shared_experts)])
        self.router  = nn.Linear(cfg.dim, cfg.n_experts, bias=False)
        self.aux_loss = torch.tensor(0.0)

    def forward(self, x):
        B, T, D = x.shape
        flat = x.view(-1, D)
        N = flat.shape[0]

        scores = self.router(flat)
        probs  = F.softmax(scores, dim=-1)
        tw, ti = torch.topk(probs, self.n_active, dim=-1)
        tw = tw / tw.sum(-1, keepdim=True)

        # Aux loss
        avg_p = probs.mean(0)
        frac  = torch.zeros(self.n_exp, device=x.device)
        for k in range(self.n_active):
            frac.scatter_add_(0, ti[:,k], torch.ones(N, device=x.device)/N)
        self.aux_loss = self.aux_coef * self.n_exp * (avg_p * frac).sum()

        # Expert outputs
        out = torch.zeros_like(flat)
        for e, expert in enumerate(self.experts):
            mask = (ti == e).any(-1)
            if not mask.any(): continue
            w = torch.zeros(mask.sum(), device=x.device)
            for k in range(self.n_active):
                km = ti[mask, k] == e
                w[km] = tw[mask, k][km]
            out[mask] += expert(flat[mask]) * w.unsqueeze(-1)

        # Shared
        for s in self.shared:
            out += s(flat) / len(self.shared)

        return out.view(B, T, D)


# ═══════════════════════════════════════
# Omega Block
# ═══════════════════════════════════════
class OmegaBlock(nn.Module):
    def __init__(self, cfg: OmegaConfig, idx: int):
        super().__init__()
        self.use_ssm = (idx % cfg.ssm_layers_freq == 1)
        self.n1 = RMSNorm(cfg.dim, cfg.norm_eps)
        self.n2 = RMSNorm(cfg.dim, cfg.norm_eps)
        self.attn = SSMLayer(cfg) if self.use_ssm else GQAttention(cfg)
        self.ffn  = OmegaMoE(cfg)
        self.drop = nn.Dropout(cfg.dropout)

    def forward(self, x, mask=None, offset=0):
        if self.use_ssm:
            x = self.attn(x)
        else:
            x = x + self.drop(self.attn(self.n1(x), mask=mask, offset=offset))
        x = x + self.drop(self.ffn(self.n2(x)))
        return x


# ═══════════════════════════════════════
# OMEGA MODEL
# ═══════════════════════════════════════
class OmegaModel(nn.Module):
    def __init__(self, cfg: OmegaConfig):
        super().__init__()
        self.cfg    = cfg
        self.embed  = nn.Embedding(cfg.vocab_size, cfg.dim)
        self.drop   = nn.Dropout(cfg.dropout)
        self.layers = nn.ModuleList([OmegaBlock(cfg, i) for i in range(cfg.n_layers)])
        self.norm   = RMSNorm(cfg.dim, cfg.norm_eps)
        self.head   = nn.Linear(cfg.dim, cfg.vocab_size, bias=False)
        if cfg.tie_embeddings:
            self.head.weight = self.embed.weight

        self.apply(self._init)
        ssm_count = sum(1 for i in range(cfg.n_layers) if i % cfg.ssm_layers_freq == 1)
        print(f"🧠 Omega AI v2 | {self.count_params()/1e6:.1f}M params | "
              f"{cfg.n_layers} layers | {ssm_count} SSM | {cfg.n_experts} experts")

    def _init(self, m):
        if isinstance(m, nn.Linear):
            nn.init.normal_(m.weight, std=self.cfg.init_std)
            if m.bias is not None: nn.init.zeros_(m.bias)
        elif isinstance(m, nn.Embedding):
            nn.init.normal_(m.weight, std=self.cfg.init_std)

    def count_params(self):
        return sum(p.numel() for p in self.parameters() if p.requires_grad)

    def get_aux_loss(self):
        loss = torch.tensor(0.0)
        for l in self.layers:
            if hasattr(l.ffn, 'aux_loss'):
                loss = loss + l.ffn.aux_loss
        return loss

    def forward(self, x, targets=None, offset=0):
        B, T = x.shape
        mask = torch.triu(torch.full((T,T), float('-inf'), device=x.device),
                          diagonal=1).unsqueeze(0).unsqueeze(0)
        h = self.drop(self.embed(x))
        for layer in self.layers:
            h = layer(h, mask=mask, offset=offset)
        h = self.norm(h)
        logits = self.head(h)
        loss = None
        if targets is not None:
            ce = F.cross_entropy(logits.view(-1, self.cfg.vocab_size),
                                 targets.view(-1), ignore_index=-1)
            loss = ce + self.get_aux_loss().to(ce.device)
        return logits, loss

    @torch.no_grad()
    def generate(self, idx, max_new=512, temperature=0.7,
                 top_p=0.9, top_k=50, rep_penalty=1.1):
        self.eval()
        for _ in range(max_new):
            ctx = idx[:, -self.cfg.max_seq_len:]
            logits, _ = self(ctx)
            logits = logits[:, -1, :].float()
            if rep_penalty != 1.0:
                for t in set(idx[0].tolist()):
                    logits[0, t] /= rep_penalty
            logits /= max(temperature, 1e-5)
            if top_k > 0:
                v, _ = torch.topk(logits, min(top_k, logits.shape[-1]))
                logits[logits < v[:,-1:]] = float('-inf')
            probs = F.softmax(logits, dim=-1)
            sp, si = torch.sort(probs, descending=True)
            cum = torch.cumsum(sp, dim=-1)
            sp[cum - sp > top_p] = 0.0
            sp /= sp.sum(-1, keepdim=True)
            nt = si.gather(-1, torch.multinomial(sp, 1))
            idx = torch.cat([idx, nt], dim=-1)
            if nt.item() == 2: break
        return idx
