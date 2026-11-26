"""Reads the SightTestGame log lines and prints, per run and per 10 seconds, how much of the time the crosshair was on a bot."""
import re
import sys

RX = re.compile(
    r"t=([\d.]+) frames=(\d+) fireFrames=(\d+) hits=(\d+) pct=([\d.]+) locks=(\d+) firstLock=(-?[\d.]+) longestLock=([\d.]+) "
    r"nearestPx=(\d+) looks=(\d+) moves=(\d+) camX=(-?\d+) camY=(-?\d+)"
)
runs = [[]]
for line in open(sys.argv[1], errors="replace"):
    m = RX.search(line)
    if not m:
        continue
    row = tuple(float(x) for x in m.groups())
    if runs[-1] and row[0] < runs[-1][-1][0]:
        runs.append([])
    runs[-1].append(row)

for n, rows in enumerate(runs):
    if not rows:
        continue
    print(f"--- run {n + 1}: {len(rows)} log lines, {rows[-1][0]:.0f} s")
    last_t = rows[-1][0]
    k = 0
    while k < last_t:
        win = [r for r in rows if k <= r[0] <= k + 10]
        if len(win) >= 2:
            a, b = win[0], win[-1]
            df = b[2] - a[2]
            dh = b[3] - a[3]
            dt = max(0.001, b[0] - a[0])
            print(
                f"t {k:3d}-{k + 10:3d} s: on target {100 * dh / df if df else 0:5.1f}%  fps {(b[1] - a[1]) / dt:5.1f}  "
                f"locks +{int(b[5] - a[5])}  looks +{int(b[9] - a[9])}  moves +{int(b[10] - a[10])}  nearest {b[8]:.0f}px  cam ({b[11]:.0f},{b[12]:.0f})"
            )
        k += 10
    r = rows[-1]
    print(f"total: on target {r[4]:.1f}%  locks {int(r[5])}  first lock at {r[6]:.1f} s  longest lock {r[7]:.1f} s  looks {int(r[9])}  moves {int(r[10])}")
