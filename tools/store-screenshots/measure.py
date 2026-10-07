"""Probe the raw Play screenshots: find the Android status-bar band and key colours.

Run:  py tools/store-screenshots/measure.py
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "store-assets"

FILES = [
    "1-landing.png",
    "2-gear-types.png",
    "3-editor.png",
    "4-parameters.png",
    "4b-parameters.png",
    "5-export.png",
]


def row_signature(img: Image.Image, y: int) -> tuple[int, int, int]:
    """Mean colour of a row (sampled every 8px for speed)."""
    px = img.load()
    w = img.width
    r = g = b = 0
    n = 0
    for x in range(0, w, 8):
        p = px[x, y]
        r += p[0]
        g += p[1]
        b += p[2]
        n += 1
    return (r // n, g // n, b // n)


def main() -> int:
    for name in FILES:
        path = SRC / name
        if not path.exists():
            print(f"MISSING {name}")
            continue
        img = Image.open(path).convert("RGB")
        print(f"== {name}  {img.width}x{img.height}  mode={img.mode}")
        prev = None
        for y in range(0, 260, 4):
            sig = row_signature(img, y)
            delta = 0 if prev is None else max(abs(a - b) for a, b in zip(sig, prev))
            marker = "  <-- change" if delta > 6 else ""
            print(f"   y={y:4d} rgb={sig} d={delta:3d}{marker}")
            prev = sig
        # dominant colours
        small = img.resize((64, 114))
        counts: dict[tuple[int, int, int], int] = {}
        for p in small.getdata():
            key = (p[0] // 16 * 16, p[1] // 16 * 16, p[2] // 16 * 16)
            counts[key] = counts.get(key, 0) + 1
        top = sorted(counts.items(), key=lambda kv: -kv[1])[:6]
        print("   dominant:", ", ".join(f"#{r:02X}{g:02X}{b:02X}({c})" for (r, g, b), c in top))
    return 0


if __name__ == "__main__":
    sys.exit(main())
