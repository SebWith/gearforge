"""Build annotated Google Play screenshots for GearForge.

Pipeline
--------
1. Crop the Android status bar / gesture bar out of each raw emulator capture.
2. Base64-embed the capture plus the app's own hero background into an HTML page.
3. Screenshot each page with headless Edge at exactly 1080x1920.
4. Re-encode as 24-bit RGB PNG (no alpha) so the files satisfy Play's rules.

Run:
    py tools/store-screenshots/build_slides.py
    py tools/store-screenshots/build_slides.py --only en
"""

from __future__ import annotations

import argparse
import base64
import csv
import io
import math
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools" / "store-screenshots"
CAPTURE = TOOLS / "_capture"
BUILD = TOOLS / "_build"
ASSETS = ROOT / "store-assets"
FINAL = ASSETS / "final"
HERO_BG = ROOT / "android" / "src" / "main" / "res" / "drawable-nodpi" / "bg_hero.jpg"

EDGE = Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")

CANVAS_W, CANVAS_H = 1080, 1920
PAD_TOP, PAD_X, PAD_BOTTOM = 92, 84, 30
BAR_TOP = 132          # Android status bar height in the 1080x2340 captures
BAR_BOTTOM = 66        # gesture navigation bar
BEZEL = 12             # device frame border thickness
MAX_CONTENT_W = 756    # widest screenshot area; shrinks so the frame always fits
MIN_CONTENT_W = 640

HEADLINE_PX, HEADLINE_LH = 84, 1.09
EYEBROW_BLOCK = 77     # pill height + margin
SUB_PX, SUB_LH, SUB_GAP = 34, 1.4, 26
SUB_CHARS_PER_LINE = 44

FONT_DISPLAY = "Bahnschrift, 'Segoe UI Variable Display', 'Segoe UI', sans-serif"
FONT_TEXT = "'Segoe UI Variable Text', 'Segoe UI', Tahoma, sans-serif"


@dataclass
class Slide:
    slug: str
    source: str                 # capture file name in _capture/
    eyebrow: str
    headline: str               # "\n" separates lines
    sub: str
    lang: str
    offset: int = 0             # source window top offset (scroll position)

    @property
    def headline_html(self) -> str:
        return "<br>".join(self.headline.split("\n"))

    def header_height(self) -> int:
        """Estimated CSS height of the text block above the device frame."""
        lines = self.headline.count("\n") + 1
        plain = re.sub(r"&[a-z]+;", "x", self.sub)
        sub_lines = max(1, math.ceil(len(plain) / SUB_CHARS_PER_LINE))
        return (
            EYEBROW_BLOCK
            + round(lines * HEADLINE_PX * HEADLINE_LH)
            + SUB_GAP
            + round(sub_lines * SUB_PX * SUB_LH)
            + 14
        )


def slides(lang: str) -> list[Slide]:
    """One entry per Play Store screenshot. Every claim is verified against the app."""
    if lang == "en":
        return [
            Slide("01-design-gears", "01-launch.png",
                  "Gear designer for 3D printing",
                  "Design the gear.\nPrint the gear.",
                  "Parametric involute gears, 14 gear types and 6 export formats — all on your phone.",
                  lang),
            Slide("02-gear-types", "02-types.png",
                  "14 gear types",
                  "Spur, helical, bevel,\nplanetary and more",
                  "Rack &amp; pinion, worm pair, internal ring, timing belt and compound gears.",
                  lang),
            Slide("03-presets", "03-view.png",
                  "Presets",
                  "Start from a proven\nreduction ratio",
                  "3:1, 5:1 and 7:1 planetary stages for gearmotors, robotics and high-torque reducers.",
                  lang),
            Slide("04-3d-preview", "04-workspace.png",
                  "Live 3D preview",
                  "Inspect the exact teeth\nyou are going to print",
                  "Rotate and zoom the real mesh, with undo and redo for every change.",
                  lang),
            Slide("05-parameters", "07-params-scroll.png",
                  "Fully parametric",
                  "Module, teeth,\npressure angle",
                  "Backlash, profile shift, addendum, dedendum and tip relief — in mm or inch.",
                  lang),
            Slide("06-results", "12-results.png",
                  "Live results",
                  "Every value,\ncalculated instantly",
                  "Pitch, outer and root diameters, centre distance, ratio, weight and effective backlash.",
                  lang),
            Slide("07-export", "14-export.png",
                  "6 export formats",
                  "STL · 3MF · STEP\nIGES · SVG · DXF",
                  "Saved straight to Downloads with triangle count and dimensions. 3 free exports — then Pro or a short rewarded ad.",
                  lang),
        ]

    return [
        Slide("01-designa-kugghjul", "sv-01-landing.png",
              "Kugghjulsdesign för 3D-utskrift",
              "Designa kugghjulet.\nSkriv ut det.",
              "Parametrisk kugggeometri, 14 kugghjulstyper och 6 exportformat — direkt i mobilen.",
              lang),
        Slide("02-kugghjulstyper", "sv-02-types.png",
              "14 kugghjulstyper",
              "Rak, sned, konisk,\nplanetväxel &amp; fler",
              "Kuggstång &amp; pinjong, snäckväxel, invändig ring, kuggrem och sammansatta kugghjul.",
              lang),
        Slide("03-forinstallningar", "sv-03-presets.png",
              "Förinställningar",
              "Börja från en\nbeprövad utväxling",
              "Planetsteg på 3:1, 5:1 och 7:1 för växelmotorer, robotik och högmoment-reducerare.",
              lang),
        Slide("04-3d-forhandsvisning", "sv-04-workspace.png",
              "Live 3D-förhandsvisning",
              "Granska kuggen\ndu ska skriva ut",
              "Rotera och zooma den riktiga meshen — och ångra varje ändring.",
              lang),
        Slide("05-parametrar", "sv-05-params.png",
              "Helt parametrisk",
              "Modul, kuggantal,\ntryckvinkel",
              "Spel, profilförskjutning, addendum, dedendum och topprelief — i mm eller tum.",
              lang, offset=110),
        Slide("06-resultat", "sv-07-results.png",
              "Direkta resultat",
              "Varje värde\nräknas ut direkt",
              "Delnings-, ytter- och fotdiameter, axelavstånd, utväxling, vikt och effektivt spel.",
              lang, offset=220),   # clean seam below the slider thumb; no text is sliced
        Slide("07-export", "sv-06-export.png",
              "6 exportformat",
              "STL · 3MF · STEP\nIGES · SVG · DXF",
              "Sparas direkt i Hämtade filer med antal trianglar och mått. 3 gratisexporter — sedan Pro eller en kort belöningsvideo.",
              lang),
    ]


def load_capture(name: str) -> Image.Image:
    path = CAPTURE / name
    if not path.exists():
        raise SystemExit(f"missing capture: {path}")
    img = Image.open(path).convert("RGB")
    if img.width != 1080:
        raise SystemExit(f"unexpected capture width for {name}: {img.width}")
    return img.crop((0, BAR_TOP, img.width, img.height - BAR_BOTTOM))


def data_uri(img: Image.Image, fmt: str = "PNG", **kwargs) -> str:
    buf = io.BytesIO()
    img.save(buf, fmt, **kwargs)
    mime = "image/png" if fmt == "PNG" else "image/jpeg"
    return f"data:{mime};base64,{base64.b64encode(buf.getvalue()).decode('ascii')}"


def fit_device(slide: Slide, source_h: int) -> tuple[int, int, int]:
    """Return (content_width, content_height, frame_height) that fit the slide."""
    available = CANVAS_H - PAD_TOP - PAD_BOTTOM - slide.header_height() - 2 * BEZEL
    width = min(MAX_CONTENT_W, math.floor(available * 1080 / source_h))
    width = max(MIN_CONTENT_W, width)
    height = round(width * source_h / 1080)
    return width, height, height + 2 * BEZEL


def render_html(slide: Slide, hero_uri: str) -> tuple[str, int]:
    src = load_capture(slide.source)
    offset = max(0, min(slide.offset, src.height - 1))
    window = src.height - offset
    crop = src.crop((0, offset, src.width, offset + window))

    content_w, content_h, frame_h = fit_device(slide, window)
    shot_uri = data_uri(crop)

    html = f"""<!DOCTYPE html>
<html lang="{slide.lang}">
<head>
<meta charset="utf-8">
<style>
  * {{ margin:0; padding:0; box-sizing:border-box; }}
  html, body {{ width:{CANVAS_W}px; height:{CANVAS_H}px; overflow:hidden; }}
  body {{
    background:#04121c;
    display:flex; flex-direction:column;
    padding:{PAD_TOP}px {PAD_X}px 0;
    position:relative;
  }}
  .bg {{
    position:absolute; inset:0;
    background-image:url("{hero_uri}");
    background-size:cover; background-position:center top;
  }}
  .veil {{
    position:absolute; inset:0;
    background:
      radial-gradient(880px 660px at 86% 4%, rgba(70,165,230,.24), transparent 72%),
      linear-gradient(180deg, rgba(4,16,26,.70) 0%, rgba(3,13,21,.86) 44%, rgba(2,9,15,.95) 100%);
  }}
  .content {{ position:relative; display:flex; flex-direction:column; flex:1; }}
  .eyebrow {{ margin-bottom:22px; }}
  .eyebrow span {{
    display:inline-block;
    font-family:{FONT_TEXT};
    font-size:29px; font-weight:600; letter-spacing:3.4px; text-transform:uppercase;
    color:#7FCBFF;
    padding:9px 20px 8px; border-radius:999px;
    background:rgba(58,150,214,.15); border:1px solid rgba(110,196,255,.32);
  }}
  h1 {{
    font-family:{FONT_DISPLAY};
    font-variation-settings:'wght' 700;
    font-size:{HEADLINE_PX}px; line-height:{HEADLINE_LH}; letter-spacing:-.6px;
    color:#FFFFFF; text-shadow:0 4px 26px rgba(0,0,0,.55);
  }}
  .sub {{
    font-family:{FONT_TEXT};
    font-size:{SUB_PX}px; line-height:{SUB_LH}; color:#A8CADD;
    margin-top:{SUB_GAP}px; max-width:912px;
  }}
  .device {{
    width:{content_w + 2 * BEZEL}px;
    height:{frame_h}px;
    margin-top:auto;
    align-self:center;
    border-radius:52px;
    border:{BEZEL}px solid #141d26;
    box-shadow:0 30px 80px rgba(0,0,0,.62), 0 0 0 1px rgba(140,205,255,.18);
    background:#05090d;
    overflow:hidden;
  }}
  .device img {{ width:{content_w}px; height:{content_h}px; display:block; }}
</style>
</head>
<body>
  <div class="bg"></div>
  <div class="veil"></div>
  <div class="content">
    <div class="eyebrow"><span>{slide.eyebrow}</span></div>
    <h1>{slide.headline_html}</h1>
    <div class="sub">{slide.sub}</div>
    <div class="device"><img src="{shot_uri}" alt=""></div>
  </div>
</body>
</html>
"""
    return html, frame_h


def render_png(html_path: Path, png_path: Path) -> None:
    cmd = [
        str(EDGE),
        "--headless=new",
        "--disable-gpu",
        "--hide-scrollbars",
        "--force-device-scale-factor=1",
        f"--window-size={CANVAS_W},{CANVAS_H}",
        f"--screenshot={png_path.as_posix()}",
        "file:///" + html_path.as_posix(),
    ]
    subprocess.run(cmd, check=False, capture_output=True)
    if not png_path.exists():
        raise SystemExit(f"headless render failed for {html_path.name}")


def normalise(png_path: Path) -> int:
    """Force 24-bit RGB (no alpha) and return the file size in bytes."""
    img = Image.open(png_path).convert("RGB")
    if img.size != (CANVAS_W, CANVAS_H):
        raise SystemExit(f"{png_path.name}: rendered {img.size}, expected {(CANVAS_W, CANVAS_H)}")
    img.save(png_path, "PNG", optimize=True)
    return png_path.stat().st_size


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["en", "sv"])
    args = ap.parse_args()

    BUILD.mkdir(parents=True, exist_ok=True)
    hero_uri = data_uri(Image.open(HERO_BG).convert("RGB"), "JPEG", quality=88)
    langs = [args.only] if args.only else ["en", "sv"]

    manifest: list[dict[str, str]] = []
    for lang in langs:
        out_dir = FINAL / lang
        out_dir.mkdir(parents=True, exist_ok=True)
        for slide in slides(lang):
            html_path = BUILD / f"{lang}-{slide.slug}.html"
            png_path = out_dir / f"{slide.slug}.png"
            html, frame_h = render_html(slide, hero_uri)
            html_path.write_text(html, encoding="utf-8")
            render_png(html_path, png_path)
            size = normalise(png_path)
            print(f"  {lang}/{png_path.name:28s} 1080x1920  frame={frame_h:4d}px  {size/1024:7.1f} KB")
            manifest.append({
                "language": lang,
                "file": f"{lang}/{png_path.name}",
                "position": slide.slug[:2],
                "eyebrow": slide.eyebrow,
                "headline": slide.headline.replace("\n", " "),
                "source_capture": slide.source,
            })

    with (FINAL / "manifest.csv").open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(
            fh, fieldnames=["language", "position", "file", "eyebrow", "headline", "source_capture"]
        )
        writer.writeheader()
        writer.writerows(manifest)
    print(f"\nwrote {FINAL / 'manifest.csv'} ({len(manifest)} images)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
