import sys, torch, json, os
sys.path.insert(0,'.')
from omega.model.architecture import get_config, AIONModel, AIONConfig
from omega.tokenizer.bpe import OmegaTokenizer
from omega.memory.persistent import OmegaPersistentMemory
from omega.agent.agent import OmegaAgent

with open('checkpoints/config.json') as f:
    raw = json.load(f)

fields = {k:v for k,v in raw.items() if k in AIONConfig.__dataclass_fields__}
cfg = AIONConfig(**fields)
model = AIONModel(cfg)
ck = 'checkpoints/aion_best.pt'
if not os.path.exists(ck):
    ck = 'checkpoints/omega_best.pt'
if os.path.exists(ck):
    data = torch.load(ck, map_location='cpu')
    model.load_state_dict(data['model'])
    print(f"Loaded step={data['step']} loss={data['best_loss']:.4f}")
model.eval()
tok = OmegaTokenizer.load('checkpoints')
os.makedirs('memory', exist_ok=True)
mem = OmegaPersistentMemory('memory/aion.db')
agent = OmegaAgent(model, tok, memory=mem)
for q in ['ما هو AION؟', 'def fibonacci']:
    r = agent.chat(q)
    print(f"Q: {q[:30]} | R: {r[:50]}")
print(f"Pipeline OK | {mem.stats()}")
mem.close()
