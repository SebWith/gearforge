#!/usr/bin/env python3
"""Statisk konsistenskontroll av GearParams <-> SavedConfigs-serialiseringen.

`GearParams` har ~95 falt. `SavedConfigs.toJson`/`fromJson` skriver och laser dem for
hand. Tva felklasser uppstar dar, och bada ar TYSTA:

  1. Ett falt som aldrig skrivs -> anvandarens installning tappas vid "
     sparad konfiguration" och vid processdeath-aterstallning. Ingen varning.
  2. Ett falt vars standardvarde i `fromJson` skiljer sig fran standardvardet i
     `GearParams` -> en GAMMAL sparad konfiguration far tyst andra geometri nar
     nyckeln saknas i JSON:en. Detta ar den farligaste klassen: det ser ut som en
     geometribugg langt senare.
  3. En nyckel som skrivs men aldrig lass -> vardet kastas utan att nagon markar det.

Kontrollerar ocksa att varje falt som skrivs aven lases (och tvartom).

Exit-kod 0 = OK, 1 = avvikelse.

Anvandning:
    py tools/check_persistence.py
    py tools/check_persistence.py --quiet
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MODEL = REPO_ROOT / "core/src/main/java/com/gearforge/core/GearModel.kt"
CONFIGS = REPO_ROOT / "android/src/main/java/com/gearforge/app/SavedConfigs.kt"

# val name: Type = default       (default ar allt fram till komma pa samma niva)
CTOR_PARAM = re.compile(r"^\s*val\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([A-Za-z_][A-Za-z0-9_<>?,\s]*?)\s*=\s*(.+?),?\s*$")
# put("key", p.field)  |  put("key", p.bore.field)
TO_JSON = re.compile(r'put\("([^"]+)",\s*p\.([A-Za-z0-9_.]+)\)')
# field = o.optDouble("key", default)   och   field = SomeEnum.valueOf(o.optString("key", d))
# Den omslutande valueOf(...) maste tillatas, annars missas enum-falten helt.
FROM_JSON = re.compile(
    r'([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?:[A-Za-z_][A-Za-z0-9_.]*\.valueOf\(\s*)?'
    r'o\.opt(?:Double|Int|String|Boolean)\(\s*"([^"]+)"\s*(?:,\s*(.*?))?\)',
    re.S,
)
# Alla nycklar som lases, oavsett sammanhang. Anvands for nyckel-niva-kontrollen.
READ_KEY = re.compile(r'o\.opt(?:Double|Int|String|Boolean)\(\s*"([^"]+)"')

# Falt som ar inneslutna objekt (deras delfalt persisteras separat).
NESTED_OBJECTS = {"bore"}
# Falt som trader in i en nästlad struktur (BoreSpec) i fromJson
NESTED_FIELD = re.compile(r'^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?:BoreType|ToothProfile|PrecisionLevel|UnitSystem)\.')

# Falt som medvetet inte serialiseras, med skal. Nyckel = faltnamn.
INTENTIONAL = {
    "stage2HelixAngleDeg": "reserverat falt; coerced() tvingar det alltid till 0.0",
}


def strip_comments(line: str) -> str:
    """Tar bort // -kommentar utan att rora // inuti en strang."""
    out = []
    in_str = False
    i = 0
    while i < len(line):
        ch = line[i]
        if ch == '"':
            in_str = not in_str
            out.append(ch)
        elif not in_str and ch == "/" and i + 1 < len(line) and line[i + 1] == "/":
            break
        else:
            out.append(ch)
        i += 1
    return "".join(out)


def parse_gear_params(text: str) -> dict[str, str]:
    """Returnerar {faltnamn: standardvarde som strang} for data class GearParams."""
    start = text.index("data class GearParams(")
    i = start + len("data class GearParams(")
    depth = 1
    end = i
    while i < len(text) and depth > 0:
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
        i += 1
    end = i - 1
    body = text[start:end]

    params: dict[str, str] = {}
    for raw in body.split("\n"):
        line = strip_comments(raw)
        if "val " not in line:
            continue
        m = CTOR_PARAM.match(line)
        if m:
            params[m.group(1)] = m.group(3).strip().rstrip(",")
    return params


def parse_to_json(text: str) -> tuple[dict[str, str], set[str]]:
    """Returnerar ({faltnamn: json-nyckel}, alla skrivna json-nycklar).

    Hanterar tre former:
      put("key", p.field)            -> GearParams-falt
      put("key", p.field.name)       -> GearParams-falt (enum); suffixet .name ignoreras
      put("key", p.bore.field)       -> nastlat falt, inte ett GearParams-falt
    """
    field_to_key: dict[str, str] = {}
    all_keys: set[str] = set()
    for key, path in TO_JSON.findall(text):
        all_keys.add(key)
        parts = path.split(".")
        if parts[0] == "bore":
            continue  # nastlat i BoreSpec, hanteras separat
        field_to_key[parts[0]] = key
    # toothOverrides skrivs fran en lokal variabel, inte fran p.*
    all_keys.add("toothOverrides")
    return field_to_key, all_keys


def parse_from_json(text: str) -> dict[str, tuple[str, str | None]]:
    """Returnerar {faltnamn: (json-nyckel, standardvarde eller None)}."""
    out: dict[str, tuple[str, str | None]] = {}
    for field, key, default in FROM_JSON.findall(text):
        out[field] = (key, default.strip() if default else None)
    return out


def normalise(value: str | None) -> str | None:
    """Gor Kotlin-standardvarden jamforbara.

    Hanterar:
      1.0 == 1.0            (numeriska)
      "Steel" == Steel      (strangar)
      GearType.SPUR == "SPUR"  (kvalificerade enum-varden)
    """
    if value is None:
        return None
    v = value.strip().rstrip(",").strip()
    if len(v) >= 2 and v[0] == '"' and v[-1] == '"':
        v = v[1:-1]
    # Kvalificerad enum: GearType.SPUR -> SPUR
    qualified = re.match(r"^[A-Z][A-Za-z0-9_]*\.([A-Z][A-Z0-9_]*)$", v)
    if qualified:
        return qualified.group(1)
    if v.endswith(".0"):
        try:
            return str(float(v))
        except ValueError:
            pass
    try:
        return str(float(v)).rstrip("0").rstrip(".") if "." in v else str(int(v))
    except ValueError:
        return v


def main() -> int:
    parser = argparse.ArgumentParser(description="Kontrollera GearParams <-> SavedConfigs")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    for path in (MODEL, CONFIGS):
        if not path.exists():
            print(f"FEL: hittar inte {path}")
            return 1

    model_text = MODEL.read_text(encoding="utf-8")
    cfg_text = CONFIGS.read_text(encoding="utf-8")

    params = parse_gear_params(model_text)
    written, written_keys = parse_to_json(cfg_text)
    read = parse_from_json(cfg_text)
    read_keys = set(READ_KEY.findall(cfg_text))

    errors: list[str] = []
    warnings: list[str] = []

    # --- 1. Falt som inte persisteras alls ------------------------------------
    not_persisted = [
        f for f in params
        if f not in written and f != "toothOverrides" and f not in NESTED_OBJECTS
    ]
    for f in sorted(not_persisted):
        if f in INTENTIONAL:
            warnings.append(f"INTE PERSISTERAT (avsiktligt): {f} — {INTENTIONAL[f]}")
        else:
            errors.append(
                f"INTE PERSISTERAT: '{f}' (standard {params[f]}) skrivs inte av toJson "
                f"— vardet tappas vid sparad konfiguration och processdeath"
            )

    # --- 2. JSON-nycklar som skrivs men aldrig lases -------------------------
    # Jamfor pa NYCKELNIVA, inte faltniva: ett falt kan skrivas under en annan
    # nyckel an det lases tillbaka som.
    for key in sorted(written_keys - read_keys - {"toothOverrides"}):
        errors.append(
            f"SKRIVS MEN LÄSES ALDRIG: nyckeln '{key}' sparas men fromJson laser den inte "
            f"— vardet kastas utan att nagon markar det"
        )

    # --- 2b. json-nycklar som lases men aldrig skrivs ------------------------
    nested_bore_keys = {
        "boreType", "boreDiameter", "dCut", "keyW", "keyD", "hex", "square",
        "keywayStandard", "dCutSecondFlat",
    }
    for key in sorted(read_keys - written_keys - nested_bore_keys - {"toothOverrides"}):
        warnings.append(
            f"LÄSES MEN SKRIVS ALDRIG: nyckeln '{key}' har en fallback men skrivs aldrig "
            f"— fallbacken ar alltid i kraft"
        )

    # --- 3. Standardvarden som glidit isar -----------------------------------
    for f in sorted(set(params) & set(read)):
        ctor_default = normalise(params[f])
        json_default = normalise(read[f][1])
        if json_default is None:
            continue  # ingen fallback -> ingen drift
        if ctor_default is None or ctor_default.startswith(("empty", "BoreSpec", "mutable")):
            continue
        if ctor_default != json_default:
            errors.append(
                f"STANDARDVÄRDE SKILJER: '{f}' — GearParams={params[f]} "
                f"men fromJson faller tillbaka pa {read[f][1]} (nyckel '{read[f][0]}'). "
                f"En gammal sparad konfiguration far da tyst andra geometri."
            )

    if not args.quiet:
        print(f"GearParams-falt: {len(params)}")
        print(f"Persisterade falt: {len(written)}   skrivna nycklar: {len(written_keys)}   lästa nycklar: {len(read_keys)}")

    for w in warnings:
        print(f"  [INFO]  {w}")
    for e in errors:
        print(f"  [FEL]   {e}")

    if errors:
        print(f"\n{len(errors)} fel, {len(warnings)} noteringar.")
        return 1
    if not args.quiet:
        print(f"\nOK - alla falt persisteras och standardvardena stammer ({len(warnings)} noteringar).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
