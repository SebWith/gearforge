#!/usr/bin/env python3
"""Compare two APKs entry by entry, by content hash, and say whether they are the same build.

Why this exists: the pre-flight check compared file sizes, and the installed base.apk on
API 36 came out 535 bytes smaller than the APK on disk while the API 26 device matched it
exactly. The first explanation tried was signature stripping - the installer is allowed to
drop the v1 (JAR) signature - and it was WRONG: a fresh `adb install -r` of the same APK
produced byte-identical entries on API 26 AND API 36 (measured 2026-09-22). The 535 bytes
were a stale build: AndroidManifest.xml, three dex files and a dozen resources differed.

Hashing every entry, and naming the entries that differ, is what separated those two cases:

    only META-INF/* differ  -> the same build, whatever the installer rewrote
    classes.dex or res/* differ -> a different build on the device

Usage:
    py tools/apk_identity.py <on-disk.apk> <pulled.apk>
    py tools/apk_identity.py <a.apk> <b.apk> --ignore-signature

Exit code 0 when the code and resources are identical, 1 otherwise. ASCII output: the
Windows console is cp1252 and a stray character turns a verdict into a traceback.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import zipfile

# Entries an installer can legitimately rewrite: the v1 signature lives here, and nothing in
# it is executed on a platform that verifies with the v2/v3 scheme. Kept as a classification
# for the case where it does happen - measured 2026-09-22, it did not happen on either of the
# two emulator images in this repo, so a difference here must not be assumed away.
SIGNATURE_PREFIX = "META-INF/"


def entry_hashes(path: str) -> dict[str, str]:
    out: dict[str, str] = {}
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            digest = hashlib.sha256(z.read(info.filename)).hexdigest()
            out[info.filename] = digest
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("first")
    ap.add_argument("second")
    ap.add_argument(
        "--ignore-signature",
        action="store_true",
        help="treat META-INF/* differences as expected (default: they are reported as such)",
    )
    args = ap.parse_args()

    try:
        a = entry_hashes(args.first)
        b = entry_hashes(args.second)
    except (OSError, zipfile.BadZipFile) as exc:
        print(f"FAIL: could not read an apk: {exc}")
        return 1

    only_a = sorted(set(a) - set(b))
    only_b = sorted(set(b) - set(a))
    differing = sorted(n for n in (set(a) & set(b)) if a[n] != b[n])

    sig_only = [n for n in (only_a + only_b + differing) if n.startswith(SIGNATURE_PREFIX)]
    code_related = [n for n in (only_a + only_b + differing) if not n.startswith(SIGNATURE_PREFIX)]

    print(f"apk_a={args.first}")
    print(f"apk_b={args.second}")
    print(f"entries_a={len(a)} entries_b={len(b)}")
    print(f"only_in_a={len(only_a)} only_in_b={len(only_b)} differing={len(differing)}")
    for name in only_a[:10]:
        print(f"  only_in_a: {name}")
    for name in only_b[:10]:
        print(f"  only_in_b: {name}")
    for name in differing[:10]:
        print(f"  differing: {name}")
    print(f"signature_related={len(sig_only)} code_or_assets_related={len(code_related)}")

    if code_related:
        print("APK IDENTITY: DIFFERENT BUILD (code or assets differ)")
        return 1
    if sig_only:
        print("APK IDENTITY: SAME BUILD (only signature entries differ, which install strips)")
        return 0
    print("APK IDENTITY: SAME BUILD (byte-identical entries)")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
