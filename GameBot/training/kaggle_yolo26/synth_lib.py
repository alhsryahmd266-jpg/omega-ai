# Synthetic FAR enemies: real enemy crops, shrunk and pasted onto real (enemy-free) frames of the player's own recordings.
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
