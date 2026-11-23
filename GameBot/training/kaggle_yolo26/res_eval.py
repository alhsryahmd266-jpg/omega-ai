# CPU experiment (no GPU quota): does feeding the detector at the screen's NATIVE resolution find more small enemies?
# Held-out synthetic far enemies (crops from never-trained Warzone images, pasted on never-trained frames of the player's recordings),
# recall by enemy height, same model, different inference sizes.
import os, glob, json, random, shutil, subprocess, urllib.request, zipfile, time
import numpy as np, cv2
OUT = os.getcwd(); BASE = "https://github.com/alhsryahmd266-jpg/omega-ai"
def dl(url, dst): urllib.request.urlretrieve(url, dst); return dst
os.makedirs("w", exist_ok=True)
for u, d in ((f"{BASE}/releases/download/cod-merged-v2/merged_cod.zip", "w/A"), (f"{BASE}/raw/yolo-data/video_all.zip", "w/V")):
    dl(u, "w/tmp.zip"); zipfile.ZipFile("w/tmp.zip").extractall(d); os.remove("w/tmp.zip")
dl(f"{BASE}/releases/download/vision-latest/enemy_vision.pt", "w/vision.pt")
SYN = {}; exec(open("synth_lib.py").read(), SYN)
man = json.load(open("w/V/manifest.json"))
bgs = [f"w/V/{sp}/images/{n}.jpg" for sp, n in man["quiet"] + man["hardneg"] if sp == "valid"]
pool = SYN["build_crop_pool"](["w/A/wzval/images"], max_crops=150, seed=5)
print("held-out crops", len(pool), "backgrounds", len(bgs), flush=True)
BUCKETS = [("8-12px", 8, 12), ("12-20px", 12, 20), ("20-35px", 20, 35), ("35-60px", 35, 60)]
rnd = random.Random(1); items = []
os.makedirs("w/test/images", exist_ok=True)
for bname, lo, hi in BUCKETS:
    k = 0
    while k < 40:
        bg = cv2.imread(rnd.choice(bgs)); bg = cv2.resize(bg, (1600, 720)) if bg.shape[1] != 1600 else bg
        crop, b, mk = rnd.choice(pool)
        th = rnd.uniform(lo, hi)
        cx = rnd.uniform(SYN["ALLOWED"][0], SYN["ALLOWED"][2]); cy = rnd.uniform(SYN["ALLOWED"][1], SYN["ALLOWED"][3])
        box = SYN["paste_one"](bg, crop, b, mk, th, cx, cy, rnd)
        if box is None: continue
        f = f"w/test/images/{bname}_{k:03d}.jpg"; cv2.imwrite(f, bg, [cv2.IMWRITE_JPEG_QUALITY, 92])
        items.append((bname, f, ((box[0] + box[2] / 2) / 1600, (box[1] + box[3] / 2) / 720, box[2] / 1600, box[3] / 720))); k += 1
print("test images", len(items), flush=True)
subprocess.run("pip install -q ultralytics --extra-index-url https://download.pytorch.org/whl/cpu", shell=True)
from ultralytics import YOLO
model = YOLO("w/vision.pt")
def iou(a, b):
    ax1, ay1, ax2, ay2 = a[0]-a[2]/2, a[1]-a[3]/2, a[0]+a[2]/2, a[1]+a[3]/2
    bx1, by1, bx2, by2 = b[0]-b[2]/2, b[1]-b[3]/2, b[0]+b[2]/2, b[1]+b[3]/2
    iw = max(0, min(ax2, bx2) - max(ax1, bx1)); ih = max(0, min(ay2, by2) - max(ay1, by1)); i = iw * ih
    u = a[2]*a[3] + b[2]*b[3] - i
    return i / u if u > 0 else 0
res = {"settings": {}}
for sz in (1152, 1600, 1920):
    t0 = time.time(); tp = {b[0]: 0 for b in BUCKETS}; n = {b[0]: 0 for b in BUCKETS}; fp = 0
    for i in range(0, len(items), 16):
        chunk = items[i:i + 16]
        for (bname, f, gt), r in zip(chunk, model.predict([c[1] for c in chunk], imgsz=sz, conf=0.25, verbose=False, device="cpu")):
            preds = r.boxes.xywhn.cpu().numpy().tolist(); n[bname] += 1
            hit = [p for p in preds if iou(gt, p) >= 0.3]
            tp[bname] += 1 if hit else 0; fp += len(preds) - len(hit)
    res["settings"][str(sz)] = {"recall": {k: round(tp[k] / n[k], 3) for k in n}, "false_boxes_per_image": round(fp / len(items), 3), "seconds_per_image": round((time.time() - t0) / len(items), 2)}
    print(sz, res["settings"][str(sz)], flush=True)
json.dump(res, open("res_eval.json", "w"), indent=2)
