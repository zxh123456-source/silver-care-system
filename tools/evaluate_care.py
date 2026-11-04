"""Run or verify the fixed synthetic care-quality contract against production Java.

This is an offline protocol/grounding evaluation, not an external model benchmark.
No patient records, provider credentials, or network service are required.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "源码/beadhouse-backend"
FIXTURE = BACKEND / "src/test/resources/ai-quality/care-cases.json"
REPORT = BACKEND / "target/ai-quality/report.json"


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("Duplicate JSON key in quality evidence")
        value[key] = item
    return value


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique_object)


def verify_report(fixture=FIXTURE, report=REPORT, *, require_pass=True):
    expected, data = read_json(fixture), read_json(report)
    if not isinstance(expected, list) or not expected:
        raise ValueError("Quality fixture must contain cases")
    expected_ids = [item["id"] for item in expected]
    if len(set(expected_ids)) != len(expected_ids):
        raise ValueError("Duplicate fixture case ID")
    if type(data.get("schemaVersion")) is not int or data["schemaVersion"] != 1 or data.get("mode") != "offline-contract":
        raise ValueError("Unexpected quality evidence schema or evaluation mode")
    if data.get("fixtureSha256") != hashlib.sha256(fixture.read_bytes()).hexdigest():
        raise ValueError("Quality evidence was generated for a different fixture")
    cases = data.get("cases")
    if not isinstance(cases, list) or len(cases) != len(expected_ids):
        raise ValueError("Incomplete quality evidence")
    if {item["id"] for item in cases} != set(expected_ids):
        raise ValueError("Quality evidence case IDs differ from the fixed fixture")
    categories = {item["id"]: item["category"] for item in expected}
    if any(type(item.get("passed")) is not bool or item.get("category") != categories[item["id"]]
           for item in cases):
        raise ValueError("Invalid case outcome or category")
    passed = sum(item["passed"] for item in cases)
    for key, number in (("caseCount", len(cases)), ("passed", passed), ("failed", len(cases) - passed)):
        if type(data.get(key)) is not int or data[key] != number:
            raise ValueError("Quality evidence totals are inconsistent")
    if require_pass and passed != len(cases):
        raise ValueError(f"Care quality evaluation failed: {len(cases) - passed}/{len(cases)} cases")
    return {"mode": "offline-contract", "cases": len(cases), "passed": passed, "failed": len(cases) - passed,
            "categories": sorted(set(categories.values())), "externalModelEvaluated": False}


def run_evaluation(maven):
    # A stale successful artifact must not survive a failed or skipped invocation.
    if REPORT.is_symlink():
        raise ValueError("Refusing linked quality report")
    if REPORT.exists():
        REPORT.unlink()
    executable = maven or os.environ.get("SILVERCARE_MAVEN") or shutil.which("mvn") or shutil.which("mvn.cmd")
    if not executable:
        raise ValueError("Maven is required; set SILVERCARE_MAVEN or pass --maven")
    result = subprocess.run([executable, "--batch-mode", "--no-transfer-progress", "-Dfile.encoding=UTF-8",
                             "-Dtest=AiCareQualityEvaluationTest", "test"], cwd=BACKEND, check=False)
    if result.returncode:
        raise ValueError(f"Care quality Maven tests failed (exit {result.returncode})")
    return verify_report()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-report", action="store_true", help="Verify evidence from an existing Maven run")
    parser.add_argument("--maven", help="Maven executable path (the project requires JDK 8)")
    parser.add_argument("--baseline-report", type=Path, help="Compare a preserved report for the identical fixture")
    args = parser.parse_args()
    try:
        summary = verify_report() if args.check_report else run_evaluation(args.maven)
        if args.baseline_report:
            baseline = verify_report(report=args.baseline_report, require_pass=False)
            summary["comparison"] = {"baselinePassed": baseline["passed"], "currentPassed": summary["passed"],
                                     "additionalPassingCases": summary["passed"] - baseline["passed"]}
        print(json.dumps(summary, ensure_ascii=True))
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Care quality check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
