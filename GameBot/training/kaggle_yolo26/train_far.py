# Kaggle script: FAR-RANGE / SMALL-OBJECT detector (P2 head, high resolution) + size-bucketed recall report.
# __MODEL__ is replaced by 'n' or 's' when the kernel is pushed.
import os, glob, json, shutil, subprocess, traceback, urllib.request, zipfile
MODEL = "__MODEL__"; NAME = f"yolo26{MODEL}"
OUT = "/kaggle/working"; os.chdir(OUT)
A = f"{OUT}/dsA"; B = f"{OUT}/dsB"; MODELS = f"{OUT}/models/{NAME}"
URL_A = "https://github.com/alhsryahmd266-jpg/omega-ai/releases/download/cod-merged-v2/merged_cod.zip"
URL_B = "https://github.com/alhsryahmd266-jpg/omega-ai/raw/yolo-data/dataset.zip"
BASE_URL = f"https://github.com/alhsryahmd266-jpg/omega-ai/releases/download/gamebot-yolo26-cod-v1/{NAME}_cod_enemy_best.pt"
result = {"model": NAME, "errors": []}
os.makedirs(MODELS, exist_ok=True)
def sh(c): print("$", c, flush=True); return subprocess.run(c, shell=True).returncode
def save():
    json.dump(result, open(f"{OUT}/far_result.json", "w"), indent=2)
    shutil.copy(f"{OUT}/far_result.json", f"{MODELS}/far_result.json")
sh("pip install -q -U ultralytics")
for url, dst in ((URL_A, A), (URL_B, B)):
    urllib.request.urlretrieve(url, f"{OUT}/tmp.zip")
    with zipfile.ZipFile(f"{OUT}/tmp.zip") as z: z.extractall(dst)
    os.remove(f"{OUT}/tmp.zip")
imgs = sorted(glob.glob(f"{A}/train/images/*"))
mb = [f"{A}/train/images/{l.strip()}" for l in open(f"{A}/mobile_train_list.txt") if l.strip()]
open(f"{A}/train_all.txt", "w").write("\n".join(imgs + mb + mb))
YA = f"{A}/stage1.yaml"
open(YA, "w").write(f"path: {A}\ntrain: {A}/train_all.txt\nval: valid/images\ntest: test/images\nnc: 1\nnames: ['enemy']\n")
YB = f"{B}/abs.yaml"
open(YB, "w").write(f"path: {B}\ntrain: train/images\nval: valid/images\ntest: test/images\nnc: 1\nnames: ['enemy']\n")
from ultralytics import YOLO
import ultralytics; result["ultralytics"] = ultralytics.__version__

EDGES = [("tiny<0.05%", 0.0005), ("small<0.2%", 0.002), ("medium<0.8%", 0.008), ("large", 9.0)]
def iou(a, b):
    ax1, ay1, ax2, ay2 = a[0]-a[2]/2, a[1]-a[3]/2, a[0]+a[2]/2, a[1]+a[3]/2
    bx1, by1, bx2, by2 = b[0]-b[2]/2, b[1]-b[3]/2, b[0]+b[2]/2, b[1]+b[3]/2
    iw = max(0.0, min(ax2, bx2) - max(ax1, bx1)); ih = max(0.0, min(ay2, by2) - max(ay1, by1))
    inter = iw * ih; u = a[2]*a[3] + b[2]*b[3] - inter
    return inter / u if u > 0 else 0.0
def far_eval(model, imgsz, imgdir, conf=0.25):
    """Recall by object-size bucket (area as fraction of the image) + false positives per image."""
    files = sorted(glob.glob(f"{imgdir}/*"))
    tp = {k: 0 for k, _ in EDGES}; tot = {k: 0 for k, _ in EDGES}; fp = 0
    for i in range(0, len(files), 32):
        chunk = files[i:i+32]
        for f, r in zip(chunk, model.predict(chunk, imgsz=imgsz, conf=conf, verbose=False, device=0)):
            lf = f.replace("/images/", "/labels/").rsplit(".", 1)[0] + ".txt"
            gts = [[float(v) for v in l.split()[1:5]] for l in open(lf) if l.strip()] if os.path.exists(lf) else []
            preds = r.boxes.xywhn.cpu().numpy().tolist()        # [cx, cy, w, h] normalised
            used = set()
            for g in gts:
                bucket = next(k for k, e in EDGES if g[2]*g[3] < e); tot[bucket] += 1
                best, bj = 0.0, -1
                for j, p in enumerate(preds):
                    v = iou(g, p)
                    if v > best: best, bj = v, j
                if best >= 0.3: tp[bucket] += 1; used.add(bj)
            fp += max(0, len(preds) - len(used))
    out = {k: {"recall": round(tp[k]/tot[k], 3) if tot[k] else None, "n": tot[k]} for k, _ in EDGES}
    out["fp_per_image"] = round(fp / max(1, len(files)), 3)
    return out
def far_report(model, imgsz):
    try:
        return {"warzone_heldout": far_eval(model, imgsz, f"{A}/wzval/images"), "mobile_val": far_eval(model, imgsz, f"{B}/valid/images")}
    except Exception as e:
        traceback.print_exc(); return {"error": str(e)[:300]}
def metrics(m, yaml, imgsz):
    out = {}
    for sp in ("val", "test"):
        try:
            r = m.val(data=yaml, imgsz=imgsz, split=sp, device=0)
            out[sp] = dict(map50=float(r.box.map50), map50_95=float(r.box.map), precision=float(r.box.mp), recall=float(r.box.mr))
        except Exception as e: out[sp + "_error"] = str(e)[:200]
    return out

# 0) baseline = the current COD model (no P2, 960) on the same far-range test
try:
    urllib.request.urlretrieve(BASE_URL, f"{OUT}/base.pt")
    result["baseline_cod_v1_960"] = far_report(YOLO(f"{OUT}/base.pt"), 960)
except Exception as e:
    result["errors"].append(f"baseline: {e}"); traceback.print_exc()
save()

# 1) stage 1: P2 model (stride-4 head sees tiny objects) on Warzone + mobile x3
try:
    YOLO(f"{NAME}.pt")                       # triggers the pretrained download
    m = YOLO(f"{NAME}-p2.yaml").load(f"{NAME}.pt")
    m.train(data=YA, epochs=30, imgsz=960, batch=12, device=0, project=f"{OUT}/runs", name="s1", exist_ok=True,
            patience=25, workers=2, seed=0, plots=False, close_mosaic=6, scale=0.8, translate=0.2, mixup=0.05, hsv_s=0.6, hsv_v=0.4)
    s1 = f"{OUT}/runs/s1/weights/best.pt"
    result["stage1_p2_960"] = far_report(YOLO(s1), 960); save()
    # 2) stage 2: mobile set at 1280
    m2 = YOLO(s1)
    m2.train(data=YB, epochs=100, imgsz=1280, batch=8 if MODEL == "n" else 6, device=0, project=f"{OUT}/runs", name="s2",
             exist_ok=True, patience=50, workers=2, seed=1, plots=False, cos_lr=True, lr0=0.002, lrf=0.02, warmup_epochs=2,
             close_mosaic=15, scale=0.8, translate=0.2, mixup=0.1, hsv_s=0.7, hsv_v=0.5, degrees=0.0, flipud=0.0)
    best = f"{OUT}/runs/s2/weights/best.pt"
    shutil.copy(best, f"{MODELS}/{NAME}_far_p2_best.pt")
    fm = YOLO(best)
    result["final_metrics_1280"] = metrics(fm, YB, 1280)
    result["final_far_960"] = far_report(fm, 960)
    result["final_far_1280"] = far_report(fm, 1280); save()
    for sz in (960, 1280):
        try:
            YOLO(best).export(format="onnx", imgsz=sz)
            for f in glob.glob(f"{OUT}/runs/s2/weights/*.onnx"): shutil.move(f, f"{MODELS}/{NAME}_far_p2_{sz}.onnx")
        except Exception as e: result["errors"].append(f"onnx{sz}: {str(e)[:200]}")
        try:
            YOLO(best).export(format="tflite", imgsz=sz)
            c = glob.glob(f"{OUT}/runs/s2/weights/**/*.tflite", recursive=True)
            if c: shutil.copy(c[0], f"{MODELS}/{NAME}_far_p2_{sz}_fp32.tflite")
            else: result["errors"].append(f"tflite{sz}: no file")
        except Exception as e: result["errors"].append(f"tflite{sz}: {str(e)[:300]}"); traceback.print_exc()
        for f in glob.glob(f"{OUT}/runs/s2/weights/*_saved_model"): shutil.rmtree(f, ignore_errors=True)
        for f in glob.glob(f"{OUT}/runs/s2/weights/*.tflite"): os.remove(f)
except Exception as e:
    result["errors"].append(f"train: {e}"); traceback.print_exc()
save()
for p in (f"{OUT}/runs", A, B): shutil.rmtree(p, ignore_errors=True)
for f in glob.glob(f"{OUT}/*.pt"): os.remove(f)
print(json.dumps(result, indent=2))
