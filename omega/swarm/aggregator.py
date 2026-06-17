"""
AION-SWARM Gradient Aggregator
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
يجمع gradients من كل الـ shards
ويحدّث النموذج الرئيسي
"""
import os, sys, json, glob, time
import torch
import torch.nn as nn
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
from omega.model.architecture import get_config, AIONModel, AIONConfig


def federated_average(grad_paths: list, weights: list = None) -> dict:
    """
    Federated Averaging: متوسط مرجّح للـ gradients
    كل shard يُساهم بوزن يتناسب مع جودة أدائه
    """
    if not grad_paths:
        return {}

    # تحميل أول grad لمعرفة الأشكال
    base = torch.load(grad_paths[0], map_location='cpu')
    averaged = {k: torch.zeros_like(v) for k, v in base.items()}

    # أوزان موحدة لو مش محددة
    if weights is None:
        weights = [1.0 / len(grad_paths)] * len(grad_paths)
    else:
        total = sum(weights)
        weights = [w / total for w in weights]

    loaded = 0
    for path, w in zip(grad_paths, weights):
        if not os.path.exists(path):
            print(f"⚠️  Missing: {path}")
            continue
        grads = torch.load(path, map_location='cpu')
        for k in averaged:
            if k in grads:
                averaged[k] += grads[k] * w
        loaded += 1
        print(f"  ✅ Loaded shard: {os.path.basename(path)} (weight={w:.3f})")

    print(f"\n📊 Aggregated {loaded}/{len(grad_paths)} shards")
    return averaged


def apply_gradients(model: AIONModel, averaged_grads: dict,
                    lr: float = 1e-4) -> AIONModel:
    """تطبيق الـ gradients المدمجة على النموذج"""
    with torch.no_grad():
        for name, param in model.named_parameters():
            if name in averaged_grads:
                param.data -= lr * averaged_grads[name]
    return model


def weights_average(weight_paths: list, losses: list = None) -> dict:
    """
    Model Soup: متوسط الـ weights مباشرة
    أحياناً أفضل من دمج الـ gradients
    """
    if not weight_paths:
        return {}

    # أوزان المدمج بعكس الـ loss (loss أقل = وزن أكبر)
    if losses:
        inv_losses = [1.0 / max(l, 1e-6) for l in losses]
        total = sum(inv_losses)
        blend = [w / total for w in inv_losses]
    else:
        blend = [1.0 / len(weight_paths)] * len(weight_paths)

    base_data = torch.load(weight_paths[0], map_location='cpu')
    state = base_data['model']
    averaged = {k: torch.zeros_like(v) for k, v in state.items()}

    for path, w in zip(weight_paths, blend):
        if not os.path.exists(path):
            continue
        data = torch.load(path, map_location='cpu')
        for k in averaged:
            if k in data['model']:
                averaged[k] += data['model'][k] * w

    return averaged


def aggregate(swarm_dir: str, checkpoint_path: str, config_path: str,
              out_path: str, method: str = 'soup') -> dict:
    """
    دمج كامل:
    1. جمع نتائج الـ shards
    2. دمج الـ weights أو الـ gradients
    3. حفظ النموذج المحدّث
    """
    print(f"\n{'═'*50}")
    print(f"  AION-SWARM Aggregator")
    print(f"  Method: {method}")
    print(f"{'═'*50}\n")

    # تحميل نتائج الـ shards
    result_files = sorted(glob.glob(os.path.join(swarm_dir, 'result_*.json')))
    results = []
    for rf in result_files:
        with open(rf) as f:
            results.append(json.load(f))
    print(f"📊 Found {len(results)} shard results")

    if not results:
        print("❌ No shard results found!")
        return {}

    # تحميل الـ config
    with open(config_path) as f:
        raw = json.load(f)
    fields = {k: v for k, v in raw.items() if k in AIONConfig.__dataclass_fields__}
    cfg = AIONConfig(**fields)
    model = AIONModel(cfg)

    if method == 'soup':
        # Model Soup: متوسط الـ weights
        weight_paths = [
            os.path.join(swarm_dir, f"weights_{r['shard_id']:02d}.pt")
            for r in results
        ]
        losses = [r['avg_loss'] for r in results]

        print(f"\n🍲 Model Soup averaging {len(weight_paths)} shards...")
        for i, (path, loss) in enumerate(zip(weight_paths, losses)):
            print(f"  Shard {i}: loss={loss:.4f} | {os.path.basename(path)}")

        averaged = weights_average(weight_paths, losses)
        if averaged:
            model.load_state_dict(averaged)
            print("✅ Weights averaged successfully")

    else:
        # Gradient aggregation
        if os.path.exists(checkpoint_path):
            ck = torch.load(checkpoint_path, map_location='cpu')
            model.load_state_dict(ck['model'])

        grad_paths = [
            os.path.join(swarm_dir, f"grad_{r['shard_id']:02d}.pt")
            for r in results
        ]
        losses = [r['avg_loss'] for r in results]
        weights_list = [1.0 / max(l, 1e-6) for l in losses]

        averaged_grads = federated_average(grad_paths, weights_list)
        if averaged_grads:
            model = apply_gradients(model, averaged_grads, lr=3e-4)
            print("✅ Gradients applied")

    # حساب إحصائيات
    avg_loss    = sum(r['avg_loss'] for r in results) / len(results)
    total_steps = sum(r['n_steps']  for r in results)
    best_shard  = min(results, key=lambda r: r['avg_loss'])

    meta = {
        'generation': int(time.time()),
        'n_shards': len(results),
        'avg_loss': avg_loss,
        'best_shard': best_shard['shard_id'],
        'best_loss': best_shard['avg_loss'],
        'total_steps': total_steps,
        'method': method,
    }

    # حفظ النموذج المحدّث
    os.makedirs(os.path.dirname(out_path) if os.path.dirname(out_path) else '.', exist_ok=True)
    torch.save({'model': model.state_dict(), 'meta': meta,
                'step': total_steps, 'best_loss': avg_loss}, out_path)
    print(f"\n💾 Aggregated model saved: {out_path}")
    print(f"📈 Avg loss: {avg_loss:.4f} | Best shard: #{best_shard['shard_id']} ({best_shard['avg_loss']:.4f})")

    # حفظ تقرير الدمج
    report_path = os.path.join(os.path.dirname(out_path), 'aggregation_report.json')
    with open(report_path, 'w') as f:
        json.dump({'meta': meta, 'shards': results}, f, indent=2)

    return meta


if __name__ == '__main__':
    import argparse
    p = argparse.ArgumentParser()
    p.add_argument('--swarm-dir',   default='swarm_out')
    p.add_argument('--checkpoint',  default='checkpoints/aion_best.pt')
    p.add_argument('--config',      default='checkpoints/config.json')
    p.add_argument('--out',         default='checkpoints/aion_best.pt')
    p.add_argument('--method',      default='soup', choices=['soup', 'grad'])
    args = p.parse_args()

    meta = aggregate(args.swarm_dir, args.checkpoint,
                     args.config, args.out, args.method)
    print(f"\n✅ Done: {meta}")
