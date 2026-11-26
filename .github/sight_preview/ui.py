"""Tiny uiautomator helper for the emulator run: tap a visible node by its text or resource id (used only for system dialogs)."""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True).stdout


def dump():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        xml = adb("exec-out", "cat", "/sdcard/ui.xml")
        if "<hierarchy" in xml:
            return ET.fromstring(xml[xml.index("<hierarchy"):])
    return None


def nodes(root):
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            yield n, (x1 + x2) // 2, (y1 + y2) // 2


def find(root, pattern):
    rx = re.compile(pattern)
    for n, cx, cy in nodes(root):
        if rx.search(n.get("text", "")) or rx.search(n.get("resource-id", "")) or rx.search(n.get("content-desc", "")):
            yield n, cx, cy


def tap_first(root, pattern):
    for n, cx, cy in find(root, pattern):
        print("tap", repr(n.get("text")), n.get("resource-id"), cx, cy)
        adb("shell", "input", "tap", str(cx), str(cy))
        return True
    return False


if __name__ == "__main__":
    cmd = sys.argv[1]
    root = dump()
    if root is None:
        print("no ui dump")
        sys.exit(2)
    if cmd == "texts":
        for n, cx, cy in nodes(root):
            t = n.get("text") or n.get("content-desc")
            if t:
                print(cx, cy, n.get("class"), n.get("resource-id"), repr(t))
    elif cmd == "tap":
        sys.exit(0 if tap_first(root, sys.argv[2]) else 1)
    elif cmd == "consent":
        # the system's screen-capture permission dialog: pick "entire screen" if it asks, then the positive button
        tap_first(root, r"(?i)entire screen")
        root2 = dump() or root
        ok = tap_first(root2, r"(?i)^(start now|start|share screen|allow|start recording)$|android:id/button1")
        sys.exit(0 if ok else 1)
