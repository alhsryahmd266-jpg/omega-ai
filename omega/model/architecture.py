"""
╔══════════════════════════════════════════════════════════════════╗
║                    AION Architecture v1.0                        ║
║         أول نموذج يعيد تعريف نفسه أثناء التفكير                ║
╠══════════════════════════════════════════════════════════════════╣
║                                                                  ║
║  الابتكارات الأربع:                                              ║
║                                                                  ║
║  ① Meta-Cognitive Loop                                           ║
║     النموذج يراقب تفكيره ويعدّله في real-time                   ║
║                                                                  ║
║  ② Live LoRA Adapter                                             ║
║     يعدّل أوزانه مؤقتاً للمسائل الصعبة ثم يرجع للأصل           ║
║                                                                  ║
║  ③ Confidence Oracle                                             ║
║     يقيس ثقته في إجابته — لو أقل من threshold يعيد التفكير      ║
║                                                                  ║
║  ④ Hierarchical Sparse MoE (من NOVA)                             ║
║     3 مستويات خبراء: كلمة + جملة + مستند                       ║
║                                                                  ║
╚══════════════════════════════════════════════════════════════════╝
"""

import math
import torch
import torch.nn as nn
import torch.nn.functional as F
from dataclasses import dataclass
from typing import Optional, Tuple, List, Dict


@dataclass
class AIONConfig:
    # ── Core dimensions ──────────────────────────────────────
    vocab_size: int   = 65536
    dim: int          = 2048
    n_layers: int     = 32
    max_seq_len: int  = 8192

    # ── Attention (GQA) ──────────────────────────────────────
    n_heads: int      = 16
    n_kv_heads: int   = 4
    head_dim: int     = 128

    # ── Hierarchical MoE ─────────────────────────────────────
    # Level 1: token-level (fast, many)
    n_tok_experts: int    = 64
    n_tok_active: int     = 2
    tok_expert_dim: int   = 512
    # Level 2: sequence-level (deep, fewer)
    n_seq_experts: int    = 16
    n_seq_active: int     = 2
    seq_expert_dim: int   = 1024
    # Level 3: global (rare, very deep)
    n_glob_experts: int   = 8
    n_glob_active: int    = 2
    glob_expert_dim: int  = 2048
    glob_every_n: int     = 8
    # Shared (always on)
    n_shared: int         = 2
    shared_dim: int       = 256

    # ── SSM (RSM) ────────────────────────────────────────────
    ssm_d_state: int  = 128
    ssm_expand: int   = 2
    ssm_d_conv: int   = 4
    ssm_every_n: int  = 4

    # ── Meta-Cognitive Loop ───────────────────────────────────
    meta_dim: int         = 256        # بعد الـ meta monitor
    confidence_threshold: float = 0.75 # أقل من كده = أعد التفكير
    max_rethink_steps: int = 3         # أقصى عدد مرات إعادة التفكير

    # ── Live LoRA ─────────────────────────────────────────────
    lora_rank: int        = 32         # رتبة الـ LoRA المؤقتة
    lora_alpha: float     = 64.0
    lora_layers: int      = 8          # عدد الطبقات اللي تتعدّل

    # ── Training ──────────────────────────────────────────────
    dropout: float    = 0.0
    norm_eps: float   = 1e-6
    rope_theta: float = 1_000_000.0
    init_std: float   = 0.006
    aux_loss_coef: float = 0.001
    tie_embeddings: bool = False


# ══════════════════════════════════════════════════════════════════
# PRIMITIVES
# ══════════════════════════════════════════════════════════════════

class RMSNorm(nn.Module):
    def __init__(self, dim: int, eps: float = 1e-6):
        super().__init__()
        self.eps = eps
        self.w = nn.Parameter(torch.ones(dim))

    def forward(self, x):
        return x * torch.rsqrt(x.pow(2).mean(-1, keepdim=True) + self.eps) * self.w


class RoPE(nn.Module):
    def __init__(self, dim: int, max_seq: int, theta: float):
        super().__init__()
        freqs = 1.0 / (theta ** (torch.arange(0, dim, 2).float() / dim))
        t = torch.arange(max_seq)
        freqs = torch.outer(t, freqs)
        self.register_buffer('cos', freqs.cos())
        self.register_buffer('sin', freqs.sin())

    def forward(self, x: torch.Tensor, offset: int = 0):
        T = x.shape[2]
        c = self.cos[offset:offset+T].view(1, 1, T, -1)
        s = self.sin[offset:offset+T].view(1, 1, T, -1)
        x1, x2 = x[..., ::2], x[..., 1::2]
        o = torch.zeros_like(x)
        o[..., ::2]  = x1 * c - x2 * s
        o[..., 1::2] = x1 * s + x2 * c
        return o.to(x.dtype)


# ══════════════════════════════════════════════════════════════════
# ① LIVE LORA ADAPTER
#    يعدّل الأوزان مؤقتاً أثناء التفكير في المسائل الصعبة
# ══════════════════════════════════════════════════════════════════

class LiveLoRAAdapter(nn.Module):
    """
    LoRA حي: يتفعّل تلقائياً لما الـ Confidence Oracle
    يقول إن المسألة صعبة. بعد الإجابة يرجع للأصل.
    """
    def __init__(self, dim: int, rank: int, alpha: float, n_layers: int):
        super().__init__()
        self.rank  = rank
        self.scale = alpha / rank
        self.n_layers = n_layers

        # LoRA weights لكل طبقة
        self.lora_A = nn.ParameterList([
            nn.Parameter(torch.randn(dim, rank) * 0.01)
            for _ in range(n_layers)
        ])
        self.lora_B = nn.ParameterList([
            nn.Parameter(torch.zeros(rank, dim))
            for _ in range(n_layers)
        ])

        # Gate: يتحكم في قوة الـ LoRA
        self.gate = nn.Sequential(
            nn.Linear(dim, rank, bias=False),
            nn.Sigmoid()
        )
        self.active = False
        self.strength = 1.0

    def activate(self, strength: float = 1.0):
        """تفعيل الـ LoRA بقوة معينة"""
        self.active = True
        self.strength = strength

    def deactivate(self):
        """إيقاف الـ LoRA والرجوع للأصل"""
        self.active = False

    def adapt(self, x: torch.Tensor, layer_idx: int) -> torch.Tensor:
        """تطبيق الـ LoRA على الـ hidden states"""
        if not self.active or layer_idx >= self.n_layers:
            return x
        A = self.lora_A[layer_idx]   # (D, rank)
        B = self.lora_B[layer_idx]   # (rank, D)
        # gate: (B, T, 1) scalar per position
        gate = self.gate(x).mean(dim=-1, keepdim=True)  # (B, T, 1)
        delta = (x @ A @ B) * self.scale * self.strength * gate
        return x + delta


# ══════════════════════════════════════════════════════════════════
# ② CONFIDENCE ORACLE
#    يقيس مدى ثقة النموذج في إجابته
# ══════════════════════════════════════════════════════════════════

class ConfidenceOracle(nn.Module):
    """
    يحلل الـ hidden states ويقرر:
    - هل النموذج متأكد؟ → اكمل
    - مش متأكد؟ → أعد التفكير مع LoRA
    """
    def __init__(self, dim: int, meta_dim: int):
        super().__init__()
        self.probe = nn.Sequential(
            nn.Linear(dim, meta_dim, bias=False),
            nn.SiLU(),
            nn.Linear(meta_dim, meta_dim // 2, bias=False),
            nn.SiLU(),
            nn.Linear(meta_dim // 2, 1, bias=False),
            nn.Sigmoid()
        )
        # يتعلم من التاريخ
        self.history_proj = nn.Linear(dim, meta_dim // 4, bias=False)

    def forward(self, h: torch.Tensor,
                history: Optional[torch.Tensor] = None) -> torch.Tensor:
        """
        h: (B, T, D) - hidden states
        returns: (B,) confidence score ∈ [0,1]
        """
        # خد متوسط الـ hidden states كـ summary
        summary = h.mean(dim=1)  # (B, D)

        conf = self.probe(summary).squeeze(-1)  # (B,)

        # لو في تاريخ تفكير → أدمجه
        if history is not None:
            hist_summary = history.mean(dim=1)
            hist_feat = self.history_proj(hist_summary)
            # Higher history variance = أقل ثقة
            hist_var = hist_feat.var(dim=-1, keepdim=True).squeeze(-1)
            conf = conf * (1.0 / (1.0 + hist_var.clamp(max=2.0)))

        return conf


# ══════════════════════════════════════════════════════════════════
# ③ META-COGNITIVE MONITOR
#    يراقب جودة التفكير ويوجّه إعادة البناء
# ══════════════════════════════════════════════════════════════════

class MetaCognitiveMonitor(nn.Module):
    """
    يراقب الـ hidden states عبر الطبقات ويكتشف:
    - هل النموذج في حلقة دورانية؟
    - هل هناك تناقض؟
    - هل التركيز على الأجزاء الصح؟
    """
    def __init__(self, dim: int, meta_dim: int):
        super().__init__()
        self.dim = dim

        # يراقب التغيير عبر الطبقات
        self.layer_tracker = nn.GRU(
            input_size=dim,
            hidden_size=meta_dim,
            batch_first=True,
            num_layers=2
        )

        # يكشف التناقضات
        self.contradiction_detector = nn.Sequential(
            nn.Linear(meta_dim * 2, meta_dim, bias=False),
            nn.SiLU(),
            nn.Linear(meta_dim, 1, bias=False),
            nn.Sigmoid()
        )

        # يولّد إشارة توجيه
        self.guidance_proj = nn.Linear(meta_dim, dim, bias=False)

        self.norm = RMSNorm(dim)
        self._hidden = None

    def reset(self):
        self._hidden = None

    def forward(self, h: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        """
        h: (B, T, D)
        returns: guidance signal (B, T, D), contradiction_score (B,)
        """
        B, T, D = h.shape
        summary = h.mean(dim=1, keepdim=True)  # (B, 1, D)

        meta_out, self._hidden = self.layer_tracker(
            summary, self._hidden)  # (B, 1, meta_dim)

        # guidance
        guidance = self.guidance_proj(meta_out)  # (B, 1, D)
        guidance = guidance.expand(B, T, D)

        # contradiction score
        if self._hidden is not None and self._hidden.shape[0] >= 2:
            h1 = self._hidden[0]  # (B, meta_dim)
            h2 = self._hidden[1]  # (B, meta_dim)
            contra = self.contradiction_detector(
                torch.cat([h1, h2], dim=-1)).squeeze(-1)
        else:
            contra = torch.zeros(B, device=h.device)

        return guidance, contra


# ══════════════════════════════════════════════════════════════════
# ATTENTION (GQA + RoPE + QK-Norm)
# ══════════════════════════════════════════════════════════════════

class AIONAttention(nn.Module):
    def __init__(self, cfg: AIONConfig):
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
        self.q_norm = RMSNorm(self.d, cfg.norm_eps)
        self.k_norm = RMSNorm(self.d, cfg.norm_eps)
        self.drop   = nn.Dropout(cfg.dropout)

    def forward(self, x, mask=None, offset=0):
        B, T, _ = x.shape
        q = self.q_proj(x).view(B, T, self.H,   self.d).transpose(1, 2)
        k = self.k_proj(x).view(B, T, self.Hkv, self.d).transpose(1, 2)
        v = self.v_proj(x).view(B, T, self.Hkv, self.d).transpose(1, 2)
        q = self.q_norm(q)
        k = self.k_norm(k)
        q = self.rope(q, offset)
        k = self.rope(k, offset)
        k = k.repeat_interleave(self.rep, dim=1)
        v = v.repeat_interleave(self.rep, dim=1)
        attn = torch.matmul(q, k.transpose(-2, -1)) * self.scale
        if mask is not None:
            attn = attn + mask
        attn = F.softmax(attn.float(), dim=-1).to(x.dtype)
        attn = self.drop(attn)
        out  = torch.matmul(attn, v).transpose(1, 2).contiguous().view(B, T, -1)
        return self.o_proj(out)


# ══════════════════════════════════════════════════════════════════
# SSM LAYER
# ══════════════════════════════════════════════════════════════════

class AIONSSMLayer(nn.Module):
    def __init__(self, cfg: AIONConfig):
        super().__init__()
        D = cfg.dim
        inner = D * cfg.ssm_expand
        self.inner   = inner
        self.d_state = cfg.ssm_d_state

        self.in_proj  = nn.Linear(D, inner * 2, bias=False)
        self.conv     = nn.Conv1d(inner, inner, cfg.ssm_d_conv,
                                  padding=cfg.ssm_d_conv - 1, groups=inner)
        self.dt_proj  = nn.Linear(inner, inner, bias=True)
        self.A_log    = nn.Parameter(-torch.exp(
            torch.randn(inner, cfg.ssm_d_state) * 0.1))
        self.B_proj   = nn.Linear(inner, cfg.ssm_d_state, bias=False)
        self.C_proj   = nn.Linear(inner, cfg.ssm_d_state, bias=False)
        self.D_param  = nn.Parameter(torch.ones(inner))
        self.out_proj = nn.Linear(inner, D, bias=False)
        self.norm     = RMSNorm(D)

    def forward(self, x, **kw):
        B, T, D = x.shape
        res = x
        x   = self.norm(x)
        xz  = self.in_proj(x)
        xi, z = xz.chunk(2, dim=-1)
        xc = self.conv(xi.transpose(1, 2))[:, :, :T].transpose(1, 2)
        xc = F.silu(xc)
        A  = self.A_log.float()
        B_ = self.B_proj(xc)
        C_ = self.C_proj(xc)
        dt = F.softplus(self.dt_proj(xc))
        dA = torch.exp(dt.unsqueeze(-1) * A.unsqueeze(0).unsqueeze(0))
        dB = dt.unsqueeze(-1) * B_.unsqueeze(2)
        h  = torch.zeros(B, self.inner, self.d_state, device=x.device, dtype=x.dtype)
        outs = []
        for t in range(T):
            h = h * dA[:, t] + dB[:, t] * xc[:, t].unsqueeze(-1)
            y = (h * C_[:, t].unsqueeze(1)).sum(-1)
            outs.append(y)
        y = torch.stack(outs, dim=1)
        y = y + xc * self.D_param
        y = y * F.silu(z)
        return res + self.out_proj(y)


# ══════════════════════════════════════════════════════════════════
# HIERARCHICAL MOE
# ══════════════════════════════════════════════════════════════════

class SwiGLU(nn.Module):
    def __init__(self, d_in: int, d_h: int):
        super().__init__()
        self.g = nn.Linear(d_in, d_h, bias=False)
        self.u = nn.Linear(d_in, d_h, bias=False)
        self.d = nn.Linear(d_h, d_in, bias=False)

    def forward(self, x):
        return self.d(F.silu(self.g(x)) * self.u(x))


class HierarchicalMoE(nn.Module):
    def __init__(self, cfg: AIONConfig, use_global: bool = False):
        super().__init__()
        D = cfg.dim
        self.aux_coef   = cfg.aux_loss_coef
        self.use_global = use_global
        self.aux_loss   = torch.tensor(0.0)

        # Token-level experts
        self.tok_router  = nn.Linear(D, cfg.n_tok_experts, bias=False)
        self.tok_experts = nn.ModuleList([
            SwiGLU(D, cfg.tok_expert_dim) for _ in range(cfg.n_tok_experts)])
        self.n_tok_active = cfg.n_tok_active

        # Seq-level experts
        self.seq_router  = nn.Linear(D, cfg.n_seq_experts, bias=False)
        self.seq_experts = nn.ModuleList([
            SwiGLU(D, cfg.seq_expert_dim) for _ in range(cfg.n_seq_experts)])
        self.n_seq_active = cfg.n_seq_active

        # Global experts
        if use_global:
            self.glob_router  = nn.Linear(D, cfg.n_glob_experts, bias=False)
            self.glob_experts = nn.ModuleList([
                SwiGLU(D, cfg.glob_expert_dim) for _ in range(cfg.n_glob_experts)])
            self.n_glob_active = cfg.n_glob_active

        # Shared
        self.shared = nn.ModuleList([
            SwiGLU(D, cfg.shared_dim) for _ in range(cfg.n_shared)])

        n_in = 3 if use_global else 2
        self.combine = nn.Linear(D * n_in, D, bias=False)

    def _dispatch(self, flat, router, experts, n_active):
        probs = F.softmax(router(flat), dim=-1)
        tw, ti = torch.topk(probs, n_active, dim=-1)
        tw = tw / tw.sum(-1, keepdim=True).clamp(min=1e-6)
        N, D = flat.shape
        out = torch.zeros_like(flat)
        for e_idx, expert in enumerate(experts):
            mask = (ti == e_idx).any(-1)
            if not mask.any():
                continue
            w = torch.zeros(mask.sum(), device=flat.device)
            for k in range(n_active):
                km = ti[mask, k] == e_idx
                if km.any():
                    w[km] = tw[mask, k][km]
            out[mask] += expert(flat[mask]) * w.unsqueeze(-1)
        # aux loss
        avg_p = probs.mean(0)
        frac  = torch.zeros(len(experts), device=flat.device)
        for k in range(n_active):
            frac.scatter_add_(0, ti[:, k], torch.ones(N, device=flat.device) / (N * n_active))
        aux = len(experts) * (avg_p * frac).sum()
        return out, aux

    def forward(self, x):
        B, T, D = x.shape
        flat = x.view(-1, D)
        total_aux = torch.tensor(0.0, device=x.device)

        # Shared
        shared_out = sum(s(flat) for s in self.shared) / len(self.shared)

        # Token level
        tok_out, aux1 = self._dispatch(flat, self.tok_router,
                                        self.tok_experts, self.n_tok_active)
        total_aux = total_aux + aux1

        # Seq level
        seq_out, aux2 = self._dispatch(flat, self.seq_router,
                                        self.seq_experts, self.n_seq_active)
        total_aux = total_aux + aux2

        # Global
        if self.use_global:
            glob_out, aux3 = self._dispatch(flat, self.glob_router,
                                             self.glob_experts, self.n_glob_active)
            total_aux = total_aux + aux3
            combined = self.combine(torch.cat([tok_out, seq_out, glob_out], dim=-1))
        else:
            combined = self.combine(torch.cat([tok_out, seq_out], dim=-1))

        self.aux_loss = total_aux * self.aux_coef
        return (combined + shared_out).view(B, T, D)


# ══════════════════════════════════════════════════════════════════
# AION BLOCK
# ══════════════════════════════════════════════════════════════════

class AIONBlock(nn.Module):
    def __init__(self, cfg: AIONConfig, layer_idx: int):
        super().__init__()
        self.layer_idx = layer_idx
        self.use_ssm   = (layer_idx % cfg.ssm_every_n == cfg.ssm_every_n - 1)
        use_global     = (layer_idx % cfg.glob_every_n == cfg.glob_every_n - 1)

        self.n1 = RMSNorm(cfg.dim, cfg.norm_eps)
        self.n2 = RMSNorm(cfg.dim, cfg.norm_eps)

        self.mixer = AIONSSMLayer(cfg) if self.use_ssm else AIONAttention(cfg)
        self.moe   = HierarchicalMoE(cfg, use_global=use_global)
        self.drop  = nn.Dropout(cfg.dropout)

    def forward(self, x, mask=None, lora: Optional[LiveLoRAAdapter] = None):
        # Mixer
        if self.use_ssm:
            x = self.mixer(x) + x
        else:
            x = x + self.drop(self.mixer(self.n1(x), mask=mask))

        # Live LoRA adaptation
        if lora is not None:
            x = lora.adapt(x, self.layer_idx)

        # MoE
        x = x + self.drop(self.moe(self.n2(x)))
        return x


# ══════════════════════════════════════════════════════════════════
# AION MODEL — النموذج الكامل
# ══════════════════════════════════════════════════════════════════

class AIONModel(nn.Module):
    """
    AION: Adaptive Intelligence with Ongoing self-reNormalization

    النموذج الأول الذي يعيد تعريف نفسه أثناء التفكير.
    """

    def __init__(self, cfg: AIONConfig):
        super().__init__()
        self.cfg = cfg

        # Core transformer
        self.embed  = nn.Embedding(cfg.vocab_size, cfg.dim)
        self.drop   = nn.Dropout(cfg.dropout)
        self.layers = nn.ModuleList([
            AIONBlock(cfg, i) for i in range(cfg.n_layers)])
        self.norm   = RMSNorm(cfg.dim, cfg.norm_eps)
        self.head   = nn.Linear(cfg.dim, cfg.vocab_size, bias=False)
        if cfg.tie_embeddings:
            self.head.weight = self.embed.weight

        # ── المكونات الثورية ──────────────────────────────────
        # ① Live LoRA
        self.lora = LiveLoRAAdapter(
            cfg.dim, cfg.lora_rank, cfg.lora_alpha, cfg.lora_layers)

        # ② Confidence Oracle
        self.oracle = ConfidenceOracle(cfg.dim, cfg.meta_dim)

        # ③ Meta-Cognitive Monitor
        self.meta = MetaCognitiveMonitor(cfg.dim, cfg.meta_dim)

        self.apply(self._init_weights)
        for n, p in self.named_parameters():
            if any(k in n for k in ['o_proj', 'down', 'out_proj', 'combine']):
                nn.init.normal_(p, std=cfg.init_std / math.sqrt(2 * cfg.n_layers))

        total = self.count_params()
        ssm_n = sum(1 for i in range(cfg.n_layers) if i % cfg.ssm_every_n == cfg.ssm_every_n - 1)
        glob_n = cfg.n_layers // cfg.glob_every_n
        print(f"""
╔══════════════════════════════════════════╗
║           AION v1.0 Ready                ║
╠══════════════════════════════════════════╣
║  Total params:  {total/1e9:6.2f}B                ║
║  Layers:        {cfg.n_layers}                      ║
║  SSM layers:    {ssm_n}                       ║
║  Global MoE:    every {cfg.glob_every_n} layers ({glob_n} total)  ║
║  Tok experts:   {cfg.n_tok_experts}→{cfg.n_tok_active} active              ║
║  Seq experts:   {cfg.n_seq_experts}→{cfg.n_seq_active} active               ║
║  LoRA rank:     {cfg.lora_rank}                      ║
║  Context:       {cfg.max_seq_len}                  ║
╚══════════════════════════════════════════╝""")

    def _init_weights(self, m):
        if isinstance(m, nn.Linear):
            nn.init.normal_(m.weight, std=self.cfg.init_std)
            if m.bias is not None:
                nn.init.zeros_(m.bias)
        elif isinstance(m, nn.Embedding):
            nn.init.normal_(m.weight, std=self.cfg.init_std)

    def count_params(self):
        return sum(p.numel() for p in self.parameters() if p.requires_grad)

    def get_aux_loss(self):
        loss = torch.tensor(0.0)
        for l in self.layers:
            loss = loss + l.moe.aux_loss.cpu()
        return loss

    def _forward_once(self, x, mask, lora=None):
        """تشغيل واحد عبر الطبقات"""
        h = self.drop(self.embed(x))
        self.meta.reset()
        layer_states = []

        for i, layer in enumerate(self.layers):
            h = layer(h, mask=mask, lora=lora)
            # Meta monitor يراقب كل طبقة
            if i % 4 == 3:
                guidance, contra = self.meta(h)
                h = h + guidance * 0.1   # إشارة توجيه خفيفة
                layer_states.append(h.detach())

        h = self.norm(h)
        return h, layer_states

    def forward(self, x: torch.Tensor,
                targets: Optional[torch.Tensor] = None,
                enable_metacog: bool = False) -> Tuple[torch.Tensor, Optional[torch.Tensor]]:
        B, T = x.shape
        mask = torch.triu(
            torch.full((T, T), float('-inf'), device=x.device),
            diagonal=1).unsqueeze(0).unsqueeze(0)

        if enable_metacog and not self.training:
            # ══ Meta-Cognitive Loop ══════════════════════════
            history = None
            for attempt in range(self.cfg.max_rethink_steps):
                # تشغيل عادي أول مرة
                h, states = self._forward_once(x, mask,
                    lora=self.lora if attempt > 0 else None)

                # قيّم الثقة
                conf = self.oracle(h, history)
                avg_conf = conf.mean().item()

                if avg_conf >= self.cfg.confidence_threshold or \
                   attempt == self.cfg.max_rethink_steps - 1:
                    break

                # ثقة منخفضة → فعّل LoRA وأعد
                strength = 1.0 - avg_conf   # كلما أقل ثقة كلما أقوى LoRA
                self.lora.activate(strength)
                history = h.detach()

            self.lora.deactivate()
        else:
            h, _ = self._forward_once(x, mask)

        logits = self.head(h)
        loss = None
        if targets is not None:
            ce = F.cross_entropy(
                logits.view(-1, self.cfg.vocab_size),
                targets.view(-1), ignore_index=-1)
            loss = ce + self.get_aux_loss().to(ce.device)

        return logits, loss

    @torch.no_grad()
    def generate(self, idx, max_new=512, temperature=0.7,
                 top_p=0.9, top_k=50, rep_penalty=1.1,
                 enable_metacog=True):
        self.eval()
        for _ in range(max_new):
            ctx = idx[:, -self.cfg.max_seq_len:]
            logits, _ = self(ctx, enable_metacog=enable_metacog)
            logits = logits[:, -1, :].float()
            if rep_penalty != 1.0:
                for t in set(idx[0].tolist()[-200:]):
                    logits[0, t] = logits[0, t] / rep_penalty
            logits /= max(temperature, 1e-5)
            if top_k > 0:
                v, _ = torch.topk(logits, min(top_k, logits.shape[-1]))
                logits[logits < v[:, -1:]] = float('-inf')
            probs = F.softmax(logits, dim=-1)
            sp, si = torch.sort(probs, descending=True)
            cum = torch.cumsum(sp, dim=-1)
            sp[cum - sp > top_p] = 0.0
            sp /= sp.sum(-1, keepdim=True).clamp(min=1e-6)
            nt = si.gather(-1, torch.multinomial(sp, 1))
            idx = torch.cat([idx, nt], dim=-1)
            if nt.item() == 2:
                break
        return idx


# ══════════════════════════════════════════════════════════════════
# CONFIGS
# ══════════════════════════════════════════════════════════════════

def OmegaConfig():
    return AIONConfig()


def get_config(size: str = 'nano') -> AIONConfig:
    if size == 'nano':
        return AIONConfig(
            vocab_size=1024, dim=128, n_layers=4,
            n_heads=4, n_kv_heads=2, head_dim=32,
            n_tok_experts=4, n_tok_active=1, tok_expert_dim=64,
            n_seq_experts=2, n_seq_active=1, seq_expert_dim=128,
            n_glob_experts=2, n_glob_active=1, glob_expert_dim=128, glob_every_n=4,
            n_shared=1, shared_dim=32,
            ssm_d_state=16, ssm_expand=2, ssm_d_conv=4, ssm_every_n=4,
            meta_dim=32, lora_rank=8, lora_alpha=16.0, lora_layers=4,
            max_seq_len=128,
        )
    elif size == 'small':
        return AIONConfig(
            vocab_size=16000, dim=512, n_layers=8,
            n_heads=8, n_kv_heads=2, head_dim=64,
            n_tok_experts=8, n_tok_active=2, tok_expert_dim=256,
            n_seq_experts=4, n_seq_active=2, seq_expert_dim=512,
            n_glob_experts=2, n_glob_active=1, glob_expert_dim=512, glob_every_n=4,
            n_shared=1, shared_dim=128,
            ssm_d_state=64, ssm_expand=2, ssm_d_conv=4, ssm_every_n=4,
            meta_dim=64, lora_rank=16, lora_alpha=32.0, lora_layers=8,
            max_seq_len=1024,
        )
    else:  # large = 16GB target
        return AIONConfig()


# Aliases for backward compatibility
OmegaModel  = AIONModel
OmegaConfig = AIONConfig
NovaConfig  = AIONConfig
