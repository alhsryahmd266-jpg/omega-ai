# Kaggle script: TWO-STAGE training.
# Stage 1: COCO-pretrained YOLO26 on merged COD Warzone + COD Mobile (mobile images x3).
# Stage 2: fine-tune on the mobile set only at 960 (the target domain). Then export ONNX + TFLite.
import os, glob, json, shutil, subprocess, traceback, urllib.request, zipfile
OUT = "/kaggle/working"; MODELS = f"{OUT}/models"
MERGED = "https://github.com/alhsryahmd266-jpg/omega-ai/releases/download/cod-merged-v1/merged_cod.zip"
MOBILE = "https://github.com/alhsryahmd266-jpg/omega-ai/raw/yolo-data/dataset.zip"
result = {"models": {}, "errors": []}
def sh(c):
    print("$", c, flush=True); return subprocess.run(c, shell=True).returncode
def save():
    os.makedirs(MODELS, exist_ok=True)
    json.dump(result, open(f"{OUT}/cod_result.json", "w"), indent=2)
    shutil.copy(f"{OUT}/cod_result.json", f"{MODELS}/cod_result.json")
sh("pip install -q -U ultralytics")
os.makedirs(MODELS, exist_ok=True)
for url, dst in ((MERGED, "merged"), (MOBILE, "mobile")):
    urllib.request.urlretrieve(url, f"{OUT}/{dst}.zip")
    with zipfile.ZipFile(f"{OUT}/{dst}.zip") as z: z.extractall(f"{OUT}/{dst}")
A, B = f"{OUT}/merged", f"{OUT}/mobile"
imgs = sorted(glob.glob(f"{A}/train/images/*"))
mob = [f"{A}/train/images/{l.strip()}" for l in open(f"{A}/mobile_train_list.txt") if l.strip()]
open(f"{OUT}/trainA.txt", "w").write("\n".join(imgs + mob + mob) + "\n")
print("stage1 train images:", len(imgs), "+ mobile x2 extra:", len(mob) * 2, flush=True)
YA = f"{OUT}/dataA.yaml"; YB = f"{OUT}/dataB.yaml"
open(YA, "w").write(f"train: {OUT}/trainA.txt\nval: {A}/valid/images\ntest: {A}/test/images\nnc: 1\nnames: ['enemy']\n")
open(YB, "w").write(f"path: {B}\ntrain: train/images\nval: valid/images\ntest: test/images\nnc: 1\nnames: ['enemy']\n")
from ultralytics import YOLO
import ultralytics
result["ultralytics"] = ultralytics.__version__

def export_all(name, best_pt, imgsz, d, info):
    wdir = os.path.dirname(best_pt)
    try:
        YOLO(best_pt).export(format="onnx", imgsz=imgsz)
        c = glob.glob(f"{wdir}/*.onnx")
        if c: shutil.copy(c[0], f"{d}/{name}_cod_{imgsz}.onnx"); info["onnx"] = True
    except Exception as e:
        info["onnx_error"] = str(e)[:300]
    got = False
    try:
        YOLO(best_pt).export(format="tflite", imgsz=imgsz)
        c = glob.glob(f"{wdir}/**/*.tflite", recursive=True)
        if c: shutil.copy(c[0], f"{d}/{name}_cod_{imgsz}_fp32.tflite"); info["tflite"] = "litert-fp32"; got = True
    except Exception as e:
        info["tflite_error"] = str(e)[:400]; traceback.print_exc()
    if not got:
        try:
            onnx = glob.glob(f"{wdir}/*.onnx")[0]
            sh("pip install -q onnx2tf tensorflow tf_keras onnx_graphsurgeon sng4onnx onnxsim ai_edge_litert psutil")
            o2 = f"{OUT}/o2t_{name}"
            info["onnx2tf_rc"] = sh(f"onnx2tf -i {onnx} -o {o2} -osd")
            for f in glob.glob(f"{o2}/*.tflite"):
                shutil.copy(f, f"{d}/{name}_cod_{os.path.basename(f)}"); info.setdefault("onnx2tf", []).append(os.path.basename(f))
        except Exception as e:
            info["onnx2tf_error"] = str(e)[:400]

def metrics(m, data, imgsz, split):
    r = m.val(data=data, imgsz=imgsz, split=split, device=0)
    return dict(map50=float(r.box.map50), map50_95=float(r.box.map), precision=float(r.box.mp), recall=float(r.box.mr))

def run(name, e1, e2, batch=16):
    d = f"{MODELS}/{name}"; os.makedirs(d, exist_ok=True)
    info = {"stage1_epochs": e1, "stage1_imgsz": 768, "stage2_epochs": e2, "stage2_imgsz": 960}
    try:
        m = YOLO(f"{name}.pt")
        m.train(data=YA, epochs=e1, imgsz=768, batch=batch, device=0, project=f"{OUT}/runs", name=f"{name}_s1",
                exist_ok=True, patience=25, workers=2, seed=0, plots=False, close_mosaic=10,
                mosaic=1.0, mixup=0.05, scale=0.6, degrees=0.0, flipud=0.0)
        s1 = f"{OUT}/runs/{name}_s1/weights/best.pt"
        info["stage1_mobile_val"] = metrics(YOLO(s1), YB, 960, "val")
        shutil.copy(s1, f"{d}/{name}_cod_stage1.pt")
        save()
        m2 = YOLO(s1)
        m2.train(data=YB, epochs=e2, imgsz=960, batch=batch, device=0, project=f"{OUT}/runs", name=f"{name}_s2",
                 exist_ok=True, patience=50, workers=2, seed=1, plots=False, cos_lr=True, lr0=0.002, lrf=0.02,
                 warmup_epochs=2, close_mosaic=25, mosaic=1.0, mixup=0.1, scale=0.7, translate=0.15,
                 hsv_h=0.02, hsv_s=0.7, hsv_v=0.5, degrees=0.0, flipud=0.0)
        best = f"{OUT}/runs/{name}_s2/weights/best.pt"
        shutil.copy(best, f"{d}/{name}_cod_best.pt")
        bm = YOLO(best)
        info["val"] = metrics(bm, YB, 960, "val")
        try: info["test"] = metrics(bm, YB, 960, "test")
        except Exception as e: info["test_error"] = str(e)[:200]
        export_all(name, best, 960, d, info)
    except Exception as e:
        info["error"] = str(e)[:400]; result["errors"].append(f"{name}: {e}"); traceback.print_exc()
    result["models"][name] = info
    save()

run("yolo26n", 80, 150)
run("yolo26s", 60, 120)
shutil.rmtree(f"{OUT}/runs", ignore_errors=True)
for p in ("merged", "mobile"): shutil.rmtree(f"{OUT}/{p}", ignore_errors=True)
for f in glob.glob(f"{OUT}/*.zip") + glob.glob(f"{OUT}/*.pt") + glob.glob(f"{OUT}/trainA.txt"): os.remove(f)
print(json.dumps(result, indent=2))
