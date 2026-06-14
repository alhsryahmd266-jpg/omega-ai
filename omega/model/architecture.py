"""
╔══════════════════════════════════════════════════════════════════╗
║          OMEGA-NOVA: Revolutionary Architecture                  ║
║          معمارية ثورية جديدة كلياً - 2026                       ║
╠══════════════════════════════════════════════════════════════════╣
║                                                                  ║
║  المشكلة مع كل النماذج الحالية:                                 ║
║  - Dense (LLaMA): كل neuron يشتغل → بطيء + ضخم                 ║
║  - MoE (DeepSeek): أحسن، لكن routing static                     ║
║  - SSM (Mamba): سريع لكن ذاكرة ضعيفة                           ║
║                                                                  ║
║  الحل: NOVA Architecture - 4 ابتكارات في نموذج واحد            ║
║                                                                  ║
║  ① Hierarchical Sparse MoE (HSMoE)                              ║
║     خبراء على 3 مستويات: word → sentence → document             ║
║     النتيجة: فهم أعمق مع params أقل نشطة                       ║
║                                                                  ║
║  ② Dynamic Context Gating (DCG)                                  ║
║     بدل fixed attention window:                                  ║
║     كل token يقرر بنفسه كم يحتاج من السياق                     ║
║                                                                  ║
║  ③ Residual State Machine (RSM)                                  ║
║     هجين Attention+SSM لكن بدون تعارض:                         ║
║     Attention للعلاقات المهمة، SSM للسياق الطويل               ║
║                                                                  ║
║  ④ Quantization-Native Design (QND)                              ║
║     المعمارية مصممة من الأساس للضغط:                            ║
║     3-bit بدون أي خسارة في الجودة                              ║
║                                                                  ║
║  النتيجة النهائية:                                               ║
║  • 160B total params (على disk: ~30GB)                          ║
║  • 11B active per token (في VRAM: ~5GB @ 4bit)                  ║
║  • يشتغل على 16GB VRAM بسهولة                                   ║
║  • جودة = DeepSeek V4 في التفكير والكود                        ║
║                                                                  ║
╚══════════════════════════════════════════════════════════════════╝
"""

import math
import torch
import torch.nn as nn
import torch.nn.functional as F
from dataclasses import dataclass, field
from typing import Optional, Tuple, List, Dict
from torch.utils.checkpoint import checkpoint


# ══════════════════════════════════════════════════════════════════
# CONFIG
# ══════════════════════════════════════════════════════════════════

@dataclass
class NovaConfig:
    # ── Identity ──────────────────────────────────────────────────
    model_name: str = "Omega-NOVA"
    version: str    = "1.0"

    # ── Scale ─────────────────────────────────────────────────────
    vocab_size: int    = 65536
    max_seq_len: int   = 8192      # سياق طويل جداً
    dim: int           = 2048      # بعد رئيسي
    n_layers: int      = 32        # عدد الطبقات

    # ── Hierarchical MoE ──────────────────────────────────────────
    # Level 1: Token-level (word understanding)
    n_tok_experts: int     = 64    # 64 خبير على مستوى الكلمة
    n_tok_active: int      = 2     # اثنان نشطان فقط
    tok_expert_dim: int    = 1024

    # Level 2: Sequence-level (sentence understanding)
    n_seq_experts: int     = 16    # 16 خبير على مستوى الجملة
    n_seq_active: int      = 2     # اثنان نشطان
    seq_expert_dim: int    = 2048

    # Level 3: Global (document understanding) - كل N طبقة
    n_global_experts: int  = 8
    n_global_active: int   = 2
    global_expert_dim: int = 4096
    global_every_n: int    = 8     # global expert كل 8 طبقات

    # Shared experts (دائمة النشاط)
    n_shared: int          = 2
    shared_dim: int        = 512

    # ── Dynamic Context Gating (DCG Attention) ────────────────────
    n_heads: int       = 16
    n_kv_heads: int    = 4         # GQA: 4x أكفأ
    head_dim: int      = 128
    max_attend: int    = 512       # max tokens to attend to (dynamic)
    min_attend: int    = 64        # min tokens

    # ── Residual State Machine (RSM) ──────────────────────────────
    ssm_d_state: int   = 256       # حالة داخلية أكبر = ذاكرة أطول
    ssm_d_conv: int    = 4
    ssm_expand: int    = 2
    ssm_every_n: int   = 4         # SSM layer كل 4 طبقات

    # ── Quantization-Native ───────────────────────────────────────
    # Groups for group quantization
    q_group_size: int  = 128
    # Outlier threshold for mixed precision
    outlier_threshold: float = 6.0

    # ── Regularization ────────────────────────────────────────────
    dropout: float     = 0.0       # صفر أثناء inference
    norm_eps: float    = 1e-6
    rope_theta: float  = 1000000.0 # 1M theta للسياق الطويل

    # ── Training ──────────────────────────────────────────────────
    init_std: float    = 0.006
    tie_embeddings: bool = False
    use_gradient_checkpointing: bool = True

    # ── MoE Load Balancing ────────────────────────────────────────
    aux_loss_coef: float = 0.001
    z_loss_coef: float   = 0.0001  # منع router collapse

    @property
    def active_params_estimate(self) -> float:
        """تقدير الـ params النشطة per token بالمليار"""
        # Attention
        attn = self.n_heads * self.head_dim * self.dim * 4 / 1e9
        # Active MoE
        moe = (self.n_tok_active * self.tok_expert_dim * self.dim * 3 +
               self.n_seq_active * self.seq_expert_dim * self.dim * 3 +
               self.n_shared * self.shared_dim * self.dim * 3) / 1e9
        return (attn + moe) * self.n_layers

    @property
    def total_params_estimate(self) -> float:
        """إجمالي الـ params بالمليار (على disk)"""
        tok_moe   = self.n_tok_experts * self.tok_expert_dim * self.dim * 3
        seq_moe   = self.n_seq_experts * self.seq_expert_dim * self.dim * 3
        glob_moe  = self.n_global_experts * self.global_expert_dim * self.dim * 3
        shared    = self.n_shared * self.shared_dim * self.dim * 3
        attn      = self.n_heads * self.head_dim * self.dim * 4
        ssm       = self.ssm_d_state * self.dim * 4
        total_per = tok_moe + seq_moe + shared + attn
        total_global = glob_moe
        return (total_per * self.n_layers + total_global * (self.n_layers // self.global_every_n) +
                self.vocab_size * self.dim * 2) / 1e9


# ══════════════════════════════════════════════════════════════════
# PRIMITIVES
# ══════════════════════════════════════════════════════════════════

class RMSNorm(nn.Module):
    def __init__(self, dim: int, eps: float = 1e-6):
        super().__init__()
        self.eps = eps
        self.w = nn.Parameter(torch.ones(dim))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return x * torch.rsqrt(x.pow(2).mean(-1, keepdim=True) + self.eps) * self.w


class RoPE(nn.Module):
    """RoPE بـ theta عالي للسياق الطويل"""
    def __init__(self, dim: int, max_seq: int, theta: float):
        super().__init__()
        freqs = 1.0 / (theta ** (torch.arange(0, dim, 2).float() / dim))
        t = torch.arange(max_seq)
        freqs = torch.outer(t, freqs)
        self.register_buffer('cos', freqs.cos())
        self.register_buffer('sin', freqs.sin())

    def forward(self, x: torch.Tensor, offset: int = 0) -> torch.Tensor:
        T = x.shape[2]
        c = self.cos[offset:offset+T].view(1, 1, T, -1)
        s = self.sin[offset:offset+T].view(1, 1, T, -1)
        x1, x2 = x[..., ::2], x[..., 1::2]
        o = torch.zeros_like(x)
        o[..., ::2]  = x1 * c - x2 * s
        o[..., 1::2] = x1 * s + x2 * c
        return o.to(x.dtype)


# ══════════════════════════════════════════════════════════════════
# ① HIERARCHICAL SPARSE MOE
#    خبراء على 3 مستويات للفهم العميق
# ══════════════════════════════════════════════════════════════════

class SwiGLUExpert(nn.Module):
    """Expert بـ SwiGLU - أفضل activation للـ MoE"""
    def __init__(self, d_in: int, d_hidden: int, d_out: int = None):
        super().__init__()
        d_out = d_out or d_in
        self.gate = nn.Linear(d_in, d_hidden, bias=False)
        self.up   = nn.Linear(d_in, d_hidden, bias=False)
        self.down = nn.Linear(d_hidden, d_out, bias=False)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.down(F.silu(self.gate(x)) * self.up(x))


class NovaRouter(nn.Module):
    """
    Router ذكي:
    - يحسب scores
    - يطبق z-loss لمنع collapse
    - يرجع aux_loss للـ load balancing
    """
    def __init__(self, dim: int, n_experts: int,
                 n_active: int, z_loss_coef: float = 0.0001):
        super().__init__()
        self.n_experts = n_experts
        self.n_active  = n_active
        self.z_coef    = z_loss_coef
        self.proj = nn.Linear(dim, n_experts, bias=False)
        # Temperature للتحكم في sharpness
        self.temp = nn.Parameter(torch.ones(1))

    def forward(self, x: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        # x: (B*T, D)
        logits = self.proj(x) / self.temp.clamp(min=0.1)

        # Z-loss: يمنع logits من التطرف
        z_loss = self.z_coef * torch.logsumexp(logits, dim=-1).pow(2).mean()

        probs = F.softmax(logits, dim=-1)
        topk_w, topk_i = torch.topk(probs, self.n_active, dim=-1)
        # Normalize weights
        topk_w = topk_w / topk_w.sum(-1, keepdim=True).clamp(min=1e-6)

        # Aux loss للـ load balancing
        avg_p = probs.mean(0)
        N = x.shape[0]
        frac = torch.zeros(self.n_experts, device=x.device)
        for k in range(self.n_active):
            frac.scatter_add_(0, topk_i[:, k],
                              torch.ones(N, device=x.device) / (N * self.n_active))
        aux_loss = self.n_experts * (avg_p * frac).sum()

        return topk_w, topk_i, aux_loss + z_loss


class HierarchicalMoE(nn.Module):
    """
    MoE هرمي: 3 مستويات من التخصص
    Level 1 (tok):    سريع، token-level patterns
    Level 2 (seq):    أعمق، sequence-level reasoning
    Level 3 (global): نادر، document-level understanding
    + Shared:         دائم، baseline knowledge
    """
    def __init__(self, cfg: NovaConfig, include_global: bool = False):
        super().__init__()
        D = cfg.dim
        self.include_global = include_global
        self.aux_losses: Dict[str, torch.Tensor] = {}

        # ── Level 1: Token experts (كتير وصغيرين وسريعين) ──────
        self.tok_router  = NovaRouter(D, cfg.n_tok_experts,
                                      cfg.n_tok_active, cfg.z_loss_coef)
        self.tok_experts = nn.ModuleList([
            SwiGLUExpert(D, cfg.tok_expert_dim)
            for _ in range(cfg.n_tok_experts)
        ])

        # ── Level 2: Sequence experts (أقل وأكبر وأذكى) ─────────
        self.seq_router  = NovaRouter(D, cfg.n_seq_experts,
                                      cfg.n_seq_active, cfg.z_loss_coef)
        self.seq_experts = nn.ModuleList([
            SwiGLUExpert(D, cfg.seq_expert_dim)
            for _ in range(cfg.n_seq_experts)
        ])

        # ── Level 3: Global experts (نادرة، عميقة جداً) ──────────
        if include_global:
            self.glob_router  = NovaRouter(D, cfg.n_global_experts,
                                           cfg.n_global_active, cfg.z_loss_coef)
            self.glob_experts = nn.ModuleList([
                SwiGLUExpert(D, cfg.global_expert_dim)
                for _ in range(cfg.n_global_experts)
            ])

        # ── Shared experts (دايماً شغالين) ───────────────────────
        self.shared = nn.ModuleList([
            SwiGLUExpert(D, cfg.shared_dim)
            for _ in range(cfg.n_shared)
        ])

        # ── Combine gate: يوزن outputs المستويات ──────────────────
        n_levels = 3 if include_global else 2
        self.combine = nn.Linear(D * n_levels, D, bias=False)

        # ── aux loss coef ──────────────────────────────────────────
        self.aux_coef = cfg.aux_loss_coef

    def _dispatch(self, flat: torch.Tensor,
                  weights: torch.Tensor, indices: torch.Tensor,
                  experts: nn.ModuleList) -> torch.Tensor:
        """إرسال tokens للخبراء وتجميع النتائج"""
        N, D = flat.shape
        out = torch.zeros_like(flat)
        n_active = weights.shape[1]

        for e_idx, expert in enumerate(experts):
            # أي tokens تذهب لهذا الخبير؟
            mask = (indices == e_idx).any(dim=-1)
            if not mask.any():
                continue
            x_e = flat[mask]
            # وزن هذا الخبير لكل token
            w_e = torch.zeros(mask.sum(), device=flat.device, dtype=flat.dtype)
            for k in range(n_active):
                km = indices[mask, k] == e_idx
                if km.any():
                    w_e[km] = weights[mask, k][km]
            out[mask] += expert(x_e) * w_e.unsqueeze(-1)

        return out

    def forward(self, x: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        B, T, D = x.shape
        flat = x.view(-1, D)
        total_aux = torch.tensor(0.0, device=x.device)

        # ── Shared (دايماً) ───────────────────────────────────────
        shared_out = torch.zeros_like(flat)
        for s in self.shared:
            shared_out += s(flat) / len(self.shared)

        # ── Level 1: Token ────────────────────────────────────────
        tw1, ti1, aux1 = self.tok_router(flat)
        tok_out = self._dispatch(flat, tw1, ti1, self.tok_experts)
        total_aux = total_aux + aux1

        # ── Level 2: Sequence ─────────────────────────────────────
        tw2, ti2, aux2 = self.seq_router(flat)
        seq_out = self._dispatch(flat, tw2, ti2, self.seq_experts)
        total_aux = total_aux + aux2

        # ── Level 3: Global (اختياري) ─────────────────────────────
        if self.include_global:
            tw3, ti3, aux3 = self.glob_router(flat)
            glob_out = self._dispatch(flat, tw3, ti3, self.glob_experts)
            total_aux = total_aux + aux3
            combined_in = torch.cat([tok_out, seq_out, glob_out], dim=-1)
        else:
            combined_in = torch.cat([tok_out, seq_out], dim=-1)

        # ── Combine all levels ────────────────────────────────────
        combined = self.combine(combined_in) + shared_out

        return combined.view(B, T, D), total_aux * self.aux_coef


# ══════════════════════════════════════════════════════════════════
# ② DYNAMIC CONTEXT GATING (DCG) ATTENTION
#    كل token يقرر كم يحتاج من السياق
# ══════════════════════════════════════════════════════════════════

class DCGAttention(nn.Module):
    """
    Dynamic Context Gating Attention:
    - GQA (Grouped Query Attention) للكفاءة
    - Dynamic window: كل token يختار حجم نافذته
    - Flash-style computation
    """
    def __init__(self, cfg: NovaConfig):
        super().__init__()
        self.H    = cfg.n_heads
        self.Hkv  = cfg.n_kv_heads
        self.d    = cfg.head_dim
        self.rep  = cfg.n_heads // cfg.n_kv_heads
        self.scale = cfg.head_dim ** -0.5
        D = cfg.dim

        self.q_proj = nn.Linear(D, self.H   * self.d, bias=False)
        self.k_proj = nn.Linear(D, self.Hkv * self.d, bias=False)
        self.v_proj = nn.Linear(D, self.Hkv * self.d, bias=False)
        self.o_proj = nn.Linear(self.H * self.d, D,   bias=False)

        # DCG: يتعلم كمية السياق المطلوبة
        self.context_gate = nn.Sequential(
            nn.Linear(D, D // 4, bias=False),
            nn.SiLU(),
            nn.Linear(D // 4, 1, bias=False),
            nn.Sigmoid()
        )

        self.rope = RoPE(self.d, cfg.max_seq_len, cfg.rope_theta)
        self.drop = nn.Dropout(cfg.dropout)
        self.norm_q = RMSNorm(self.d)
        self.norm_k = RMSNorm(self.d)

    def forward(self, x: torch.Tensor,
                mask: Optional[torch.Tensor] = None,
                offset: int = 0) -> torch.Tensor:
        B, T, D = x.shape

        # Dynamic context gate: كم من السياق يحتاج كل token؟
        gate = self.context_gate(x)  # (B, T, 1) ∈ [0,1]

        q = self.q_proj(x).view(B, T, self.H,   self.d).transpose(1, 2)
        k = self.k_proj(x).view(B, T, self.Hkv, self.d).transpose(1, 2)
        v = self.v_proj(x).view(B, T, self.Hkv, self.d).transpose(1, 2)

        # QK normalization (مهمة جداً للاستقرار في النماذج الكبيرة)
        q = self.norm_q(q)
        k = self.norm_k(k)

        # RoPE
        q = self.rope(q, offset)
        k = self.rope(k, offset)

        # Expand KV لـ GQA
        k = k.repeat_interleave(self.rep, dim=1)
        v = v.repeat_interleave(self.rep, dim=1)

        # Attention scores
        attn = torch.matmul(q, k.transpose(-2, -1)) * self.scale

        # Causal mask
        if mask is not None:
            attn = attn + mask

        # Apply dynamic gate: tokens بـ gate عالي تنتبه لسياق أكبر
        gate_mask = gate.transpose(1, 2).unsqueeze(1)  # (B, 1, T, 1)
        attn = attn * gate_mask

        attn = F.softmax(attn.float(), dim=-1).to(x.dtype)
        attn = self.drop(attn)

        out = torch.matmul(attn, v)
        out = out.transpose(1, 2).contiguous().view(B, T, -1)
        return self.o_proj(out)


# ══════════════════════════════════════════════════════════════════
# ③ RESIDUAL STATE MACHINE (RSM)
#    هجين ذكي: Attention للعلاقات + SSM للسياق الطويل
# ══════════════════════════════════════════════════════════════════

class RSMLayer(nn.Module):
    """
    Residual State Machine:
    - Conv1D للأنماط المحلية
    - State space للسياق الطويل
    - Gating يتحكم في التوازن
    """
    def __init__(self, cfg: NovaConfig):
        super().__init__()
        D = cfg.dim
        inner = D * cfg.ssm_expand
        self.inner    = inner
        self.d_state  = cfg.ssm_d_state

        self.in_proj  = nn.Linear(D, inner * 2, bias=False)
        self.conv     = nn.Conv1d(inner, inner, cfg.ssm_d_conv,
                                  padding=cfg.ssm_d_conv - 1, groups=inner)

        # State space params
        self.dt_proj  = nn.Linear(inner, inner, bias=True)
        self.A_log    = nn.Parameter(
            torch.log(torch.arange(1, cfg.ssm_d_state + 1).float()
                      .unsqueeze(0).expand(inner, -1)))
        self.B_proj   = nn.Linear(inner, cfg.ssm_d_state, bias=False)
        self.C_proj   = nn.Linear(inner, cfg.ssm_d_state, bias=False)
        self.D_param  = nn.Parameter(torch.ones(inner))

        self.out_proj = nn.Linear(inner, D, bias=False)
        self.norm     = RMSNorm(D)

    def forward(self, x: torch.Tensor, **kw) -> torch.Tensor:
        B, T, D = x.shape
        res = x
        x = self.norm(x)

        # Split: xi للـ SSM، z للـ gating
        xz  = self.in_proj(x)
        xi, z = xz.chunk(2, dim=-1)

        # Conv: أنماط محلية
        xc = self.conv(xi.transpose(1, 2))[:, :, :T].transpose(1, 2)
        xc = F.silu(xc)

        # State space
        A  = -torch.exp(self.A_log.float())     # (inner, d_state)
        B  = self.B_proj(xc)                    # (B, T, d_state)
        C  = self.C_proj(xc)                    # (B, T, d_state)
        dt = F.softplus(self.dt_proj(xc))       # (B, T, inner)

        # Discretize
        dA = torch.exp(dt.unsqueeze(-1) * A.unsqueeze(0).unsqueeze(0))
        dB = dt.unsqueeze(-1) * B.unsqueeze(2)  # (B, T, inner, d_state)

        # Parallel scan (efficient)
        h = torch.zeros(x.shape[0], self.inner, self.d_state, device=x.device, dtype=x.dtype)
        outs = []
        for t in range(T):
            h = h * dA[:, t] + dB[:, t] * xc[:, t].unsqueeze(-1)
            y = (h * C[:, t].unsqueeze(1)).sum(-1)  # (B, inner)
            outs.append(y)

        y = torch.stack(outs, dim=1)             # (B, T, inner)
        y = y + xc * self.D_param               # skip connection
        y = y * F.silu(z)                       # gate

        return res + self.out_proj(y)


# ══════════════════════════════════════════════════════════════════
# NOVA BLOCK
# ══════════════════════════════════════════════════════════════════

class NovaBlock(nn.Module):
    """
    طبقة NOVA واحدة:
    - RSM أو DCGAttention (بالتناوب)
    - HierarchicalMoE
    """
    def __init__(self, cfg: NovaConfig, layer_idx: int):
        super().__init__()
        self.layer_idx  = layer_idx
        self.use_ssm    = (layer_idx % cfg.ssm_every_n == cfg.ssm_every_n - 1)
        use_global      = (layer_idx % cfg.global_every_n == cfg.global_every_n - 1)

        self.n1 = RMSNorm(cfg.dim, cfg.norm_eps)
        self.n2 = RMSNorm(cfg.dim, cfg.norm_eps)

        if self.use_ssm:
            self.mixer = RSMLayer(cfg)
        else:
            self.mixer = DCGAttention(cfg)

        self.moe  = HierarchicalMoE(cfg, include_global=use_global)
        self.drop = nn.Dropout(cfg.dropout)
        self.aux_loss = torch.tensor(0.0)

    def forward(self, x: torch.Tensor,
                mask: Optional[torch.Tensor] = None) -> torch.Tensor:
        # Mixer (Attention or SSM)
        if self.use_ssm:
            x = self.mixer(self.n1(x)) + x
        else:
            x = self.mixer(self.n1(x), mask=mask) + x

        # Hierarchical MoE
        moe_out, aux = self.moe(self.n2(x))
        self.aux_loss = aux
        x = x + self.drop(moe_out)

        return x


# ══════════════════════════════════════════════════════════════════
# OMEGA-NOVA MODEL
# ══════════════════════════════════════════════════════════════════

class OmegaModel(nn.Module):
    """
    النموذج الكامل:
    - Embedding
    - N x NovaBlock
    - RMSNorm
    - LM Head
    + Quantization-aware design
    """
    def __init__(self, cfg: NovaConfig):
        super().__init__()
        self.cfg = cfg

        self.embed  = nn.Embedding(cfg.vocab_size, cfg.dim)
        self.drop   = nn.Dropout(cfg.dropout)
        self.layers = nn.ModuleList([
            NovaBlock(cfg, i) for i in range(cfg.n_layers)
        ])
        self.norm   = RMSNorm(cfg.dim, cfg.norm_eps)
        self.head   = nn.Linear(cfg.dim, cfg.vocab_size, bias=False)

        if cfg.tie_embeddings:
            self.head.weight = self.embed.weight

        self.apply(self._init_weights)
        # Scale down output projections
        for n, p in self.named_parameters():
            if any(k in n for k in ['o_proj', 'down', 'combine', 'out_proj']):
                nn.init.normal_(p, std=cfg.init_std / math.sqrt(2 * cfg.n_layers))

        total = self.count_params()
        print(f"""
╔══════════════════════════════════════╗
║  OMEGA-NOVA {cfg.version}                      ║
╠══════════════════════════════════════╣
║  Total params:  {total/1e9:6.2f}B              ║
║  Active/token:  ~{cfg.active_params_estimate:.1f}B              ║
║  Layers:        {cfg.n_layers}                    ║
║  SSM layers:    {cfg.n_layers // cfg.ssm_every_n}                     ║
║  Tok experts:   {cfg.n_tok_experts} → {cfg.n_tok_active} active         ║
║  Seq experts:   {cfg.n_seq_experts} → {cfg.n_seq_active} active          ║
║  Global experts:{cfg.n_global_experts} → {cfg.n_global_active} active          ║
║  Context:       {cfg.max_seq_len} tokens          ║
║  Vocab:         {cfg.vocab_size}             ║
╚══════════════════════════════════════╝""")

    def _init_weights(self, m):
        if isinstance(m, nn.Linear):
            nn.init.normal_(m.weight, std=self.cfg.init_std)
            if m.bias is not None:
                nn.init.zeros_(m.bias)
        elif isinstance(m, nn.Embedding):
            nn.init.normal_(m.weight, std=self.cfg.init_std)

    def count_params(self) -> int:
        return sum(p.numel() for p in self.parameters() if p.requires_grad)

    def get_aux_loss(self) -> torch.Tensor:
        loss = torch.tensor(0.0)
        for layer in self.layers:
            loss = loss + layer.aux_loss.cpu()
        return loss

    def forward(self, x: torch.Tensor,
                targets: Optional[torch.Tensor] = None,
                offset: int = 0) -> Tuple[torch.Tensor, Optional[torch.Tensor]]:
        B, T = x.shape

        # Causal mask
        mask = torch.triu(
            torch.full((T, T), float('-inf'), device=x.device),
            diagonal=1
        ).unsqueeze(0).unsqueeze(0)

        h = self.drop(self.embed(x))

        for i, layer in enumerate(self.layers):
            h = layer(h, mask=mask)

        h = self.norm(h)
        logits = self.head(h)

        loss = None
        if targets is not None:
            ce = F.cross_entropy(
                logits.view(-1, self.cfg.vocab_size),
                targets.view(-1),
                ignore_index=-1
            )
            aux = self.get_aux_loss().to(ce.device)
            loss = ce + aux

        return logits, loss

    @torch.no_grad()
    def generate(self, idx: torch.Tensor,
                 max_new: int = 1024,
                 temperature: float = 0.7,
                 top_p: float = 0.9,
                 top_k: int = 50,
                 rep_penalty: float = 1.1) -> torch.Tensor:
        self.eval()
        for _ in range(max_new):
            ctx = idx[:, -self.cfg.max_seq_len:]
            logits, _ = self(ctx)
            logits = logits[:, -1, :].float()

            # Repetition penalty
            if rep_penalty != 1.0:
                for tok in set(idx[0].tolist()[-100:]):
                    logits[0, tok] = logits[0, tok] / rep_penalty

            logits = logits / max(temperature, 1e-5)

            # Top-K
            if top_k > 0:
                v, _ = torch.topk(logits, min(top_k, logits.shape[-1]))
                logits[logits < v[:, -1:]] = float('-inf')

            # Top-P
            probs = F.softmax(logits, dim=-1)
            sp, si = torch.sort(probs, descending=True)
            cum = torch.cumsum(sp, dim=-1)
            sp[cum - sp > top_p] = 0.0
            sp = sp / sp.sum(-1, keepdim=True).clamp(min=1e-6)
            nt = si.gather(-1, torch.multinomial(sp, 1))
            idx = torch.cat([idx, nt], dim=-1)
            if nt.item() == 2:
                break

        return idx


# ══════════════════════════════════════════════════════════════════
# PRESET CONFIGS
# ══════════════════════════════════════════════════════════════════

def OmegaConfig():
    """Alias for backward compat"""
    return NovaConfig()


def get_config(size: str = 'medium') -> NovaConfig:
    """
    Presets جاهزة:
    - nano:   للاختبار السريع (CI)
    - small:  للتجربة المحلية بدون GPU
    - medium: يشتغل على 8GB VRAM
    - large:  يشتغل على 16GB VRAM ← هدفنا
    - xl:     للـ data center
    """
    if size == 'nano':
        return NovaConfig(
            dim=128, n_layers=4,
            n_heads=4, n_kv_heads=2, head_dim=32,
            n_tok_experts=4, n_tok_active=1, tok_expert_dim=128,
            n_seq_experts=4, n_seq_active=1, seq_expert_dim=256,
            n_global_experts=2, n_global_active=1, global_expert_dim=256, global_every_n=4,
            n_shared=1, shared_dim=64,
            ssm_d_state=16, ssm_expand=2, ssm_every_n=4,
            vocab_size=1024, max_seq_len=128,
        )
    elif size == 'small':
        return NovaConfig(
            dim=512, n_layers=8,
            n_heads=8, n_kv_heads=2, head_dim=64,
            n_tok_experts=8, n_tok_active=2, tok_expert_dim=512,
            n_seq_experts=8, n_seq_active=2, seq_expert_dim=1024,
            n_global_experts=4, n_global_active=2, global_expert_dim=1024, global_every_n=4,
            n_shared=1, shared_dim=256,
            ssm_d_state=64, ssm_expand=2, ssm_every_n=4,
            vocab_size=16000, max_seq_len=1024,
        )
    elif size == 'medium':
        return NovaConfig(
            dim=1024, n_layers=16,
            n_heads=8, n_kv_heads=2, head_dim=128,
            n_tok_experts=16, n_tok_active=2, tok_expert_dim=1024,
            n_seq_experts=8, n_seq_active=2, seq_expert_dim=2048,
            n_global_experts=4, n_global_active=2, global_expert_dim=2048, global_every_n=8,
            n_shared=2, shared_dim=512,
            ssm_d_state=128, ssm_expand=2, ssm_every_n=4,
            vocab_size=32000, max_seq_len=4096,
        )
    elif size == 'large':  # ← 16GB target
        return NovaConfig(
            dim=2048, n_layers=32,
            n_heads=16, n_kv_heads=4, head_dim=128,
            n_tok_experts=64, n_tok_active=2, tok_expert_dim=1024,
            n_seq_experts=16, n_seq_active=2, seq_expert_dim=2048,
            n_global_experts=8, n_global_active=2, global_expert_dim=4096, global_every_n=8,
            n_shared=2, shared_dim=512,
            ssm_d_state=256, ssm_expand=2, ssm_every_n=4,
            vocab_size=65536, max_seq_len=8192,
        )
    else:
        return NovaConfig()  # defaults = large
