import math
import random
import numpy as np
import cv2
from PIL import Image, ImageDraw
from tk import *
from grads import hexc, mesh, finish, sheen


def stalk_layer(w, h, clumps, seed=4, S=2):
    """dry reed stalks with feathery tops: clumps = [(x, y_base, height, spread, n_stalks)] -> RGBA float image"""
    im = Image.new("RGBA", (w * S, h * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    rnd = random.Random(seed)
    for (cx, cy, hgt, spread, n) in clumps:
        for k in range(n):
            bx = (cx + rnd.uniform(-1, 1) * spread * 0.35) * S
            by = (cy + rnd.uniform(-0.01, 0.02) * h) * S
            L = hgt * rnd.uniform(0.55, 1.0) * S
            lean = rnd.uniform(-1, 1) * spread * S * 1.0
            tx, ty = bx + lean, by - L
            cxp, cyp = bx + lean * 0.15, by - L * 0.6
            tone = rnd.uniform(0.0, 1.0)
            col = (int(86 + 70 * tone), int(70 + 62 * tone), int(46 + 46 * tone), 255)
            pts = [bez((bx, by), (cxp, cyp), (tx, ty), i / 36.0) for i in range(37)]
            for i in range(36):
                t = i / 36.0
                d.line([pts[i], pts[i + 1]], fill=col, width=max(1, int((5.2 - 3.4 * t) * S * 0.62)))
            # feathery top: a fan of short strokes
            for j in range(rnd.randint(12, 20)):
                a = math.radians(-90 + rnd.uniform(-38, 38) + (lean / (L + 1)) * 30)
                ll = L * rnd.uniform(0.12, 0.26)
                ex, ey = tx + math.cos(a) * ll, ty + math.sin(a) * ll
                c2 = (int(110 + 90 * tone), int(92 + 80 * tone), int(60 + 70 * tone), 235)
                d.line([(tx, ty + ll * 0.2), (ex, ey)], fill=c2, width=max(1, int(2.2 * S * 0.7)))
        # low shrubs at the base
        for k in range(n * 2):
            bx = (cx + rnd.uniform(-1, 1) * spread * 0.6) * S
            by = (cy + rnd.uniform(0.0, 0.02) * h) * S
            a = math.radians(-90 + rnd.uniform(-70, 70))
            ll = hgt * rnd.uniform(0.10, 0.22) * S
            d.line([(bx, by), (bx + math.cos(a) * ll, by + math.sin(a) * ll)], fill=(60, 50, 34, 255), width=max(1, int(1.6 * S * 0.8)))
    im = im.resize((w, h), Image.LANCZOS)
    return np.asarray(im, np.float32) / 255.0


def beach():
    h, w = H, W
    xx, yy = grid(h, w)
    hor = 0.41 * h
    shore = 0.70 * h + 0.010 * h * np.sin(xx / w * 2 * np.pi * 1.4 + 0.6) + 0.006 * h * np.sin(xx / w * 2 * np.pi * 3.3 + 1.0)
    # ---- sky: rose at the top, lighter and warmer at the horizon, long soft streaks of cloud
    t = np.clip(yy / hor, 0, 1)
    sky = ramp(t, [(0.0, hexc('#CC5C86')), (0.35, hexc('#DC6F85')), (0.70, hexc('#E68E9A')), (1.0, hexc('#EFB0AE'))])
    n1 = fbm(h, w, base=8, octaves=6, pers=0.58, seed=31, ax=0.22, ay=1.0)
    n2 = fbm(h, w, base=14, octaves=5, pers=0.5, seed=32, ax=0.18, ay=1.0)
    streak = smoothstep(0.50, 0.80, n1 * 0.65 + n2 * 0.45 - 0.08)
    sky = sky * (1 - 0.30 * streak[..., None]) + np.array([1.0, 0.82, 0.82], np.float32) * (0.30 * streak)[..., None]
    veil = smoothstep(0.5, 0.85, n2) * (1 - t) * 0.16
    sky = sky * (1 - veil[..., None]) + np.array([0.78, 0.52, 0.80], np.float32) * veil[..., None]
    sky += (np.exp(-((yy - hor) / (0.10 * h)) ** 2) * 0.10)[..., None] * np.array([1.0, 0.85, 0.75], np.float32)
    # ---- sea: pale at the horizon, saturated blue in the middle, ripples of light
    u = np.clip((yy - hor) / (shore - hor + 1e-3), 0, 1)
    sea = ramp(u, [(0.0, hexc('#8DB8D6')), (0.18, hexc('#4D9BC8')), (0.55, hexc('#2F86B8')), (1.0, hexc('#4C9BC6'))])
    T = fbm(2048, 2048, base=34, octaves=5, pers=0.55, seed=41, ax=0.16, ay=1.0)
    depth = 1.0 / (u * 1.2 + 0.16)
    mapx = xx + 300.0 + 40.0 * np.sin(yy * 0.02)
    mapy = ((depth - 1.0 / 1.36) * 240.0 + 17) % 2048
    R = cv2.remap(T, mapx.astype(np.float32), mapy.astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)
    sea *= (0.90 + 0.22 * R)[..., None]
    gl = smoothstep(0.62, 0.86, R) * (0.25 + 0.5 * (1 - u))
    sea += (gl * 0.22)[..., None] * np.array([0.85, 0.95, 1.0], np.float32)
    sea = sea * (1 - np.exp(-((u) / 0.05) ** 2)[..., None] * 0.2)
    # ---- sand: pale, a little blue; wet near the water
    sd = np.clip((yy - shore) / (h - shore.min()), 0, 1)
    sand = ramp(sd, [(0.0, hexc('#9FB2C6')), (0.12, hexc('#BCC6D3')), (0.5, hexc('#C9D0DA')), (1.0, hexc('#B7C0CE'))])
    sr = fbm(h, w, base=26, octaves=4, pers=0.55, seed=51, ax=0.5, ay=1.0)
    sand *= (0.94 + 0.12 * sr)[..., None]
    wet = np.exp(-np.clip(yy - shore, 0, None) / (0.05 * h))
    sand = sand * (1 - 0.18 * wet[..., None]) + sea[..., :] * (0.12 * wet)[..., None]
    sand = sand * (1 - 0.16 * wet[..., None]) + np.array([0.90, 0.72, 0.76], np.float32) * (0.16 * wet)[..., None]
    dist = yy - shore
    img = np.where((yy < hor)[..., None], sky, np.where((dist < 0)[..., None], sea, sand))
    sw = smoothstep(-3.0, 3.0, dist)[..., None]
    img = np.where((yy >= hor)[..., None], sea * (1 - sw) + sand * sw, img)
    # soft horizon: sky melts into the sea
    hz = np.exp(-((yy - hor) / (0.012 * h)) ** 2)
    img = img * (1 - 0.6 * hz[..., None]) + blur(img, 3.0) * (0.6 * hz[..., None])
    # ---- foam along the shore, and a few small waves in the sea
    lace = fbm(h, w, base=30, octaves=4, pers=0.6, seed=61, ax=1.5, ay=1.0)
    foam = (np.exp(-(np.clip(-dist, 0, None) / 14.0) ** 2) * (dist < 4) * smoothstep(0.42, 0.78, lace) * 0.8 + np.exp(-((dist - 3) / 4.5) ** 2) * 0.55)
    foam = np.clip(foam * (0.5 + 0.5 * lace), 0, 1)
    img += foam[..., None] * np.array([0.95, 0.97, 1.0], np.float32) * 0.45
    for (yf, amp, ph) in [(0.50, 0.5, 1.0), (0.58, 0.7, 3.0), (0.64, 0.9, 5.0)]:
        wl = hor + (shore - hor) * ((yf - 0.41) / 0.29) + 0.006 * h * np.sin(xx / w * 2 * np.pi * (2.2 + ph * 0.2) + ph)
        line = np.exp(-((yy - wl) / (0.0035 * h * (0.5 + amp))) ** 2) * smoothstep(0.35, 0.8, fbm(h, w, base=20, octaves=3, seed=int(70 + ph), ax=0.8, ay=1.0))
        img += line[..., None] * np.array([0.9, 0.96, 1.0], np.float32) * 0.30 * amp
    # ---- reeds and dry shrubs
    reeds = stalk_layer(w, h, [(0.31 * w, 0.82 * h, 0.30 * h, 0.04 * w, 9), (0.40 * w, 0.84 * h, 0.22 * h, 0.035 * w, 7),
                              (0.83 * w, 0.80 * h, 0.17 * h, 0.04 * w, 6), (0.04 * w, 0.78 * h, 0.10 * h, 0.05 * w, 6),
                              (0.60 * w, 0.76 * h, 0.07 * h, 0.05 * w, 5)], seed=5)
    a = blur(reeds[..., 3], 0.8)[..., None]
    rc = reeds[..., :3] * np.array([0.78, 0.72, 0.66], np.float32)
    # distant clumps are softer and lighter
    img = img * (1 - a) + rc * a
    # ---- finish: a little glow and a soft darkening at the edges
    img = bloom(img, 0.85, 24, 0.20)
    img = finish(img, 0.016, 0.20, 8)
    return img


def waves():
    h, w = H, W
    xx, yy = grid(h, w)
    bend = fbm(h, w, base=2, octaves=3, pers=0.5, seed=83, ax=0.5, ay=1.0)
    wob = np.sin(xx / w * 2 * np.pi * 1.3 + bend * 6.0) * 0.012 * h + np.sin(xx / w * 2 * np.pi * 0.7 + 1.0) * 0.02 * h
    yv = yy + wob + (bend - 0.5) * 90
    T1 = fbm(2048, 2048, base=46, octaves=6, pers=0.6, seed=81, ax=0.14, ay=1.0)
    T2 = fbm(2048, 2048, base=110, octaves=4, pers=0.55, seed=82, ax=0.10, ay=1.0)
    mapx = xx + 300.0 + 30.0 * np.sin(yy * 0.011)
    mapy = (yv * (0.95 + 0.7 * (1 - yy / h))) % 2048
    A = cv2.remap(T1, mapx.astype(np.float32), mapy.astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)
    B = cv2.remap(T2, (mapx * 0.8 + 300).astype(np.float32), (mapy * 1.3).astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_WRAP)
    F = np.clip(A * 0.70 + B * 0.38, 0, 1)
    dy = sobel_y(F) * 22.0
    facing = np.clip(0.50 - dy * 1.5, 0, 1)
    base = ramp(F, [(0.0, hexc('#283033')), (0.35, hexc('#474C4F')), (0.6, hexc('#6C6665')), (1.0, hexc('#8F8689'))])
    hi = hexc('#D2C6D6')
    img = base + (facing ** 2.0)[..., None] * (hi - base) * 0.62
    glow = gauss2(xx, yy, 0.58 * w, 0.30 * h, 0.75 * w, 0.60 * h)
    img = img * (0.60 + 0.58 * glow)[..., None]
    img += (glow * 0.05)[..., None] * np.array([1.0, 0.85, 0.9], np.float32)
    sp = smoothstep(0.82, 0.97, B) * facing
    img += (sp * 0.22)[..., None] * np.array([1.0, 0.95, 1.0], np.float32)
    return finish(img, 0.024, 0.26, 9)


def drops(img, n, seed, region, rmin=3, rmax=13):
    """water drops on a surface: a bright speck, a dark rim on the far side, the picture seen again, upside down, inside"""
    h, w = img.shape[:2]
    rnd = random.Random(seed)
    big = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out = img.copy()
    for _ in range(n):
        x = rnd.uniform(region[0], region[2]) * w
        y = rnd.uniform(region[1], region[3]) * h
        r = rnd.uniform(rmin, rmax) ** 1.0
        r = rmin + (rmax - rmin) * rnd.random() ** 2.2
        x0, x1 = int(max(0, x - r - 2)), int(min(w, x + r + 3))
        y0, y1 = int(max(0, y - r - 2)), int(min(h, y + r + 3))
        if x1 - x0 < 3 or y1 - y0 < 3:
            continue
        gy, gx = np.mgrid[y0:y1, x0:x1].astype(np.float32)
        d = np.sqrt((gx - x) ** 2 + (gy - y) ** 2) / r
        inside = d < 1.0
        m = smoothstep(1.0, 0.92, d)
        # refraction: look at the picture mirrored through the drop
        sx = np.clip(x + (x - gx) * 0.8, 0, w - 1)
        sy = np.clip(y + (y - gy) * 0.8, 0, h - 1)
        refr = out[sy.astype(int), sx.astype(int)]
        lightdir = ((gx - x) * -0.7 + (gy - y) * -0.7) / (r + 1e-3)
        edge = np.clip((d - 0.55) / 0.45, 0, 1)
        dark = edge * np.clip(-lightdir * 0.5 + 0.5, 0, 1) * 0.42
        bright = edge * np.clip(lightdir * 0.5 + 0.5, 0, 1) * 0.22
        spec = np.exp(-(((gx - (x - r * 0.35)) / (r * 0.28)) ** 2 + ((gy - (y - r * 0.38)) / (r * 0.22)) ** 2)) * 0.95
        cur = out[y0:y1, x0:x1]
        newc = refr * (1.0 - dark[..., None]) + bright[..., None] + spec[..., None]
        out[y0:y1, x0:x1] = cur * (1 - m[..., None]) + newc * m[..., None]
    return out


def paint():
    h, w = H, W
    xx, yy = grid(h, w)
    # a warped colour field: soft patches of gouache that run into each other
    wx = fbm(h, w, base=3, octaves=5, pers=0.55, seed=91)
    wy = fbm(h, w, base=3, octaves=5, pers=0.55, seed=92)
    wx2 = fbm(h, w, base=6, octaves=4, pers=0.5, seed=93)
    wy2 = fbm(h, w, base=6, octaves=4, pers=0.5, seed=94)
    pts = [(0.05, 0.10, '#D6B33C', 0.30), (0.30, 0.00, '#E59A2E', 0.28), (0.62, 0.05, '#D9488F', 0.30), (0.95, 0.10, '#8E4FB0', 0.30),
           (0.00, 0.55, '#1F7FB0', 0.30), (0.28, 0.45, '#9B7EB0', 0.30), (0.55, 0.45, '#E5902E', 0.28), (0.85, 0.50, '#E04A3A', 0.30),
           (0.10, 1.00, '#2E9AC0', 0.30), (0.40, 0.95, '#D9B13A', 0.28), (0.72, 1.00, '#DA3E2C', 0.30), (1.00, 0.95, '#A63A5F', 0.28),
           (0.45, 0.20, '#F0DCC8', 0.22), (0.78, 0.30, '#F2A24A', 0.22)]
    num = np.zeros((h, w, 3), np.float32)
    den = np.full((h, w), 1e-4, np.float32)
    px = xx / w + (wx - 0.5) * 0.55 + (wx2 - 0.5) * 0.18
    py = yy / h + (wy - 0.5) * 0.65 + (wy2 - 0.5) * 0.20
    for (cx, cy, col, r) in pts:
        d2 = (((px - cx) * (w / h)) ** 2 + (py - cy) ** 2) / (r * r)
        wgt = np.exp(-d2 * 2.2).astype(np.float32)
        num += wgt[..., None] * hexc(col)
        den += wgt
    img = num / den[..., None]
    # pigment texture and pooled edges
    tex = fbm(h, w, base=18, octaves=5, pers=0.55, seed=95)
    img *= (0.93 + 0.14 * tex)[..., None]
    edge = np.abs(sobel_x(blur(img.mean(axis=2), 3.0))) + np.abs(sobel_y(blur(img.mean(axis=2), 3.0)))
    img *= (1.0 - np.clip(edge * 14, 0, 0.18))[..., None]
    # glossy light over it
    img = sheen(img, 0.0, 0.8, 1.0, 0.2, 0.12, 0.08)
    img += (gauss2(xx, yy, 0.25 * w, 0.0, 0.5 * w, 0.4 * h) * 0.07)[..., None]
    img = drops(img, 150, 3, (0.45, 0.28, 1.0, 1.0), 3, 14)
    img = drops(img, 40, 5, (0.0, 0.0, 0.5, 0.6), 2, 8)
    return finish(img, 0.014, 0.18, 10)


if __name__ == "__main__":
    import sys
    outdir = sys.argv[1] if len(sys.argv) > 1 else "."
    which = sys.argv[2:] or ["beach", "waves", "paint"]
    fns = {"beach": beach, "waves": waves, "paint": paint}
    for n in which:
        save(fns[n](), f"{outdir}/{n}.jpg", 88)
        print(n)
