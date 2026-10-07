"""Find the status-bar content band (clock / icons) in every raw screenshot.

Run:  py tools/store-screenshots/measure3.py
"""
from __future__ import annotations

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
]

THRESH = 115


def lum(p: tuple[int, int, int]) -> float:
    return 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]


def bright_rows(img: Image.Image, x0: int, x1: int) -> list[int]:
    px = img.load()
    out = []
    for y in range(0, 300):
        hits = sum(1 for x in range(x0, x1, 2) if lum(px[x, y]) > THRESH)
        if hits >= 2:
            out.append(y)
    return out


def runs(rows: list[int]) -> list[tuple[int, int]]:
    out: list[tuple[int, int]] = []
    if not rows:
        return out
    start = prev = rows[0]
    for y in rows[1:]:
        if y - prev > 4:
            out.append((start, prev))
            start = y
        prev = y
    out.append((start, prev))
    return out


def main() -> None:
    for name in FILES:
        path = SRC / name
        if not path.exists():
            print(f"MISSING {name}")
            continue
        img = Image.open(path).convert("RGB")
        bands = runs(bright_rows(img, 30, 260))
        print(f"{name:22s} status-content bands (x30-260): {bands}")
    print()
    # Corner column, to spot a chrome/background boundary in the panel screens.
    img = Image.open(SRC / "4-parameters.png").convert("RGB")
    px = img.load()
    last = None
    for y in range(0, 320):
        c = px[2, y]
        if last is None or max(abs(a - b) for a, b in zip(c, last)) > 3:
            print(f"  4-parameters x=2 y={y:3d} -> {c}")
            last = c


if __name__ == "__main__":
    main()
