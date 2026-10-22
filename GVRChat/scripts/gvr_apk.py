#!/usr/bin/env python3
"""gvr-apk: unpack / repack / align / sign Android APKs on the phone (Alpine Linux, Python 3 stdlib only).

  gvr-apk setup                       install Java + the signer + the decoder/builder (once)
  gvr-apk info    <apk>               what is inside
  gvr-apk unpack  <apk> [outdir]      plain unzip (default /workspace/apk_work/<name>); edit assets / libs / dex / json freely
  gvr-apk pack    <dir> <out.apk>     zip it back (aligned like zipalign), drop old signatures, sign with the debug key
  gvr-apk sign    <apk> [out.apk]     align + sign an APK you changed yourself
  gvr-apk decode  <apk> [outdir]      full decode: smali + readable manifest/resources (APKEditor, pure Java)
  gvr-apk build   <dir> <out.apk>     rebuild a decoded folder + align + sign (APKEditor, no native aapt2 needed)
"""
GVR_APK_VERSION = 4

import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

TOOLS = os.environ.get('GVR_TOOLS', '/opt/gvr')
SIGNER_URL = 'https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar'
# APKEditor decodes/rebuilds AndroidManifest.xml and resources.arsc in pure Java: no native aapt2 binary needed,
# so it works on every CPU this script can run on (apktool's `build` needs a native aapt2 that is not shipped
# for ARM64 Android and, even installed from Alpine, is frequently broken for this use). Pinned to the newest
# release (checked when this file was last updated).
APKEDITOR_URL = 'https://github.com/REAndroid/APKEditor/releases/download/V1.4.9/APKEditor-1.4.9.jar'
META = '.gvr_apk_meta.json'
STORED_EXT = ('.so', '.ogg', '.mp3', '.mp4', '.webm', '.jpg', '.jpeg', '.png', '.webp', '.gif', '.zip')
SIG_FILES = re.compile(r'^META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))$', re.I)
ALIGN_DATE = (2008, 1, 1, 0, 0, 0)


def die(msg):
    print('ERROR: ' + msg)
    sys.exit(1)


def human(n):
    for unit in ('B', 'KB', 'MB', 'GB'):
        if n < 1024 or unit == 'GB':
            return ('%d %s' % (n, unit)) if unit == 'B' else ('%.1f %s' % (n, unit))
        n /= 1024.0


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def run(cmd, timeout=900):
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    return p.returncode, p.stdout.decode('utf-8', 'replace')


def need_apk(path):
    if not os.path.isfile(path):
        die('file not found: ' + path)
    if not zipfile.is_zipfile(path):
        die('not a valid APK/zip: ' + path)


# -- tools: Java, signer, decoder/builder -----------------------------------
def ensure_java():
    if shutil.which('java'):
        return
    if not shutil.which('apk'):
        die('Java is missing and this is not Alpine Linux (no apk). Install a JRE first.')
    print('[setup] installing Java (openjdk17-jre-headless)...')
    rc, out = run(['apk', 'add', '--no-cache', 'openjdk17-jre-headless', 'ca-certificates'], 900)
    if rc != 0 or not shutil.which('java'):
        die('could not install Java:\n' + out[-600:])


def download(url, dest):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + '.part'
    last = None
    for _ in range(2):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'gvr-apk/%d' % GVR_APK_VERSION})
            with urllib.request.urlopen(req, timeout=120) as r, open(tmp, 'wb') as f:
                shutil.copyfileobj(r, f)
            os.replace(tmp, dest)
            return
        except Exception as e:  # noqa: BLE001
            last = e
    for tool in (['curl', '-fsSL', '-o', tmp, url], ['wget', '-q', '-O', tmp, url]):
        if shutil.which(tool[0]):
            rc, _ = run(tool, 300)
            if rc == 0 and os.path.getsize(tmp) > 0:
                os.replace(tmp, dest)
                return
    die('download failed (%s): %s' % (last, url))


def ensure_jar(name, url):
    jar = os.path.join(TOOLS, name)
    if not (os.path.isfile(jar) and os.path.getsize(jar) > 100000):
        print('[setup] downloading %s ...' % name)
        download(url, jar)
    return jar


# -- zip writing with zipalign-compatible alignment ------------------------
def write_entry(zf, name, data, compress, mode=0o644):
    zi = zipfile.ZipInfo(name, date_time=ALIGN_DATE)
    zi.compress_type = compress
    zi.external_attr = (mode & 0xFFFF) << 16
    if compress == zipfile.ZIP_STORED:
        align = 4096 if name.endswith('.so') else 4
        off = zf.fp.tell()
        hdr = 30 + len(name.encode('utf-8'))
        pad = (-(off + hdr)) % align
        if pad:
            if pad < 4:
                pad += align
            zi.extra = struct.pack('<HH', 0xD935, pad - 4) + b'\x00' * (pad - 4)
    zf.writestr(zi, data)


def signing_state(path):
    """'v2+' (APK Signing Block present), 'v1' (META-INF signature files) or 'none'."""
    try:
        with zipfile.ZipFile(path) as zf, open(path, 'rb') as f:
            names = zf.namelist()
            if zf.start_dir >= 16:
                f.seek(zf.start_dir - 16)
                if f.read(16) == b'APK Sig Block 42':
                    return 'v2+'
            return 'v1' if any(SIG_FILES.match(n) and not n.upper().endswith('MANIFEST.MF') for n in names) else 'none'
    except Exception:  # noqa: BLE001
        return 'none'


def check_alignment(path):
    """Returns the names of STORED entries whose data does not start on its alignment boundary."""
    bad = []
    with zipfile.ZipFile(path) as zf, open(path, 'rb') as f:
        for zi in zf.infolist():
            if zi.compress_type != zipfile.ZIP_STORED or zi.is_dir():
                continue
            f.seek(zi.header_offset)
            h = f.read(30)
            n_len, e_len = struct.unpack('<HH', h[26:30])
            start = zi.header_offset + 30 + n_len + e_len
            align = 4096 if zi.filename.endswith('.so') else 4
            if start % align:
                bad.append(zi.filename)
    return bad


def choose_compression(name, original):
    if original is not None:
        return original
    if name == 'resources.arsc' or name.lower().endswith(STORED_EXT):
        return zipfile.ZIP_STORED
    return zipfile.ZIP_DEFLATED


def realign(src, dst):
    """Copy an APK, dropping old signatures, with every stored entry aligned."""
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w') as zout:
        for zi in zin.infolist():
            if zi.is_dir() or SIG_FILES.match(zi.filename):
                continue
            comp = zipfile.ZIP_STORED if zi.filename == 'resources.arsc' else zi.compress_type
            if comp not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                comp = zipfile.ZIP_DEFLATED
            write_entry(zout, zi.filename, zin.read(zi.filename), comp)


# -- signing ---------------------------------------------------------------
def sign_apk(src, dst):
    ensure_java()
    jar = ensure_jar('uber-apk-signer.jar', SIGNER_URL)
    work = tempfile.mkdtemp(prefix='gvr-sign-')
    try:
        aligned = os.path.join(work, 'in.apk')
        realign(src, aligned)
        outdir = os.path.join(work, 'out')
        rc, out = run(['java', '-jar', jar, '-a', aligned, '-o', outdir, '--skipZipAlign', '--allowResign'], 900)
        made = [os.path.join(outdir, n) for n in os.listdir(outdir)] if os.path.isdir(outdir) else []
        made = [m for m in made if m.endswith('.apk')]
        if rc != 0 or not made:
            die('signing failed:\n' + out[-900:])
        os.makedirs(os.path.dirname(os.path.abspath(dst)) or '.', exist_ok=True)
        shutil.move(made[0], dst)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def report(path, signed):
    bad = check_alignment(path)
    print('Result: %s' % path)
    print('Size:   %s   sha256: %s' % (human(os.path.getsize(path)), sha256(path)[:16]))
    print('Zip:    %s' % ('aligned OK' if not bad else 'NOT aligned: ' + ', '.join(bad[:5])))
    if signed:
        print('Signed: debug key (%s).' % ('v1+v2+v3 verified in the file' if signing_state(path) == 'v2+' else 'WARNING: no v2 block found'))
        print('Note:   a different signature than the original app, so Android will not install it OVER the original:')
        print('        uninstall the original first (its data is lost), or change the package name.')
    else:
        print('Signed: NO (run: gvr-apk sign <apk>) - Android refuses to install an unsigned APK.')


# -- commands --------------------------------------------------------------
def cmd_setup(_a):
    ensure_java()
    ensure_jar('uber-apk-signer.jar', SIGNER_URL)
    ensure_jar('apkeditor.jar', APKEDITOR_URL)
    rc, out = run(['java', '-version'], 30)
    print('Ready. ' + out.strip().splitlines()[0] if out.strip() else 'Ready.')


def cmd_info(a):
    if not a:
        die('usage: gvr-apk info <apk>')
    need_apk(a[0])
    with zipfile.ZipFile(a[0]) as zf:
        infos = [i for i in zf.infolist() if not i.is_dir()]
        names = [i.filename for i in infos]
        dex = sorted(n for n in names if re.fullmatch(r'classes\d*\.dex', n))
        abis = sorted({n.split('/')[1] for n in names if n.startswith('lib/') and n.count('/') >= 2})
        sigs = [n for n in names if SIG_FILES.match(n)]
        tops = {}
        for n in names:
            top = n.split('/')[0] if '/' in n else '(root)'
            tops[top] = tops.get(top, 0) + 1
        total = sum(i.file_size for i in infos)
        print('APK: %s  (%s on disk, %s unpacked, %d files)' % (a[0], human(os.path.getsize(a[0])), human(total), len(infos)))
        print('dex: %s' % (', '.join(dex) or 'none'))
        print('native ABIs: %s' % (', '.join(abis) or 'none'))
        print('resources.arsc: %s   AndroidManifest.xml: %s' % ('yes' if 'resources.arsc' in names else 'NO', 'yes' if 'AndroidManifest.xml' in names else 'NO'))
        st = signing_state(a[0])
        print('signature: %s' % ({'v2+': 'signed (APK Signing Block, v2/v3)', 'v1': 'signed (v1 jar signature: ' + ', '.join(sigs) + ')', 'none': 'NOT signed'}[st]))
        print('top-level: ' + ', '.join('%s (%d)' % (k, v) for k, v in sorted(tops.items(), key=lambda kv: -kv[1])[:10]))
        big = sorted(infos, key=lambda i: -i.file_size)[:5]
        print('largest: ' + ', '.join('%s (%s)' % (i.filename, human(i.file_size)) for i in big))
        print('Tip: for the package name / permissions / components use the inspect_file tool on the APK.')


def cmd_unpack(a):
    if not a:
        die('usage: gvr-apk unpack <apk> [outdir]')
    apk = a[0]
    need_apk(apk)
    stem = re.sub(r'[^A-Za-z0-9._-]+', '_', os.path.splitext(os.path.basename(apk))[0]) or 'apk'
    out = os.path.abspath(a[1] if len(a) > 1 else os.path.join('/workspace/apk_work' if os.path.isdir('/workspace') else os.getcwd(), stem))
    if os.path.exists(out):
        if os.path.isfile(os.path.join(out, META)):
            shutil.rmtree(out)
        elif os.listdir(out):
            die('output folder is not empty and was not created by gvr-apk: ' + out)
    os.makedirs(out, exist_ok=True)
    entries = []
    with zipfile.ZipFile(apk) as zf:
        for zi in zf.infolist():
            if zi.is_dir():
                continue
            name = zi.filename
            if name.startswith('/') or '..' in name.split('/'):
                die('unsafe path inside the archive: ' + name)
            dest = os.path.join(out, name)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with zf.open(zi) as src, open(dest, 'wb') as dst:
                shutil.copyfileobj(src, dst)
            entries.append({'name': name, 'compress': zi.compress_type})
    with open(os.path.join(out, META), 'w') as f:
        json.dump({'source': apk, 'entries': entries}, f)
    print('Unpacked %d files to %s' % (len(entries), out))
    tops = sorted({e['name'].split('/')[0] for e in entries})
    print('Top level: ' + ', '.join(tops[:20]))
    print('Next: change files in that folder (assets/, lib/, res/raw, classes.dex, ...), then:')
    print('      gvr-apk pack %s <out.apk>' % out)
    print('Note: AndroidManifest.xml and res/*.xml are BINARY in a built APK; for readable manifest/resources use: gvr-apk decode')


def cmd_pack(a):
    nosign = '--no-sign' in a
    a = [x for x in a if x != '--no-sign']
    if len(a) < 2:
        die('usage: gvr-apk pack <dir> <out.apk> [--no-sign]')
    src, out = a[0], os.path.abspath(a[1])
    if not os.path.isdir(src):
        die('folder not found: ' + src)
    original = {}
    order = []
    mp = os.path.join(src, META)
    if os.path.isfile(mp):
        try:
            meta = json.load(open(mp))
            for e in meta.get('entries', []):
                original[e['name']] = e['compress']
                order.append(e['name'])
        except Exception:  # noqa: BLE001
            pass
    present = []
    for root, _dirs, files in os.walk(src):
        for fn in files:
            rel = os.path.relpath(os.path.join(root, fn), src).replace(os.sep, '/')
            if rel == META or SIG_FILES.match(rel):
                continue
            present.append(rel)
    ordered = [n for n in order if n in set(present)] + sorted(set(present) - set(order))
    if 'AndroidManifest.xml' not in ordered:
        die('AndroidManifest.xml is missing in ' + src + ' (is this an unpacked APK?)')
    os.makedirs(os.path.dirname(out) or '.', exist_ok=True)
    unsigned = out + '.unsigned'
    with zipfile.ZipFile(unsigned, 'w') as zf:
        for rel in ordered:
            with open(os.path.join(src, rel), 'rb') as f:
                data = f.read()
            write_entry(zf, rel, data, choose_compression(rel, original.get(rel)))
    print('Packed %d files (old signatures removed).' % len(ordered))
    if nosign:
        os.replace(unsigned, out)
        report(out, False)
        return
    try:
        sign_apk(unsigned, out)
    finally:
        if os.path.exists(unsigned):
            os.remove(unsigned)
    report(out, True)


def cmd_sign(a):
    if not a:
        die('usage: gvr-apk sign <apk> [out.apk]')
    need_apk(a[0])
    out = os.path.abspath(a[1]) if len(a) > 1 else re.sub(r'\.apk$', '', a[0]) + '-signed.apk'
    sign_apk(a[0], out)
    report(out, True)


def apkeditor(args):
    ensure_java()
    jar = ensure_jar('apkeditor.jar', APKEDITOR_URL)
    return run(['java', '-jar', jar] + args, 1800)


def cmd_decode(a):
    if not a:
        die('usage: gvr-apk decode <apk> [outdir]')
    need_apk(a[0])
    stem = re.sub(r'[^A-Za-z0-9._-]+', '_', os.path.splitext(os.path.basename(a[0]))[0]) or 'apk'
    out = os.path.abspath(a[1] if len(a) > 1 else os.path.join('/workspace/apk_work' if os.path.isdir('/workspace') else os.getcwd(), stem + '_decoded'))
    if os.path.isdir(out):
        shutil.rmtree(out)
    rc, text = apkeditor(['d', '-i', a[0], '-o', out])
    print(text[-1500:])
    if rc != 0 or not os.path.isfile(os.path.join(out, 'AndroidManifest.xml')):
        die('decode failed')
    print('Decoded to %s' % out)
    print('  AndroidManifest.xml           readable manifest (edit permissions/components here)')
    print('  resources/package_*/res/...   readable resource XML (strings, layouts, ...)')
    print('  smali/classes*/...            decompiled code (one .smali file per class)')
    print('  root/...                      everything else as-is (assets, lib, META-INF, ...)')
    print('Keep archive-info.json / path-map.json / uncompressed-files.json: the build step reads them.')
    print('Rebuild with: gvr-apk build %s <out.apk>' % out)


def cmd_build(a):
    if len(a) < 2:
        die('usage: gvr-apk build <dir> <out.apk>')
    out = os.path.abspath(a[1])
    tmp = out + '.apkeditor'
    if os.path.exists(tmp):
        os.remove(tmp)
    rc, text = apkeditor(['b', '-i', a[0], '-o', tmp])
    print(text[-1500:])
    if rc != 0 or not os.path.isfile(tmp):
        die('build failed')
    try:
        sign_apk(tmp, out)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)
    report(out, True)


COMMANDS = {'setup': cmd_setup, 'info': cmd_info, 'unpack': cmd_unpack, 'pack': cmd_pack, 'sign': cmd_sign,
            'decode': cmd_decode, 'build': cmd_build}

if __name__ == '__main__':
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print(__doc__)
        sys.exit(2 if len(sys.argv) > 1 else 0)
    try:
        COMMANDS[sys.argv[1]](sys.argv[2:])
    except subprocess.TimeoutExpired:
        die('timed out')
