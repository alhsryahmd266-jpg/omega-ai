# ---------------------------------------------------------------------------------------------------------
# Brain library: reads a screen recording and recovers what the player did, then builds training samples.
#   movement  : floating-joystick knob position relative to its ring (HUD, found per frame)
#   camera    : global scene shift between consecutive frames (phase correlation on the sky/terrain band)
#   state     : enemies seen by the vision model (3 nearest the crosshair) + previous action
# The feature layout below is a CONTRACT with the Android app (BrainNet.kt). Change it only together with the app.
# ---------------------------------------------------------------------------------------------------------
import cv2, numpy as np

IN_DIM = 24
OUT_DIM = 4
YAW_SCALE = 1.5     # screen widths per second that map to 1.0
PITCH_SCALE = 1.0   # screen heights per second that map to 1.0
FPS = 10.0

def find_ring(im):
    reg = im[330:720, 0:520]
    g = cv2.cvtColor(reg, cv2.COLOR_BGR2GRAY)
    g = cv2.GaussianBlur(g, (5, 5), 1.2)
    c = cv2.HoughCircles(g, cv2.HOUGH_GRADIENT, dp=1.1, minDist=40, param1=70, param2=22, minRadius=70, maxRadius=95)
    if c is None: return None
    best = None
    for x, y, r in c[0][:6]:
        score = 0
        for dx, dy in ((-0.82, 0), (0.82, 0), (0, 0.82)):
            px, py = int(x + dx * r), int(y + dy * r)
            if 6 <= px < reg.shape[1] - 6 and 6 <= py < reg.shape[0] - 6:
                patch = g[py - 6:py + 7, px - 6:px + 7].astype(np.float32)
                score += patch.max() - np.median(g[max(0, py - 14):py + 15, max(0, px - 14):px + 15])
        if best is None or score > best[0]: best = (score, x, y + 330, r)
    return None if best is None or best[0] < 40 else (float(best[1]), float(best[2]), float(best[3]))

def _unit(vx, vy):
    n = float(np.hypot(vx, vy))
    return (vx / n, vy / n) if n > 1 else (vx, vy)

def find_knob(im, ring):
    cx, cy, r = ring
    x0, x1, y0, y1 = int(max(0, cx - 1.7 * r)), int(min(1600, cx + 1.7 * r)), int(max(0, cy - 1.7 * r)), int(min(720, cy + 1.7 * r))
    reg = im[y0:y1, x0:x1]
    hsv = cv2.cvtColor(reg, cv2.COLOR_BGR2HSV)
    m = ((hsv[..., 2] > 190) & (hsv[..., 1] < 60)).astype(np.uint8) * 255
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
    n, lab, st, cen = cv2.connectedComponentsWithStats(m)
    best = None
    for i in range(1, n):
        a = st[i, cv2.CC_STAT_AREA]; w = st[i, cv2.CC_STAT_WIDTH]; h = st[i, cv2.CC_STAT_HEIGHT]
        if 0.06 * 3.14 * r * r < a < 0.40 * 3.14 * r * r and 0.7 < w / max(1, h) < 1.4 and a / (w * h) > 0.6:
            d = np.hypot(cen[i][0] + x0 - cx, cen[i][1] + y0 - cy)
            if d < 1.5 * r and (best is None or a > best[0]): best = (a, cen[i][0] + x0, cen[i][1] + y0)
    if best is not None:
        return _unit((best[1] - cx) / r, (best[2] - cy) / r)
    g = cv2.GaussianBlur(cv2.cvtColor(reg, cv2.COLOR_BGR2GRAY), (5, 5), 1.2)
    c = cv2.HoughCircles(g, cv2.HOUGH_GRADIENT, dp=1.1, minDist=30, param1=60, param2=16, minRadius=int(0.32 * r), maxRadius=int(0.48 * r))
    if c is None: return None
    yy, xx = np.ogrid[:g.shape[0], :g.shape[1]]
    best = None
    for x, y, rr in c[0][:8]:
        if np.hypot(x + x0 - cx, y + y0 - cy) > 1.45 * r: continue
        inside = (xx - x) ** 2 + (yy - y) ** 2 < (0.8 * rr) ** 2
        ring_ = ((xx - x) ** 2 + (yy - y) ** 2 > (1.25 * rr) ** 2) & ((xx - x) ** 2 + (yy - y) ** 2 < (1.7 * rr) ** 2)
        if inside.sum() < 50 or ring_.sum() < 50: continue
        contrast = float(g[inside].mean() - g[ring_].mean()); flat = float(g[inside].std())
        sc = contrast - 0.8 * flat
        if contrast > 12 and flat < 38 and (best is None or sc > best[0]): best = (sc, x + x0, y + y0)
    if best is None: return None
    return _unit((best[1] - cx) / r, (best[2] - cy) / r)

_MASK = None
def camera_shift(prev_gray, cur_gray):
    """Median scene shift (dx, dy) in FULL-frame pixels between two 800x360 grayscale uint8 frames (feature tracking on the
    centre-top region: no HUD, no own avatar). None if too few features could be tracked."""
    global _MASK
    if _MASK is None:
        _MASK = np.zeros((360, 800), np.uint8); _MASK[20:215, 190:650] = 255
    pa = cv2.goodFeaturesToTrack(prev_gray, 300, 0.01, 8, mask=_MASK)
    if pa is None or len(pa) < 20: return None
    pb, st, err = cv2.calcOpticalFlowPyrLK(prev_gray, cur_gray, pa, None, winSize=(21, 21), maxLevel=4)
    ok = st.ravel() == 1
    d = (pb[ok] - pa[ok]).reshape(-1, 2)
    if len(d) < 15: return None
    return float(np.median(d[:, 0])) * 2.0, float(np.median(d[:, 1])) * 2.0

def sky_gray(im):
    return cv2.resize(cv2.cvtColor(im, cv2.COLOR_BGR2GRAY), (800, 360))

def read_actions(path, max_frames=None, progress=None):
    """Per-frame arrays for a 10 fps 1600x720 recording: frames x [vx, vy, yaw_rate, pitch_rate, valid]. Also returns the frames' count."""
    cap = cv2.VideoCapture(path)
    out = []; prev = None; last_ring = None; n = 0
    while True:
        ok, im = cap.read()
        if not ok or (max_frames and n >= max_frames): break
        n += 1
        if im.shape[1] != 1600: im = cv2.resize(im, (1600, 720))
        ring = find_ring(im) or last_ring
        if ring is not None: last_ring = ring
        k = find_knob(im, ring) if ring is not None else None
        g = sky_gray(im)
        sh = camera_shift(prev, g) if prev is not None else None
        prev = g
        if k is None or sh is None:
            out.append((0.0, 0.0, 0.0, 0.0, 0.0))
        else:
            yaw = -sh[0] / 1600.0 * FPS                 # camera turning right => scene shifts left
            pitch = -sh[1] / 720.0 * FPS
            out.append((k[0], -k[1], yaw, pitch, 1.0))  # moveY positive = forward
        if progress and n % 500 == 0: progress(n)
    cap.release()
    return np.array(out, dtype=np.float32)

def smooth(a, k=3):
    """centered moving average along time for the first 4 columns (valid-aware)"""
    out = a.copy()
    h = k // 2
    for i in range(len(a)):
        lo, hi = max(0, i - h), min(len(a), i + h + 1)
        seg = a[lo:hi]; v = seg[:, 4] > 0
        if v.any() and a[i, 4] > 0: out[i, :4] = seg[v, :4].mean(axis=0)
    return out

def enemy_features(dets, since_seen_s):
    """dets: list of (cx, cy, w, h, conf) normalised to the frame. Returns the 20 state numbers (3 enemies x 6 + 2 misc)."""
    ds = sorted(dets, key=lambda d: (d[0] - 0.5) ** 2 + (d[1] - 0.5) ** 2)[:3]
    f = []
    for i in range(3):
        if i < len(ds):
            cx, cy, w, h, c = ds[i]
            f += [1.0, max(-1.0, min(1.0, (cx - 0.5) * 2)), max(-1.0, min(1.0, (cy - 0.5) * 2)), min(1.0, w * 8), min(1.0, h * 4), c]
        else:
            f += [0.0] * 6
    f += [min(since_seen_s, 10.0) / 10.0, min(len(dets), 5) / 5.0]
    return f            # 20 numbers

def build_samples(actions, dets_per_frame):
    """actions: [N,5]; dets_per_frame: list of N lists. Returns X [M,24], Y [M,4], enemy_present [M] for valid frames."""
    A = smooth(actions)
    X = []; Y = []; E = []
    since = 99.0
    prev = np.zeros(4, np.float32)
    for i in range(len(A)):
        dets = dets_per_frame[i]
        since = 0.0 if dets else min(since + 1.0 / FPS, 99.0)
        if A[i, 4] > 0:
            tgt = np.array([A[i, 0], A[i, 1], A[i, 2] / YAW_SCALE, A[i, 3] / PITCH_SCALE], np.float32).clip(-1, 1)
            X.append(enemy_features(dets, since) + prev.tolist()); Y.append(tgt); E.append(1.0 if dets else 0.0)
            prev = tgt
    return np.array(X, np.float32), np.array(Y, np.float32), np.array(E, np.float32)

# ======================================== Kaggle main ========================================
import os, glob, json, shutil, subprocess, traceback, urllib.request, time
OUT = "/kaggle/working"; MODELS = f"{OUT}/models"; WORK = f"{OUT}/work"
BASE = "https://github.com/alhsryahmd266-jpg/omega-ai"
os.makedirs(MODELS, exist_ok=True); os.makedirs(WORK, exist_ok=True); os.chdir(OUT)
result = {"errors": [], "videos": {}}
def sh(c): print("$", c, flush=True); return subprocess.run(c, shell=True).returncode
def save():
    json.dump(result, open(f"{OUT}/brain_result.json", "w"), indent=2, default=str)
    shutil.copy(f"{OUT}/brain_result.json", f"{MODELS}/brain_result.json")

sh("pip install -q -U ultralytics")
import torch, torch.nn as nn
from ultralytics import YOLO
CUDA = torch.cuda.is_available()
DET_STEP = 1 if CUDA else 3                  # CPU: detect every 3rd frame and hold
result["cuda"] = CUDA

class Brain(nn.Module):
    def __init__(self):
        super().__init__()
        self.net = nn.Sequential(nn.Linear(IN_DIM, 128), nn.ReLU(), nn.Linear(128, 128), nn.ReLU(), nn.Linear(128, OUT_DIM), nn.Tanh())
    def forward(self, x): return self.net(x)

def evaluate(model, X, Y, E):
    model.eval()
    with torch.no_grad(): P = model(torch.tensor(X)).numpy()
    mse = float(((P - Y) ** 2).mean())
    m = {"mse": mse, "n": int(len(X))}
    if len(X):
        base = float(((Y - Y.mean(axis=0, keepdims=True)) ** 2).mean()); m["baseline_mse_mean"] = base
        m["persistence_mse"] = float(((X[:, 20:24] - Y) ** 2).mean())
        sel = (np.abs(Y[:, 2]) > 0.05)
        m["aim_direction_agree"] = float((np.sign(P[sel, 2]) == np.sign(Y[sel, 2])).mean()) if sel.any() else None
        fwd = np.abs(Y[:, 1]) > 0.3
        m["forward_back_agree"] = float((np.sign(P[fwd, 1]) == np.sign(Y[fwd, 1])).mean()) if fwd.any() else None
        # when an enemy is on screen: does the player (true) / the brain (pred) turn toward it?
        en = (X[:, 0] > 0.5) & (np.abs(X[:, 1]) > 0.08) & (np.abs(Y[:, 2]) > 0.05)
        if en.any():
            m["turn_toward_enemy_human"] = float((np.sign(Y[en, 2]) == np.sign(X[en, 1])).mean())
            m["turn_toward_enemy_brain"] = float((np.sign(P[en, 2]) == np.sign(X[en, 1])).mean())
            m["n_enemy_turn_samples"] = int(en.sum())
    return m

def train_brain(Xtr, Ytr, Etr, Xva, Yva, Eva, init_state=None, epochs=400, seed=0):
    torch.manual_seed(seed); np.random.seed(seed)
    model = Brain()
    if init_state is not None:
        try: model.load_state_dict(init_state)
        except Exception as e: print("warm start failed:", e)
    opt = torch.optim.AdamW(model.parameters(), lr=1.5e-3 if init_state is None else 6e-4, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=epochs)
    Xt, Yt = torch.tensor(Xtr), torch.tensor(Ytr)
    w = torch.tensor(1.0 + 2.0 * Etr)                         # situations with an enemy on screen count 3x
    Xv, Yv = torch.tensor(Xva), torch.tensor(Yva)
    best = (1e9, None); bad = 0
    for ep in range(epochs):
        model.train()
        perm = torch.randperm(len(Xt))
        for i in range(0, len(Xt), 256):
            idx = perm[i:i + 256]
            xb = Xt[idx] + 0.02 * torch.randn_like(Xt[idx])   # small input noise = robustness to detector jitter
            loss = (nn.functional.smooth_l1_loss(model(xb), Yt[idx], reduction="none").mean(dim=1) * w[idx]).mean()
            opt.zero_grad(); loss.backward(); opt.step()
        sched.step()
        model.eval()
        with torch.no_grad(): vl = float(nn.functional.smooth_l1_loss(model(Xv), Yv)) if len(Xv) else float(loss)
        if vl < best[0] - 1e-5: best = (vl, {k: v.clone() for k, v in model.state_dict().items()}); bad = 0
        else:
            bad += 1
            if bad >= 40: break
    model.load_state_dict(best[1]); model.eval()
    return model, ep + 1

def export_tflite(model, path):
    import litert_torch
    m = model.eval().cpu()
    edge = litert_torch.convert(m, (torch.randn(1, IN_DIM),))
    try: edge.export(path)
    except AttributeError: edge.save(path)
    from ai_edge_litert.interpreter import Interpreter
    it = Interpreter(model_path=path); it.allocate_tensors()
    ind, outd = it.get_input_details()[0], it.get_output_details()[0]
    x = np.random.rand(1, IN_DIM).astype(np.float32) * 2 - 1
    it.set_tensor(ind["index"], x); it.invoke()
    y = it.get_tensor(outd["index"])
    with torch.no_grad(): yt = m(torch.tensor(x)).numpy()
    return {"input": ind["shape"].tolist(), "output": outd["shape"].tolist(), "max_abs_diff_vs_torch": float(np.abs(y - yt).max())}

# ---- 0) smoke test of the learn+export path on synthetic data (fail early, not after the long video pass)
try:
    try: YOLO("yolo26n.pt").export(format="litert", imgsz=64)       # installs the litert toolchain
    except Exception as e: print("warm-up export:", str(e)[:150])
    rs = np.random.RandomState(0)
    Xs = rs.randn(600, IN_DIM).astype(np.float32).clip(-1, 1); Ys = np.tanh(Xs[:, :OUT_DIM]).astype(np.float32); Es = np.zeros(600, np.float32)
    ms, _ = train_brain(Xs[:500], Ys[:500], Es[:500], Xs[500:], Ys[500:], Es[500:], epochs=15)
    result["smoke_export"] = export_tflite(ms, f"{WORK}/smoke.tflite")
except Exception as e:
    result["errors"].append(f"smoke: {e}"); traceback.print_exc()
save()

# ---- 1) videos (cumulative: every prepared recording found on the data branch)
api = json.load(urllib.request.urlopen(urllib.request.Request(f"https://api.github.com/repos/alhsryahmd266-jpg/omega-ai/contents/videos?ref=yolo-data", headers={"User-Agent": "brain"})))
vids = sorted([(f["name"], f["download_url"]) for f in api if f["name"].endswith(".mp4")])
vision_pt = f"{WORK}/vision.pt"
urllib.request.urlretrieve(f"{BASE}/releases/download/vision-latest/enemy_vision.pt", vision_pt)
det = YOLO(vision_pt)

def detect_video(path, n_frames):
    dets = [[] for _ in range(n_frames)]
    cap = cv2.VideoCapture(path); idx = 0; batch = []; bidx = []
    def flush():
        if not batch: return
        for i, r in zip(bidx, det.predict(batch, imgsz=1152, conf=0.25, verbose=False, device=0 if CUDA else "cpu")):
            dets[i] = [tuple(b) + (float(c),) for b, c in zip(r.boxes.xywhn.cpu().numpy().tolist(), r.boxes.conf.cpu().numpy().tolist())]
        batch.clear(); bidx.clear()
    while True:
        ok, im = cap.read()
        if not ok or idx >= n_frames: break
        if idx % DET_STEP == 0:
            batch.append(im); bidx.append(idx)
            if len(batch) >= (16 if CUDA else 4): flush()
        idx += 1
    flush(); cap.release()
    for i in range(n_frames):                       # hold the last detection between detector frames
        if i % DET_STEP != 0 and i > 0: dets[i] = dets[i - 1]
    return dets

allX, allY, allE, allV, vaX, vaY, vaE = [], [], [], [], [], [], []
trX, trY, trE = [], [], []
for name, url in vids:
    try:
        p = f"{WORK}/{name}"; urllib.request.urlretrieve(url, p)
        t0 = time.time()
        A = read_actions(p)
        dets = detect_video(p, len(A))
        X, Y, E = build_samples(A, dets)
        cut = int(len(X) * 0.85)
        trX.append(X[:cut]); trY.append(Y[:cut]); trE.append(E[:cut]); vaX.append(X[cut:]); vaY.append(Y[cut:]); vaE.append(E[cut:])
        result["videos"][name] = {"frames": int(len(A)), "valid_action_share": float(A[:, 4].mean()), "samples": int(len(X)),
                                  "enemy_frames": int(E.sum()), "seconds": round(time.time() - t0)}
        os.remove(p)
    except Exception as e:
        result["errors"].append(f"{name}: {e}"); traceback.print_exc()
    save()
Xtr, Ytr, Etr = np.concatenate(trX), np.concatenate(trY), np.concatenate(trE)
Xva, Yva, Eva = np.concatenate(vaX), np.concatenate(vaY), np.concatenate(vaE)
np.savez_compressed(f"{MODELS}/brain_samples.npz", Xtr=Xtr, Ytr=Ytr, Etr=Etr, Xva=Xva, Yva=Yva, Eva=Eva)
result["data"] = {"train": int(len(Xtr)), "val": int(len(Xva)), "train_enemy_share": float(Etr.mean())}
save()

# ---- 2) train: continue from the published brain when there is one
prev_state, prev_hist = None, {"rounds": []}
try:
    urllib.request.urlretrieve(f"{BASE}/releases/download/brain-latest/brain.pt", f"{WORK}/prev_brain.pt")
    ck = torch.load(f"{WORK}/prev_brain.pt", map_location="cpu"); prev_state = ck["state_dict"]
    prev_hist = json.load(urllib.request.urlopen(f"{BASE}/releases/download/brain-latest/brain_history.json"))
except Exception as e:
    print("no previous brain (first round):", str(e)[:100])
rnd = len(prev_hist["rounds"])
best_model, ep = train_brain(Xtr, Ytr, Etr, Xva, Yva, Eva, init_state=prev_state, seed=rnd)
result["epochs_run"] = ep
result["after"] = evaluate(best_model, Xva, Yva, Eva)
if prev_state is not None:
    pm = Brain(); pm.load_state_dict(prev_state)
    result["before"] = evaluate(pm, Xva, Yva, Eva)

# ---- 3) gate + export
a = result["after"]; b = result.get("before")
learned = a["mse"] <= 0.92 * a["baseline_mse_mean"]
not_worse = b is None or a["mse"] <= b["mse"] * 1.03
result["gate"] = {"beats_baseline_by_8pct": bool(learned), "not_worse_than_previous": bool(not_worse)}
result["accepted"] = bool(learned and not_worse)
try:
    tf = f"{MODELS}/brain.tflite" if result["accepted"] else f"{MODELS}/rejected_brain.tflite"
    result["tflite"] = export_tflite(best_model, tf)
    torch.save({"state_dict": best_model.state_dict(), "in": IN_DIM, "out": OUT_DIM}, f"{MODELS}/brain.pt" if result["accepted"] else f"{MODELS}/rejected_brain.pt")
except Exception as e:
    result["errors"].append(f"export: {e}"); result["accepted"] = False; traceback.print_exc()
meta = {"in_dim": IN_DIM, "out_dim": OUT_DIM, "yaw_scale_screen_widths_per_s": YAW_SCALE, "pitch_scale_screen_heights_per_s": PITCH_SCALE,
        "inputs": "3 nearest enemies x [present,dx,dy,w*8,h*4,conf] + [since_seen/10, n_enemies/5] + prev [moveX,moveY,yaw,pitch]",
        "outputs": "[moveX, moveY(forward +), yaw rate (right +), pitch rate (up +)], each in -1..1", "round": rnd,
        "accepted": result["accepted"], "metrics": a}
json.dump(meta, open(f"{MODELS}/brain_meta.json", "w"), indent=2)
prev_hist["rounds"].append({"round": rnd, "accepted": result["accepted"], "train": int(len(Xtr)), "val": int(len(Xva)), "videos": len(vids),
                            "val_mse": a["mse"], "baseline_mse": a["baseline_mse_mean"], "aim_direction_agree": a.get("aim_direction_agree"),
                            "turn_toward_enemy_brain": a.get("turn_toward_enemy_brain"), "turn_toward_enemy_human": a.get("turn_toward_enemy_human")})
json.dump(prev_hist, open(f"{MODELS}/brain_history.json", "w"), indent=2)
save()
print(json.dumps(result, indent=2, default=str))
