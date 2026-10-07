#!/usr/bin/env python3
"""Accessibility audit of a dumped Android UI hierarchy, and of the Compose source.

Three inputs, one verdict each (exit 0 = clean, 1 = findings, 2 = unreadable input):

  dump      the XML produced by `adb shell uiautomator dump`
              * clickable nodes with no accessible label (screen reader says "button")
              * touch targets smaller than 48 dp (the Material minimum)
  --tree    the accessibility-node JSONL written by build/audit/a11y-20261001/tree/A11yTree.java,
            which carries what uiautomator's XML drops (stateDescription, heading, error, character
            locations):
              * actionable nodes without a spoken name, text fields without a name
              * targets under 48 dp
              * text whose characters are clipped out of view (font scale)
  --source  the Kotlin under android/src/main/java, for the patterns the 2026-10-01 audit fixed:
              * FilterChip outside SelectChip (selected state shown only as a 1.1:1 fill)
              * dialog titles that are not headings (use DialogTitle)
              * icon-only buttons whose icon has contentDescription = null
              * text fields with neither a label nor a contentDescription
              * a Switch with its own callback (announced without the row's label)

Output is ASCII on purpose: the Windows console is cp1252 and a stray character would
turn a passing audit into a traceback.

Usage:
    py tools/a11y_audit.py build/gf.xml
    py tools/a11y_audit.py build/gf.xml --density 2.75
    py tools/a11y_audit.py --tree runs/landing.a11y.jsonl --density 2.75
    py tools/a11y_audit.py --source android/src/main/java
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
MIN_DP = 48.0


def bounds_of(node: ET.Element) -> tuple[int, int, int, int] | None:
    m = BOUNDS.match(node.get("bounds", ""))
    if not m:
        return None
    x1, y1, x2, y2 = (int(g) for g in m.groups())
    return x1, y1, x2, y2


def label_of(node: ET.Element) -> str:
    text = (node.get("text") or "").strip()
    desc = (node.get("content-desc") or "").strip()
    if text and desc:
        return f"{text} / {desc}"
    return text or desc


def effective_label(node: ET.Element) -> str:
    """The label a screen reader actually gets, descendants included.

    A Compose `Modifier.clickable { Text(...) }` often reports its text on a CHILD node, so
    reading only the clickable node's own attributes flags every such container as unlabelled.
    Measured 2026-09-20: 12 of 12 clickable nodes were reported unlabelled before the subtree
    was taken into account - a false positive on the entire screen. The union of the subtree
    is what TalkBack announces.
    """
    parts = []
    own = label_of(node)
    if own:
        parts.append(own)
    for child in node.iter():
        if child is node:
            continue
        child_label = label_of(child)
        if child_label and child_label not in parts:
            parts.append(child_label)
    return " / ".join(parts)


def audit(root: ET.Element, density: float) -> tuple[int, list[str], list[str]]:
    """Returns (clickable count, unlabelled, too-small) for one dumped screen."""
    min_px = MIN_DP * density
    clickable = 0
    unlabelled: list[str] = []
    small: list[str] = []
    parents = {child: parent for parent in root.iter() for child in parent}
    first = next(root.iter("node"), None)
    screen = bounds_of(first) if first is not None else None
    for node in root.iter("node"):
        if node.get("clickable") != "true":
            continue
        b = bounds_of(node)
        if b is None:
            continue
        clickable += 1
        if cut_by_scroll_edge(node, b, parents):
            continue
        x1, y1, x2, y2 = b
        w, h = x2 - x1, y2 - y1
        label = effective_label(node).encode("ascii", "backslashreplace").decode("ascii")
        where = f"{node.get('class', '?').split('.')[-1]} at [{x1},{y1}][{x2},{y2}]"
        if not label:
            unlabelled.append(where)
        if (w < min_px or h < min_px) and not cut_by_window_edge(b, screen):
            small.append(
                f"{where} {w}x{h}px ({w / density:.0f}x{h / density:.0f}dp) label='{label}'"
            )
    return clickable, unlabelled, small


def shares_edge(b: tuple[int, int, int, int], frame: tuple[int, int, int, int]) -> bool:
    """b is cut by frame: it lies on one of its edges, or sticks out of it.

    Both are what a node half scrolled out of view looks like. Compose reports a button's expanded touch
    bounds unclipped, so a 48 dp button under the bottom edge of a list can stick out of the list.
    """
    x1, y1, x2, y2 = b
    fx1, fy1, fx2, fy2 = frame
    if y1 < fy1 - 2 or y2 > fy2 + 2 or x1 < fx1 - 2 or x2 > fx2 + 2:
        return True
    on_edge = abs(y1 - fy1) <= 2 or abs(y2 - fy2) <= 2 or abs(x1 - fx1) <= 2 or abs(x2 - fx2) <= 2
    return on_edge and ((x2 - x1) < (fx2 - fx1) or (y2 - y1) < (fy2 - fy1))


def cut_by_window_edge(b: tuple[int, int, int, int], screen: tuple[int, int, int, int] | None) -> bool:
    """A row half under the window's top or bottom edge is dumped with only its visible part.

    The last saved file in the Saved files sheet sits under the bottom of the screen and reported 41 dp
    (2026-10-01). A control really placed flush against the window edge would be skipped too; none is.
    """
    if screen is None or tuple(b) == tuple(screen):
        return False
    return abs(b[3] - screen[3]) <= 2 or abs(b[1] - screen[1]) <= 2


def cut_by_scroll_edge(node: ET.Element, b: tuple[int, int, int, int], parents: dict) -> bool:
    """A node cut by the edge of a scrolling container is dumped with only its visible part.

    A chip half scrolled out of a horizontal row reports 12 dp of width and none of its text, which is a
    fact about the scroll position, not about the chip (measured 2026-10-01 in the Settings dialog).
    """
    p = parents.get(node)
    while p is not None:
        if p.get("scrollable") == "true":
            frame = bounds_of(p)
            if frame is not None and shares_edge(b, frame):
                return True
        p = parents.get(p)
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description="A11y audit of a uiautomator dump, an A11yTree dump or the source")
    parser.add_argument("dump", nargs="?")
    parser.add_argument("--density", type=float, default=2.75,
                        help="screen density (dpi/160). Pixel 7 AVD is 2.75 => 1080 px = 393 dp")
    parser.add_argument("--tree", nargs="+", help="A11yTree JSONL dump(s)")
    parser.add_argument("--source", help="Kotlin source root to lint (android/src/main/java)")
    args = parser.parse_args()

    if args.source:
        return main_source(Path(args.source))
    if args.tree:
        return main_tree([Path(p) for p in args.tree], args.density)
    if not args.dump:
        parser.error("give a uiautomator dump, --tree or --source")

    try:
        root = ET.parse(args.dump).getroot()
    except (OSError, ET.ParseError) as exc:
        print(f"FEL: kan inte lasa {args.dump}: {exc}")
        return 2

    min_px = MIN_DP * args.density
    clickable, unlabelled, small = audit(root, args.density)

    print(f"dump={args.dump} density={args.density} min_touch={min_px:.0f}px ({MIN_DP:.0f}dp)")
    print(f"clickable_nodes={clickable}")
    print(f"unlabelled_clickable={len(unlabelled)}")
    for u in unlabelled:
        print(f"  [A11Y] ingen etikett: {u}")
    print(f"small_touch_targets={len(small)}")
    for s in small:
        print(f"  [A11Y] for litet tryckmal: {s}")

    if unlabelled or small:
        print("A11Y: avvikelser hittade")
        return 1
    print("A11Y OK: alla klickbara noder har etikett och tillrackligt stora tryckmal")
    return 0


# ---- accessibility-node tree (A11yTree JSONL) -------------------------------------------------

def load_tree(text: str) -> list[dict]:
    nodes = []
    for line in text.splitlines():
        if not line.startswith("{"):
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if obj.get("kind") == "node":
            nodes.append(obj)
    return nodes


def _actionable(n: dict) -> bool:
    return bool(n.get("clickable") or n.get("checkable") or n.get("editable") or n.get("longClickable"))


def spoken_name(nodes: list[dict], n: dict) -> str:
    """Own text/desc plus the text of non-actionable descendants: what TalkBack composes for n."""
    parts = [x for x in (n.get("desc"), n.get("text")) if x]
    prefix = n["p"] + "."
    for m in nodes:
        if m["w"] != n["w"] or not m["p"].startswith(prefix) or _actionable(m):
            continue
        for x in (m.get("desc"), m.get("text")):
            if x and x not in parts:
                parts.append(x)
    return " / ".join(parts)


def audit_tree(nodes: list[dict], density: float) -> list[str]:
    min_px = MIN_DP * density - 1
    findings = []
    by_path = {(n["w"], n["p"]): n for n in nodes}

    def cut(n: dict) -> bool:
        p = n["p"]
        while "." in p:
            p = p.rsplit(".", 1)[0]
            a = by_path.get((n["w"], p))
            if a is not None and a.get("scrollable") and shares_edge(tuple(n["b"]), tuple(a["b"])):
                return True
        return False

    for n in nodes:
        if not n.get("visible") or cut(n):
            continue
        x1, y1, x2, y2 = n["b"]
        where = f"{n['cls'].split('.')[-1]} at [{x1},{y1}][{x2},{y2}]"
        name = spoken_name(nodes, n)
        if n.get("editable"):
            if not (n.get("desc") or n.get("hint") or name.replace(n.get("text") or "", "").strip(" /")):
                findings.append(f"text field without a name: {where}")
        elif _actionable(n) and not name:
            findings.append(f"no accessible name: {where}")
        if (n.get("clickable") or n.get("checkable") or n.get("editable")) and x2 > x1 and y2 > y1:
            if (x2 - x1) < min_px or (y2 - y1) < min_px:
                findings.append(
                    f"target {(x2 - x1) / density:.0f}x{(y2 - y1) / density:.0f}dp under 48dp: {where} "
                    f"'{name[:50]}'".encode("ascii", "backslashreplace").decode("ascii")
                )
        cl = n.get("charLoc")
        if isinstance(cl, dict) and (cl.get("hidden") or cl.get("partial")) and not alignment_artifact(n):
            text = (n.get("text") or "")[:50].encode("ascii", "backslashreplace").decode("ascii")
            findings.append(f"clipped text ({cl.get('hidden')} hidden, {cl.get('partial')} cut): {where} '{text}'")
    return findings


def alignment_artifact(n: dict) -> bool:
    """Centred or end-aligned text whose character boxes are reported shifted out of the node.

    Compose reports the boxes from a layout as wide as the incoming constraint while the node is only as
    wide as the text, so they move right by the alignment offset. Real clipping cuts a box at the edge
    (`partial`) or hides a whole line; a shift hides the tail and leaves the same empty space in front of
    the first glyph. Landing page, 2026-10-01: "Saved files" reported 10 of 10 boxes outside its node and
    has its full ink inside it.
    """
    cl = n.get("charLoc")
    if not isinstance(cl, dict) or not cl.get("hidden") or cl.get("partial"):
        return False
    text = n.get("text") or ""
    first = cl.get("firstHidden", -1)
    glyphs = sum(1 for c in text if not c.isspace())
    if first == 0:
        return cl["hidden"] == glyphs
    if first < 0 or not cl.get("ink") or sum(1 for c in text[first:] if not c.isspace()) != cl["hidden"]:
        return False
    ink_left, _, ink_right, _ = cl["ink"]
    visible = max(1, sum(1 for c in text[:first] if not c.isspace()))
    return ink_left - n["b"][0] >= 0.8 * cl["hidden"] * (ink_right - ink_left) / visible


def main_tree(paths: list[Path], density: float) -> int:
    total = 0
    for p in paths:
        try:
            nodes = load_tree(p.read_text(encoding="utf-8", errors="replace"))
        except OSError as exc:
            print(f"FEL: kan inte lasa {p}: {exc}")
            return 2
        findings = audit_tree(nodes, density)
        print(f"tree={p.name} nodes={len(nodes)} findings={len(findings)}")
        for f in findings:
            print(f"  [A11Y] {f}")
        total += len(findings)
    print("A11Y OK: tree clean" if total == 0 else f"A11Y: {total} avvikelser")
    return 1 if total else 0


# ---- Compose source lint -----------------------------------------------------------------------

_CALL = re.compile(r"\b(FilterChip|IconButton|FilledTonalIconButton|OutlinedTextField|TextField|Switch)\(")
_DIALOG_TITLE = re.compile(r"title\s*=\s*\{\s*Text\(")


def _skip_string(text: str, i: int) -> int:
    """Index just past the string literal opening at [i]; `${...}` templates may hold quotes of their own."""
    i += 1
    while i < len(text) and text[i] != '"':
        if text[i] == "\\":
            i += 2
        elif text.startswith("${", i):
            i = _match(text, i + 1, "{", "}") + 1
        else:
            i += 1
    return i + 1


def _match(text: str, start: int, open_ch: str, close_ch: str) -> int:
    """Index of the bracket closing the one at [start], skipping string literals and comments."""
    depth = 0
    i = start
    while i < len(text):
        c = text[i]
        if c == '"':
            i = _skip_string(text, i)
            continue
        if text.startswith("//", i):
            i = text.find("\n", i)
            if i < 0:
                return len(text) - 1
        elif c == open_ch:
            depth += 1
        elif c == close_ch:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(text) - 1


def lint_source(name: str, text: str) -> list[str]:
    """Findings for one Kotlin file, each as 'name:line: message'."""
    findings = []

    def line_of(i: int) -> int:
        return text.count("\n", 0, i) + 1

    for m in _DIALOG_TITLE.finditer(text):
        findings.append(f"{name}:{line_of(m.start())}: dialog title is not a heading - use DialogTitle(...)")
    for m in _CALL.finditer(text):
        kind = m.group(1)
        if text[max(0, m.start() - 4):m.start()].endswith("fun "):
            continue
        open_at = m.end() - 1
        close_at = _match(text, open_at, "(", ")")
        args = text[open_at:close_at + 1]
        rest = close_at + 1
        while rest < len(text) and text[rest] in " \t\r\n":
            rest += 1
        body = text[rest:_match(text, rest, "{", "}") + 1] if rest < len(text) and text[rest] == "{" else ""
        where = f"{name}:{line_of(m.start())}"
        if kind == "FilterChip" and name != "Controls.kt":
            findings.append(f"{where}: FilterChip outside SelectChip - its selected state would be colour only")
        if kind in ("IconButton", "FilledTonalIconButton"):
            content = args + body
            if "contentDescription = null" in content and not re.search(r"contentDescription\s*=\s*(?!null\b)\S", content):
                findings.append(f"{where}: icon-only {kind} without a name (contentDescription = null)")
        if kind in ("OutlinedTextField", "TextField"):
            if "label =" not in args and "contentDescription" not in args:
                findings.append(f"{where}: {kind} without a label or contentDescription")
        if kind == "Switch":
            cb = re.search(r"onCheckedChange\s*=\s*([^,)\n]+)", args)
            if cb and cb.group(1).strip() != "null":
                findings.append(f"{where}: Switch with its own callback - make the row toggleable, onCheckedChange = null")
    return findings


def main_source(root: Path) -> int:
    files = sorted(root.rglob("*.kt"))
    if not files:
        print(f"FEL: inga Kotlin-filer under {root}")
        return 2
    findings = []
    for f in files:
        findings += lint_source(f.name, f.read_text(encoding="utf-8"))
    for f in findings:
        print(f"  [A11Y] {f}")
    print(f"source files={len(files)} findings={len(findings)}")
    print("A11Y OK: source clean" if not findings else "A11Y: avvikelser hittade")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
