"""Small toolkit for rendering photographic-looking backgrounds with numpy/cv2 (no third-party artwork is used anywhere)."""
import numpy as np
import cv2
from PIL import Image

W, H = 1440, 1100


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a + 1e-9), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def fbm(h, w, base=4, octaves=6, pers=0.5, seed=0, ax=1.0, ay=1.0):
    """smooth value noise 0..1: random grids of growing size, cubic-resampled and summed"""
    r = np.random.default_rng(seed)
    out = np.zeros((h, w), np.float32)
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        gw = max(2, int(base * ax * (2 ** o)))
        gh = max(2, int(base * ay * (2 ** o)))
        g = r.random((gh, gw), dtype=np.float32)
        out += amp * cv2.resize(g, (w, h), interpolation=cv2.INTER_CUBIC)
        tot += amp
        amp *= pers
    out /= tot
    return np.clip(out, 0.0, 1.0)


def grid(h=H, w=W):
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    return xx, yy


def remap(img, mx, my):
    return cv2.remap(img.astype(np.float32), mx.astype(np.float32), my.astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT)


def warp(img, fx, fy, k):
    xx, yy = grid(img.shape[0], img.shape[1])
    return remap(img, xx + (fx - 0.5) * k, yy + (fy - 0.5) * k)


def ramp(t, stops):
    t = np.clip(t, 0.0, 1.0)
    pos = np.array([s[0] for s in stops], np.float32)
    cols = np.array([s[1] for s in stops], np.float32)
    out = np.empty(t.shape + (3,), np.float32)
    for c in range(3):
        out[..., c] = np.interp(t, pos, cols[:, c])
    return out


def blur(img, s):
    if s <= 0:
        return img
    return cv2.GaussianBlur(img, (0, 0), s)


def bloom(img, thresh=0.75, sigma=28, amount=0.55, passes=3):
    bright = np.clip(img - thresh, 0, None)
    out = img.copy()
    for i in range(passes):
        out += amount / passes * blur(bright, sigma * (1 + i * 1.6))
    return out


def vignette(img, strength=0.35, power=2.2):
    h, w = img.shape[:2]
    xx, yy = grid(h, w)
    d = np.sqrt(((xx - w / 2) / (w / 2)) ** 2 + ((yy - h / 2) / (h / 2)) ** 2) / 1.414
    v = 1.0 - strength * np.clip(d, 0, 1) ** power
    return img * v[..., None]


def grain(img, amount=0.022, seed=1):
    r = np.random.default_rng(seed)
    n = r.normal(0, 1, img.shape[:2]).astype(np.float32)
    n = cv2.GaussianBlur(n, (0, 0), 0.7)
    lum = img.mean(axis=2)
    k = amount * (0.6 + 0.8 * np.sqrt(np.clip(lum, 0, 1)))
    return img + (n * k)[..., None]


def tonemap(img, exposure=1.0):
    x = np.clip(img * exposure, 0, None)
    a, b, c, d, e = 2.51, 0.03, 2.43, 0.59, 0.14
    y = (x * (a * x + b)) / (x * (c * x + d) + e)
    return np.clip(y, 0, 1)


def save(img, path, q=88):
    im = Image.fromarray((np.clip(img, 0, 1) * 255.0 + 0.5).astype(np.uint8))
    im.save(path, "JPEG", quality=q, optimize=True, progressive=True, subsampling=0)


def mix(a, b, t):
    t = t[..., None] if (isinstance(t, np.ndarray) and t.ndim == 2) else t
    return a * (1 - t) + b * t


def gauss2(xx, yy, cx, cy, sx, sy=None):
    sy = sx if sy is None else sy
    return np.exp(-(((xx - cx) / sx) ** 2 + ((yy - cy) / sy) ** 2))


def bez(p0, p1, p2, t):
    return ((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0], (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1])


def softclip(img, knee=0.82):
    x = np.clip(img, 0, None)
    over = np.clip(x - knee, 0, None)
    return np.where(x < knee, x, knee + (1 - knee) * (1 - np.exp(-over / (1 - knee))))


def sobel_y(a):
    return cv2.Sobel(a, cv2.CV_32F, 0, 1, ksize=3) / 8.0


def sobel_x(a):
    return cv2.Sobel(a, cv2.CV_32F, 1, 0, ksize=3) / 8.0
