import json, glob, shutil, traceback, os
from ultralytics import YOLO
import ultralytics
os.makedirs("out", exist_ok=True)
rep = {"ultralytics": ultralytics.__version__}
m = YOLO("weights.pt")
ck = m.ckpt or {}
ta = ck.get("train_args", {}) or {}
imgsz = ta.get("imgsz", 960)
rep.update(names=m.names, task=m.task, imgsz=imgsz, epoch=ck.get("epoch"),
           best_fitness=str(ck.get("best_fitness")), date=ck.get("date"),
           train_data=str(ta.get("data")), epochs=ta.get("epochs"))
print(rep, flush=True)
json.dump(rep, open("out/convert_report.json", "w"), indent=2, default=str)
shutil.copy("weights.pt", "out/enemy_yolo26s_best.pt")
for split in ("val", "test"):
    try:
        r = m.val(data="ds/abs.yaml", imgsz=imgsz, split=split, device="cpu")
        rep[split] = dict(map50=float(r.box.map50), map50_95=float(r.box.map),
                          precision=float(r.box.mp), recall=float(r.box.mr))
    except Exception as e:
        rep[split + "_error"] = str(e)[:300]; traceback.print_exc()
    json.dump(rep, open("out/convert_report.json", "w"), indent=2, default=str)
for fmt, kw in (("onnx", {}), ("tflite", {"half": True})):
    try:
        YOLO("weights.pt").export(format=fmt, imgsz=imgsz, **kw)
        cands = [c for c in glob.glob(f"**/*.{fmt}", recursive=True) if not c.startswith("out/") and "int8" not in c]
        if fmt == "tflite":
            cands = [c for c in cands if "float16" in c] or cands
        if cands:
            dst = f"out/enemy_yolo26s_{imgsz}.{fmt}"
            shutil.copy(cands[0], dst); rep[fmt] = dst
        else:
            rep[fmt + "_error"] = "no file produced"
    except Exception as e:
        rep[fmt + "_error"] = str(e)[:400]; traceback.print_exc()
    json.dump(rep, open("out/convert_report.json", "w"), indent=2, default=str)
print(json.dumps(rep, indent=2, default=str))
