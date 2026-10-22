#!/usr/bin/env python3
"""Regenerates src/gvrApkScript.ts from scripts/gvr_apk.py (the app ships the helper as a string and
writes it into Alpine on first use). Run from GVRChat/:  python3 scripts/gen_apk_script.py"""
import json, re, sys, pathlib

root = pathlib.Path(__file__).resolve().parent.parent
src = (root / 'scripts' / 'gvr_apk.py').read_text(encoding='utf-8')
if not src.isascii():
    sys.exit('gvr_apk.py must be ASCII only (it is base64-shipped without UTF-8 handling)')
ver = re.search(r'^GVR_APK_VERSION = (\d+)', src, re.M).group(1)
out = (
    '/* GENERATED from scripts/gvr_apk.py by scripts/gen_apk_script.py — do not edit by hand. */\n'
    f'export const GVR_APK_VERSION = {ver};\n'
    f'export const GVR_APK_PY: string = {json.dumps(src)};\n'
)
(root / 'src' / 'gvrApkScript.ts').write_text(out, encoding='utf-8')
print('wrote src/gvrApkScript.ts', len(out), 'bytes, version', ver)
