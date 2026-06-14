import os, sys, json, torch
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from omega.model.architecture import OmegaConfig, OmegaModel
from omega.trainer.train import OmegaTrainer
from scripts.prepare_data import get_training_data

def main():
    is_ci = os.environ.get('CI','false') == 'true'
    if is_ci:
        cfg = OmegaConfig(
            vocab_size=16000, dim=512, n_layers=8,
            n_heads=8, n_kv_heads=2, head_dim=64,
            kv_lora_rank=128, q_lora_rank=192, rope_head_dim=16,
            n_experts=4, n_active_experts=2, n_shared_experts=1,
            expert_dim=512, ssm_d_state=32, ssm_expand=2,
            ssm_headdim=32, ssm_layers_freq=3,
            max_seq_len=256, dropout=0.05,
        )
        tc = dict(batch_size=2, seq_len=128, max_lr=3e-4, min_lr=1e-5,
                  warmup=30, total_steps=300, grad_accum=2,
                  grad_clip=1.0, wd=0.1, log_every=20,
                  out_dir='checkpoints', epochs=2)
        print("Mode: CI quick-train")
    else:
        cfg = OmegaConfig()  # Full config
        tc = dict(batch_size=8, seq_len=512, max_lr=3e-4, min_lr=1e-5,
                  warmup=200, total_steps=20000, grad_accum=8,
                  grad_clip=1.0, wd=0.1, log_every=100,
                  out_dir='checkpoints', epochs=10)
        print("Mode: Full training")

    data_path = 'data/training_data.json'
    if os.path.exists(data_path):
        with open(data_path, 'r', encoding='utf-8') as f:
            samples = json.load(f)
    else:
        samples = get_training_data()

    trainer = OmegaTrainer(cfg, tc)
    trainer.run(samples, epochs=tc['epochs'])

    os.makedirs('checkpoints', exist_ok=True)
    with open('checkpoints/config.json','w') as f:
        json.dump(cfg.__dict__, f, indent=2)
    print("✅ Training complete!")

if __name__ == '__main__': main()
