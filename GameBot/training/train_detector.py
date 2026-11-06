#!/usr/bin/env python3
"""Train a small YOLO detector for GameBot and export it to TFLite.

Usage:
    pip install ultralytics
    python train_detector.py --data data.yaml --imgsz 416 --epochs 100
"""
import argparse

from ultralytics import YOLO


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True, help="path to data.yaml")
    ap.add_argument("--model", default="yolo11n.pt", help="starting weights")
    ap.add_argument("--imgsz", type=int, default=416)
    ap.add_argument("--epochs", type=int, default=100)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--int8", action="store_true", help="also export an INT8 model")
    a = ap.parse_args()

    model = YOLO(a.model)
    model.train(data=a.data, imgsz=a.imgsz, epochs=a.epochs, batch=a.batch)
    best = YOLO(str(model.trainer.best))
    print("TFLite (float):", best.export(format="tflite", imgsz=a.imgsz))
    if a.int8:
        print("TFLite (int8):", best.export(format="tflite", imgsz=a.imgsz, int8=True, data=a.data))


if __name__ == "__main__":
    main()
