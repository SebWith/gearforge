from pathlib import Path
import re
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]


class CiConfigTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = yaml.safe_load((ROOT / ".github/workflows/android-ci.yml").read_text(encoding="utf-8"))

    def test_every_action_is_an_immutable_commit(self):
        for path in (ROOT / ".github/workflows").glob("*.y*ml"):
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
            for job in document["jobs"].values():
                references = [job["uses"]] if "uses" in job else [
                    step["uses"] for step in job["steps"] if "uses" in step
                ]
                for reference in references:
                    with self.subTest(action=reference):
                        self.assertRegex(reference, r"^[^@]+@[0-9a-f]{40}$")

    def test_gradle_distribution_is_checksum_pinned(self):
        properties = dict(line.split("=", 1) for line in
                          (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text().splitlines()
                          if "=" in line)
        self.assertTrue(re.fullmatch(r"[0-9a-f]{64}", properties["distributionSha256Sum"]))
        self.assertEqual("true", properties["validateDistributionUrl"])

    def test_writer_fixtures_and_android_unit_tests_cannot_be_omitted(self):
        jobs = self.workflow["jobs"]
        core_commands = "\n".join(step.get("run", "") for step in jobs["core-tests"]["steps"])
        self.assertIn(":core:test --rerun", core_commands)
        self.assertIn("tools/verify_export.py", core_commands)
        self.assertIn("--expect-diameter 22 --expect-triangles 5632", core_commands)
        self.assertIn("test_verify_export.py", core_commands)
        android_commands = "\n".join(step.get("run", "") for step in jobs["android-build"]["steps"])
        self.assertIn(":android:testDebugUnitTest", android_commands)

    def test_security_gate_fails_closed_and_preserves_reports(self):
        job = self.workflow["jobs"]["dependency-security"]
        self.assertNotIn("needs", job)
        self.assertFalse(job.get("continue-on-error", False))
        scan = next(step for step in job["steps"] if step.get("run") == "python tools/scan_dependencies.py")
        self.assertFalse(scan.get("continue-on-error", False))
        upload = next(step for step in job["steps"] if step.get("uses", "").startswith("actions/upload-artifact@"))
        self.assertEqual("always()", upload["if"])
        self.assertEqual("read", self.workflow["permissions"]["contents"])


if __name__ == "__main__":
    unittest.main()