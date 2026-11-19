#!/usr/bin/env python3
"""Draws a busy, photo-like test picture as a PNG with the standard library only: a dark one (a dusk sky over water with sparkles and
grain) or, with "bright", a pale one (a cream sky over mint water) so the other end of the range is tried too.
preview.sh keeps them as the user's own pictures so the "my picture" looks can be seen with real pictures behind the keys.
usage: mkphoto.py out.png [bright]"""
import math
import random
import struct
import sys
import zlib

W, H = 720, 1280


def mix(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def main(path, bright=False):
    rnd = random.Random(7)
    sky = [(0.0, (22, 18, 74)), (0.35, (120, 40, 120)), (0.55, (240, 110, 70)), (0.62, (255, 200, 110))]
    if bright:
        sky = [(0.0, (250, 244, 210)), (0.35, (236, 238, 200)), (0.55, (214, 236, 214)), (0.62, (190, 232, 220))]
    horizon = int(H * 0.62)
    sunx, suny, sunr = W * 0.62, horizon - 40, 90
    img = [[(0, 0, 0)] * W for _ in range(H)]
    for y in range(H):
        for x in range(W):
            if y < horizon:
                t = y / horizon
                c = sky[0][1]
                for i in range(1, len(sky)):
                    if t <= sky[i][0]:
                        k = (t - sky[i - 1][0]) / (sky[i][0] - sky[i - 1][0])
                        c = mix(sky[i - 1][1], sky[i][1], k)
                        break
                else:
                    c = sky[-1][1]
                d = math.hypot(x - sunx, y - suny)
                if d < sunr:
                    c = mix(c, (255, 244, 200), 1.0 - d / sunr * 0.25)
                else:
                    c = mix(c, (255, 200, 120), max(0.0, 0.55 - (d - sunr) / 420.0))
            else:
                t = (y - horizon) / (H - horizon)
                c = mix((60, 40, 110), (8, 10, 40), t) if not bright else mix((150, 214, 200), (96, 176, 170), t)
                ripple = math.sin(y * 0.55 + math.sin(x * 0.03) * 3.0)
                glit = max(0.0, 1.0 - abs(x - sunx) / (40 + 220 * t)) * (0.35 + 0.35 * ripple) * (1.0 - t * 0.6)
                c = mix(c, (255, 190, 110), max(0.0, min(1.0, glit)))
            n = rnd.randint(-10, 10)
            img[y][x] = (c[0] + n, c[1] + n, c[2] + n)
    # sparkles and a few palm-like dark strokes: high-frequency detail that a keyboard must stay legible over
    for _ in range(260):
        cx, cy, r = rnd.randrange(W), rnd.randrange(H), rnd.randint(1, 4)
        col = rnd.choice(((255, 255, 255), (255, 220, 140), (140, 200, 255))) if not bright else rnd.choice(((255, 255, 255), (250, 250, 235), (120, 180, 170)))
        for y in range(max(0, cy - r), min(H, cy + r + 1)):
            for x in range(max(0, cx - r), min(W, cx + r + 1)):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    img[y][x] = col
    for _ in range(14):
        x0, y0 = rnd.randrange(W), rnd.randrange(int(H * 0.5), H)
        ang = rnd.uniform(-1.2, -0.3)
        for s in range(0, 160):
            x, y = int(x0 + math.cos(ang) * s), int(y0 + math.sin(ang) * s + s * s * 0.004)
            for dx in (0, 1, 2):
                if 0 <= x + dx < W and 0 <= y < H:
                    img[y][x + dx] = (14, 12, 30) if not bright else (60, 110, 96)
    raw = bytearray()
    for row in img:
        raw.append(0)
        for r, g, b in row:
            raw += bytes((max(0, min(255, int(r))), max(0, min(255, int(g))), max(0, min(255, int(b)))))

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


if __name__ == "__main__":
    main(sys.argv[1], len(sys.argv) > 2 and sys.argv[2] == "bright")
