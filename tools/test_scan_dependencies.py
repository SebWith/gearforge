import copy
import tempfile
from pathlib import Path
import unittest

import scan_dependencies


class DependencyScanTest(unittest.TestCase):
    def setUp(self):
        self.inventory = {"bomFormat": "CycloneDX", "components": [
            {"name": "sample", "group": "org.example", "version": "1.0", "properties": [
                {"name": "gearforge:configurations", "value": ":android:project:debugRuntimeClasspath"},
                {"name": "gearforge:direct-configurations", "value": ""},
            ]}
        ]}
        self.result = {"results": [{"packages": [
            {"package": {"name": "sample", "version": "1.0", "ecosystem": "Maven"}}
        ]}]}

    def test_complete_clean_scan(self):
        report = scan_dependencies.summarize(self.inventory, self.result, 0)
        self.assertEqual("clean", report["status"])
        self.assertEqual(1, report["scanned_packages"])

    def test_query_failure_is_never_clean(self):
        for code in (2, 127, 128):
            with self.subTest(code=code), self.assertRaises(ValueError):
                scan_dependencies.summarize(self.inventory, self.result, code)

    def test_missing_packages_fail_coverage(self):
        self.result["results"][0]["packages"] = []
        with self.assertRaisesRegex(ValueError, "coverage"):
            scan_dependencies.summarize(self.inventory, self.result, 0)

    def test_wrong_version_fails_coverage(self):
        self.result["results"][0]["packages"][0]["package"]["version"] = "2.0"
        with self.assertRaisesRegex(ValueError, "coverage"):
            scan_dependencies.summarize(self.inventory, self.result, 0)

    def test_empty_inventory_is_not_a_clean_scan(self):
        self.inventory["components"] = []
        with self.assertRaises(ValueError):
            scan_dependencies.summarize(self.inventory, self.result, 0)

    def test_advisory_keeps_scope_and_patch_evidence(self):
        self.result["results"][0]["packages"][0]["vulnerabilities"] = [
            {"id": "TEST-1", "summary": "Test advisory", "affected": [
                {"package": {"name": "org.example:sample"}, "ranges": [
                    {"events": [{"introduced": "0"}, {"fixed": "1.1"}]}
                ]}
            ]}
        ]
        report = scan_dependencies.summarize(self.inventory, self.result, 1)
        self.assertEqual("vulnerable", report["status"])
        self.assertEqual(1, report["unique_advisories"])
        finding = report["findings"][0]
        self.assertEqual([], finding["direct_configurations"])
        self.assertEqual(["1.1"], finding["advisories"][0]["fixed_versions"])
        with self.assertRaises(ValueError):
            scan_dependencies.summarize(self.inventory, self.result, 0)

    def test_findings_exit_without_advisories_is_error(self):
        with self.assertRaises(ValueError):
            scan_dependencies.summarize(self.inventory, self.result, 1)

    def test_ambiguous_coordinates_are_not_misattributed(self):
        duplicate = copy.deepcopy(self.inventory["components"][0])
        duplicate["group"] = "different.group"
        self.inventory["components"].append(duplicate)
        package = self.result["results"][0]["packages"][0]
        package["vulnerabilities"] = [{"id": "TEST-1"}]
        self.result["results"][0]["packages"].append(copy.deepcopy(package))
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            scan_dependencies.summarize(self.inventory, self.result, 1)

    def test_wrong_scanner_hash_is_rejected_before_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "scanner"
            binary.write_bytes(b"not the official scanner")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                scan_dependencies.scanner_binary(Path(directory), binary)


if __name__ == "__main__":
    unittest.main()