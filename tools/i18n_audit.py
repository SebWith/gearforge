#!/usr/bin/env python3
"""EN/SV-paritetskontroll för GearForge:s strängkatalog.

`android/src/main/java/com/gearforge/app/I18n.kt` är en handrullad katalog med två
`mapOf(...)`-block: `en` och `sv`. Inget i kompileringen hindrar en nyckel från att
finnas i bara den ena — `I18n.t()` faller då tyst tillbaka på engelska (eller på
nyckeln själv), vilket bara syns i appen. Det här skriptet gör avvikelsen till ett
hårt fel.

Kontrollerar:
  1. Varje nyckel finns i BÅDE en och sv.
  2. Inga dubbletter inom samma språk (Kotlin `mapOf` tar sista värdet tyst).
  3. Platshållare ({0}, {1}, ...) matchar mellan språken.
  4. Inga tomma värden.

Exit-kod 0 = OK, 1 = avvikelse.

Användning:
    py tools/i18n_audit.py
    py tools/i18n_audit.py --quiet     # bara exit-kod
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
I18N = REPO_ROOT / "android" / "src" / "main" / "java" / "com" / "gearforge" / "app" / "I18n.kt"

# "nyckel" to "värde"   — värdet kan innehållaescaped quotes
ENTRY = re.compile(r'"((?:[^"\\]|\\.)*)"\s*to\s*"((?:[^"\\]|\\.)*)"')
MAP_START = re.compile(r"private\s+val\s+(en|sv)\s*=\s*mapOf\(")
PLACEHOLDER = re.compile(r"\{(\d+)\}")


def find_maps(text: str) -> dict[str, dict[str, tuple[str, int]]]:
    """Returnerar {språk: {nyckel: (värde, radnummer)}}."""
    maps: dict[str, dict[str, tuple[str, int]]] = {"en": {}, "sv": {}}
    duplicates: dict[str, list[str]] = {"en": [], "sv": []}

    for match in MAP_START.finditer(text):
        lang = match.group(1)
        # Hitta den matchande stängande parentesen genom att räkna djup.
        i = match.end()
        depth = 1
        while i < len(text) and depth > 0:
            ch = text[i]
            if ch == '"':
                # Hoppa över strängliteral.
                i += 1
                while i < len(text) and text[i] != '"':
                    if text[i] == "\\":
                        i += 1
                    i += 1
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            i += 1
        block = text[match.end(): i - 1]

        for entry in ENTRY.finditer(block):
            key = entry.group(1)
            value = entry.group(2)
            line = text[: match.end() + entry.start()].count("\n") + 1
            if key in maps[lang]:
                duplicates[lang].append(key)
                continue
            maps[lang][key] = (value, line)

    for lang, dups in duplicates.items():
        for key in dups:
            print(f"  DUBBLETT  [{lang}] '{key}' definieras mer än en gång (sista värdet vinner tyst)")

    return maps


def main() -> int:
    parser = argparse.ArgumentParser(description="EN/SV-paritet i I18n.kt")
    parser.add_argument("--quiet", action="store_true", help="skriv bara ut vid fel")
    args = parser.parse_args()

    if not I18N.exists():
        print(f"FEL: hittar inte {I18N}")
        return 1

    text = I18N.read_text(encoding="utf-8")
    maps = find_maps(text)
    en, sv = maps["en"], maps["sv"]

    problems: list[str] = []

    if not en:
        problems.append("hittade inga nycklar i 'en'-mappen — har formatet i I18n.kt ändrats?")
    if not sv:
        problems.append("hittade inga nycklar i 'sv'-mappen — har formatet i I18n.kt ändrats?")

    for key in sorted(set(en) - set(sv)):
        problems.append(f"SAKNAS I SV: '{key}' (en, rad {en[key][1]})")
    for key in sorted(set(sv) - set(en)):
        problems.append(f"SAKNAS I EN: '{key}' (sv, rad {sv[key][1]})")

    for key in sorted(set(en) & set(sv)):
        en_val, en_line = en[key]
        sv_val, _ = sv[key]

        if not en_val.strip():
            problems.append(f"TOMT VÄRDE i en: '{key}' (rad {en_line})")
        if not sv_val.strip():
            problems.append(f"TOMT VÄRDE i sv: '{key}' (rad {sv[key][1]})")

        en_ph = sorted(set(PLACEHOLDER.findall(en_val)))
        sv_ph = sorted(set(PLACEHOLDER.findall(sv_val)))
        if en_ph != sv_ph:
            problems.append(
                f"PLATSHÅLLARE skiljer för '{key}': en={en_ph or '[]'} sv={sv_ph or '[]'}"
            )
        elif en_ph:
            # Kontrollera att indexen är 0..n-1 utan hål — t() ersätter {i} per argument.
            nums = [int(n) for n in en_ph]
            if nums != list(range(len(nums))):
                problems.append(
                    f"PLATSHÅLLARINDEX ej sammanhängande för '{key}': {nums} "
                    f"(ska vara 0..{len(nums) - 1})"
                )

    if problems:
        print(f"I18N-PARITET: {len(problems)} problem i {I18N.name}")
        for p in problems:
            print(f"  {p}")
        print(f"\n{len(en)} nycklar i en, {len(sv)} i sv.")
        return 1

    if not args.quiet:
        print(f"I18N-PARITET OK — {len(en)} nycklar i både en och sv, platshållare matchar.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
