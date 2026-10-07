"""Locate the status-bar band and the toolbar band in one raw screenshot.

Run:  py tools/store-screenshots/measure2.py
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "store-assets"
TARGET = "4-parameters.png"


def bright_rows(img: Image.Image, x0: int, x1: int, thresh: int = 140) -> list[int]:
    px = img.load()
    rows = []
    for y in range(0, 400):
        hit = 0
        for x in range(x0, x1, 2):
            p = px[x, y]
            if 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2] > thresh:
                hit += 1
        if hit >= 2:
            rows.append(y)
    return rows


def runs(rows: list[int]) -> list[tuple[int, int]]:
    if not rows:
        return []
    out = []
    start = prev = rows[0]
    for y in rows[1:]:
        if y - prev > 3:
            out.append((start, prev))
            start = y
        prev = y
    out.append((start, prev))
    return out


def main() -> None:
    img = Image.open(SRC / TARGET).convert("RGB")
    print(f"{TARGET} {img.width}x{img.height}")
    print("  clock band  :", runs(bright_rows(img, 60, 200)))
    print("  back arrow  :", runs(bright_rows(img, 40, 120)))
    print("  title text  :", runs(bright_rows(img, 200, 420)))
    # vertical profile of the left edge to see the chrome boundary
    px = img.load()
    prev = None
    for y in range(0, 60, 2):
        c = px[4, y]
        if c != prev:
            print(f"  x=4  y={y:3d} -> {c}")
            prev = c


if __name__ == "__main__":
    main()
