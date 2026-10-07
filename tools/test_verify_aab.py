"""Release guard regressions; fixtures contain no signing key or credentials."""
import argparse
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import verify_aab as guard

ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "tools/verify_aab.py"
BUNDLETOOL = ROOT / "build/tools/bundletool-all-1.18.2.jar"
APP_ID = "ca-app-pub-6154121627229543~9677913532"
REWARDED_ID = "ca-app-pub-6154121627229543/4517387519"
PACKAGE = "com.gearforge.geargenerator"
ANDROID = "http://schemas.android.com/apk/res/android"
SIGNED_FIXTURE: Path | None = None
FIXTURE_PIN: str | None = None


def verification_command(bundle: Path, pin: str = "A" * 64) -> list[str]:
    return [sys.executable, str(VERIFIER), str(bundle), "--bundletool", str(BUNDLETOOL),
            "--expected-app-id", APP_ID, "--expected-rewarded-id", REWARDED_ID,
            "--expected-package", PACKAGE, "--expected-version-code", "9",
            "--expected-version-name", "1.1", "--expectedCertificateSha256", pin]


def varint(value: int) -> bytes:
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def field(number: int, value: str | bytes) -> bytes:
    payload = value.encode() if isinstance(value, str) else value
    return varint(number * 8 + 2) + varint(len(payload)) + payload


def attribute(name: str, value: str, namespace: str = ANDROID) -> bytes:
    return field(4, field(1, namespace) + field(2, name) + field(3, value))


def manifest(app_id: str = APP_ID, *, misplaced: bool = False,
             version: str | None = "1.1", package: str = PACKAGE) -> bytes:
    metadata = field(3, "meta-data")
    metadata += attribute("name", "wrong.key" if misplaced else
                          "com.google.android.gms.ads.APPLICATION_ID")
    metadata += attribute("value", app_id)
    application = field(3, "application") + field(5, field(1, metadata))
    root = field(1, field(1, "android") + field(2, ANDROID)) + field(3, "manifest")
    root += attribute("package", package, "") + attribute("versionCode", "9")
    if version is not None:
        root += attribute("versionName", version)
    return field(1, root + field(5, field(1, application)))


def dex(*strings: str) -> bytes:
    header = bytearray(112)
    header[:8] = b"dex\n035\0"
    struct.pack_into("<II", header, 36, 112, 0x12345678)
    struct.pack_into("<II", header, 56, len(strings), 112)
    offsets = bytearray()
    data = bytearray()
    for text in strings:
        offsets += struct.pack("<I", 112 + 4 * len(strings) + len(data))
        data += varint(len(text)) + text.encode() + b"\0"
    struct.pack_into("<I", header, 32, len(header + offsets + data))
    return bytes(header + offsets + data)


class ReleaseGuardRegressionTest(unittest.TestCase):
    def verify(self, manifest_bytes: bytes, dex_bytes: bytes) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory(prefix="gearforge-aab-test-") as directory:
            bundle = Path(directory) / "fixture.aab"
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("BundleConfig.pb", b"")
                archive.writestr("base/resources.pb", b"")
                archive.writestr("base/manifest/AndroidManifest.xml", manifest_bytes)
                archive.writestr("base/dex/classes.dex", dex_bytes)
            return subprocess.run(
                verification_command(bundle),
                capture_output=True, text=True, timeout=90, check=False,
            )

    def assert_rejected(self, manifest_bytes: bytes, dex_bytes: bytes, reason: str) -> None:
        result = self.verify(manifest_bytes, dex_bytes)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(reason, result.stdout + result.stderr)

    def test_wrong_app_suffix_same_publisher(self) -> None:
        self.assert_rejected(manifest(APP_ID[:-1] + "0"), dex(REWARDED_ID), "AdMob App ID")

    def test_wrong_rewarded_suffix_same_publisher(self) -> None:
        self.assert_rejected(manifest(), dex(REWARDED_ID[:-1] + "0"), "rewarded DEX string")

    def test_right_app_id_under_wrong_metadata_key(self) -> None:
        self.assert_rejected(manifest(misplaced=True), dex(REWARDED_ID), "AdMob App ID")

    def test_right_rewarded_id_outside_dex_string_table(self) -> None:
        data = bytearray(dex(APP_ID) + REWARDED_ID.encode() + b"\0")
        struct.pack_into("<I", data, 32, len(data))
        self.assert_rejected(manifest(), bytes(data), "rewarded DEX string")

    def test_missing_version(self) -> None:
        self.assert_rejected(manifest(version=None), dex(REWARDED_ID), "versionName")

    def test_unsigned_bundle(self) -> None:
        self.assert_rejected(manifest(), dex(REWARDED_ID), "signature")

    def test_wrong_package(self) -> None:
        self.assert_rejected(manifest(package="com.gearforge.wrong"), dex(REWARDED_ID), "package")

    def test_wrong_version(self) -> None:
        self.assert_rejected(manifest(version="1.0"), dex(REWARDED_ID), "versionName")

    def test_missing_version_code(self) -> None:
        self.assert_rejected(manifest().replace(attribute("versionCode", "9"),
                                               attribute("unimportant", "9")),
                             dex(REWARDED_ID), "versionCode")

    def test_rewarded_substring_is_not_an_exact_string(self) -> None:
        self.assert_rejected(manifest(), dex("prefix" + REWARDED_ID + "suffix"), "rewarded DEX string")

    def test_multidex_tenth_file_is_checked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "multidex.aab"
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("BundleConfig.pb", b"")
                archive.writestr("base/resources.pb", b"")
                archive.writestr("base/manifest/AndroidManifest.xml", manifest())
                archive.writestr("base/dex/classes.dex", dex(APP_ID))
                archive.writestr("base/dex/classes10.dex", dex(REWARDED_ID))
            expected = guard.parse_args(verification_command(bundle)[2:])
            with patch.object(guard, "verify_signature", return_value=5) as signature:
                self.assertEqual(5, guard.verify_bundle(bundle, expected))
                signature.assert_called_once()


class SignedFixtureRegressionTest(unittest.TestCase):
    """Optional real-crypto tests. A fixture pin does NOT establish release trust."""
    fixture: Path
    pin: str

    @classmethod
    def setUpClass(cls) -> None:
        if SIGNED_FIXTURE is None or FIXTURE_PIN is None:
            raise unittest.SkipTest("Provide --signed-fixture and --fixture-certificate-sha256 for real-crypto tests")
        cls.fixture = SIGNED_FIXTURE.resolve(strict=True)
        cls.pin = FIXTURE_PIN

    def test_signed_fixture_with_explicit_test_pin(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            receipt = Path(directory) / "verified.json"
            command = verification_command(self.fixture, self.pin)
            command += ["--expected-sha256", guard.sha256(self.fixture), "--receipt", str(receipt)]
            result = subprocess.run(command, capture_output=True, text=True, timeout=120, check=False)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertTrue(receipt.is_file())

    def test_same_certificate_prefix_wrong_suffix(self) -> None:
        wrong_pin = self.pin[:-1] + ("0" if self.pin[-1] != "0" else "1")
        with self.assertRaisesRegex(ValueError, "Certificate SHA-256"):
            guard.verify_signature(self.fixture, wrong_pin)

    def test_tampered_unsigned_and_incomplete_archives(self) -> None:
        for mode in ("changed_payload", "added_payload", "deleted_payload", "removed_signatures",
                     "changed_signature"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                bundle = Path(directory) / "changed.aab"
                with zipfile.ZipFile(self.fixture) as original, zipfile.ZipFile(bundle, "w") as altered:
                    for entry in original.infolist():
                        if mode == "deleted_payload" and entry.filename == "base/resources.pb":
                            continue
                        if mode == "removed_signatures" and entry.filename.startswith("META-INF/"):
                            continue
                        content = original.read(entry)
                        if mode == "changed_payload" and entry.filename == "base/resources.pb":
                            content += b"tampered"
                        if mode == "changed_signature" and entry.filename.endswith(".RSA"):
                            content = content[:-1] + bytes([content[-1] ^ 1])
                        altered.writestr(entry, content)
                    if mode == "added_payload":
                        altered.writestr("BUNDLE-METADATA/unsigned.txt", b"unsigned payload")
                with self.assertRaises(ValueError):
                    guard.verify_signature(bundle, self.pin)


class BuildScriptRegressionTest(unittest.TestCase):
    def run_gate(self, mode: str) -> subprocess.CompletedProcess[str]:
        script = (ROOT / "tools/build-release-aab.ps1").read_text(encoding="utf-8-sig")
        gate = script.split("# --- 4.", 1)[1].split("\n", 1)[1]
        with tempfile.TemporaryDirectory(prefix="gearforge-release-gate-") as directory:
            output = Path(directory)
            (output / "unrelated-stale.aab").write_bytes(b"stale output")
            artifact = output / "android-release.aab"
            if mode != "missing_artifact":
                artifact.write_bytes(b"current build output")
            setup = r"""
$ErrorActionPreference = 'Stop'
function Fail($message) { throw $message }
$aabPath = Join-Path 'DIRECTORY' 'android-release.aab'
$bundleDir = 'DIRECTORY'
$verifier = 'unused-mocked-verifier.py'
$buildStartedUtc = [DateTime]::UtcNow.AddMinutes(-1)
$ExpectedCertificateSha256 = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
$ExpectedPackage = 'com.gearforge.geargenerator'
$ExpectedVersionCode = 9
$ExpectedVersionName = '1.1'
$AdmobAppId = 'ca-app-pub-6154121627229543~9677913532'
$AdmobRewardedUnitId = 'ca-app-pub-6154121627229543/4517387519'
$verificationArgs = @()
function py {
    $global:LASTEXITCODE = 0
    if ('MODE' -eq 'failed_verifier') { $global:LASTEXITCODE = 1; return }
    if ('MODE' -eq 'no_receipt') { return }
    if ($args[1] -ne $aabPath) { throw 'Verifier was given another artifact' }
    $receiptIndex = [Array]::IndexOf($args, '--receipt') + 1
    if ($receiptIndex -eq 0) { throw 'Missing receipt argument' }
    $hash = (Get-FileHash -LiteralPath $aabPath -Algorithm SHA256).Hash
    if ('MODE' -eq 'wrong_hash') { $hash = 'bad hash' }
    if ('MODE' -eq 'tampered_artifact') { [IO.File]::WriteAllText($aabPath, 'changed after verification') }
    $receipt = @{
        status = 'verified'; aab = $aabPath; sha256 = $hash
        certificateSha256 = $ExpectedCertificateSha256; package = $ExpectedPackage
        versionCode = 9; versionName = '1.1'; appId = $AdmobAppId
        rewardedId = $AdmobRewardedUnitId; signedEntries = 4
    }
    if ('MODE' -eq 'wrong_version') { $receipt.versionCode = 8 }
    if ('MODE' -eq 'wrong_pin') { $receipt.certificateSha256 = 'B' * 64 }
    $receipt | ConvertTo-Json | Set-Content -LiteralPath $args[$receiptIndex] -Encoding UTF8
}
""".replace("DIRECTORY", directory.replace("'", "''")).replace("MODE", mode)
            return subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", setup + gate],
                                  capture_output=True, text=True, timeout=30, check=False)

    def test_post_build_gate_rejects_missing_or_unverified_artifacts(self) -> None:
        for mode in ("missing_artifact", "failed_verifier", "no_receipt", "wrong_hash",
                     "tampered_artifact", "wrong_version", "wrong_pin"):
            with self.subTest(mode=mode):
                result = self.run_gate(mode)
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)

    def test_post_build_gate_accepts_matching_receipt(self) -> None:
        result = self.run_gate("ok")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_powershell_parser_and_fresh_output_order(self) -> None:
        path = ROOT / "tools/build-release-aab.ps1"
        parser = r"""
$parseErrors = @()
[void][System.Management.Automation.Language.Parser]::ParseFile('SCRIPT', [ref]$null, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) { $parseErrors | Out-String | Write-Output; exit 1 }
""".replace("SCRIPT", str(path).replace("'", "''"))
        result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", parser],
                                capture_output=True, text=True, timeout=30, check=False)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        script = path.read_text(encoding="utf-8-sig")
        self.assertLess(script.index('Move-Item -LiteralPath $aabPath'), script.index(':android:bundleRelease'))
        self.assertIn("build\\outputs\\bundle\\release\\android-release.aab", script)
        self.assertNotIn("Get-ChildItem", script)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--signed-fixture", type=Path)
    parser.add_argument("--fixture-certificate-sha256")
    options, remaining = parser.parse_known_args()
    if bool(options.signed_fixture) != bool(options.fixture_certificate_sha256):
        parser.error("Provide both signed-fixture options, or neither")
    SIGNED_FIXTURE = options.signed_fixture
    FIXTURE_PIN = options.fixture_certificate_sha256
    unittest.main(argv=[sys.argv[0], *remaining], verbosity=2)