"""Render the top strip of a raw screenshot with a pixel ruler, for visual inspection.

Run:  py tools/store-screenshots/ruler.py [file.png]
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "store-assets"
OUT = Path(__file__).resolve().parent / "_debug"


def main() -> None:
    names = sys.argv[1:] or ["4-parameters.png", "1-landing.png", "3-editor.png"]
    OUT.mkdir(parents=True, exist_ok=True)
    for name in names:
        img = Image.open(SRC / name).convert("RGB").crop((0, 0, 1080, 400))
        d = ImageDraw.Draw(img)
        for y in range(0, 400, 25):
            colour = (255, 0, 0) if y % 100 == 0 else (255, 140, 0)
            d.line([(0, y), (1080, y)], fill=colour, width=1)
            d.text((4, y + 2), str(y), fill=(255, 255, 255))
        img.save(OUT / f"ruler-{name}")
        print(f"wrote {OUT / f'ruler-{name}'}")


if __name__ == "__main__":
    main()
