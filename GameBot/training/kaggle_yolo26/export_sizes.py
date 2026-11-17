# Kaggle script: pick the best speed/accuracy input size for the trained COD-enemy YOLO26 detectors.
# For each model (n, s) and size (480, 640, 800): measure val/test mAP on the mobile set, export TFLite (fp32 + fp16 if supported).
import os, glob, json, shutil, subprocess, traceback, urllib.request, zipfile, time
OUT = "/kaggle/working"; MODELS = f"{OUT}/models"; B = f"{OUT}/dsB"; os.makedirs(MODELS, exist_ok=True)
BASE = "https://github.com/alhsryahmd266-jpg/omega-ai/releases/download/gamebot-yolo26-cod-v1"
URL_B = "https://github.com/alhsryahmd266-jpg/omega-ai/raw/yolo-data/dataset.zip"
res = {"models": {}, "note": "val/test come from the same video frames as train (Roboflow random split) so absolute numbers are optimistic; compare sizes relatively."}
def sh(c): print("$", c, flush=True); return subprocess.run(c, shell=True).returncode
def save():
    json.dump(res, open(f"{OUT}/sizes_result.json", "w"), indent=2); shutil.copy(f"{OUT}/sizes_result.json", f"{MODELS}/sizes_result.json")
sh("pip install -q -U ultralytics")
urllib.request.urlretrieve(URL_B, f"{OUT}/b.zip")
with zipfile.ZipFile(f"{OUT}/b.zip") as z: z.extractall(B)
YB = f"{B}/abs.yaml"
open(YB, "w").write(f"path: {B}\ntrain: train/images\nval: valid/images\ntest: test/images\nnc: 1\nnames: ['enemy']\n")
from ultralytics import YOLO
import ultralytics; res["ultralytics"] = ultralytics.__version__

def newest_tflite(since):
    c = [f for f in glob.glob(f"{OUT}/**/*.tflite", recursive=True) if not f.startswith(MODELS) and os.path.getmtime(f) >= since]
    return max(c, key=os.path.getmtime) if c else None

for name in ("yolo26n", "yolo26s"):
    pt = f"{OUT}/{name}.pt"
    try: urllib.request.urlretrieve(f"{BASE}/{name}_cod_enemy_best.pt", pt)
    except Exception as e: res["models"][name] = {"error": str(e)[:200]}; save(); continue
    res["models"][name] = {}
    for sz in (480, 640, 800):
        info = {}; res["models"][name][str(sz)] = info
        for sp in ("val", "test"):
            try:
                r = YOLO(pt).val(data=YB, imgsz=sz, split=sp, device=0, plots=False, verbose=False)
                info[sp] = dict(map50=round(float(r.box.map50), 4), map50_95=round(float(r.box.map), 4), precision=round(float(r.box.mp), 4), recall=round(float(r.box.mr), 4))
                if sp == "val": info["ms_per_img_gpu"] = {k: round(float(v), 2) for k, v in r.speed.items()}
            except Exception as e: info[sp + "_error"] = str(e)[:200]
        for half in (False, True):
            t0 = time.time()
            try:
                YOLO(pt).export(format="tflite", imgsz=sz, half=half)
                f = newest_tflite(t0 - 1)
                if f:
                    dst = f"{MODELS}/{name}_cod_{sz}_{'fp16' if half else 'fp32'}.tflite"; shutil.copy(f, dst); os.remove(f)
                    info["tflite_fp16" if half else "tflite_fp32"] = os.path.getsize(dst)
                else: info["tflite_" + ("fp16" if half else "fp32") + "_error"] = "no file"
            except Exception as e:
                info["tflite_" + ("fp16" if half else "fp32") + "_error"] = str(e)[:300]; traceback.print_exc()
        save()
save(); print(json.dumps(res, indent=2))
