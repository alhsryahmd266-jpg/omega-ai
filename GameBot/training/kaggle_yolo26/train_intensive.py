# Kaggle script: INTENSIVE round. Warm-restarts the round-1 YOLO26 enemy detectors for 300 epochs
# at 960px with stronger augmentation, then exports TFLite (litert, FP32; onnx2tf fallback) + ONNX.
import os, sys, glob, json, shutil, subprocess, traceback, urllib.request, zipfile

OUT = "/kaggle/working"; DS = f"{OUT}/ds"; MODELS = f"{OUT}/models"
DATA_URL = "https://github.com/alhsryahmd266-jpg/omega-ai/raw/yolo-data/dataset.zip"
R1 = "https://github.com/alhsryahmd266-jpg/omega-ai/releases/download/gamebot-yolo26-v3"
result = {"models": {}, "errors": []}

def sh(cmd):
    print("$", cmd, flush=True); return subprocess.run(cmd, shell=True).returncode

def save():
    json.dump(result, open(f"{OUT}/intensive_result.json", "w"), indent=2)
    os.makedirs(MODELS, exist_ok=True)
    shutil.copy(f"{OUT}/intensive_result.json", f"{MODELS}/intensive_result.json")

sh("pip install -q -U ultralytics")
os.makedirs(MODELS, exist_ok=True)
urllib.request.urlretrieve(DATA_URL, f"{OUT}/dataset.zip")
with zipfile.ZipFile(f"{OUT}/dataset.zip") as z: z.extractall(DS)
YAML = f"{DS}/data_abs.yaml"
open(YAML, "w").write(f"path: {DS}\ntrain: train/images\nval: valid/images\ntest: test/images\nnc: 1\nnames: ['enemy']\n")

from ultralytics import YOLO
import ultralytics
result["ultralytics"] = ultralytics.__version__

def export_all(name, best_pt, imgsz, d, info):
    try:
        YOLO(best_pt).export(format="onnx", imgsz=imgsz)
        c = glob.glob(f"{OUT}/runs/{name}/weights/*.onnx")
        if c: shutil.copy(c[0], f"{d}/{name}_v2_enemy_{imgsz}.onnx"); info["onnx"] = True
    except Exception as e:
        info["onnx_error"] = str(e)[:300]
    got = False
    try:
        YOLO(best_pt).export(format="tflite", imgsz=imgsz)
        c = [x for x in glob.glob(f"{OUT}/runs/{name}/weights/**/*.tflite", recursive=True)]
        if c:
            shutil.copy(c[0], f"{d}/{name}_v2_enemy_{imgsz}_fp32.tflite"); info["tflite"] = "litert-fp32"; got = True
    except Exception as e:
        info["tflite_error"] = str(e)[:400]; traceback.print_exc()
    if not got:
        try:
            onnx = glob.glob(f"{OUT}/runs/{name}/weights/*.onnx")[0]
            sh("pip install -q onnx2tf tensorflow tf_keras onnx_graphsurgeon sng4onnx onnxsim ai_edge_litert psutil")
            o2 = f"{OUT}/o2t_{name}"
            info["onnx2tf_rc"] = sh(f"onnx2tf -i {onnx} -o {o2} -osd")
            for f in glob.glob(f"{o2}/*.tflite"):
                shutil.copy(f, f"{d}/{name}_v2_{os.path.basename(f)}"); info.setdefault("onnx2tf", []).append(os.path.basename(f))
        except Exception as e:
            info["onnx2tf_error"] = str(e)[:400]; traceback.print_exc()

def run(name, imgsz, epochs, batch):
    d = f"{MODELS}/{name}"; os.makedirs(d, exist_ok=True)
    info = {"imgsz": imgsz, "epochs_requested": epochs}
    try:
        init = f"{OUT}/{name}_r1.pt"
        urllib.request.urlretrieve(f"{R1}/{name}_enemy_best.pt", init)
        m = YOLO(init)
        m.train(data=YAML, epochs=epochs, imgsz=imgsz, batch=batch, device=0,
                project=f"{OUT}/runs", name=name, exist_ok=True, patience=100, workers=2,
                seed=1, plots=False, cos_lr=True, lr0=0.002, lrf=0.02, warmup_epochs=2,
                close_mosaic=30, mosaic=1.0, mixup=0.1, scale=0.7, translate=0.15,
                hsv_h=0.02, hsv_s=0.7, hsv_v=0.5, degrees=0.0, flipud=0.0, fliplr=0.5)
        best_pt = f"{OUT}/runs/{name}/weights/best.pt"
        shutil.copy(best_pt, f"{d}/{name}_v2_enemy_best.pt")
        best = YOLO(best_pt)
        for split in ("val", "test"):
            try:
                r = best.val(data=YAML, imgsz=imgsz, split=split, device=0)
                info[split] = dict(map50=float(r.box.map50), map50_95=float(r.box.map),
                                   precision=float(r.box.mp), recall=float(r.box.mr))
            except Exception as e:
                info[split + "_error"] = str(e)[:200]
        export_all(name, best_pt, imgsz, d, info)
    except Exception as e:
        info["error"] = str(e)[:400]; result["errors"].append(f"{name}: {e}"); traceback.print_exc()
    result["models"][name] = info
    save()

run("yolo26n", 960, 300, 16)
run("yolo26s", 960, 300, 16)
shutil.rmtree(f"{OUT}/runs", ignore_errors=True)
shutil.rmtree(DS, ignore_errors=True)
for f in glob.glob(f"{OUT}/*.pt") + glob.glob(f"{OUT}/dataset.zip"): os.remove(f)
print(json.dumps(result, indent=2))
