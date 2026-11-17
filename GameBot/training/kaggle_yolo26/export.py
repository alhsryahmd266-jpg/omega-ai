# Kaggle script: export trained YOLO26 enemy detectors to TFLite (FP32 + FP16 when possible).
import os, glob, json, shutil, subprocess, traceback, urllib.request
OUT = "/kaggle/working"; MODELS = f"{OUT}/models"; os.makedirs(MODELS, exist_ok=True)
BASE = "https://github.com/alhsryahmd266-jpg/omega-ai/releases/download/gamebot-yolo26-v3"
res = {}
def sh(c):
    print("$", c, flush=True); return subprocess.run(c, shell=True).returncode
sh("pip install -q -U ultralytics")
from ultralytics import YOLO
for name in ("yolo26n", "yolo26s"):
    info = {}; res[name] = info
    pt = f"{OUT}/{name}.pt"
    urllib.request.urlretrieve(f"{BASE}/{name}_enemy_best.pt", pt)
    d = f"{MODELS}/{name}"; os.makedirs(d, exist_ok=True)
    try:
        YOLO(pt).export(format="tflite", imgsz=960)
        c = glob.glob(f"{OUT}/**/*.tflite", recursive=True)
        c = [x for x in c if not x.startswith(MODELS) and name in x]
        if c:
            shutil.copy(c[0], f"{d}/{name}_enemy_960_fp32.tflite"); info["litert"] = os.path.basename(c[0])
        else:
            info["litert_error"] = "no file"
    except Exception as e:
        info["litert_error"] = str(e)[:400]; traceback.print_exc()
    if "litert" not in info:
        try:
            YOLO(pt).export(format="onnx", imgsz=960)
            onnx = pt.replace(".pt", ".onnx")
            sh("pip install -q onnx2tf tensorflow tf_keras onnx_graphsurgeon sng4onnx onnxsim ai_edge_litert psutil")
            o2 = f"{OUT}/o2t_{name}"
            rc = sh(f"onnx2tf -i {onnx} -o {o2} -osd")
            for f in glob.glob(f"{o2}/*.tflite"):
                shutil.copy(f, f"{d}/{name}_{os.path.basename(f)}"); info.setdefault("onnx2tf", []).append(os.path.basename(f))
            info["onnx2tf_rc"] = rc
        except Exception as e:
            info["onnx2tf_error"] = str(e)[:400]; traceback.print_exc()
    json.dump(res, open(f"{MODELS}/export_result.json", "w"), indent=2)
    shutil.copy(f"{MODELS}/export_result.json", f"{OUT}/export_result.json")
print(json.dumps(res, indent=2))
