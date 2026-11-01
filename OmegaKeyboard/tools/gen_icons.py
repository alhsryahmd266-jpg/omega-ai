#!/usr/bin/env python3
"""Draws the legacy (pre-Android-8) launcher icons: res/mipmap-*/ic_launcher.png.
Android 8+ uses the vector adaptive icon instead (res/mipmap-anydpi-v26)."""
import os
import sys
from PIL import Image, ImageDraw

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
S = 768  # draw big, then downscale for smooth edges


def gradient(size):
    c0, c1, c2 = (26, 115, 232), (91, 75, 224), (155, 63, 214)
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2.0 * (size - 1))
            if t < 0.55:
                k = t / 0.55
                a, b = c0, c1
            else:
                k = (t - 0.55) / 0.45
                a, b = c1, c2
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * k) for i in range(3))
    return img


def star(d, cx, cy, r, fill):
    pts = []
    import math
    for i in range(8):
        ang = math.pi / 4 * i - math.pi / 2
        rad = r if i % 2 == 0 else r * 0.28
        pts.append((cx + rad * math.cos(ang), cy + rad * math.sin(ang)))
    d.polygon(pts, fill=fill)


def draw():
    bg = gradient(S)
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, S - 1, S - 1), radius=int(S * 0.22), fill=255)
    icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    icon.paste(bg, (0, 0), mask)
    d = ImageDraw.Draw(icon)
    # keyboard body
    x0, y0, x1, y1 = int(S * 0.16), int(S * 0.30), int(S * 0.84), int(S * 0.72)
    d.rounded_rectangle((x0, y0, x1, y1), radius=int(S * 0.06), fill=(255, 255, 255, 255))
    key = (91, 75, 224, 255)
    pad = int(S * 0.045)
    kw = int((x1 - x0 - pad * 6) / 5)
    kh = int(S * 0.065)
    for row in range(2):
        ry = y0 + pad + row * (kh + int(S * 0.035))
        for i in range(5):
            kx = x0 + pad + i * (kw + int(pad * 0.5))
            d.rounded_rectangle((kx, ry, kx + kw, ry + kh), radius=int(S * 0.015), fill=key)
    sy = y0 + pad + 2 * (kh + int(S * 0.035))
    d.rounded_rectangle((x0 + pad * 2.2, sy, x1 - pad * 2.2, sy + kh), radius=int(S * 0.015), fill=key)
    star(d, int(S * 0.76), int(S * 0.26), int(S * 0.13), (255, 224, 130, 255))
    return icon


def main():
    big = draw()
    for name, px in SIZES.items():
        folder = os.path.join(OUT, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        img = big.resize((px, px), Image.LANCZOS)
        img.save(os.path.join(folder, "ic_launcher.png"))
        img.save(os.path.join(folder, "ic_launcher_round.png"))
    print("icons written")


if __name__ == "__main__":
    main()
