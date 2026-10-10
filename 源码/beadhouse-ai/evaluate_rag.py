import copy
import json
import math
import os
import statistics
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import httpx


def percentile(values: list[float], value: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * value) - 1)]


def main() -> int:
    root = Path(__file__).parent
    fixture = json.loads((root / "evals" / "policy_rag_cases.json").read_text(encoding="utf-8"))
    base_url = os.getenv("RAG_EVAL_URL", "http://127.0.0.1:8001").rstrip("/")
    token = os.getenv("RAG_INTERNAL_TOKEN", "")
    if not token:
        print("Configure RAG_INTERNAL_TOKEN before running evaluation", file=sys.stderr)
        return 2
    expected_backend = os.getenv("RAG_EXPECT_BACKEND", "")
    p95_limit = float(os.getenv("RAG_P95_MAX_MS", "5000"))
    run_id = str(time.time_ns())
    id_offset = (time.time_ns() % 10_000_000) * 1000
    documents = copy.deepcopy(fixture["documents"])
    id_map: dict[int, int] = {}
    for document in documents:
        document["document_key"] = f"{document['document_key']}-{run_id}"
        for chunk in document["chunks"]:
            original = chunk["chunk_id"]
            chunk["chunk_id"] = original + id_offset
            id_map[original] = chunk["chunk_id"]
    all_ids = set(id_map.values())
    headers = {"X-Internal-Token": token}
    latencies: list[float] = []
    reciprocal_ranks: list[float] = []
    top1 = 0
    hits = 0
    invalid_citations = 0
    degraded_count = 0
    backend_mismatches = 0
    failures: list[str] = []
    negative_correct = 0

    with httpx.Client(base_url=base_url, headers=headers, timeout=20, trust_env=False) as client:
        for document in documents:
            response = client.post("/v1/documents/index", json=document)
            response.raise_for_status()
        try:
            client.post("/v1/query", json={"question": fixture["cases"][0]["question"], "top_k": 5}).raise_for_status()
            for case_index, case in enumerate(fixture["cases"], start=1):
                started = time.perf_counter()
                response = client.post("/v1/query", json={"question": case["question"], "top_k": 5})
                latencies.append((time.perf_counter() - started) * 1000)
                response.raise_for_status()
                payload = response.json()
                ids = [item["chunk_id"] for item in payload["hits"]]
                expected = {id_map[item] for item in case["expected_chunk_ids"]}
                invalid_citations += sum(1 for item in ids if item not in all_ids)
                degraded_count += int(payload.get("degraded", False))
                backend_mismatches += int(bool(expected_backend) and payload.get("backend") != expected_backend)
                rank = next((index for index, value in enumerate(ids, start=1) if value in expected), None)
                if rank is None:
                    reciprocal_ranks.append(0.0)
                    failures.append(f"positive case {case_index} missed expected citations")
                else:
                    hits += 1
                    top1 += int(rank == 1)
                    reciprocal_ranks.append(1.0 / rank)
                print(f"positive={case_index} top={ids[:3]} expected={sorted(expected)}")
            for case_index, case in enumerate(fixture["negative_cases"], start=1):
                started = time.perf_counter()
                response = client.post("/v1/query", json={"question": case["question"], "top_k": 5})
                latencies.append((time.perf_counter() - started) * 1000)
                response.raise_for_status()
                payload = response.json()
                ids = [item["chunk_id"] for item in payload["hits"]]
                invalid_citations += sum(1 for item in ids if item not in all_ids)
                degraded_count += int(payload.get("degraded", False))
                backend_mismatches += int(bool(expected_backend) and payload.get("backend") != expected_backend)
                if not ids:
                    negative_correct += 1
                else:
                    failures.append(f"negative case {case_index} returned citations")
                print(f"negative={case_index} top={ids[:3]}")
        finally:
            for document in documents:
                client.post("/v1/documents/delete", json={"document_key": document["document_key"]})

    positive_count = len(fixture["cases"])
    negative_count = len(fixture["negative_cases"])
    metrics = {
        "positive_cases": positive_count,
        "negative_cases": negative_count,
        "hit_at_5": hits / positive_count if positive_count else 0.0,
        "mrr": sum(reciprocal_ranks) / positive_count if positive_count else 0.0,
        "top1_accuracy": top1 / positive_count if positive_count else 0.0,
        "no_answer_accuracy": negative_correct / negative_count if negative_count else 0.0,
        "citation_id_validity": 1.0 if invalid_citations == 0 else 0.0,
        "p95_latency_ms": round(percentile(latencies, 0.95), 2),
        "degraded_responses": degraded_count,
        "backend_mismatches": backend_mismatches,
    }
    gates = {
        "positive_cases": positive_count >= 20,
        "hit_at_5": metrics["hit_at_5"] >= 0.90,
        "mrr": metrics["mrr"] >= 0.80,
        "top1_accuracy": metrics["top1_accuracy"] >= 0.75,
        "no_answer_accuracy": metrics["no_answer_accuracy"] >= 0.90,
        "citation_id_validity": metrics["citation_id_validity"] == 1.0,
        "p95_latency_ms": metrics["p95_latency_ms"] <= p95_limit,
        "degraded_responses": degraded_count == 0,
        "backend": backend_mismatches == 0,
    }
    for name, passed in gates.items():
        if not passed:
            failures.append(f"gate failed: {name}")
    report = {"metrics": metrics, "gates": gates, "failures": failures}
    report_path = root / "evals" / "latest_report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    testsuite = ET.Element("testsuite", name="policy-rag-eval", tests=str(len(gates)), failures=str(len(failures)))
    for name, passed in gates.items():
        testcase = ET.SubElement(testsuite, "testcase", name=name)
        if not passed:
            ET.SubElement(testcase, "failure", message=f"gate failed: {name}")
    ET.ElementTree(testsuite).write(root / "evals" / "latest_report.xml", encoding="utf-8", xml_declaration=True)
    print(json.dumps(metrics, ensure_ascii=False))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
