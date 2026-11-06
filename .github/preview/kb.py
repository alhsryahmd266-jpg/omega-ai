#!/usr/bin/env python3
"""Helpers for preview.sh (emulator screenshots of the keyboard): prefs files, finding the text field, tapping keys and tools by name."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args, timeout=90):
    try:
        return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(args, 124, "", "timeout")


def prefs_xml(items):
    lines = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
    for item in items:
        kind, rest = item.split(":", 1)
        key, value = rest.split("=", 1)
        if kind == "s":
            lines.append(f'    <string name="{key}">{value}</string>')
        elif kind == "b":
            lines.append(f'    <boolean name="{key}" value="{value}" />')
        elif kind == "i":
            n = int(value, 0)
            if n >= 2 ** 31:
                n -= 2 ** 32
            lines.append(f'    <int name="{key}" value="{n}" />')
        else:
            raise SystemExit(f"bad prefs item: {item}")
    lines.append("</map>")
    return "\n".join(lines) + "\n"


def screen_size():
    m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size").stdout)
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


def find_edit():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    text = adb("exec-out", "cat", "/sdcard/ui.xml").stdout
    if "<" not in text:
        return None
    try:
        root = ET.fromstring(text[text.index("<"):])
    except ET.ParseError:
        return None
    for node in root.iter("node"):
        if node.get("class") == "android.widget.EditText":
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def ask_dump():
    """asks the keyboard (preview builds only) where its keys and strip buttons are; returns two {name: (x, y)} maps"""
    adb("logcat", "-c")
    adb("shell", "am", "broadcast", "-a", "com.omega.keyboard.DEBUG_DUMP", "-p", "com.omega.keyboard")
    time.sleep(1.0)
    log = adb("logcat", "-d", "-v", "tag", "-s", "OmegaKeys:D", "OmegaStrip:D").stdout
    keys, strip = {}, {}
    for line in log.splitlines():
        m = re.match(r"^D/(OmegaKeys|OmegaStrip)\s*:\s*(.*)$", line.strip())
        if not m:
            continue
        target = keys if m.group(1) == "OmegaKeys" else strip
        target.clear()
        for part in m.group(2).split(";"):
            if "=" not in part:
                continue
            name, xy = part.rsplit("=", 1)
            try:
                x, y = xy.split(",")
                target[name] = (int(x), int(y))
            except ValueError:
                pass
    return keys, strip


def cmd_prefs(path, items):
    with open(path, "w", encoding="utf-8") as f:
        f.write(prefs_xml(items))


def cmd_field():
    w, h = screen_size()
    for _ in range(7):
        pos = find_edit()
        if pos and 0.08 * h < pos[1] < 0.55 * h:
            adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
            return 0
        adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.7)), str(w // 2), str(int(h * 0.3)), "350")
        time.sleep(0.9)
    print("text field not found", file=sys.stderr)
    return 1


def cmd_dump():
    keys, strip = {}, {}
    for _ in range(4):
        keys, strip = ask_dump()
        if keys:
            break
        time.sleep(1.0)
    print("KEYS", keys)
    print("STRIP", strip)
    return 0 if keys else 1


def cmd_type(text):
    keys = {}
    for _ in range(4):
        keys, _s = ask_dump()
        if keys:
            break
        time.sleep(1.0)
    if not keys:
        print("keyboard not visible: no keys known", file=sys.stderr)
        return 1
    taps = []
    for ch in text:
        name = "SPACE" if ch == " " else ch
        pos = keys.get(name) or keys.get(name.lower()) or keys.get(name.upper())
        if not pos:
            print(f"missing key {ch!r}", file=sys.stderr)
            continue
        taps.append(f"input tap {pos[0]} {pos[1]}")
    if taps:
        adb("shell", "; sleep 0.2; ".join(taps), timeout=180)
    return 0


def cmd_tool(name):
    for attempt in range(3):
        _k, strip = ask_dump()
        pos = strip.get("t:" + name)
        if pos:
            adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
            return 0
        toggle = strip.get("toggle")
        if toggle:                      # the suggestions are showing: open the tool row first
            adb("shell", "input", "tap", str(toggle[0]), str(toggle[1]))
            time.sleep(0.8)
        else:
            time.sleep(1.0)
    print(f"tool {name} not found", file=sys.stderr)
    return 1


def main(argv):
    if len(argv) < 2:
        return 2
    cmd = argv[1]
    if cmd == "prefs":
        cmd_prefs(argv[2], argv[3:])
        return 0
    if cmd == "field":
        return cmd_field()
    if cmd == "dump":
        return cmd_dump()
    if cmd == "type":
        return cmd_type(argv[2])
    if cmd == "tool":
        return cmd_tool(argv[2])
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
