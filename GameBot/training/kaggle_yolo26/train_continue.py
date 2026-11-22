# Kaggle script: ONE CONTINUAL ROUND. Warm-starts from the published vision-latest model and trains on the cumulative data
# (base sets + every video so far), compares before/after on held-out sets and only accepts the update if nothing regressed.
# (older header follows) FINAL mixed training (Warzone + mobile + curated gameplay video), P2 head, no mobile-only stage
# (that stage made the model forget everything except the 432 mobile images). Reports size-bucketed recall and
# false-positive rates on own-avatar / crosshair / scope frames from the recorded video.
# __MODEL__ is replaced by 'n' or 's' when the kernel is pushed.
import os, glob, json, shutil, subprocess, traceback, urllib.request, zipfile, time
MODEL = "n"; NAME = "yolo26n"
LATEST = "https://github.com/alhsryahmd266-jpg/omega-ai/releases/download/vision-latest"
OUT = "/kaggle/working"; os.chdir(OUT)
A = f"{OUT}/dsA"; B = f"{OUT}/dsB"; V = f"{OUT}/dsV"; MODELS = f"{OUT}/models/{NAME}"
URL_A = "https://github.com/alhsryahmd266-jpg/omega-ai/releases/download/cod-merged-v2/merged_cod.zip"
URL_B = "https://github.com/alhsryahmd266-jpg/omega-ai/raw/yolo-data/dataset.zip"
URL_V = "https://github.com/alhsryahmd266-jpg/omega-ai/raw/yolo-data/video_all.zip"
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
# ---- zoom augmentation: add the same three magnified windows the app scans (crosshair / left / right, 50% x 60% of the frame)
from PIL import Image
def _zoom_crops(img_path):
    lbl = img_path.replace("/images/", "/labels/").rsplit(".", 1)[0] + ".txt"
    im = Image.open(img_path).convert("RGB"); W, H = im.size
    boxes = [[float(v) for v in l.split()[1:5]] for l in open(lbl) if l.strip()] if os.path.exists(lbl) else []
    cw, ch = int(W * 0.5), int(H * 0.6); y0 = (H - ch) // 2
    base = os.path.basename(img_path).rsplit(".", 1)[0]
    for tag, x0 in (("zc", (W - cw) // 2), ("zl", 0), ("zr", W - cw)):
        out_i = f"{V}/train/images/{base}_{tag}.jpg"; out_l = f"{V}/train/labels/{base}_{tag}.txt"
        rows = []
        for cx, cy, bw, bh in boxes:
            bx1, by1, bx2, by2 = (cx - bw / 2) * W, (cy - bh / 2) * H, (cx + bw / 2) * W, (cy + bh / 2) * H
            ix1, iy1, ix2, iy2 = max(bx1, x0), max(by1, y0), min(bx2, x0 + cw), min(by2, y0 + ch)
            if ix2 - ix1 < 4 or iy2 - iy1 < 4: continue
            if (ix2 - ix1) * (iy2 - iy1) < 0.6 * (bx2 - bx1) * (by2 - by1): continue
            rows.append(f"0 {((ix1 + ix2) / 2 - x0) / cw:.6f} {((iy1 + iy2) / 2 - y0) / ch:.6f} {(ix2 - ix1) / cw:.6f} {(iy2 - iy1) / ch:.6f}")
        im.crop((x0, y0, x0 + cw, y0 + ch)).save(out_i, quality=92)
        open(out_l, "w").write("\n".join(rows) + ("\n" if rows else ""))
for _p in sorted(glob.glob(f"{V}/train/images/*.jpg")):
    if "_z" not in os.path.basename(_p): _zoom_crops(_p)
vtr = sorted(glob.glob(f"{V}/train/images/*"))
# ---- synthetic FAR enemies: real crops (GrabCut person mask), shrunk to 7-75 px, pasted on enemy-free frames of the player's own videos
SYNTH_SRC = r'''# Synthetic FAR enemies: real enemy crops, shrunk and pasted onto real (enemy-free) frames of the player's own recordings.
import cv2, glob, json, os, random
import numpy as np

W, H = 1600, 720
ALLOWED = (260, 80, 1250, 430)      # x0, y0, x1, y1 where an enemy can plausibly stand (no HUD, not on the own avatar)

def load_labels(lf):
    rows = []
    if os.path.exists(lf):
        for l in open(lf):
            p = l.split()
            if len(p) >= 5: rows.append([float(v) for v in p[1:5]])
    return rows

def fg_mask(crop, box):
    """Foreground (the person) via GrabCut started from the labelled box; None if the result is implausible."""
    bx, by, bw, bh = [int(round(v)) for v in box]
    h, w = crop.shape[:2]
    bx, by = max(1, bx), max(1, by); bw, bh = min(bw, w - bx - 1), min(bh, h - by - 1)
    if bw < 8 or bh < 16: return None
    mask = np.zeros((h, w), np.uint8); bgd = np.zeros((1, 65), np.float64); fgd = np.zeros((1, 65), np.float64)
    try:
        cv2.grabCut(crop, mask, (bx, by, bw, bh), bgd, fgd, 4, cv2.GC_INIT_WITH_RECT)
    except cv2.error:
        return None
    fg = ((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD)).astype(np.uint8)
    fg[:by, :] = 0; fg[by + bh:, :] = 0; fg[:, :bx] = 0; fg[:, bx + bw:] = 0
    frac = fg.sum() / float(bw * bh)
    if frac < 0.22 or frac > 0.92: return None
    fg = cv2.morphologyEx(fg, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    return fg.astype(np.float32)

def build_crop_pool(image_dirs, max_crops=700, min_h=44, seed=0):
    rnd = random.Random(seed); files = []
    for d in image_dirs: files += glob.glob(f"{d}/*.jpg") + glob.glob(f"{d}/*.png")
    rnd.shuffle(files); pool = []
    for f in files:
        if len(pool) >= max_crops: break
        lf = f.replace("/images/", "/labels/").rsplit(".", 1)[0] + ".txt"
        boxes = load_labels(lf)
        if not boxes: continue
        im = cv2.imread(f)
        if im is None: continue
        ih, iw = im.shape[:2]
        for cx, cy, w, h in boxes:
            bw, bh = w * iw, h * ih
            if bh < min_h or not (1.3 <= bh / max(bw, 1) <= 4.2): continue
            mx, my = 0.10 * bw, 0.06 * bh
            x0, y0 = int(max(0, cx * iw - bw / 2 - mx)), int(max(0, cy * ih - bh / 2 - my))
            x1, y1 = int(min(iw, cx * iw + bw / 2 + mx)), int(min(ih, cy * ih + bh / 2 + my))
            if x1 - x0 < 10 or y1 - y0 < 20: continue
            # fraction of the crop that is the enemy box (so the label stays tight after resizing)
            crop = im[y0:y1, x0:x1].copy()
            box = (cx * iw - bw / 2 - x0, cy * ih - bh / 2 - y0, bw, bh)
            m = fg_mask(crop, box)
            if m is None: continue
            pool.append((crop, box, m))
            break
    return pool

def paste_one(bg, crop, box_in_crop, mask, target_h, cx, cy, rnd):
    """Paste the person (silhouette from `mask`) so the enemy box is target_h px tall, centered at (cx, cy)."""
    bx, by, bw, bh = box_in_crop
    s = target_h / bh
    nw, nh = max(3, int(round(crop.shape[1] * s))), max(5, int(round(crop.shape[0] * s)))
    small = cv2.resize(crop, (nw, nh), interpolation=cv2.INTER_AREA)
    a = cv2.resize(mask, (nw, nh), interpolation=cv2.INTER_AREA)
    a = cv2.GaussianBlur(a, (0, 0), max(0.5, 0.07 * min(nw, nh)))[..., None]
    sg = rnd.uniform(0.0, 0.9)
    if sg > 0.2: small = cv2.GaussianBlur(small, (0, 0), sg)
    ex, ey, ew, eh = bx * s, by * s, bw * s, bh * s
    x0 = int(round(cx - ex - ew / 2)); y0 = int(round(cy - ey - eh / 2))
    if x0 < 0 or y0 < 0 or x0 + nw > W or y0 + nh > H: return None
    roi = bg[y0:y0 + nh, x0:x0 + nw].astype(np.float32)
    src = small.astype(np.float32)
    fgm = (a[..., 0] > 0.5)
    if fgm.sum() < 3: return None
    # keep the person's own contrast but move its brightness a bit toward the local scene (distance haze)
    sm = src[fgm].mean(); rm = roi.mean()
    src = np.clip(src + (rm - sm) * rnd.uniform(0.15, 0.45), 0, 255)
    bg[y0:y0 + nh, x0:x0 + nw] = (a * src + (1 - a) * roi).astype(np.uint8)
    return (x0 + ex, y0 + ey, ew, eh)

def iou(a, b):
    ix = max(0, min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0])); iy = max(0, min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1]))
    i = ix * iy; u = a[2] * a[3] + b[2] * b[3] - i
    return i / u if u > 0 else 0

def make_synthetic(bgs, pool, n, out_dir, seed=0):
    rnd = random.Random(seed)
    os.makedirs(f"{out_dir}/images", exist_ok=True); os.makedirs(f"{out_dir}/labels", exist_ok=True)
    made = 0; sizes = []
    for i in range(n):
        bg = cv2.imread(rnd.choice(bgs))
        if bg is None: continue
        if bg.shape[1] != W: bg = cv2.resize(bg, (W, H))
        boxes = []
        for _ in range(rnd.choice([1, 1, 1, 2, 2, 3])):
            th = float(np.exp(rnd.uniform(np.log(7), np.log(75))))                # heights 7..75 px; most of them small
            crop, b, m = rnd.choice(pool)
            cx = rnd.uniform(ALLOWED[0], ALLOWED[2]); cy = rnd.uniform(ALLOWED[1], ALLOWED[3])
            box = paste_one(bg, crop, b, m, th, cx, cy, rnd)
            if box is None or any(iou(box, o) > 0.15 for o in boxes): continue
            boxes.append(box); sizes.append(box[3])
        if not boxes: continue
        name = f"syn_{seed}_{i:05d}"
        cv2.imwrite(f"{out_dir}/images/{name}.jpg", bg, [cv2.IMWRITE_JPEG_QUALITY, 90])
        with open(f"{out_dir}/labels/{name}.txt", "w") as fh:
            for x, y, w, h in boxes: fh.write(f"0 {(x + w / 2) / W:.6f} {(y + h / 2) / H:.6f} {w / W:.6f} {h / H:.6f}\n")
        made += 1
    return made, sizes
'''
SYN = {}; exec(SYNTH_SRC, SYN)
syn_imgs = []
try:
    _man0 = json.load(open(f"{V}/manifest.json"))
    _bgs = [f"{V}/{sp}/images/{n}.jpg" for sp, n in _man0["quiet"] + _man0["hardneg"] if sp == "train"]
    _dirs = [f"{A}/train/images"] + [f"{V}/train/images"]
    _pool = SYN["build_crop_pool"](_dirs, max_crops=420, seed=rnd0 if "rnd0" in globals() else 0)
    _made, _sizes = SYN["make_synthetic"](_bgs, _pool, 2500, f"{OUT}/synth/train", seed=11)
    syn_imgs = sorted(glob.glob(f"{OUT}/synth/train/images/*.jpg"))
    result["synthetic"] = {"crops": len(_pool), "backgrounds": len(_bgs), "images": len(syn_imgs),
                           "box_height_px_p10_p50_p90": [float(x) for x in __import__("numpy").percentile(_sizes, [10, 50, 90])] if _sizes else None}
except Exception as e:
    result["errors"].append(f"synthetic: {e}"); traceback.print_exc()
open(f"{A}/train_all.txt", "w").write("\n".join(imgs + mb + mb + vtr * 3 + syn_imgs))      # mobile x3, video x4, + synthetic far enemies
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

# 0) the current published model is the reference
cur = f"{OUT}/current.pt"
urllib.request.urlretrieve(f"{LATEST}/enemy_vision.pt", cur)
try:
    hist = json.load(urllib.request.urlopen(f"{LATEST}/vision_history.json"))
except Exception:
    hist = {"rounds": []}
ref = YOLO(cur)
result["before"] = {"far": far_report(ref, 1152), "video": video_eval(ref, 1152), "mobile": metrics(ref, YB, 1152)}
save()

# 1) continue training from the current weights on the cumulative data, in chunks, until the far/small target is reached
rnd = len(hist["rounds"])
TARGET_SMALL = 0.70      # recall on HELD-OUT Warzone enemies smaller than 0.2% of the frame (never trained on)
def get(d, *p):
    for k in p:
        if not isinstance(d, dict) or k not in d: return None
        d = d[k]
    return d
try:
    before_fp = get(result["before"], "video", "hardneg_frames_with_false_detection", "rate") or 0.0
    cand = cur; best_score = -9.0; best = None; chunks = []; t_start = time.time()
    for ci in range(5):
        m = YOLO(cand)
        m.train(data=YA, epochs=24, time=0.8, imgsz=1152, batch=8, device=0, project=f"{OUT}/runs", name=f"chunk{ci}", exist_ok=True,
                patience=15, workers=2, seed=rnd + 1 + ci, plots=False, close_mosaic=6, scale=0.8, translate=0.2, mixup=0.05,
                hsv_s=0.6, hsv_v=0.4, fliplr=0.5, cos_lr=True, lr0=0.001 / (1 + 0.4 * ci), lrf=0.1, warmup_epochs=1)
        cand = f"{OUT}/runs/chunk{ci}/weights/best.pt"
        cm = YOLO(cand)
        rep_ = far_report(cm, 1152); vid_ = video_eval(cm, 1152)
        small = get(rep_, "warzone_heldout", "small<0.2%", "recall"); fp = get(vid_, "video", "x") if False else get(vid_, "hardneg_frames_with_false_detection", "rate")
        chunks.append({"chunk": ci, "minutes": round((time.time() - t_start) / 60), "tiny_recall": get(rep_, "warzone_heldout", "tiny<0.05%", "recall"),
                       "small_recall": small, "medium_recall": get(rep_, "warzone_heldout", "medium<0.8%", "recall"),
                       "large_recall": get(rep_, "warzone_heldout", "large", "recall"), "hardneg_false_alarms": fp,
                       "enemy_recall_video": get(vid_, "enemy_recall", "recall")})
        result["chunks"] = chunks; save()
        score = (small or 0.0) - 0.5 * (fp or 0.0)
        if score > best_score: best_score, best = score, cand
        if small is not None and small >= TARGET_SMALL and (fp is None or fp <= before_fp + 0.05): break
        if time.time() - t_start > 4.2 * 3600: break
    result["target_small_reached"] = bool(chunks and (chunks[-1]["small_recall"] or 0) >= TARGET_SMALL)
    new = YOLO(best)
    result["after"] = {"far": far_report(new, 1152), "video": video_eval(new, 1152), "mobile": metrics(new, YB, 1152)}
    save()

    # 2) gate: never publish a regression
    b, a = result["before"], result["after"]
    checks = {
        "enemy_recall": (get(a, "video", "enemy_recall", "recall"), get(b, "video", "enemy_recall", "recall"), -0.05),
        "own_avatar_false_alarms": (get(b, "video", "hardneg_frames_with_false_detection", "rate"), get(a, "video", "hardneg_frames_with_false_detection", "rate"), -0.05),
        "far_medium_recall": (get(a, "far", "warzone_heldout", "medium<0.8%", "recall"), get(b, "far", "warzone_heldout", "medium<0.8%", "recall"), -0.03),
        "mobile_map50": (get(a, "mobile", "val", "map50"), get(b, "mobile", "val", "map50"), -0.02),
        "far_small_recall": (get(a, "far", "warzone_heldout", "small<0.2%", "recall"), get(b, "far", "warzone_heldout", "small<0.2%", "recall"), -0.02),
    }
    verdict = {}
    for k, (x, y, tol) in checks.items():
        verdict[k] = True if (x is None or y is None) else (x - y) >= tol      # x must not be worse than y by more than |tol|
    result["gate"] = {"checks": {k: [v[0], v[1]] for k, v in checks.items()}, "passed": verdict}
    result["accepted"] = all(verdict.values())
    shutil.copy(best, f"{MODELS}/enemy_vision.pt" if result["accepted"] else f"{MODELS}/rejected_candidate.pt")

    # 3) export (NHWC input, the layout the app requires)
    try:
        YOLO("yolo26n.pt").export(format="litert", imgsz=64)
    except Exception as e:
        print("warm-up export:", str(e)[:200])
    import torch, litert_torch
    _orig = litert_torch.convert
    class NHWC(torch.nn.Module):
        def __init__(self, m): super().__init__(); self.m = m
        def forward(self, x): return self.m(x.permute(0, 3, 1, 2))
    def _conv(model, args, *a, **k):
        return _orig(NHWC(model).eval(), (args[0].permute(0, 2, 3, 1).contiguous(),), *a, **k)
    litert_torch.convert = _conv
    for f in glob.glob(f"{os.path.dirname(best)}/*.tflite"): os.remove(f)
    YOLO(best).export(format="litert", imgsz=1152)
    c = glob.glob(f"{os.path.dirname(best)}/**/*.tflite", recursive=True)
    if not c: raise RuntimeError("no tflite produced")
    dst = f"{MODELS}/enemy_vision.tflite" if result["accepted"] else f"{MODELS}/rejected_candidate.tflite"
    shutil.copy(c[0], dst)
    from ai_edge_litert.interpreter import Interpreter
    it = Interpreter(model_path=dst); it.allocate_tensors()
    result["tflite_io"] = [it.get_input_details()[0]["shape"].tolist(), it.get_output_details()[0]["shape"].tolist()]
    if result["accepted"]: open(f"{MODELS}/labels.txt", "w").write("enemy\n")
except Exception as e:
    result["errors"].append(f"round: {e}"); result["accepted"] = False; traceback.print_exc()

hist["rounds"].append({"round": rnd, "accepted": bool(result.get("accepted")), "train_images": len(imgs) + len(mb) * 2 + len(vtr) * 3,
                       "video_train_frames": len(vtr), "before": result.get("before", {}).get("video"), "after": result.get("after", {}).get("video"),
                       "mobile_map50_after": (result.get("after", {}).get("mobile", {}).get("val", {}) or {}).get("map50")})
json.dump(hist, open(f"{MODELS}/vision_history.json", "w"), indent=2)
shutil.copy(f"{MODELS}/vision_history.json", f"{OUT}/vision_history.json")
save()
for p in (f"{OUT}/runs", A, B, V): shutil.rmtree(p, ignore_errors=True)
for f in glob.glob(f"{OUT}/*.pt"): os.remove(f)
print(json.dumps(result, indent=2))
