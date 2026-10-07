#!/usr/bin/env python3
"""Fail when user-facing text is hardcoded instead of going through ``I18n.t``.

Why this exists
---------------
A hardcoded string compiles, passes lint, and shows up as English text in the Swedish UI — the
failure mode is invisible until someone reads the app in the other language. It has already
happened once in this project, which is why the rule lives in the contributor instructions; this
script is what makes the rule checkable instead of aspirational.

What it scans
-------------
``android/src/main/java`` for the ways a literal reaches a screen:

* ``Text("...")``            — visible text
* ``contentDescription = ``  — what a screen reader announces
* ``stateDescription = ``    — the value a screen reader announces for a control

The literal is read with brace-aware interpolation handling, so ``Text("${a} of ${b}")`` is one
string and not a fragment cut short at the first inner quote. Interpolations are then removed and
the remainder has to contain a letter to count: ``"\\u2022 "`` (a bullet), ``"\\u00B0"`` (a degree
sign) and ``"\\u2014"`` (an em dash) read the same in both languages and are not text, while
``" view"`` inside ``"${it.name.lowercase()} view"`` is.

Allowlist
---------
``ALLOWLIST`` holds the hits that are deliberate, each with the reason. It is keyed by file name and
the literal, so moving the string to another file surfaces it again rather than silently inheriting
an exemption. Run with ``--list-allowlist`` to print the current entries.

Exit code 0 when every hit is allowlisted, 1 otherwise.
"""

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCE_ROOT = REPO_ROOT / "android" / "src" / "main" / "java"

MARKERS = (
    ("visible text", re.compile(r"\bText\(\s*")),
    ("content description", re.compile(r"contentDescription\s*=\s*")),
    ("state description", re.compile(r"stateDescription\s*=\s*")),
)

# (file name, literal) -> reason. Kept short: this is a decision record, not an essay.
# The two ViewportGizmo entries (item E1) were removed on 2026-10-01 when the gizmo's name, state and
# actions moved to I18n; an empty allowlist is the goal, not a gap.
ALLOWLIST: dict[tuple[str, str], str] = {}

LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')


def read_literal(line: str, start: int) -> tuple[str, int] | None:
    """Reads the Kotlin string literal that begins at or after [start] on [line].

    Returns the literal's content (interpolations included) and the index of its opening quote, or
    None when no literal begins on the line. A quote inside ``${...}`` does not end the literal,
    which is what stops ``Text("${a} of ${b}")`` from being reported as only its first fragment.
    """
    quote = line.find('"', start)
    if quote < 0:
        return None
    index = quote + 1
    out: list[str] = []
    depth = 0
    while index < len(line):
        char = line[index]
        if char == "\\":
            out.append(line[index : index + 2])
            index += 2
            continue
        if char == "$" and index + 1 < len(line) and line[index + 1] == "{":
            depth += 1
            out.append("${")
            index += 2
            continue
        if depth > 0:
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
            out.append(char)
            index += 1
            continue
        if char == '"':
            return "".join(out), quote
        out.append(char)
        index += 1
    return None


LETTER = re.compile(r"[^\W\d_]", re.UNICODE)


def strip_interpolation(literal: str) -> str:
    """Removes ``${...}`` (brace-aware) and ``$name`` from a literal, leaving only its own text.

    Both forms have to go: ``"$name — text"`` interpolates the same way ``"${name} — text"`` does,
    and leaving ``name`` behind would report the *variable* as hardcoded English.
    """
    out: list[str] = []
    index = 0
    while index < len(literal):
        char = literal[index]
        if char == "\\":
            out.append(literal[index : index + 2])
            index += 2
            continue
        if char == "$" and index + 1 < len(literal) and literal[index + 1] == "{":
            depth = 1
            index += 2
            while index < len(literal) and depth > 0:
                if literal[index] == "{":
                    depth += 1
                elif literal[index] == "}":
                    depth -= 1
                index += 1
            out.append(" ")
            continue
        if char == "$" and index + 1 < len(literal) and (literal[index + 1].isalpha() or literal[index + 1] == "_"):
            index += 2
            while index < len(literal) and (literal[index].isalnum() or literal[index] == "_"):
                index += 1
            out.append(" ")
            continue
        out.append(char)
        index += 1
    return "".join(out)


def is_text(literal: str) -> bool:
    """True when the literal carries a letter a user could read, interpolation removed.

    A degree sign or a bullet is not language; a word is. That is the line between "decorative
    punctuation" and "English that will look English in the Swedish UI".
    """
    without_interpolation = strip_interpolation(literal)
    without_escapes = re.sub(r"\\u[0-9A-Fa-f]{4}", "", without_interpolation)
    without_escapes = re.sub(r'\\[ntr"\\\']', " ", without_escapes)
    return LETTER.search(without_escapes) is not None


def scan() -> list[tuple[Path, int, str, str]]:
    hits: list[tuple[Path, int, str, str]] = []
    for path in sorted(SOURCE_ROOT.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        for number, line in enumerate(text.splitlines(), start=1):
            stripped = line.lstrip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue  # a comment is not user-facing text
            for label, marker in MARKERS:
                match = marker.search(line)
                if match is None:
                    continue
                # Between the marker and the literal sits the expression that produces the text. If
                # that expression calls I18n, the literal is a catalogue key, not hardcoded text —
                # this is what keeps ``Text(I18n.t(lang, "reset_view"))`` from being a hit.
                read = read_literal(line, match.end())
                if read is None or "I18n." in line[match.end() : read[1]]:
                    continue
                if not is_text(read[0]):
                    continue
                hits.append((path, number, label, read[0]))
    return hits


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list-allowlist", action="store_true", help="print the allowlist and exit")
    args = parser.parse_args()

    if args.list_allowlist:
        if not ALLOWLIST:
            print("ALLOWLIST is empty")
            return 0
        for (name, literal), reason in sorted(ALLOWLIST.items()):
            print(f"{name}: {literal!r}\n    {reason}")
        return 0

    hits = scan()
    unexpected = [hit for hit in hits if (hit[0].name, hit[3]) not in ALLOWLIST]
    allowed = len(hits) - len(unexpected)

    for path, number, label, literal in unexpected:
        relative = path.relative_to(REPO_ROOT)
        print(f"{relative}:{number}: {label} is hardcoded: {literal!r}")

    print(f"LITERALS total={len(hits)} allowlisted={allowed} unexpected={len(unexpected)}")
    if unexpected:
        print("Every user-facing string must go through I18n.t(lang, key), in both languages.")
        return 1
    print("LITERALS OK - no hardcoded user-facing text outside the allowlist.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
