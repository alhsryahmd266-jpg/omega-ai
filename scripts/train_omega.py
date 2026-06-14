import os, sys, json, torch
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from omega.model.architecture import get_config, OmegaModel
from omega.trainer.train import OmegaTrainer
from scripts.prepare_data import get_training_data

def main():
    is_ci = os.environ.get('CI','false') == 'true'
    
    if is_ci:
        cfg = get_config('nano')
        tc = dict(batch_size=2, seq_len=64, max_lr=3e-4, min_lr=1e-5,
                  warmup=20, total_steps=200, grad_accum=2,
                  grad_clip=1.0, wd=0.1, log_every=20,
                  out_dir='checkpoints', epochs=1)
        print("Mode: CI nano")
    else:
        cfg = get_config('large')  # 16GB target
        tc = dict(batch_size=4, seq_len=512, max_lr=2e-4, min_lr=1e-5,
                  warmup=500, total_steps=50000, grad_accum=8,
                  grad_clip=1.0, wd=0.1, log_every=100,
                  out_dir='checkpoints', epochs=10)
        print("Mode: LARGE (16GB)")

    data_path = 'data/training_data.json'
    if os.path.exists(data_path):
        with open(data_path, 'r', encoding='utf-8') as f:
            samples = json.load(f)
    else:
        samples = get_training_data()

    trainer = OmegaTrainer(cfg, tc)
    trainer.run(samples, epochs=tc['epochs'])

    os.makedirs('checkpoints', exist_ok=True)
    cfg_dict = {k: v for k, v in cfg.__dict__.items() if not k.startswith('_')}
    with open('checkpoints/config.json','w') as f:
        json.dump(cfg_dict, f, indent=2)
    print("Done!")

if __name__ == '__main__': main()
