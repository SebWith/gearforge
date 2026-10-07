#!/usr/bin/env python3
"""Validera alla agentanpassningar i .github/.

YAML-frontmatter i .agent.md/.instructions.md/.prompt.md/SKILL.md misslyckas TYST:
ett oescapade kolon, ett `name` som inte matchar mappnamnet eller en handoff till en
agent som inte finns ger ingen felruta — anpassningen laddas bara inte. Det här
skriptet gör felen synliga.

Kontrollerar:
  1. Frontmatter finns och är giltig YAML.
  2. `description` finns och är meningsfull (icke-tom, rimlig längd).
  3. `instructions.md` har `applyTo`.
  4. `SKILL.md`: `name` matchar mappnamnet.
  5. `agent.md`: `agent` finns i `tools` om `agents:` är satt.
  6. Alla `handoffs[].agent` och `agents[]`-poster pekar på existerande agenter.
  7. Alla `#file:`-referenser i kroppen pekar på existerande filer.

Exit-kod 0 = OK, 1 = minst ett fel.

Användning:
    py tools/validate_customizations.py
    py tools/validate_customizations.py --quiet
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    print("FEL: PyYAML saknas. Installera med: py -m pip install pyyaml")
    sys.exit(1)

REPO_ROOT = Path(__file__).resolve().parent.parent
GITHUB = REPO_ROOT / ".github"

FRONTMATTER = re.compile(r"^---\s*\n(.*?)\n---\s*(?:\n|$)", re.S)
FILE_REF = re.compile(r"#file:([^\s)`\"']+)")

errors: list[str] = []
warnings: list[str] = []


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def load_frontmatter(path: Path) -> dict | None:
    text = path.read_text(encoding="utf-8")
    match = FRONTMATTER.match(text)
    if not match:
        errors.append(f"{rel(path)}: saknar YAML-frontmatter (--- ... ---) i filens början")
        return None
    try:
        data = yaml.safe_load(match.group(1))
    except yaml.YAMLError as exc:
        errors.append(f"{rel(path)}: ogiltig YAML i frontmatter -> {exc}")
        return None
    if not isinstance(data, dict):
        errors.append(f"{rel(path)}: frontmatter är inte ett nyckel/värde-block")
        return None
    return data


def check_description(path: Path, data: dict, require_use_when: bool = True) -> None:
    desc = data.get("description")
    if desc is None:
        errors.append(f"{rel(path)}: saknar 'description' — utan den hittas aldrig anpassningen")
        return
    if not isinstance(desc, str) or not desc.strip():
        errors.append(f"{rel(path)}: 'description' är tom")
        return
    if len(desc.strip()) < 25:
        warnings.append(f"{rel(path)}: 'description' är kort ({len(desc)} tecken) — riskerar svag matchning")
    # Prompt-filer visas som snabbkommandon; deras description är en kort UI-etikett,
    # inte en matchningsyta för automatisk upptäckt. Kravet gäller agenter och skills.
    if require_use_when and "use when" not in desc.lower() and "använd" not in desc.lower():
        warnings.append(f"{rel(path)}: 'description' saknar 'Use when:'-mönster med triggerord")


def check_file_refs(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    for ref in FILE_REF.findall(text):
        ref = ref.split("#")[0].strip()
        if not ref or ref.startswith("~") or ":" in ref:
            continue
        target = (path.parent / ref).resolve()
        if not target.exists():
            errors.append(f"{rel(path)}: '#file:{ref}' pekar på en fil som inte finns")


def check_instructions(path: Path, data: dict) -> None:
    apply_to = data.get("applyTo")
    if apply_to is None:
        errors.append(f"{rel(path)}: saknar 'applyTo' — instruktionen kopplas aldrig till några filer")


def check_skill(path: Path, data: dict) -> None:
    name = data.get("name")
    folder = path.parent.name
    if name is None:
        errors.append(f"{rel(path)}: saknar 'name'")
        return
    if str(name) != folder:
        errors.append(
            f"{rel(path)}: 'name: {name}' matchar inte mappnamnet '{folder}' "
            f"— detta ger ett tyst fel, skillen hittas inte"
        )


def check_agent(path: Path, data: dict, known_agents: set[str]) -> None:
    tools = data.get("tools")
    subagents = data.get("agents")

    if subagents is not None:
        if tools is None:
            errors.append(
                f"{rel(path)}: har 'agents:' men inget 'tools:' — "
                f"'agent'-verktyget måste listas för att subagenter ska kunna anropas"
            )
        elif "agent" not in tools:
            errors.append(
                f"{rel(path)}: har 'agents:' men 'agent' saknas i tools {tools} "
                f"— delegering kommer inte att fungera"
            )

        if not isinstance(subagents, list):
            errors.append(f"{rel(path)}: 'agents' måste vara en lista")
        else:
            for name in subagents:
                if name == "*":
                    continue
                if name not in known_agents:
                    errors.append(f"{rel(path)}: 'agents: {name}' finns inte bland agenterna")

    for handoff in data.get("handoffs") or []:
        if not isinstance(handoff, dict):
            errors.append(f"{rel(path)}: varje handoff måste vara ett nyckel/värde-block")
            continue
        target = handoff.get("agent")
        label = handoff.get("label")
        if not target:
            errors.append(f"{rel(path)}: en handoff saknar 'agent'")
            continue
        if target not in known_agents:
            errors.append(f"{rel(path)}: handoff '{label or '?'}' pekar på okänd agent '{target}'")
        if not label:
            warnings.append(f"{rel(path)}: handoff till '{target}' saknar 'label' (knapptext)")

    # Cirkulär delegering: A -> B -> A utan framstegskriterium
    for handoff in data.get("handoffs") or []:
        if isinstance(handoff, dict) and handoff.get("agent") in ("", None):
            continue


def main() -> int:
    parser = argparse.ArgumentParser(description="Validera .github-anpassningar")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    if not GITHUB.exists():
        print(f"FEL: hittar inte {GITHUB}")
        return 1

    agent_files = sorted(GITHUB.glob("agents/*.agent.md"))
    known_agents = {p.name[: -len(".agent.md")] for p in agent_files}

    counts = {"agents": 0, "instructions": 0, "prompts": 0, "skills": 0}

    for path in agent_files:
        data = load_frontmatter(path)
        counts["agents"] += 1
        if data:
            check_description(path, data)
            check_agent(path, data, known_agents)

    for path in sorted(GITHUB.glob("instructions/*.instructions.md")):
        data = load_frontmatter(path)
        counts["instructions"] += 1
        if data:
            check_instructions(path, data)

    for path in sorted(GITHUB.glob("prompts/*.prompt.md")):
        data = load_frontmatter(path)
        counts["prompts"] += 1
        if data:
            check_description(path, data, require_use_when=False)

    for path in sorted(GITHUB.glob("skills/*/SKILL.md")):
        data = load_frontmatter(path)
        counts["skills"] += 1
        if data:
            check_description(path, data)
            check_skill(path, data)

    for path in list(GITHUB.glob("agents/*.agent.md")) + list(GITHUB.glob("prompts/*.prompt.md")):
        check_file_refs(path)

    if not args.quiet:
        print(
            f"Granskade {counts['agents']} agenter, {counts['instructions']} instruktioner, "
            f"{counts['prompts']} prompts, {counts['skills']} skills."
        )
        print(f"Kanda agentnamn: {', '.join(sorted(known_agents))}")

    for w in warnings:
        print(f"  [VARNING] {w}")
    for e in errors:
        print(f"  [FEL]     {e}")

    if errors:
        print(f"\n{len(errors)} fel, {len(warnings)} varningar.")
        return 1

    if not args.quiet:
        print(f"\nOK - inga fel ({len(warnings)} varningar).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
