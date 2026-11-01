#!/usr/bin/env python3
"""
Generates the data assets used by Omega Keyboard.

  python3 tools/gen_assets.py --out app/src/main --icons <node_modules/@material-design-icons/svg/filled> --emoji emoji.json

* assets/dict/en.txt, assets/dict/ar.txt  : "word<TAB>score" (higher score = more frequent)
* assets/emoji.txt                        : "categoryId|emoji emoji emoji ..." (gemoji data, Unicode order)
* res/drawable/ic_<name>.xml              : Material icons as VectorDrawables (tinted at runtime)

Dependencies (only needed to regenerate): pip install wordfreq ; npm i @material-design-icons/svg
"""
import argparse
import json
import math
import os
import re
import xml.etree.ElementTree as ET

ICONS = [
    # keyboard / toolbar
    "backspace", "keyboard_return", "send", "search", "arrow_forward", "check", "language", "emoji_emotions",
    "mic", "mic_off", "settings", "translate", "g_translate", "smart_toy", "auto_awesome", "content_paste",
    "close", "chevron_right", "chevron_left", "keyboard_arrow_down", "keyboard_arrow_up", "stop", "pause",
    "play_arrow", "space_bar", "call", "swap_horiz", "undo", "bolt", "edit", "done_all", "arrow_back",
    "more_horiz", "content_copy", "delete", "add", "visibility", "visibility_off", "refresh", "schedule",
    "emoji_people", "pets", "restaurant", "sports_soccer", "directions_car", "lightbulb", "emoji_symbols",
    "flag", "volume_up", "tune", "keyboard", "check_circle", "error", "warning", "download", "folder_open",
    "memory", "lock_open", "accessibility_new", "security", "history", "backspace", "info", "palette",
    "touch_app", "record_voice_over", "play_circle", "text_fields", "south_east", "expand_more", "expand_less",
]

EN_BLACKLIST = {
    "fuck", "fucking", "fucked", "fucker", "fuckers", "motherfucker", "shit", "shitty", "bitch", "bitches",
    "bastard", "asshole", "dick", "dicks", "pussy", "cunt", "slut", "whore", "nigger", "nigga", "faggot",
    "porn", "porno", "rape", "raped", "cock", "cocks", "tits", "penis", "vagina", "dildo", "anal", "cum",
}

# Colloquial (Egyptian / Gulf / Levant) words that formal-Arabic corpora under-represent.
AR_COLLOQUIAL = """
ازيك ازاي عامل عاملة ايه إيه ايوه أيوه ايوة لا لأ طيب ماشي تمام اوكي خلاص يلا معلش مفيش فيه فين امتى ليه
كده كدة كدا برضو برضه اوي أوي قوي جدا جداً دلوقتي دلوقت النهارده النهاردة بكره بكرة امبارح لسه لسا لسة بس كمان
اهو أهو ده دي دول دا دى علشان عشان عشانك بقى بقا يعني والله ان شاء الله انشالله إن شاء الله ممكن لو سمحت
من فضلك شكرا شكراً متشكر تسلم تسلمي الله يسلمك الله يخليك حبيبي حبيبتي يا باشا يا برنس يا رب ربنا يارب
صباح الخير مساء الخير تصبح على خير السلام عليكم وعليكم السلام مرحبا اهلا أهلا اهلين نورت نورتي منور
عايز عاوز عايزة عاوزة عايزين عاوزين محتاج محتاجة هروح هاجي هعمل هقولك هبعتلك هكلمك هكلمك
بتاع بتاعي بتاعك بتاعتي اللي اللى هنا هناك فوق تحت قدام ورا جنب جوه برة بره
انا أنا انت أنت انتي إنتي احنا إحنا انتو إنتو هو هي هم هما
اسف آسف أسف معلش عفوا عفواً حاضر طبعا طبعاً اكيد أكيد بالظبط بالضبط صح غلط
صاحبي صديقي اخويا اخوي اختي امي أمي ابويا أبويا بابا ماما
واتساب فيسبوك تيليجرام انستجرام يوتيوب جوجل
مبروك الف مبروك كل سنة وانت طيب عيد سعيد رمضان كريم
بحبك وحشتني وحشتيني اتأخرت متأخر مستني مستنيك مستنيكي
ازيكم عاملين عاملين ايه اخبارك اخبارك ايه
لسه فاضي مشغول شغال نايم صاحي صاحية
""".split()


def gen_dicts(out_dir):
    from wordfreq import top_n_list, word_frequency

    os.makedirs(os.path.join(out_dir, "assets", "dict"), exist_ok=True)

    # ---------------- English
    words = []
    for w in top_n_list("en", 90000, wordlist="best"):
        if not re.fullmatch(r"[a-z]+(?:'[a-z]+)?", w):
            continue
        if len(w) > 20 or (len(w) == 1 and w not in ("a", "i")):
            continue
        if w in EN_BLACKLIST:
            continue
        words.append(w)
        if len(words) >= 40000:
            break
    write_dict(os.path.join(out_dir, "assets", "dict", "en.txt"), [(w, word_frequency(w, "en", wordlist="best")) for w in words])

    # ---------------- Arabic
    diac = re.compile("[ً-ٰٟـ]")
    fix = str.maketrans({"ی": "ي", "ى": "ى", "ک": "ك", "ۀ": "ه", "ہ": "ه", "ھ": "ه", "ە": "ه"})
    merged = {}
    for w in top_n_list("ar", 120000, wordlist="best"):
        w0 = diac.sub("", w).translate(fix)
        if not re.fullmatch("[ء-ي]{2,20}", w0):
            continue
        f = word_frequency(w, "ar", wordlist="best")
        merged[w0] = max(merged.get(w0, 0), f)
    for w in AR_COLLOQUIAL:
        w0 = diac.sub("", w)
        if re.fullmatch("[ء-ي]{1,20}", w0):
            merged[w0] = max(merged.get(w0, 0), 8e-5)
    items = sorted(merged.items(), key=lambda kv: -kv[1])[:45000]
    write_dict(os.path.join(out_dir, "assets", "dict", "ar.txt"), items)


def write_dict(path, items):
    with open(path, "w", encoding="utf-8") as fh:
        for w, f in items:
            score = max(1, int(round(f * 1e9)))
            fh.write(f"{w}\t{score}\n")
    print("wrote", path, len(items), "words")


def gen_emoji(out_dir, emoji_json):
    order = ["Smileys & Emotion", "People & Body", "Animals & Nature", "Food & Drink",
             "Travel & Places", "Activities", "Objects", "Symbols", "Flags"]
    data = json.load(open(emoji_json, encoding="utf-8"))
    by = {c: [] for c in order}
    for e in data:
        if e["category"] in by:
            by[e["category"]].append(e["emoji"])
    path = os.path.join(out_dir, "assets", "emoji.txt")
    with open(path, "w", encoding="utf-8") as fh:
        for i, c in enumerate(order):
            fh.write(f"{i}|{' '.join(by[c])}\n")
    print("wrote", path, sum(len(v) for v in by.values()), "emoji")


def svg_to_vector(svg_path):
    ns = "{http://www.w3.org/2000/svg}"
    root = ET.parse(svg_path).getroot()
    paths = []
    for el in root.iter():
        tag = el.tag.replace(ns, "")
        if el.attrib.get("fill") == "none":
            continue
        if tag == "path":
            d = el.attrib["d"]
            if d.replace(" ", "") == "M0013h24v24H0z" or d.startswith("M0 0h24v24H0z"):
                continue
            paths.append((d, el.attrib.get("fill-rule") == "evenodd"))
        elif tag == "circle":
            cx, cy, r = float(el.attrib["cx"]), float(el.attrib["cy"]), float(el.attrib["r"])
            d = f"M{cx - r},{cy}a{r},{r} 0,1,0 {2 * r},0a{r},{r} 0,1,0 {-2 * r},0z"
            paths.append((d, False))
        elif tag == "rect":
            x, y = float(el.attrib.get("x", 0)), float(el.attrib.get("y", 0))
            w, h = float(el.attrib["width"]), float(el.attrib["height"])
            paths.append((f"M{x},{y}h{w}v{h}h{-w}z", False))
    out = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">']
    for d, eo in paths:
        extra = ' android:fillType="evenOdd"' if eo else ""
        out.append(f'    <path android:fillColor="#FF000000"{extra} android:pathData="{d}"/>')
    out.append("</vector>")
    return "\n".join(out) + "\n", paths


def gen_icons(out_dir, icons_dir):
    dd = os.path.join(out_dir, "res", "drawable")
    os.makedirs(dd, exist_ok=True)
    seen = set()
    missing = []
    for name in ICONS:
        if name in seen:
            continue
        seen.add(name)
        src = os.path.join(icons_dir, name + ".svg")
        if not os.path.exists(src):
            missing.append(name)
            continue
        xml, _ = svg_to_vector(src)
        with open(os.path.join(dd, f"ic_{name}.xml"), "w", encoding="utf-8") as fh:
            fh.write(xml)
    print("icons:", len(seen) - len(missing), "missing:", missing)

    # adaptive launcher icon foreground: keyboard glyph + sparkle, drawn inside the 66dp safe zone of 108dp
    kb = svg_to_vector(os.path.join(icons_dir, "keyboard.svg"))[1]
    sp = svg_to_vector(os.path.join(icons_dir, "auto_awesome.svg"))[1]
    fg = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
          '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
          '    <group android:translateX="30" android:translateY="33" android:scaleX="2" android:scaleY="2">']
    for d, _ in kb:
        fg.append(f'        <path android:fillColor="#FFFFFFFF" android:pathData="{d}"/>')
    fg.append("    </group>")
    fg.append('    <group android:translateX="58" android:translateY="22" android:scaleX="0.95" android:scaleY="0.95">')
    for d, _ in sp:
        fg.append(f'        <path android:fillColor="#FFFFE082" android:pathData="{d}"/>')
    fg.append("    </group>")
    fg.append("</vector>")
    with open(os.path.join(dd, "ic_launcher_fg.xml"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(fg) + "\n")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--icons", required=True)
    ap.add_argument("--emoji", required=True)
    ap.add_argument("--skip-dict", action="store_true")
    a = ap.parse_args()
    if not a.skip_dict:
        gen_dicts(a.out)
    gen_emoji(a.out, a.emoji)
    gen_icons(a.out, a.icons)
