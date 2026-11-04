"""Validate quality gate evidence; do not substitute for production Java cases."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import evaluate_care


class CareEvaluationEvidenceTests(unittest.TestCase):
    def test_gate_rejects_stale_missing_forged_and_failed_evidence(self):
        with tempfile.TemporaryDirectory(prefix="care-evidence-") as folder:
            fixture, report = Path(folder) / "cases.json", Path(folder) / "report.json"
            cases = [{"id": "negation", "category": "grounding"}, {"id": "timeout", "category": "fallback"}]
            fixture.write_text(json.dumps(cases), encoding="utf-8")
            valid = {"schemaVersion": 1, "mode": "offline-contract",
                     "fixtureSha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
                     "cases": [dict(item, passed=True) for item in cases], "caseCount": 2, "passed": 2, "failed": 0}
            report.write_text(json.dumps(valid), encoding="utf-8")
            self.assertEqual(evaluate_care.verify_report(fixture, report)["passed"], 2)
            bad = [{**valid, "fixtureSha256": "old"}, {**valid, "cases": valid["cases"][:1]},
                   {**valid, "passed": True}, {**valid, "mode": "real-model"},
                   {**valid, "schemaVersion": True},
                   {**valid, "cases": [valid["cases"][0]] * 2},
                   {**valid, "cases": [dict(cases[0], passed=True), dict(cases[1], passed=False)],
                    "passed": 1, "failed": 1}]
            for value in bad:
                report.write_text(json.dumps(value), encoding="utf-8")
                with self.assertRaises(ValueError):
                    evaluate_care.verify_report(fixture, report)
            self.assertEqual(evaluate_care.verify_report(fixture, report, require_pass=False)["failed"], 1)
            report.write_text('{"schemaVersion":1,"schemaVersion":1}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                evaluate_care.verify_report(fixture, report)


if __name__ == "__main__":
    unittest.main()
