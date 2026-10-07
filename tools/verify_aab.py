"""Fail-closed AAB verification. Run with --help for required expectations.

Requires JDK 17+ (java and jarsigner) and Google's bundletool-all-1.18.2.jar:
https://github.com/google/bundletool/releases/download/1.18.2/bundletool-all-1.18.2.jar
Its SHA-256 below is published on that release's GitHub asset metadata.

The certificate pin MUST come from a separately trusted upload-certificate source
(for example Play Console), not from the AAB being inspected. No default pin,
private key, password or keystore is used. Presence of an exact DEX string does
not prove that runtime code uses it; real Play/R8 testing remains separate.
"""
import argparse
import hashlib
import json
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

BUNDLETOOL_SHA256 = "378b5434cd1378bef6b2bc527b8c7f0ff2584b273830335bce54d6d0813c8584"
DEFAULT_BUNDLETOOL = Path(__file__).resolve().parents[1] / "build/tools/bundletool-all-1.18.2.jar"
TEST_PUBLISHER = b"3940256099942544"
ANDROID = "{http://schemas.android.com/apk/res/android}"

SIGNATURE_SOURCE = r"""
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.jar.JarFile;

class VerifyAabSignature {
    public static void main(String[] args) throws Exception {
        int covered = 0;
        try (var archive = new JarFile(args[0], true)) {
            var manifest = archive.getManifest();
            if (manifest == null) throw new SecurityException("Missing JAR signature manifest");
            for (var name : manifest.getEntries().keySet()) {
                if (archive.getJarEntry(name) == null)
                    throw new SecurityException("Missing signed entry: " + name);
            }
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                try (var input = archive.getInputStream(entry)) {
                    input.transferTo(OutputStream.nullOutputStream());
                }
                var name = entry.getName().toUpperCase(Locale.ROOT);
                if (name.matches("META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC)|SIG-[^/]+)"))
                    continue;
                var signers = entry.getCodeSigners();
                if (signers == null || signers.length != 1)
                    throw new SecurityException("Unsigned or multiple signers: " + entry.getName());
                var certificate = (X509Certificate) signers[0].getSignerCertPath().getCertificates().get(0);
                certificate.checkValidity();
                var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded()));
                if (!digest.equalsIgnoreCase(args[1]))
                    throw new SecurityException("Certificate SHA-256 differs from expected pin");
                covered++;
            }
        }
        if (covered == 0) throw new SecurityException("No signed payload entries");
        System.out.println(covered);
    }
}
"""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run_tool(command: list[str], allowed_codes: tuple[int, ...] = (0,)) -> str:
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                            errors="replace", stdin=subprocess.DEVNULL, timeout=120, check=False)
    require(result.returncode in allowed_codes,
            f"{Path(command[0]).name} failed ({result.returncode}): {result.stdout}\n{result.stderr}")
    return result.stdout


def verify_manifest(xml: str, expected: argparse.Namespace) -> None:
    root = ET.fromstring(xml)
    require(root.tag == "manifest", "Missing manifest root")
    for key, value in (("package", expected.expected_package),
                       (ANDROID + "versionCode", str(expected.expected_version_code)),
                       (ANDROID + "versionName", expected.expected_version_name)):
        require(root.get(key) == value, f"Manifest {key}: {root.get(key)!r}, expected {value!r}")
    require(root.get(ANDROID + "versionCodeMajor", "0") == "0", "Unexpected versionCodeMajor")
    applications = root.findall("application")
    require(len(applications) == 1, "Expected exactly one application")
    metadata = [node for node in applications[0].findall("meta-data")
                if node.get(ANDROID + "name") == "com.google.android.gms.ads.APPLICATION_ID"]
    require(len(metadata) == 1, "Expected exactly one application AdMob App ID metadata entry")
    require(metadata[0].get(ANDROID + "value") == expected.expected_app_id
            and metadata[0].get(ANDROID + "resource") is None, "AdMob App ID differs from expected value")


def dex_strings(data: bytes) -> set[bytes]:
    """Read string_ids/string_data, not byte substrings. Reject unsupported DEX layouts."""
    require(len(data) >= 112 and data[:8] in
            tuple(b"dex\n" + version + b"\0" for version in (b"035", b"037", b"038", b"039", b"040")),
            "Invalid or unsupported DEX header")
    require(struct.unpack_from("<III", data, 32) == (len(data), 112, 0x12345678),
            "Invalid DEX size, header size or byte order")
    count, table = struct.unpack_from("<II", data, 56)
    require(112 <= table <= len(data) and count <= (len(data) - table) // 4,
            "Invalid DEX string table")
    strings: set[bytes] = set()
    for index in range(count):
        offset = struct.unpack_from("<I", data, table + index * 4)[0]
        require(112 <= offset < len(data), "Invalid DEX string offset")
        length = 0
        for shift in range(0, 35, 7):
            require(offset < len(data), "Truncated DEX string length")
            value = data[offset]
            offset += 1
            length |= (value & 127) << shift
            if value < 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = data.find(b"\0", offset)
        require(end >= 0, "Unterminated DEX string")
        raw = data[offset:end]
        if raw.isascii() and len(raw) == length:
            strings.add(raw)
    return strings


def verify_signature(bundle: Path, certificate_pin: str) -> int:
    """Allow self-signed chains only with a pinned, valid signer on every payload entry."""
    with tempfile.TemporaryDirectory(prefix="gearforge-signature-") as directory:
        run_tool(["jarsigner", f"-J-Duser.home={directory}", "-verify", "-strict", str(bundle)], (0, 4))
        source = Path(directory) / "VerifyAabSignature.java"
        source.write_text(SIGNATURE_SOURCE, encoding="ascii")
        return int(run_tool(["java", "-Xmx128m", str(source), str(bundle), certificate_pin]).strip())


def verify_bundle(bundle: Path, expected: argparse.Namespace) -> int:
    with zipfile.ZipFile(bundle) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate ZIP entries")
        for name in ("BundleConfig.pb", "base/manifest/AndroidManifest.xml", "base/resources.pb"):
            require(name in names, f"Missing {name}")
        require(TEST_PUBLISHER not in archive.read("base/manifest/AndroidManifest.xml"),
                "Test publisher in manifest")
        xml = run_tool(["java", "-Xmx256m", "-Dfile.encoding=UTF-8", "-jar", str(expected.bundletool),
                        "dump", "manifest", f"--bundle={bundle}", "--module=base"])
        verify_manifest(xml, expected)
        dexes = [name for name in names
             if re.fullmatch(r"base/dex/classes(?:[2-9]|[1-9]\d+)?\.dex", name, flags=re.ASCII)]
        require(bool(dexes), "No base DEX files")
        strings: set[bytes] = set()
        for name in dexes:
            data = archive.read(name)
            require(TEST_PUBLISHER not in data, f"Test publisher in {name}")
            strings.update(dex_strings(data))
        require(expected.expected_rewarded_id.encode("ascii") in strings,
                "Missing exact rewarded DEX string")
    try:
        return verify_signature(bundle, expected.expected_certificate_sha256)
    except ValueError as error:
        raise ValueError(f"AAB signature verification failed: {error}") from error


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aab", type=Path)
    parser.add_argument("--bundletool", type=Path, default=DEFAULT_BUNDLETOOL)
    parser.add_argument("--expected-app-id", required=True)
    parser.add_argument("--expected-rewarded-id", required=True)
    parser.add_argument("--expected-package", required=True)
    parser.add_argument("--expected-version-code", required=True, type=int)
    parser.add_argument("--expected-version-name", required=True)
    parser.add_argument("--expectedCertificateSha256", "--expected-certificate-sha256",
                        dest="expected_certificate_sha256", required=True,
                        help="Full 64-hex SHA-256 of the independently trusted upload certificate")
    parser.add_argument("--expected-sha256", help="Bind verification to this exact artifact hash")
    parser.add_argument("--receipt", type=Path, help="Write a NEW JSON verification receipt on success only")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    expected = parse_args(argv)
    try:
        require(re.fullmatch(r"[0-9A-Fa-f]{64}", expected.expected_certificate_sha256) is not None,
                "expectedCertificateSha256 must be a full, separately trusted 64-hex digest")
        for value, separator in ((expected.expected_app_id, "~"), (expected.expected_rewarded_id, "/")):
            require(re.fullmatch(r"ca-app-pub-[0-9]{16}" + separator + r"[0-9]{10}", value) is not None
                    and TEST_PUBLISHER.decode() not in value, "Expected production AdMob ID is invalid")
        require(expected.expected_app_id.split("~")[0] == expected.expected_rewarded_id.split("/")[0],
                "Expected AdMob publishers differ")
        require(bool(expected.expected_package) and expected.expected_version_code > 0
                and bool(expected.expected_version_name), "Package and version expectations are required")
        require(sha256(expected.bundletool) == BUNDLETOOL_SHA256, "Unrecognized bundletool SHA-256")
        bundle = expected.aab.resolve(strict=True)
        digest = sha256(bundle)
        if expected.expected_sha256 is not None:
            require(digest.upper() == expected.expected_sha256.upper(), "Artifact SHA-256 differs from build")
        if expected.receipt is not None:
            require(not expected.receipt.exists(), "Receipt already exists; use a fresh receipt path")
        with tempfile.TemporaryDirectory(prefix="gearforge-aab-") as directory:
            snapshot = Path(directory) / "artifact.aab"
            shutil.copyfile(bundle, snapshot)
            require(sha256(snapshot) == digest, "Artifact changed while taking verification snapshot")
            covered = verify_bundle(snapshot, expected)
        require(sha256(bundle) == digest, "Artifact changed during verification")
        receipt = {"status": "verified", "aab": str(bundle), "sha256": digest,
                   "certificateSha256": expected.expected_certificate_sha256.upper(),
                   "package": expected.expected_package, "versionCode": expected.expected_version_code,
                   "versionName": expected.expected_version_name, "appId": expected.expected_app_id,
                   "rewardedId": expected.expected_rewarded_id, "signedEntries": covered}
        if expected.receipt is not None:
            with expected.receipt.open("x", encoding="utf-8") as stream:
                json.dump(receipt, stream, indent=2)
        print(json.dumps(receipt, indent=2))
        print("RESULT: all checks passed")
        return 0
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, ET.ParseError, subprocess.SubprocessError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
