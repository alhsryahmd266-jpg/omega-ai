#!/usr/bin/env python3
"""Convert per-frame JSON exported by YOUR game (screen-space boxes) to YOLO label files.

JSON per frame:
  {"frame": "f_123.jpg", "width": 854, "height": 480,
   "entities": [{"cls": "enemy", "x1": 10, "y1": 20, "x2": 60, "y2": 150}]}
"""
import argparse
import json
import pathlib

CLASSES = {"enemy": 0, "enemy_head": 1}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", required=True)
    ap.add_argument("--json", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    n = 0
    for jf in sorted(pathlib.Path(a.json).glob("*.json")):
        d = json.loads(jf.read_text())
        w, h = float(d["width"]), float(d["height"])
        lines = []
        for e in d.get("entities", []):
            if e["cls"] not in CLASSES:
                continue
            x1, y1, x2, y2 = (max(0.0, e["x1"]), max(0.0, e["y1"]), min(w, e["x2"]), min(h, e["y2"]))
            if x2 <= x1 or y2 <= y1:
                continue
            lines.append(f'{CLASSES[e["cls"]]} {(x1 + x2) / 2 / w:.6f} {(y1 + y2) / 2 / h:.6f} {(x2 - x1) / w:.6f} {(y2 - y1) / h:.6f}')
        stem = pathlib.Path(d["frame"]).stem
        (out / f"{stem}.txt").write_text("\n".join(lines))
        n += 1
    print(f"wrote {n} label files to {out}")


if __name__ == "__main__":
    main()
