# Kaggle script: FINAL mixed training (Warzone + mobile + curated gameplay video), P2 head, no mobile-only stage
# (that stage made the model forget everything except the 432 mobile images). Reports size-bucketed recall and
# false-positive rates on own-avatar / crosshair / scope frames from the recorded video.
# __MODEL__ is replaced by 'n' or 's' when the kernel is pushed.
import os, glob, json, shutil, subprocess, traceback, urllib.request, zipfile
MODEL = "__MODEL__"; NAME = f"yolo26{MODEL}"
OUT = "/kaggle/working"; os.chdir(OUT)
A = f"{OUT}/dsA"; B = f"{OUT}/dsB"; V = f"{OUT}/dsV"; MODELS = f"{OUT}/models/{NAME}"
URL_A = "https://github.com/alhsryahmd266-jpg/omega-ai/releases/download/cod-merged-v2/merged_cod.zip"
URL_B = "https://github.com/alhsryahmd266-jpg/omega-ai/raw/yolo-data/dataset.zip"
URL_V = "https://github.com/alhsryahmd266-jpg/omega-ai/raw/yolo-data/video_ds2.zip"
PREV_URL = f"https://github.com/alhsryahmd266-jpg/omega-ai/releases/download/gamebot-final-{MODEL}-v1/{NAME}_final_p2_best.pt"
BASE_URL = f"https://github.com/alhsryahmd266-jpg/omega-ai/releases/download/gamebot-yolo26-cod-v1/{NAME}_cod_enemy_best.pt"
result = {"model": NAME, "errors": []}
os.makedirs(MODELS, exist_ok=True)
def sh(c): print("$", c, flush=True); return subprocess.run(c, shell=True).returncode
def save():
    json.dump(result, open(f"{OUT}/far_result.json", "w"), indent=2)
    shutil.copy(f"{OUT}/far_result.json", f"{MODELS}/far_result.json")
sh("pip install -q -U ultralytics")
for url, dst in ((URL_A, A), (URL_B, B), (URL_V, V)):
    urllib.request.urlretrieve(url, f"{OUT}/tmp.zip")
    with zipfile.ZipFile(f"{OUT}/tmp.zip") as z: z.extractall(dst)
    os.remove(f"{OUT}/tmp.zip")
imgs = sorted(glob.glob(f"{A}/train/images/*"))
mb = [f"{A}/train/images/{l.strip()}" for l in open(f"{A}/mobile_train_list.txt") if l.strip()]
vtr = sorted(glob.glob(f"{V}/train/images/*"))
open(f"{A}/train_all.txt", "w").write("\n".join(imgs + mb + mb + vtr * 3))      # mobile x3, video x4
val_all = sorted(glob.glob(f"{A}/valid/images/*")) + sorted(glob.glob(f"{V}/valid/images/*"))
open(f"{A}/val_all.txt", "w").write("\n".join(val_all))
YA = f"{A}/final.yaml"
open(YA, "w").write(f"path: {A}\ntrain: {A}/train_all.txt\nval: {A}/val_all.txt\ntest: {A}/test/images\nnc: 1\nnames: ['enemy']\n")
YB = f"{B}/abs.yaml"
open(YB, "w").write(f"path: {B}\ntrain: train/images\nval: valid/images\ntest: test/images\nnc: 1\nnames: ['enemy']\n")
man = json.load(open(f"{V}/manifest.json"))
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

def video_eval(model, imgsz, conf=0.25):
    """Held-out gameplay-video frames: recall on real enemies, and false positives on own-avatar/crosshair/scope + quiet frames."""
    out = {}
    try:
        def run_files(names, sp="valid"):
            fl = [f"{V}/{sp}/images/{n}.jpg" for s_, n in names if s_ == sp]
            res = []
            for i in range(0, len(fl), 16):
                res += list(zip(fl[i:i+16], model.predict(fl[i:i+16], imgsz=imgsz, conf=conf, verbose=False, device=0)))
            return res
        tp = tot = 0
        for f, r in run_files(man["pos"]):
            lf = f.replace("/images/", "/labels/").rsplit(".", 1)[0] + ".txt"
            gts = [[float(v) for v in l.split()[1:5]] for l in open(lf) if l.strip()]
            preds = r.boxes.xywhn.cpu().numpy().tolist()
            for g in gts:
                tot += 1
                if any(iou(g, p) >= 0.3 for p in preds): tp += 1
        out["enemy_recall"] = {"recall": round(tp / tot, 3) if tot else None, "n": tot}
        for key in ("hardneg", "quiet"):
            rs = run_files(man[key])
            out[key + "_frames_with_false_detection"] = {"rate": round(sum(1 for _, r in rs if len(r.boxes)) / max(1, len(rs)), 3), "n": len(rs)}
    except Exception as e:
        traceback.print_exc(); out["error"] = str(e)[:300]
    return out
def metrics(m, yaml, imgsz):
    out = {}
    for sp in ("val", "test"):
        try:
            r = m.val(data=yaml, imgsz=imgsz, split=sp, device=0)
            out[sp] = dict(map50=float(r.box.map50), map50_95=float(r.box.map), precision=float(r.box.mp), recall=float(r.box.mr))
        except Exception as e: out[sp + "_error"] = str(e)[:200]
    return out

# 0) references on the same held-out sets: the old mobile-only COD model and the previous far-range (mobile-only stage 2) model
refs = {}
try:
    urllib.request.urlretrieve(BASE_URL, f"{OUT}/base.pt"); refs["baseline_cod_v1_960"] = YOLO(f"{OUT}/base.pt")
except Exception as e: result["errors"].append(f"baseline: {e}")
try:
    urllib.request.urlretrieve(PREV_URL, f"{OUT}/prev.pt"); refs["previous_final_v1"] = YOLO(f"{OUT}/prev.pt")
except Exception as e: result["errors"].append(f"prev: {e}")
for k, mdl in refs.items():
    sz = 1280 if "1280" in k else 960
    result[k] = {"far": far_report(mdl, sz), "video": video_eval(mdl, sz)}
save()

# 1) one mixed training run (no mobile-only stage)
try:
    YOLO(f"{NAME}.pt")
    m = YOLO(f"{NAME}-p2.yaml").load(f"{NAME}.pt")
    hours = 4.5 if MODEL == "n" else 5.0
    m.train(data=YA, epochs=80 if MODEL == "n" else 70, time=hours, imgsz=1152, batch=8 if MODEL == "n" else 6, device=0,
            project=f"{OUT}/runs", name="final", exist_ok=True, patience=40, workers=2, seed=0, plots=False,
            close_mosaic=10, scale=0.8, translate=0.2, mixup=0.05, hsv_s=0.6, hsv_v=0.4, fliplr=0.5, cos_lr=True)
    best = f"{OUT}/runs/final/weights/best.pt"
    shutil.copy(best, f"{MODELS}/{NAME}_final2_p2_best.pt")
    fm = YOLO(best)
    result["final_metrics_mobile"] = metrics(fm, YB, 1152)
    for sz in (960, 1152):
        result[f"final_{sz}"] = {"far": far_report(fm, sz), "video": video_eval(fm, sz)}
        save()
    # export: ONNX + LiteRT/TFLite with an NHWC input ([1,H,W,3]) - the layout the GameBot app requires
    try:
        YOLO("yolo26n.pt").export(format="litert", imgsz=64)        # installs the litert toolchain
    except Exception as e:
        print("warm-up export:", str(e)[:200])
    try:
        import torch, litert_torch
        _orig = litert_torch.convert
        class NHWC(torch.nn.Module):
            def __init__(self, m): super().__init__(); self.m = m
            def forward(self, x): return self.m(x.permute(0, 3, 1, 2))
        def _conv(model, args, *a, **k):
            return _orig(NHWC(model).eval(), (args[0].permute(0, 2, 3, 1).contiguous(),), *a, **k)
        litert_torch.convert = _conv
    except Exception as e:
        result["errors"].append(f"nhwc patch: {str(e)[:200]}")
    for sz in (960, 1152):
        try:
            YOLO(best).export(format="onnx", imgsz=sz)
            for f in glob.glob(f"{OUT}/runs/final/weights/*.onnx"): shutil.move(f, f"{MODELS}/{NAME}_final2_p2_{sz}.onnx")
        except Exception as e: result["errors"].append(f"onnx{sz}: {str(e)[:200]}")
        try:
            for f in glob.glob(f"{OUT}/runs/final/weights/*.tflite"): os.remove(f)
            YOLO(best).export(format="litert", imgsz=sz)
            c = glob.glob(f"{OUT}/runs/final/weights/**/*.tflite", recursive=True)
            if c:
                dst = f"{MODELS}/{NAME}_final2_p2_{sz}_nhwc.tflite"; shutil.copy(c[0], dst)
                from ai_edge_litert.interpreter import Interpreter
                it = Interpreter(model_path=dst); it.allocate_tensors()
                result[f"tflite_{sz}_io"] = [it.get_input_details()[0]["shape"].tolist(), it.get_output_details()[0]["shape"].tolist()]
            else: result["errors"].append(f"tflite{sz}: no file")
        except Exception as e: result["errors"].append(f"tflite{sz}: {str(e)[:300]}"); traceback.print_exc()
        save()
except Exception as e:
    result["errors"].append(f"train: {e}"); traceback.print_exc()
save()
for p in (f"{OUT}/runs", A, B, V): shutil.rmtree(p, ignore_errors=True)
for f in glob.glob(f"{OUT}/*.pt"): os.remove(f)
print(json.dumps(result, indent=2))
