#!/usr/bin/env python3
"""Convert a Unity Perception *SOLO* dataset (2D bounding boxes) into a YOLO dataset for GameBot.

Synthetic data from YOUR OWN game assets (characters, weapons, maps) is the closest match to your game's
graphics, and the boxes come out of the engine automatically - no hand labeling.

Usage:
    python perception_solo_to_yolo.py --src <unity dataset folder> --out dataset_yolo \
        --classes enemy enemy_head ally
Output:  dataset_yolo/images/*.png  dataset_yolo/labels/*.txt   (zip it and upload to Kaggle)
Label names in Unity (IdLabelConfig) must match --classes.
"""
import argparse
import json
import pathlib
import shutil


def _pair(v):
    if isinstance(v, dict):
        return float(v.get("x", 0)), float(v.get("y", 0))
    return float(v[0]), float(v[1])


def convert(src, out, classes):
    src, out = pathlib.Path(src), pathlib.Path(out)
    (out / "images").mkdir(parents=True, exist_ok=True)
    (out / "labels").mkdir(parents=True, exist_ok=True)
    ids = {c: i for i, c in enumerate(classes)}
    n_img = n_box = 0
    unknown = set()
    for fj in sorted(src.glob("sequence.*/step.*.frame.json")):
        data = json.loads(fj.read_text(encoding="utf-8-sig"))
        for cap in data.get("captures", []):
            fname, dim = cap.get("filename"), cap.get("dimension")
            if not fname or not dim:
                continue
            w, h = _pair(dim)
            img = fj.parent / fname
            if not img.exists() or w <= 0 or h <= 0:
                continue
            lines = []
            for ann in cap.get("annotations", []):
                if "BoundingBox2D" not in str(ann.get("@type", "")):
                    continue
                for v in ann.get("values", []):
                    name = v.get("labelName")
                    if name not in ids:
                        unknown.add(name)
                        continue
                    ox, oy = _pair(v["origin"])
                    bw, bh = _pair(v["dimension"])
                    x1, y1 = max(0.0, ox), max(0.0, oy)
                    x2, y2 = min(w, ox + bw), min(h, oy + bh)
                    if x2 <= x1 or y2 <= y1:
                        continue
                    lines.append(f"{ids[name]} {(x1 + x2) / 2 / w:.6f} {(y1 + y2) / 2 / h:.6f} {(x2 - x1) / w:.6f} {(y2 - y1) / h:.6f}")
                    n_box += 1
            stem = f"{fj.parent.name}_{fj.stem.replace('.frame', '')}".replace(".", "_")
            shutil.copy(img, out / "images" / f"{stem}{img.suffix}")
            (out / "labels" / f"{stem}.txt").write_text("\n".join(lines))
            n_img += 1
    print(f"images: {n_img}, boxes: {n_box}, written to {out}")
    if unknown:
        print("labels not in --classes (ignored):", sorted(str(u) for u in unknown))
    return n_img, n_box


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="Perception SOLO dataset folder (contains sequence.N)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--classes", nargs="+", default=["enemy", "enemy_head", "ally"])
    a = ap.parse_args()
    convert(a.src, a.out, a.classes)
