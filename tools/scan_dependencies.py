"""Run checksum-pinned official OSV Scanner against the resolved Gradle inventory."""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import urllib.request


VERSION = "2.6.0"
ASSETS = {
    "Windows": ("osv-scanner_windows_amd64.exe", "e0ed7644118b717b028c249ee9d3515024e55e8510747ca08906eb96765354d6"),
    "Linux": ("osv-scanner_linux_amd64", "ca69b3d3cd08f889a49dc0a383122f71cc528b83803671df5fd874d97485b108"),
}


def scanner_binary(cache, supplied=None):
    if platform.machine().lower() not in ("amd64", "x86_64") or platform.system() not in ASSETS:
        raise ValueError("Only checksum-pinned Windows/Linux x86_64 OSV binaries are supported")
    asset, expected = ASSETS[platform.system()]
    binary = supplied or cache / f"{VERSION}-{asset}"
    if not binary.is_file():
        if supplied:
            raise ValueError(f"Scanner does not exist: {supplied}")
        cache.mkdir(parents=True, exist_ok=True)
        url = f"https://github.com/google/osv-scanner/releases/download/v{VERSION}/{asset}"
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError("Downloaded OSV binary checksum mismatch")
        binary.write_bytes(data)
    if hashlib.sha256(binary.read_bytes()).hexdigest() != expected:
        raise ValueError("OSV binary checksum mismatch")
    if platform.system() == "Linux":
        binary.chmod(0o755)
    return binary.resolve()


def summarize(inventory, result, scanner_exit):
    if scanner_exit not in (0, 1):
        raise ValueError(f"OSV execution/query failed with exit {scanner_exit}")
    components = inventory["components"]
    if inventory.get("bomFormat") != "CycloneDX" or not components:
        raise ValueError("Expected a nonempty CycloneDX inventory")
    expected = Counter((item["name"], item["version"], "Maven") for item in components)
    packages = [package for source in result["results"] for package in source["packages"]]
    observed = Counter((item["package"]["name"], item["package"]["version"], item["package"]["ecosystem"])
                       for item in packages)
    if observed != expected:
        raise ValueError(f"Incomplete or mismatched OSV coverage: expected {sum(expected.values())}, observed {sum(observed.values())}")
    findings = []
    advisory_ids = set()
    for package in packages:
        advisories = package.get("vulnerabilities", [])
        if not advisories:
            continue
        matches = [item for item in components if item["name"] == package["package"]["name"]
                   and item["version"] == package["package"]["version"]]
        if len(matches) != 1:
            raise ValueError("Ambiguous Maven coordinates in OSV output")
        component = matches[0]
        scopes = {item["name"]: item["value"].splitlines() for item in component["properties"]}
        details = []
        for advisory in advisories:
            advisory_ids.add(advisory["id"])
            coordinate = f"{component['group']}:{component['name']}"
            fixed = sorted({event["fixed"] for affected in advisory.get("affected", [])
                            if affected["package"]["name"] == coordinate
                            for affected_range in affected.get("ranges", [])
                            for event in affected_range["events"] if "fixed" in event})
            details.append({"id": advisory["id"], "summary": advisory.get("summary", ""),
                            "aliases": advisory.get("aliases", []), "fixed_versions": fixed})
        findings.append({"coordinate": f"{component['group']}:{component['name']}:{component['version']}",
                         "configurations": scopes["gearforge:configurations"],
                         "direct_configurations": scopes["gearforge:direct-configurations"],
                         "advisories": details})
    if scanner_exit != (1 if findings else 0):
        raise ValueError("OSV exit status disagrees with its findings")
    return {"status": "vulnerable" if findings else "clean", "scanned_packages": len(packages),
            "affected_packages": len(findings), "unique_advisories": len(advisory_ids),
            "findings": findings}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, default=Path("build/reports/dependencies/resolved.cdx.json"))
    parser.add_argument("--output", type=Path, default=Path("build/reports/security/osv-results.json"))
    parser.add_argument("--scanner", type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    report = {"status": "error", "scanner_version": VERSION, "scanned_packages": None,
              "timestamp_utc": datetime.now(timezone.utc).isoformat()}
    exit_code = 2
    try:
        contents = args.inventory.read_bytes()
        report["inventory_sha256"] = hashlib.sha256(contents).hexdigest()
        inventory = json.loads(contents)
        binary = scanner_binary(Path("build/tools/osv-scanner"), args.scanner)
        report["scanner_sha256"] = hashlib.sha256(binary.read_bytes()).hexdigest()
        with tempfile.TemporaryDirectory(prefix="gearforge-osv-") as directory:
            shutil.copyfile(args.inventory, Path(directory) / "resolved.cdx.json")
            process = subprocess.run(
                [str(binary), "scan", "source", "--no-ignore", "--no-resolve", "--format", "json",
                 "--all-packages", "--all-vulns", "--output-file", str(args.output.resolve()), directory],
                check=False, timeout=600,
            )
        report["scanner_exit"] = process.returncode
        if process.returncode not in (0, 1):
            raise ValueError(f"OSV execution/query failed with exit {process.returncode}")
        result = json.loads(args.output.read_text(encoding="utf-8"))
        report.update(summarize(inventory, result, process.returncode))
        exit_code = 1 if report["status"] == "vulnerable" else 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        report["error"] = str(error)
        print(f"Security scan incomplete: {error}", file=sys.stderr)
    summary_path = args.output.with_suffix(".summary.json")
    summary_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "findings"}, indent=2))
    print(f"Summary: {summary_path}")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())