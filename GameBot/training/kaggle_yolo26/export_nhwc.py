# Kaggle script: export the final YOLO26 enemy detectors as LiteRT/TFLite with an NHWC input ([1,H,W,3]),
# the layout the GameBot app requires (the stock Ultralytics litert export is NCHW and the app rejects it).
import os, glob, json, shutil, subprocess, traceback, urllib.request
OUT = "/kaggle/working"; MODELS = f"{OUT}/models"; os.makedirs(MODELS, exist_ok=True); os.chdir(OUT)
BASE = "https://github.com/alhsryahmd266-jpg/omega-ai/releases/download"
JOBS = [("yolo26n", "gamebot-final-n-v1", "yolo26n_final_p2_best.pt", 960),
        ("yolo26s", "gamebot-final-s-v1", "yolo26s_final_p2_best.pt", 960),
        ("yolo26n", "gamebot-final-n-v1", "yolo26n_final_p2_best.pt", 1152)]
res = {"jobs": [], "errors": []}
def sh(c): print("$", c, flush=True); return subprocess.run(c, shell=True).returncode
sh("pip install -q -U ultralytics")
import torch
from ultralytics import YOLO
# make sure the litert toolchain is installed (Ultralytics installs it lazily on the first export)
try:
    YOLO("yolo26n.pt").export(format="litert", imgsz=64)
except Exception as e:
    print("warm-up export:", str(e)[:200])
import litert_torch
_orig = litert_torch.convert
class NHWC(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, x): return self.m(x.permute(0, 3, 1, 2))      # NHWC in -> NCHW inside
def _conv(model, args, *a, **k):
    im = args[0].permute(0, 2, 3, 1).contiguous()
    return _orig(NHWC(model).eval(), (im,), *a, **k)
litert_torch.convert = _conv
from ai_edge_litert.interpreter import Interpreter
import numpy as np
for name, tag, asset, sz in JOBS:
    info = {"model": name, "imgsz": sz}
    try:
        pt = f"{OUT}/{asset}"
        if not os.path.exists(pt): urllib.request.urlretrieve(f"{BASE}/{tag}/{asset}", pt)
        for f in glob.glob(f"{OUT}/*.tflite"): os.remove(f)
        YOLO(pt).export(format="litert", imgsz=sz)
        c = glob.glob(f"{OUT}/*.tflite")
        if not c: raise RuntimeError("no tflite produced")
        dst = f"{MODELS}/{name}_final_p2_{sz}_nhwc.tflite"
        shutil.copy(c[0], dst)
        it = Interpreter(model_path=dst); it.allocate_tensors()
        ind = it.get_input_details()[0]; outd = it.get_output_details()[0]
        info["input"] = ind["shape"].tolist(); info["output"] = outd["shape"].tolist()
        it.set_tensor(ind["index"], np.random.rand(*ind["shape"]).astype("float32")); it.invoke()
        o = it.get_tensor(outd["index"]); info["out_range"] = [float(o.min()), float(o.max())]
        info["size_mb"] = round(os.path.getsize(dst) / 1e6, 1)
    except Exception as e:
        info["error"] = str(e)[:400]; res["errors"].append(f"{name}{sz}: {e}"); traceback.print_exc()
    res["jobs"].append(info)
    json.dump(res, open(f"{OUT}/nhwc_result.json", "w"), indent=2)
    shutil.copy(f"{OUT}/nhwc_result.json", f"{MODELS}/nhwc_result.json")
print(json.dumps(res, indent=2))
