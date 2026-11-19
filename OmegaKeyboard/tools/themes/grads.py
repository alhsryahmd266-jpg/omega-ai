import numpy as np
import cv2
from tk import *


def hexc(s):
    s = s.lstrip('#')
    return np.array([int(s[i:i + 2], 16) / 255.0 for i in (0, 2, 4)], np.float32)


def mesh(points, h=H, w=W, floor=1e-4):
    """smooth colour field: points = [(x, y, '#rrggbb', radius)] with x, y, radius in 0..1 of the width/height"""
    xx, yy = grid(h, w)
    num = np.zeros((h, w, 3), np.float32)
    den = np.zeros((h, w), np.float32) + floor
    for (px, py, col, r) in points:
        d2 = ((xx / w - px) ** 2 * (w / h) ** 2 + (yy / h - py) ** 2) / (r * r)
        wgt = np.exp(-d2 * 1.8).astype(np.float32)
        num += wgt[..., None] * hexc(col)
        den += wgt
    return num / den[..., None]


def finish(img, grain_amt=0.016, vig=0.18, seed=3, bloom_amt=0.0):
    if bloom_amt:
        img = bloom(img, 0.8, 30, bloom_amt)
    img = softclip(img, 0.94)
    img = vignette(img, vig, 2.4)
    img = grain(img, grain_amt, seed)
    return np.clip(img, 0, 1)


def sheen(img, x0=0.0, y0=0.0, x1=1.0, y1=1.0, width=0.22, amount=0.10, color=(1.0, 1.0, 1.0)):
    """a soft light band between two points of the picture (glass look)"""
    h, w = img.shape[:2]
    xx, yy = grid(h, w)
    ax, ay, bx, by = x0 * w, y0 * h, x1 * w, y1 * h
    dx, dy = bx - ax, by - ay
    L = (dx * dx + dy * dy) ** 0.5
    nx, ny = -dy / L, dx / L
    d = ((xx - ax) * nx + (yy - ay) * ny) / (width * w)
    band = np.exp(-d * d)
    return img + band[..., None] * amount * np.array(color, np.float32)


def lagoon():
    img = mesh([(0.00, 0.10, '#62CDA2', 0.55), (0.12, 0.95, '#7FD3B0', 0.50), (0.45, 0.45, '#8DB9BC', 0.45),
                (0.85, 0.15, '#6F7DBE', 0.55), (1.00, 0.85, '#5A63A8', 0.55), (0.55, 1.05, '#7BA6B9', 0.4)])
    n = fbm(H, W, base=2, octaves=4, pers=0.5, seed=21)
    img = img * (0.94 + 0.12 * n)[..., None]
    img = sheen(img, 0.05, 1.0, 0.75, 0.0, 0.16, 0.07)
    img += (gauss2(*grid(), 0.12 * W, 0.0, 0.5 * W, 0.4 * H) * 0.10)[..., None]
    return finish(img, 0.014, 0.16, 4)


def blush():
    img = mesh([(0.02, 0.35, '#CF4F9C', 0.45), (0.30, 0.05, '#C879B4', 0.40), (0.55, 0.60, '#EAA27C', 0.40),
                (0.78, 0.95, '#E8B07E', 0.35), (1.00, 0.30, '#7C76C8', 0.45), (0.15, 1.00, '#B85A9C', 0.40), (0.55, 0.0, '#D98BAA', 0.35)])
    n = fbm(H, W, base=2, octaves=4, pers=0.5, seed=23)
    img = img * (0.95 + 0.10 * n)[..., None]
    img = sheen(img, 0.0, 0.9, 1.0, 0.1, 0.2, 0.08)
    img += (gauss2(*grid(), 0.30 * W, 0.0, 0.45 * W, 0.35 * H) * 0.09)[..., None]
    return finish(img, 0.014, 0.16, 5)


def ruby():
    img = mesh([(0.0, 0.2, '#D8301F', 0.55), (0.25, 0.9, '#C5241E', 0.5), (0.55, 0.5, '#B4222B', 0.45),
                (1.0, 0.1, '#7E2A72', 0.5), (0.95, 0.85, '#8E2A5E', 0.5), (0.0, 1.0, '#A81C1C', 0.4)])
    n = fbm(H, W, base=3, octaves=5, pers=0.5, seed=25)
    img = img * (0.93 + 0.14 * n)[..., None]
    img = sheen(img, 0.0, 0.6, 1.0, 0.2, 0.25, 0.07, (1.0, 0.8, 0.7))
    img += (gauss2(*grid(), 0.15 * W, 0.05 * H, 0.45 * W, 0.35 * H) * 0.10)[..., None] * np.array([1.0, 0.45, 0.35], np.float32)
    return finish(img, 0.014, 0.20, 6)


def navy():
    img = mesh([(0.0, 0.0, '#0B1050', 0.5), (0.2, 0.9, '#070A41', 0.6), (0.8, 0.1, '#1D2A88', 0.45), (1.0, 0.95, '#0A0C46', 0.5), (0.5, 0.5, '#0C1155', 0.5)])
    xx, yy = grid()
    # a blue streak of light across the upper right, like a reflection on glass
    for (off, wd, amt, col) in [(0.0, 0.08, 0.55, (0.17, 0.25, 0.80)), (0.16, 0.04, 0.25, (0.30, 0.40, 0.95))]:
        d = ((xx / W - 0.55) * 0.9 + (yy / H - 0.0) * 1.0 - off - 0.3) / wd
        img += (np.exp(-d * d) * amt)[..., None] * np.array(col, np.float32)
    n = fbm(H, W, base=3, octaves=5, pers=0.5, seed=27)
    img = img * (0.92 + 0.16 * n)[..., None]
    return finish(img, 0.028, 0.22, 7)


if __name__ == "__main__":
    import sys
    outdir = sys.argv[1] if len(sys.argv) > 1 else "."
    for name, fn in [("lagoon", lagoon), ("blush", blush), ("ruby", ruby), ("navy", navy)]:
        save(fn(), f"{outdir}/{name}.jpg", 88)
        print(name)
