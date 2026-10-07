"""Find a clean scroll offset for a Play screenshot capture.

The slides crop the raw 1080x2340 capture below the status bar. If that crop
boundary lands in the middle of a text row the slide looks sliced. This tool
scans the capture for rows that contain no "ink" (nothing that differs from the
panel background) and prints the candidate offsets, so a slide can be given an
`offset=` that starts on a clean seam.

Run:
    py tools/store-screenshots/pick_offset.py sv-07-results.png
"""

from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
CAPTURE = ROOT / "tools" / "store-screenshots" / "_capture"

SCAN_FROM, SCAN_TO = 132, 900
INK_TOLERANCE = 42        # per-channel distance from the background that counts as ink
MAX_INK_PIXELS = 6        # a row with fewer ink pixels than this is a clean seam


def main() -> int:
    name = sys.argv[1] if len(sys.argv) > 1 else "sv-07-results.png"
    img = Image.open(CAPTURE / name).convert("RGB")
    px = img.load()

    # Panel background = the most common colour in a strip that is mostly panel.
    strip = Counter()
    for y in range(SCAN_FROM, SCAN_TO, 4):
        for x in range(0, img.width, 4):
            strip[px[x, y]] += 1
    bg = strip.most_common(1)[0][0]
    print(f"{name}: panel background = {bg}")

    clean: list[int] = []
    for y in range(SCAN_FROM, SCAN_TO):
        ink = 0
        for x in range(0, img.width, 2):
            r, g, b = px[x, y]
            if abs(r - bg[0]) > INK_TOLERANCE or abs(g - bg[1]) > INK_TOLERANCE or abs(b - bg[2]) > INK_TOLERANCE:
                ink += 1
                if ink > MAX_INK_PIXELS:
                    break
        if ink <= MAX_INK_PIXELS:
            clean.append(y)

    # Group consecutive clean rows into runs.
    runs: list[tuple[int, int]] = []
    for y in clean:
        if runs and y == runs[-1][1] + 1:
            runs[-1] = (runs[-1][0], y)
        else:
            runs.append((y, y))

    print(f"\nclean seams (source y ranges, and offset = y - 132):")
    for a, b in runs:
        if b - a >= 4:
            print(f"  y {a:4d}..{b:4d}  (len {b - a + 1:3d})   offset {a - 132:4d}..{b - 132:4d}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
