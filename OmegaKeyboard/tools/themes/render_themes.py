#!/usr/bin/env python3
"""Renders the backgrounds of the picture themes of Omega Keyboard (app/src/main/assets/themes/*.jpg).

Everything is drawn by these scripts (numpy + OpenCV + Pillow): noise, gradients, light and film grain. No photo and no artwork of anybody
else is used, so there is nothing to license. Run it again after changing a picture:

    python3 tools/themes/render_themes.py app/src/main/assets/themes
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tk import save  # noqa: E402
import grads  # noqa: E402
import scenic  # noqa: E402
import water  # noqa: E402

THEMES = {
    "beach": scenic.beach,     # a pastel coast: rose sky, blue sea, pale sand, dry reeds
    "waves": scenic.waves,     # grey water with long ripples
    "paint": scenic.paint,     # patches of colour and drops of water
    "lagoon": grads.lagoon,    # mint to blue-violet
    "blush": grads.blush,      # magenta, peach and violet
    "ruby": grads.ruby,        # red to purple
    "navy": grads.navy,        # deep blue with a streak of light
    "aqua": water.aqua,        # clear sky-blue water with soft light patterns (the see-through look)
}

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    only = sys.argv[2:] or list(THEMES)
    os.makedirs(out, exist_ok=True)
    for name in only:
        save(THEMES[name](), os.path.join(out, name + ".jpg"), 88)
        print("rendered", name)
