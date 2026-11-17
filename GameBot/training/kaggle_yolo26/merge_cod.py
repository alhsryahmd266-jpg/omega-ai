# Merge COD MW Warzone (classes 0,1,enemy,head) into a single-class 'enemy' set + the mobile set.
import os, glob, shutil, yaml, sys
WZ, MB, OUT = "cod", "mob_ds", "merged"
names = yaml.safe_load(open(f"{WZ}/data.yaml"))["names"]
keep = {i for i, n in enumerate(names) if n in ("0", "enemy")}   # body boxes only; heads (1/head) dropped
print("warzone names", names, "keeping idx", keep)
for sp in ("train", "valid", "test"):
    for k in ("images", "labels"): os.makedirs(f"{OUT}/{sp}/{k}", exist_ok=True)
def img_of(lbl):
    b = lbl.replace("/labels/", "/images/").rsplit(".", 1)[0]
    c = glob.glob(b + ".*"); return c[0] if c else None
wz_n = wz_boxes = dropped = 0
for sp in ("train", "valid"):
    for lf in glob.glob(f"{WZ}/{sp}/labels/*.txt"):
        rows = [l.split() for l in open(lf) if l.strip()]
        out = [["0"] + r[1:5] for r in rows if int(float(r[0])) in keep]
        im = img_of(lf)
        if not out or not im: dropped += 1; continue
        base = "wz_" + os.path.basename(lf).rsplit(".", 1)[0]
        shutil.copy(im, f"{OUT}/train/images/{base}{os.path.splitext(im)[1]}")
        open(f"{OUT}/train/labels/{base}.txt", "w").write("\n".join(" ".join(r) for r in out) + "\n")
        wz_n += 1; wz_boxes += len(out)
print("warzone images kept", wz_n, "boxes", wz_boxes, "dropped(no body boxes)", dropped)
mb_list = []
for sp, dst in (("train", "train"), ("valid", "valid"), ("test", "test")):
    for lf in glob.glob(f"{MB}/{sp}/labels/*.txt"):
        im = img_of(lf)
        if not im: continue
        base = "mb_" + os.path.basename(lf).rsplit(".", 1)[0]
        ext = os.path.splitext(im)[1]
        shutil.copy(im, f"{OUT}/{dst}/images/{base}{ext}")
        shutil.copy(lf, f"{OUT}/{dst}/labels/{base}.txt")
        if sp == "train": mb_list.append(f"{base}{ext}")
open(f"{OUT}/mobile_train_list.txt", "w").write("\n".join(mb_list))
open(f"{OUT}/data.yaml", "w").write("train: train/images\nval: valid/images\ntest: test/images\nnc: 1\nnames: ['enemy']\n")
open(f"{OUT}/README.txt", "w").write("Merged: COD MW Warzone (fortnite-ai-aim/cod-mw-warzone-catlb v2, CC BY 4.0; body boxes only) + mobile COD set (josuedev/cod-mobile-lqfcx-vwxdk v1, CC BY 4.0). valid/test = mobile only.\n")
for sp in ("train", "valid", "test"):
    print(sp, len(os.listdir(f"{OUT}/{sp}/images")), "images")
print("mobile train images", len(mb_list))
