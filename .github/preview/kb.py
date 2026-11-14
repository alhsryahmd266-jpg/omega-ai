#!/usr/bin/env python3
"""Helpers for preview.sh (emulator screenshots of the keyboard): prefs files, finding the text field, tapping keys, tools and
sheet tabs by name. The keyboard (debug builds only) answers the broadcast com.omega.keyboard.DEBUG_DUMP by logging where its
keys (OmegaKeys), strip buttons (OmegaStrip) and appearance-sheet tabs (OmegaSheet) are on the screen."""
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
    """the size of the screen the way the user holds the phone now (portrait, or sideways when the test turned it)"""
    m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size").stdout)
    w, h = (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)
    rot = adb("shell", "settings", "get", "system", "user_rotation").stdout.strip()
    auto = adb("shell", "settings", "get", "system", "accelerometer_rotation").stdout.strip()
    if auto == "0" and rot in ("1", "3"):
        w, h = h, w
    return w, h


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
    """asks the keyboard where its keys, strip buttons and sheet tabs are; returns three {name: (x, y)} maps"""
    adb("logcat", "-c")
    adb("shell", "am", "broadcast", "-a", "com.omega.keyboard.DEBUG_DUMP", "-p", "com.omega.keyboard")
    time.sleep(1.0)
    log = adb("logcat", "-d", "-v", "tag", "-s", "OmegaKeys:D", "OmegaStrip:D", "OmegaSheet:D").stdout
    maps = {"OmegaKeys": {}, "OmegaStrip": {}, "OmegaSheet": {}}
    for line in log.splitlines():
        m = re.match(r"^D/(OmegaKeys|OmegaStrip|OmegaSheet)\s*:\s*(.*)$", line.strip())
        if not m:
            continue
        target = maps[m.group(1)]
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
    return maps["OmegaKeys"], maps["OmegaStrip"], maps["OmegaSheet"]


def wait_dump(tries=4, need="keys"):
    """asks until the wanted map is not empty (the keyboard may still be coming up)"""
    keys, strip, sheet = {}, {}, {}
    for _ in range(tries):
        keys, strip, sheet = ask_dump()
        if {"keys": keys, "strip": strip, "sheet": sheet}[need]:
            break
        time.sleep(1.0)
    return keys, strip, sheet


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
    keys, strip, sheet = wait_dump()
    print("KEYS", keys)
    print("STRIP", strip)
    print("SHEET", sheet)
    return 0 if keys else 1


def key_position(keys, name):
    if name == " ":
        name = "SPACE"
    return keys.get(name) or keys.get(name.lower()) or keys.get(name.upper())


def cmd_type(text):
    keys, _s, _t = wait_dump()
    if not keys:
        print("keyboard not visible: no keys known", file=sys.stderr)
        return 1
    taps = []
    for ch in text:
        pos = key_position(keys, ch)
        if not pos:
            print(f"missing key {ch!r}", file=sys.stderr)
            continue
        taps.append(f"input tap {pos[0]} {pos[1]}")
    if taps:
        adb("shell", "; sleep 0.2; ".join(taps), timeout=180)
    return 0


def cmd_key(name):
    """taps one key by its label or its kind name (SHIFT, SYMBOLS, EMOJI, LETTERS, ENTER, SPACE, LANG ...)"""
    keys, _s, _t = wait_dump()
    pos = key_position(keys, name)
    if not pos:
        print(f"key {name} not found", file=sys.stderr)
        return 1
    adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
    return 0


def cmd_pos(name):
    """prints 'x y' of a key (for preview.sh, which then holds it with input swipe)"""
    keys, _s, _t = wait_dump()
    pos = key_position(keys, name)
    if not pos:
        print(f"key {name} not found", file=sys.stderr)
        return 1
    print(pos[0], pos[1])
    return 0


def cmd_tool(name):
    for _attempt in range(3):
        _k, strip, _t = ask_dump()
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


def cmd_openbar():
    """makes sure the tool row of the strip is open (it is the default: asking for it must never close it)"""
    for _attempt in range(3):
        _k, strip, _t = ask_dump()
        if any(name.startswith("t:") for name in strip):
            return 0
        toggle = strip.get("toggle")
        if toggle:
            adb("shell", "input", "tap", str(toggle[0]), str(toggle[1]))
            time.sleep(0.9)
        else:
            time.sleep(1.0)
    print("the tool row did not open", file=sys.stderr)
    return 1


def cmd_shown():
    """exit code 0 when the system says an input method window is on the screen (the dump alone also works when it is hidden)"""
    out = adb("shell", "dumpsys", "input_method").stdout
    return 0 if "mInputShown=true" in out else 1


def cmd_strip(name):
    """taps a button of the suggestion strip by its tag (csend, toggle, t:<tool> ...)"""
    for _attempt in range(3):
        _k, strip, _t = ask_dump()
        pos = strip.get(name)
        if pos:
            adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
            return 0
        time.sleep(1.0)
    print(f"strip button {name} not found", file=sys.stderr)
    return 1


def cmd_tab(index):
    """taps tab <index> of the appearance sheet (0 colours, 1 themes, 2 size and effects)"""
    _k, _s, sheet = wait_dump(need="sheet")
    pos = sheet.get(f"tab{index}")
    if not pos:
        print(f"sheet tab {index} not found", file=sys.stderr)
        return 1
    adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
    return 0


def cmd_target(index):
    """taps one of the colour targets of the appearance sheet (0 text, 1 keys, 2 letters, 3 background, 4 function keys, 5 accent)"""
    _k, _s, sheet = wait_dump(need="sheet")
    pos = sheet.get(f"target{index}")
    if not pos:
        print(f"sheet target {index} not found", file=sys.stderr)
        return 1
    adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
    return 0


def cmd_drag(name, dx, dy, ms):
    """drags from a named point (a key, a strip button or a resize handle) by dx, dy pixels, taking ms milliseconds"""
    keys, strip, _t = wait_dump()
    pos = key_position(keys, name) or strip.get(name)
    if not pos:
        print(f"nothing named {name} to drag", file=sys.stderr)
        return 1
    adb("shell", "input", "swipe", str(pos[0]), str(pos[1]), str(pos[0] + int(dx)), str(pos[1] + int(dy)), str(int(ms)))
    return 0


def cmd_text():
    """prints what the first text field of the screen holds now"""
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    text = adb("exec-out", "cat", "/sdcard/ui.xml").stdout
    if "<" not in text:
        return 1
    try:
        root = ET.fromstring(text[text.index("<"):])
    except ET.ParseError:
        return 1
    for node in root.iter("node"):
        if node.get("class") == "android.widget.EditText":
            print(node.get("text", ""))
            return 0
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
    if cmd == "key":
        return cmd_key(argv[2])
    if cmd == "pos":
        return cmd_pos(argv[2])
    if cmd == "tool":
        return cmd_tool(argv[2])
    if cmd == "strip":
        return cmd_strip(argv[2])
    if cmd == "openbar":
        return cmd_openbar()
    if cmd == "shown":
        return cmd_shown()
    if cmd == "tab":
        return cmd_tab(argv[2])
    if cmd == "target":
        return cmd_target(argv[2])
    if cmd == "drag":
        return cmd_drag(argv[2], argv[3], argv[4], argv[5] if len(argv) > 5 else "600")
    if cmd == "text":
        return cmd_text()
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
