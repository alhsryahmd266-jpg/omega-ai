"""Clear shallow water seen from above: a sky-blue to turquoise gradient with soft, moving light patterns (caustics) and a faint shimmer.
No sun, no horizon: it is the see-through "water" look for glass keys."""
import numpy as np
import cv2
from tk import *
from grads import hexc, finish


def ridged(n, power=2.4):
    """thin bright lines where a smooth noise crosses its middle value: the network of light that sunlight draws on a sandy bottom"""
    return (1.0 - np.abs(n * 2.0 - 1.0)) ** power


def aqua():
    h, w = H, W
    xx, yy = grid(h, w)
    u = xx / w
    v = yy / h
    # ---- the water itself: bright sky-cyan at the top left, turquoise in the middle, deeper azure at the bottom right
    t = np.clip(0.62 * v + 0.38 * u, 0, 1)
    base = ramp(t, [(0.0, hexc('#62D8EC')), (0.30, hexc('#33C2E0')), (0.62, hexc('#1E98DA')), (1.0, hexc('#1262BC'))])
    # slow colour drift so the gradient does not look like a plain fill
    drift = fbm(h, w, base=2, octaves=4, pers=0.55, seed=301)
    base = base * (0.92 + 0.16 * drift)[..., None]
    green = smoothstep(0.55, 0.9, fbm(h, w, base=2, octaves=3, pers=0.5, seed=302))
    base = base * (1 - 0.18 * green[..., None]) + np.array([0.20, 0.82, 0.74], np.float32) * (0.18 * green)[..., None]

    # ---- caustics: two ridged layers warped by a third noise, at two sizes
    wx = (fbm(h, w, base=4, octaves=4, pers=0.5, seed=311) - 0.5) * 90.0
    wy = (fbm(h, w, base=4, octaves=4, pers=0.5, seed=312) - 0.5) * 90.0
    big = fbm(h, w, base=7, octaves=4, pers=0.5, seed=313)
    small = fbm(h, w, base=15, octaves=3, pers=0.5, seed=314)
    c1 = ridged(remap(big, xx + wx, yy + wy), 5.2)
    c2 = ridged(remap(small, xx - wy * 0.7, yy + wx * 0.7), 5.6)
    caustic = np.clip(c1 * 0.80 + c2 * 0.60, 0, 1)
    caustic = blur(caustic, 0.7) * 0.75 + blur(caustic, 5.0) * 0.55
    # the light is strongest where the water is shallow (upper left) and fades in the deep
    shallow = np.clip(1.15 - t * 1.05, 0.25, 1.0)
    light = np.array([0.80, 1.0, 1.0], np.float32)
    img = base + (caustic * shallow * 0.50)[..., None] * light

    # ---- surface shimmer: very faint broad ripples that catch the light
    rip = fbm(h, w, base=9, octaves=4, pers=0.5, seed=321, ax=1.0, ay=0.7)
    dy = sobel_y(blur(rip, 2.0)) * 60.0
    img += (np.clip(dy, -1, 1) * 0.05)[..., None]

    # ---- a soft pool of light at the top and a gentle darker edge at the bottom
    img += (gauss2(xx, yy, 0.22 * w, 0.0, 0.55 * w, 0.40 * h) * 0.05)[..., None] * np.array([0.9, 1.0, 1.0], np.float32)
    img *= (1.0 - 0.14 * smoothstep(0.55, 1.0, v))[..., None]
    img = bloom(img, 0.88, 26, 0.18)
    return finish(img, 0.014, 0.16, 11)
