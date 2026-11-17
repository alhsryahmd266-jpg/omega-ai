# Kaggle script: 2-stage training. Stage 1: COD Warzone (enemy) + COD Mobile (x4 oversampled) at 640.
# Stage 2: fine-tune on COD Mobile only at 960. Evaluate on mobile val/test, export TFLite + ONNX.
import os, glob, json, shutil, subprocess, traceback, urllib.request, zipfile

OUT = "/kaggle/working"; D = f"{OUT}/data"; MODELS = f"{OUT}/models"
URL = "https://github.com/alhsryahmd266-jpg/omega-ai/releases/download/cod-merged-dataset-v1/merged_cod_dataset.zip"
result = {"models": {}, "errors": []}
def sh(c): print("$", c, flush=True); return subprocess.run(c, shell=True).returncode
def save():
    json.dump(result, open(f"{OUT}/cod_result.json", "w"), indent=2)
    os.makedirs(MODELS, exist_ok=True); shutil.copy(f"{OUT}/cod_result.json", f"{MODELS}/cod_result.json")

sh("pip install -q -U ultralytics")
os.makedirs(MODELS, exist_ok=True)
urllib.request.urlretrieve(URL, f"{OUT}/merged.zip")
with zipfile.ZipFile(f"{OUT}/merged.zip") as z: z.extractall(D)
imgs = lambda p: sorted(glob.glob(p + "/*.jpg") + glob.glob(p + "/*.jpeg") + glob.glob(p + "/*.png"))
wz = imgs(f"{D}/wz_clean/images"); mt = imgs(f"{D}/mobile/train/images")
mv = imgs(f"{D}/mobile/valid/images"); mte = imgs(f"{D}/mobile/test/images")
print("warzone", len(wz), "mobile train/valid/test", len(mt), len(mv), len(mte), flush=True)
open(f"{D}/stage1_train.txt", "w").write("\n".join(wz + mt * 4) + "\n")
open(f"{D}/mobile_train.txt", "w").write("\n".join(mt) + "\n")
open(f"{D}/valid.txt", "w").write("\n".join(mv) + "\n")
open(f"{D}/test.txt", "w").write("\n".join(mte) + "\n")
Y1 = f"{D}/stage1.yaml"; Y2 = f"{D}/stage2.yaml"
open(Y1, "w").write(f"path: {D}\ntrain: {D}/stage1_train.txt\nval: {D}/valid.txt\ntest: {D}/test.txt\nnc: 1\nnames: ['enemy']\n")
open(Y2, "w").write(f"path: {D}\ntrain: {D}/mobile_train.txt\nval: {D}/valid.txt\ntest: {D}/test.txt\nnc: 1\nnames: ['enemy']\n")
result["counts"] = {"warzone": len(wz), "mobile_train": len(mt), "mobile_valid": len(mv), "mobile_test": len(mte)}

from ultralytics import YOLO
import ultralytics
result["ultralytics"] = ultralytics.__version__

def evalm(pt, imgsz, tag, info):
    for split in ("val", "test"):
        try:
            r = YOLO(pt).val(data=Y2, imgsz=imgsz, split=split, device=0)
            info[f"{tag}_{split}"] = dict(map50=float(r.box.map50), map50_95=float(r.box.map), precision=float(r.box.mp), recall=float(r.box.mr))
        except Exception as e:
            info[f"{tag}_{split}_error"] = str(e)[:200]

def export_all(best_pt, name, d, info):
    try:
        YOLO(best_pt).export(format="onnx", imgsz=960)
        c = glob.glob(os.path.dirname(best_pt) + "/*.onnx")
        if c: shutil.copy(c[0], f"{d}/{name}_cod_960.onnx"); info["onnx"] = True
    except Exception as e:
        info["onnx_error"] = str(e)[:300]
    try:
        YOLO(best_pt).export(format="tflite", imgsz=960)
        c = glob.glob(os.path.dirname(best_pt) + "/**/*.tflite", recursive=True)
        if c: shutil.copy(c[0], f"{d}/{name}_cod_960_fp32.tflite"); info["tflite"] = "litert-fp32"
        else: info["tflite_error"] = "no file"
    except Exception as e:
        info["tflite_error"] = str(e)[:400]; traceback.print_exc()

def run(name, ep1, ep2, batch):
    d = f"{MODELS}/{name}"; os.makedirs(d, exist_ok=True)
    info = {"stage1_epochs": ep1, "stage2_epochs": ep2}
    try:
        m = YOLO(f"{name}.pt")
        m.train(data=Y1, epochs=ep1, imgsz=640, batch=batch, device=0, project=f"{OUT}/runs", name=f"{name}_s1",
                exist_ok=True, patience=40, workers=2, seed=0, plots=False, cos_lr=True, close_mosaic=10,
                mosaic=1.0, mixup=0.1, scale=0.6, fliplr=0.5, hsv_s=0.7, hsv_v=0.4)
        s1 = f"{OUT}/runs/{name}_s1/weights/best.pt"
        evalm(s1, 640, "stage1", info)
        m2 = YOLO(s1)
        m2.train(data=Y2, epochs=ep2, imgsz=960, batch=batch, device=0, project=f"{OUT}/runs", name=f"{name}_s2",
                 exist_ok=True, patience=30, workers=2, seed=1, plots=False, lr0=0.001, lrf=0.05, cos_lr=True,
                 warmup_epochs=1, close_mosaic=10, mosaic=1.0, scale=0.6, fliplr=0.5)
        best = f"{OUT}/runs/{name}_s2/weights/best.pt"
        shutil.copy(best, f"{d}/{name}_cod_best.pt")
        evalm(best, 960, "final", info)
        export_all(best, name, d, info)
    except Exception as e:
        info["error"] = str(e)[:400]; result["errors"].append(f"{name}: {e}"); traceback.print_exc()
    result["models"][name] = info; save()

run("yolo26n", 80, 60, 16)
run("yolo26s", 60, 60, 16)
shutil.rmtree(f"{OUT}/runs", ignore_errors=True); shutil.rmtree(D, ignore_errors=True)
for f in glob.glob(f"{OUT}/*.pt") + glob.glob(f"{OUT}/merged.zip"): os.remove(f)
print(json.dumps(result, indent=2))
