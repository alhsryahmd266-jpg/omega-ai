# Kaggle script: COD two-stage training.
# Stage 1: COCO-pretrained YOLO26 -> Warzone (body boxes) + mobile set (mobile x3 weight), imgsz 640.
# Stage 2: fine-tune on the mobile set only at 960 (what the phone sees). Then export ONNX + TFLite.
import os, glob, json, shutil, subprocess, traceback, urllib.request, zipfile
OUT = "/kaggle/working"; A = f"{OUT}/dsA"; B = f"{OUT}/dsB"; MODELS = f"{OUT}/models"
URL_A = "https://github.com/alhsryahmd266-jpg/omega-ai/releases/download/cod-merged-v1/merged_cod.zip"
URL_B = "https://github.com/alhsryahmd266-jpg/omega-ai/raw/yolo-data/dataset.zip"
BASE = "https://github.com/alhsryahmd266-jpg/omega-ai/releases/download/gamebot-yolo26-intensive-v2"
result = {"models": {}, "errors": [], "baseline_note": "intensive-v2 (mobile only): n val mAP50 0.967 recall 0.907; s val mAP50 0.963 recall 0.902"}
def sh(c): print("$", c, flush=True); return subprocess.run(c, shell=True).returncode
def save():
    json.dump(result, open(f"{OUT}/cod_result.json", "w"), indent=2)
    os.makedirs(MODELS, exist_ok=True); shutil.copy(f"{OUT}/cod_result.json", f"{MODELS}/cod_result.json")
sh("pip install -q -U ultralytics"); os.makedirs(MODELS, exist_ok=True)
for url, dst in ((URL_A, A), (URL_B, B)):
    urllib.request.urlretrieve(url, f"{OUT}/tmp.zip")
    with zipfile.ZipFile(f"{OUT}/tmp.zip") as z: z.extractall(dst)
    os.remove(f"{OUT}/tmp.zip")
# stage-1 train list: everything + mobile images twice more (=> x3 weight)
imgs = sorted(glob.glob(f"{A}/train/images/*"))
mb = [f"{A}/train/images/{l.strip()}" for l in open(f"{A}/mobile_train_list.txt") if l.strip()]
lst = imgs + mb + mb
open(f"{A}/train_all.txt", "w").write("\n".join(lst))
print("stage1 train entries", len(lst), "(warzone", len(imgs) - len(mb), "+ mobile x3", len(mb) * 3, ")", flush=True)
YA = f"{A}/stage1.yaml"
open(YA, "w").write(f"path: {A}\ntrain: {A}/train_all.txt\nval: valid/images\ntest: test/images\nnc: 1\nnames: ['enemy']\n")
YB = f"{B}/abs.yaml"
open(YB, "w").write(f"path: {B}\ntrain: train/images\nval: valid/images\ntest: test/images\nnc: 1\nnames: ['enemy']\n")
from ultralytics import YOLO
import ultralytics; result["ultralytics"] = ultralytics.__version__

def export_all(name, best_pt, imgsz, d, info):
    wdir = os.path.dirname(best_pt)
    try:
        YOLO(best_pt).export(format="onnx", imgsz=imgsz)
        c = glob.glob(f"{wdir}/*.onnx")
        if c: shutil.copy(c[0], f"{d}/{name}_cod_enemy_{imgsz}.onnx"); info["onnx"] = True
    except Exception as e: info["onnx_error"] = str(e)[:300]
    got = False
    try:
        YOLO(best_pt).export(format="tflite", imgsz=imgsz)
        c = glob.glob(f"{wdir}/**/*.tflite", recursive=True)
        if c: shutil.copy(c[0], f"{d}/{name}_cod_enemy_{imgsz}_fp32.tflite"); info["tflite"] = "litert-fp32"; got = True
    except Exception as e: info["tflite_error"] = str(e)[:400]; traceback.print_exc()
    if not got:
        try:
            onnx = glob.glob(f"{wdir}/*.onnx")[0]
            sh("pip install -q onnx2tf tensorflow tf_keras onnx_graphsurgeon sng4onnx onnxsim ai_edge_litert psutil")
            o2 = f"{OUT}/o2t_{name}"; info["onnx2tf_rc"] = sh(f"onnx2tf -i {onnx} -o {o2} -osd")
            for f in glob.glob(f"{o2}/*.tflite"):
                shutil.copy(f, f"{d}/{name}_cod_{os.path.basename(f)}"); info.setdefault("onnx2tf", []).append(os.path.basename(f))
        except Exception as e: info["onnx2tf_error"] = str(e)[:400]

def metrics(m, yaml, imgsz):
    out = {}
    for sp in ("val", "test"):
        try:
            r = m.val(data=yaml, imgsz=imgsz, split=sp, device=0)
            out[sp] = dict(map50=float(r.box.map50), map50_95=float(r.box.map), precision=float(r.box.mp), recall=float(r.box.mr))
        except Exception as e: out[sp + "_error"] = str(e)[:200]
    return out

def run(name, e1, e2):
    d = f"{MODELS}/{name}"; os.makedirs(d, exist_ok=True); info = {}
    try:
        m = YOLO(f"{name}.pt")
        m.train(data=YA, epochs=e1, imgsz=640, batch=16, device=0, project=f"{OUT}/runs", name=f"{name}_s1", exist_ok=True,
                patience=25, workers=2, seed=0, plots=False, close_mosaic=8, scale=0.6, hsv_s=0.6, hsv_v=0.4, mixup=0.05, fliplr=0.5)
        s1 = f"{OUT}/runs/{name}_s1/weights/best.pt"
        info["stage1"] = metrics(YOLO(s1), YB, 640)
        save_i = dict(info); result["models"][name] = save_i; save()
        m2 = YOLO(s1)
        m2.train(data=YB, epochs=e2, imgsz=960, batch=16, device=0, project=f"{OUT}/runs", name=f"{name}_s2", exist_ok=True,
                 patience=60, workers=2, seed=1, plots=False, cos_lr=True, lr0=0.002, lrf=0.02, warmup_epochs=2, close_mosaic=25,
                 scale=0.7, translate=0.15, hsv_s=0.7, hsv_v=0.5, mixup=0.1, fliplr=0.5, degrees=0.0, flipud=0.0)
        best = f"{OUT}/runs/{name}_s2/weights/best.pt"
        shutil.copy(best, f"{d}/{name}_cod_enemy_best.pt")
        info["stage2"] = metrics(YOLO(best), YB, 960)
        export_all(name, best, 960, d, info)
    except Exception as e:
        info["error"] = str(e)[:400]; result["errors"].append(f"{name}: {e}"); traceback.print_exc()
    result["models"][name] = info; save()

run("yolo26n", 40, 150)
run("yolo26s", 35, 150)
for p in (f"{OUT}/runs", A, B): shutil.rmtree(p, ignore_errors=True)
for f in glob.glob(f"{OUT}/*.pt"): os.remove(f)
print(json.dumps(result, indent=2))
