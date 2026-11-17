# Kaggle script: trains YOLO26 enemy detectors (nano + small) and exports mobile models.
import os, sys, glob, json, shutil, subprocess, traceback, urllib.request, zipfile

OUT = "/kaggle/working"
DS = f"{OUT}/ds"
MODELS = f"{OUT}/models"
URL = "https://github.com/alhsryahmd266-jpg/omega-ai/raw/yolo-data/dataset.zip"
result = {"models": {}, "errors": []}

def sh(cmd):
    print("$", cmd, flush=True)
    return subprocess.run(cmd, shell=True).returncode

sh("pip install -q -U ultralytics")
os.makedirs(MODELS, exist_ok=True)

urllib.request.urlretrieve(URL, f"{OUT}/dataset.zip")
with zipfile.ZipFile(f"{OUT}/dataset.zip") as z:
    z.extractall(DS)
YAML = f"{DS}/data_abs.yaml"
open(YAML, "w").write(
    f"path: {DS}\ntrain: train/images\nval: valid/images\ntest: test/images\nnc: 1\nnames: ['enemy']\n"
)
for s in ("train", "valid", "test"):
    print(s, len(glob.glob(f"{DS}/{s}/images/*")), "images", flush=True)

from ultralytics import YOLO
import ultralytics
result["ultralytics"] = ultralytics.__version__

def run(name, imgsz, epochs, batch):
    d = f"{MODELS}/{name}"
    os.makedirs(d, exist_ok=True)
    info = {"imgsz": imgsz}
    try:
        m = YOLO(f"{name}.pt")
        m.train(data=YAML, epochs=epochs, imgsz=imgsz, batch=batch, device=0,
                project=f"{OUT}/runs", name=name, exist_ok=True, patience=30,
                workers=2, seed=0, plots=False, degrees=0.0, flipud=0.0,
                mosaic=1.0, close_mosaic=10, hsv_h=0.015, hsv_s=0.6, hsv_v=0.4)
        best_pt = f"{OUT}/runs/{name}/weights/best.pt"
        shutil.copy(best_pt, f"{d}/{name}_enemy_best.pt")
        best = YOLO(best_pt)
        r = best.val(data=YAML, imgsz=imgsz, split="val", device=0)
        info.update(map50=float(r.box.map50), map50_95=float(r.box.map),
                    precision=float(r.box.mp), recall=float(r.box.mr))
        try:
            r2 = best.val(data=YAML, imgsz=imgsz, split="test", device=0)
            info.update(test_map50=float(r2.box.map50), test_recall=float(r2.box.mr))
        except Exception as e:
            info["test_error"] = str(e)[:200]
        for fmt, kw in (("onnx", {}), ("tflite", {"half": True})):
            try:
                p = YOLO(best_pt).export(format=fmt, imgsz=imgsz, **kw)
                cands = glob.glob(f"{OUT}/runs/{name}/weights/**/*.{fmt}", recursive=True)
                cands = [c for c in cands if "int8" not in c] or cands
                if fmt == "tflite":
                    f16 = [c for c in cands if "float16" in c]
                    cands = f16 or cands
                if cands:
                    dst = f"{d}/{name}_enemy_{imgsz}.{fmt}"
                    shutil.copy(cands[0], dst)
                    info[fmt] = os.path.basename(dst)
                else:
                    info[fmt + "_error"] = "no file produced"
            except Exception as e:
                info[fmt + "_error"] = str(e)[:300]
                traceback.print_exc()
    except Exception as e:
        info["error"] = str(e)[:400]
        result["errors"].append(f"{name}: {e}")
        traceback.print_exc()
    result["models"][name] = info
    json.dump(result, open(f"{OUT}/yolo_result.json", "w"), indent=2)
    shutil.copy(f"{OUT}/yolo_result.json", f"{MODELS}/yolo_result.json")

# small enemies (~0.75% of frame area on average) -> train at 960
run("yolo26n", 960, 120, 16)
run("yolo26s", 960, 120, 16)
shutil.rmtree(f"{OUT}/runs", ignore_errors=True)
shutil.rmtree(DS, ignore_errors=True)
os.remove(f"{OUT}/dataset.zip")
print(json.dumps(result, indent=2))
