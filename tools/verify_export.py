#!/usr/bin/env python3
"""Strukturverifiering av GearForge-exporter (STL, 3MF, SVG, DXF, STEP, IGES).

Fångar de fel som tyst ger en värdelös fil: fel endianness, felaktig
längdberäkning, NaN-koordinater, degenererade trianglar och enhets-/skalfel.

Användning:
    py tools/verify_export.py fil.stl
    py tools/verify_export.py *.stl
    py tools/verify_export.py spur.stl --expect-diameter 22.0
    py tools/verify_export.py spur.stl --expect-triangles 18420

Exit-kod 0 = alla filer OK, 1 = minst en avvikelse.
"""

from __future__ import annotations

import argparse
import glob
import re
import struct
import sys
import zipfile
from pathlib import Path

# Report in UTF-8 regardless of what the caller's console claims to be.
#
# Measured 2026-09-21: run from the harness on a Windows console in CP1252, this script died while
# printing its IGES verdict - "UnicodeEncodeError: 'charmap' codec can't encode character '\u2264'"
# - because the message contains "<= 80 tecken" with the real less-than-or-equal sign. The export it
# was checking had succeeded; the VERIFICATION produced no verdict at all, which is worse than a
# failure: a check that cannot report is a check nobody ran. Declaring the encoding here fixes it for
# every caller instead of asking each of them to set PYTHONIOENCODING.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except (AttributeError, ValueError):
        pass

SEVERITY_FAIL = "FEL"
SEVERITY_WARN = "VARN"


class Report:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.lines: list[tuple[str, str]] = []

    def ok(self, msg: str) -> None:
        self.lines.append(("OK  ", msg))

    def warn(self, msg: str) -> None:
        self.lines.append((SEVERITY_WARN, msg))

    def fail(self, msg: str) -> None:
        self.lines.append((SEVERITY_FAIL, msg))

    @property
    def failed(self) -> bool:
        return any(sev == SEVERITY_FAIL for sev, _ in self.lines)

    def dump(self) -> None:
        print(f"\n=== {self.path.name} ({self.path.stat().st_size:,} byte) ===")
        for sev, msg in self.lines:
            print(f"  [{sev}] {msg}")


def check_magic(report: Report, data: bytes) -> str | None:
    """Identifierar format och returnerar dess namn, eller None."""
    ext = report.path.suffix.lower()

    if ext == ".stl":
        return "stl"
    if ext == ".3mf":
        if data[:4] != b"PK\x03\x04":
            report.fail("3MF ska vara ett ZIP-arkiv (PK\\x03\\x04) — magic bytes stämmer inte.")
        return "3mf"
    if ext == ".svg":
        head = data[:400].lstrip()
        if not (head.startswith(b"<?xml") or head.startswith(b"<svg")):
            report.fail("SVG ska börja med <?xml eller <svg.")
        return "svg"
    if ext == ".dxf":
        head = data[:200]
        if b"SECTION" not in head:
            report.fail("DXF ska innehålla 'SECTION' i början (gruppkod 0).")
        return "dxf"
    if ext == ".step" or ext == ".stp":
        head = data[:200]
        if b"ISO-10303-21" not in head:
            report.fail("STEP ska börja med ISO-10303-21;")
        if b"END-ISO-10303-21" not in data[-400:]:
            report.fail("STEP ska avslutas med END-ISO-10303-21;")
        return "step"
    if ext == ".iges" or ext == ".igs":
        return "iges"
    report.warn(f"Okänd filändelse '{ext}' — kontrollerar bara grundläggande struktur.")
    return None


def check_stl(report: Report, data: bytes, expect_diameter: float | None,
              expect_triangles: int | None) -> None:
    if len(data) < 84:
        report.fail(f"Filen är {len(data)} byte — för kort för en binär STL (minst 84).")
        return

    header = data[:80]
    if header[:5].lower() == b"solid" and b"facet" in data[:2000]:
        report.warn("Filen ser ut som ASCII-STL. GearForge skriver binär STL — "
                    "kontrollera att rätt skrivare användes.")

    # Läs både little och big endian för att kunna diagnostisera fel endianness.
    n_le = struct.unpack_from("<I", data, 80)[0]
    n_be = struct.unpack_from(">I", data, 80)[0]
    expected_le = (len(data) - 84) / 50.0
    expected_be = expected_le

    if abs(expected_le - round(expected_le)) > 1e-9:
        report.fail(f"Filstorleken stämmer inte med STL-layouten: (len-84)/50 = "
                    f"{expected_le:.3f} — ska vara ett heltal.")
        return

    if n_le == round(expected_le):
        n = n_le
        report.ok(f"Deklarerat triangelantal {n:,} matchar filstorleken (84 + {n}·50 = {len(data):,}).")
    elif n_be == round(expected_be):
        report.fail(f"Triangelantalet läses som stort endian-tal ({n_be:,}). "
                    f"STL kräver ByteOrder.LITTLE_ENDIAN.")
        return
    else:
        report.fail(f"Deklarerat antal ({n_le:,}) matchar inte filstorleken "
                    f"({len(data):,} byte => förväntat {round(expected_le):,}).")
        return

    if expect_triangles is not None and n != expect_triangles:
        report.fail(f"Triangelantal {n:,} != förväntat {expect_triangles:,}.")

    # Läs alla trianglar och kontrollera koordinater.
    nan_count = 0
    degenerate = 0
    minv = [float("inf")] * 3
    maxv = [float("-inf")] * 3

    for i in range(n):
        off = 84 + i * 50 + 12  # hoppa över normalvektorn
        vals = struct.unpack_from("<9f", data, off)
        if any(v != v for v in vals):  # NaN
            nan_count += 1
            continue
        if any(v in (float("inf"), float("-inf")) for v in vals):
            nan_count += 1
            continue
        a = vals[0:3]
        b = vals[3:6]
        c = vals[6:9]
        for k in range(3):
            minv[k] = min(minv[k], a[k], b[k], c[k])
            maxv[k] = max(maxv[k], a[k], b[k], c[k])
        # Area via kryssproduktens halva norm.
        ux, uy, uz = b[0] - a[0], b[1] - a[1], b[2] - a[2]
        vx, vy, vz = c[0] - a[0], c[1] - a[1], c[2] - a[2]
        cx = uy * vz - uz * vy
        cy = uz * vx - ux * vz
        cz = ux * vy - uy * vx
        if (cx * cx + cy * cy + cz * cz) ** 0.5 / 2.0 <= 1e-12:
            degenerate += 1

    if nan_count:
        report.fail(f"{nan_count:,} triangel(n) innehåller NaN/Infinity — "
                    f"skrivaren ska aldrig producera det.")
    else:
        report.ok("Inga NaN/Infinity-koordinater.")

    if degenerate:
        report.warn(f"{degenerate:,} degenererad(e) triangel(n) (area ≈ 0). "
                    f"Slicers brukar tolerera det men det är slöseri och kan ge artefakter.")
    else:
        report.ok("Inga degenererade trianglar.")

    dims = [maxv[k] - minv[k] for k in range(3)]
    report.ok(
        "Bounding box: "
        f"[{minv[0]:.3f}, {minv[1]:.3f}, {minv[2]:.3f}] .. "
        f"[{maxv[0]:.3f}, {maxv[1]:.3f}, {maxv[2]:.3f}]"
    )
    report.ok(f"Mått (X × Y × Z): {dims[0]:.3f} × {dims[1]:.3f} × {dims[2]:.3f} mm")

    largest = max(dims)
    if largest < 0.5:
        report.warn(f"Största måttet är {largest:.3f} mm — misstänkt litet. Enhetsfel?")
    if largest > 2000.0:
        report.warn(f"Största måttet är {largest:.1f} mm — misstänkt stort. Enhetsfel?")

    if expect_diameter is not None:
        # Ytterdiametern är det största XY-måttet för ett kugghjul.
        measured = max(dims[0], dims[1])
        tol = max(0.05, expect_diameter * 0.001)
        if abs(measured - expect_diameter) <= tol:
            report.ok(f"XY-diameter {measured:.3f} mm matchar förväntat {expect_diameter:.3f} mm.")
        else:
            ratio = measured / expect_diameter if expect_diameter else 0
            hint = ""
            if 24.0 < ratio < 26.5:
                hint = "  <- misstänkt 25,4× (tum/mm-förväxling)"
            elif 0.037 < ratio < 0.041:
                hint = "  <- misstänkt 1/25,4× (mm/tum-förväxling)"
            report.fail(f"XY-diameter {measured:.3f} mm != förväntat {expect_diameter:.3f} mm "
                        f"(tolerans {tol:.3f}).{hint}")


def check_3mf(report: Report, data: bytes) -> None:
    try:
        import io

        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            names = zf.namelist()
            required = ["[Content_Types].xml", "_rels/.rels"]
            missing = [r for r in required if r not in names]
            if missing:
                report.fail(f"3MF saknar obligatoriska poster: {', '.join(missing)}")
            models = [n for n in names if n.endswith(".model")]
            if not models:
                report.fail("3MF saknar en *.model-post (3D/3dmodel.model).")
            else:
                report.ok(f"3MF är ett giltigt ZIP-arkiv med {len(names)} poster, "
                          f"modell: {', '.join(models)}")
            bad = zf.testzip()
            if bad:
                report.fail(f"Korrupt post i ZIP-arkivet: {bad}")
            else:
                report.ok("Alla ZIP-poster har korrekt CRC.")
    except zipfile.BadZipFile as exc:
        report.fail(f"3MF går inte att öppna som ZIP: {exc}")


def check_text2d(report: Report, data: bytes, fmt: str) -> None:
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as exc:
        report.fail(f"{fmt.upper()} är inte giltig UTF-8: {exc}")
        return

    if fmt == "svg":
        if "<svg" not in text:
            report.fail("SVG saknar <svg>-element.")
        if "</svg>" not in text:
            report.fail("SVG saknar avslutande </svg> — filen är trunkerad.")
        if "viewBox" not in text and "width" not in text:
            report.warn("SVG saknar både viewBox och width — skalningsbarheten blir odefinierad.")
        report.ok(f"SVG är välformad text ({len(text):,} tecken).")

    if fmt == "dxf":
        pairs = [ln.strip() for ln in text.replace("\r\n", "\n").split("\n")]
        nonempty = [ln for ln in pairs if ln]
        if len(nonempty) % 2 != 0:
            report.fail("DXF har ett udda antal gruppkodsrader — gruppkoder kommer i par.")
        else:
            report.ok(f"DXF har {len(nonempty) // 2:,} gruppkodspar.")
        if "EOF" not in nonempty[-5:]:
            report.warn("DXF saknar avslutande EOF-markör i slutet av filen.")


def check_iges(report: Report, data: bytes) -> None:
    text = data.decode("latin-1", errors="replace")
    lines = [ln for ln in text.replace("\r\n", "\n").split("\n") if ln]
    if not lines:
        report.fail("IGES-filen är tom.")
        return
    bad = [i + 1 for i, ln in enumerate(lines) if len(ln.rstrip()) > 80]
    if bad:
        report.fail(f"{len(bad)} IGES-post(er) är längre än 80 tecken "
                    f"(första: rad {bad[0]}). IGES kräver exakt 80 tecken per post.")
    else:
        report.ok(f"IGES: {len(lines):,} poster, alla ≤ 80 tecken.")


def verify(path: Path, expect_diameter: float | None, expect_triangles: int | None) -> Report:
    report = Report(path)
    if not path.exists():
        report.fail("Filen finns inte.")
        return report

    data = path.read_bytes()
    if not data:
        report.fail("Filen är tom (0 byte).")
        return report

    fmt = check_magic(report, data)
    if fmt == "stl":
        check_stl(report, data, expect_diameter, expect_triangles)
    elif fmt == "3mf":
        check_3mf(report, data)
    elif fmt in ("svg", "dxf"):
        check_text2d(report, data, fmt)
    elif fmt == "iges":
        check_iges(report, data)
    elif fmt == "step":
        if re.search(rb"#\[I@[0-9a-fA-F]+", data):
            report.fail("STEP contains JVM array identities instead of numeric entity references.")
        else:
            report.ok(f"STEP-struktur OK ({len(data):,} byte).")

    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Verifiera GearForge-exporter.")
    parser.add_argument("files", nargs="+", help="Filer eller globmönster.")
    parser.add_argument("--expect-diameter", type=float, default=None,
                        help="Förväntad ytterdiameter i mm (STL).")
    parser.add_argument("--expect-triangles", type=int, default=None,
                        help="Förväntat triangelantal (STL).")
    args = parser.parse_args()

    paths: list[Path] = []
    for pattern in args.files:
        hits = sorted(Path(p) for p in glob.glob(pattern))
        if not hits:
            hits = [Path(pattern)]
        paths.extend(hits)

    reports = [verify(p, args.expect_diameter, args.expect_triangles) for p in paths]
    for r in reports:
        r.dump()

    failed = [r for r in reports if r.failed]
    warned = [r for r in reports if not r.failed and any(s == SEVERITY_WARN for s, _ in r.lines)]

    print(f"\n{len(reports)} fil(er): {len(reports) - len(failed) - len(warned)} OK, "
          f"{len(warned)} med varningar, {len(failed)} med fel.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
