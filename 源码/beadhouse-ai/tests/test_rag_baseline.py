"""Deterministic evaluation contracts; no model download or service is started here."""
import copy
from contextlib import contextmanager
import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

import httpx
import pytest

import evaluate_rag as evaluation


BGE = "BAAI/bge-small-zh-v1.5"
FIXTURE_PATH = Path(__file__).resolve().parents[1] / "evals" / "policy_rag_cases.json"


def fixture():
    return json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))


def diagnostics(**overrides):
    value = dict(instance_id="owned-spec-instance", backend="milvus-hybrid", requested_backend="milvus",
                 embedding_backend=BGE, embedding_requested_backend="fastembed", embedding_requested_model=BGE,
                 embedding_loaded=True, dimension=512, configured_dimension=512,
                 model=dict(name=BGE, onnx_files=[dict(name="model.onnx", sha256="1a" * 32, size_bytes=1024)]),
                 dependencies=dict(fastembed="0.8.1", onnxruntime="1.30.0", pymilvus="2.6.17"),
                 degraded=False, fallback_code=None, embedding_degraded=False, embedding_fallback_code=None,
                 milvus_collection="test_baseline_owned_v2", milvus_collection_generation=101)
    value.update(overrides)
    return value


def valid_results(rounds=3):
    data = fixture()
    all_ids = [chunk["chunk_id"] for doc in data["documents"] for chunk in doc["chunks"]]
    rows = []
    groups = [("positive", data["cases"]), ("retrieval_negative", data["negative_cases"]),
              ("security", data["security_negative_cases"])]
    for round_no in range(1, rounds + 1):
        for category, cases in groups:
            for index, case in enumerate(cases, 1):
                gold = case.get("expected_chunk_ids", [])
                rows.append(dict(category=category, case_id=f"{category}-{index}", round=round_no,
                                 question=case["question"], expected_chunk_ids=gold,
                                 hits=[dict(chunk_id=number, score=1.0) for number in gold], latency_ms=25.0,
                                 backend="milvus-hybrid", embedding_backend=BGE, degraded=False,
                                 fallback_code="PROMPT_INJECTION_BLOCKED" if category == "security" else None,
                                 valid_chunk_ids=all_ids, identity_valid=True))
    return rows


def test_one_single_citation_miss_is_diagnostic_when_fixed_aggregate_gates_pass():
    rows = valid_results()
    for row in rows:
        if row["case_id"] == "positive-1": row["hits"] = []
    metrics = evaluation.calculate_metrics(rows)
    assert metrics["positive_cases"] == 22
    assert metrics["hit_at_5"] == pytest.approx(21 / 22)
    assert metrics["mrr"] == pytest.approx(21 / 22)
    assert metrics["top1_accuracy"] == pytest.approx(21 / 22)
    assert metrics["macro_recall_at_5"] == pytest.approx(21 / 22)
    assert metrics["multi_citation_complete"] == 1.0
    assert all(evaluation.evaluate_gates(metrics).values())


def test_multi_citation_partial_hit_is_not_complete_and_duplicates_do_not_inflate_recall():
    rows = valid_results()
    for row in rows:
        if len(row["expected_chunk_ids"]) > 1:
            row["hits"] = [dict(chunk_id=row["expected_chunk_ids"][0], score=1)] * 5
    metrics = evaluation.calculate_metrics(rows)
    assert metrics["hit_at_5"] == 1.0
    assert metrics["macro_recall_at_5"] == pytest.approx(21.5 / 22)
    assert metrics["multi_citation_complete"] == 0.0
    assert not all(evaluation.evaluate_gates(metrics).values())


def test_retrieval_refusal_and_injection_are_separate_populations():
    rows = valid_results()
    for row in rows:
        if row["case_id"] == "retrieval_negative-1": row["hits"] = [dict(chunk_id=900001, score=1)]
    metrics = evaluation.calculate_metrics(rows)
    assert metrics["retrieval_negative_cases"] == 3
    assert metrics["security_negative_cases"] == 2
    assert metrics["no_answer_accuracy"] == pytest.approx(2 / 3)
    assert metrics["security_block_accuracy"] == 1.0
    assert not all(evaluation.evaluate_gates(metrics).values())
    for row in rows:
        if row["category"] == "retrieval_negative": row["hits"] = []
        if row["case_id"] == "security-1": row["fallback_code"] = None
    metrics = evaluation.calculate_metrics(rows)
    assert metrics["no_answer_accuracy"] == 1.0
    assert metrics["security_block_accuracy"] == 0.5
    assert not all(evaluation.evaluate_gates(metrics).values())


def test_latency_is_formal_retrieval_p95_with_per_category_round_samples():
    rows = valid_results()
    for row in rows:
        row["latency_ms"] = 90000 if row["category"] == "security" else 123
    metrics = evaluation.calculate_metrics(rows)
    assert metrics["p95_latency_ms"] == 123
    assert set(metrics["latency_by_category"]) == {"positive", "retrieval_negative", "security"}
    assert metrics["latency_by_category"]["security"]["p95_ms"] == 90000
    assert metrics["latency_by_category"]["positive"]["count"] == 66


@pytest.mark.parametrize("mutate", [
    lambda row: row.update(embedding_backend="hashed"),
    lambda row: row.update(embedding_backend="BAAI/wrong-model"),
    lambda row: row.update(backend="memory-hybrid"),
    lambda row: row.update(degraded=True),
    lambda row: row.update(identity_valid=False),
])
def test_identity_gate_rejects_wrong_actual_model_or_backend_even_when_degraded_false(mutate):
    rows = valid_results()
    mutate(rows[0])
    assert not all(evaluation.evaluate_gates(evaluation.calculate_metrics(rows)).values())


def test_only_top_five_hits_count_and_unknown_citations_fail_the_gate():
    rows = valid_results()
    first = rows[0]
    first["hits"] = [dict(chunk_id=900012, score=1)] * 5 + [dict(chunk_id=900001, score=0)]
    metrics = evaluation.calculate_metrics(rows)
    assert metrics["hit_at_5"] < 1.0
    rows[1]["hits"].insert(0, dict(chunk_id=999999999, score=10))
    metrics = evaluation.calculate_metrics(rows)
    assert metrics["citation_id_validity"] < 1.0
    assert not all(evaluation.evaluate_gates(metrics).values())


class Peer:
    def __init__(self, metadata=None, mutate_query=None, fail_import=None, fail_query=False, fail_delete=None):
        self.metadata = metadata or diagnostics()
        self.mutate_query = mutate_query
        self.fail_import, self.fail_query, self.fail_delete = fail_import, fail_query, fail_delete
        self.syncs, self.queries = [], []
        data = fixture()
        self.gold = {case["question"]: case["expected_chunk_ids"] for case in data["cases"]}
        self.security = {case["question"] for case in data["security_negative_cases"]}

    def handle(self, request):
        path = request.url.path
        if path == "/v2/diagnostics": return httpx.Response(200, json=self.metadata)
        if path == "/health":
            return httpx.Response(200, json={**self.metadata, "status": "ok"})
        body = json.loads(request.content)
        if path == "/v2/documents/sync":
            self.syncs.append(body)
            if body["action"] == "UPSERT" and len([row for row in self.syncs if row["action"] == "UPSERT"]) == self.fail_import:
                raise httpx.ReadTimeout("lost import response")
            if body["action"] == "DELETE" and len([row for row in self.syncs if row["action"] == "DELETE"]) == self.fail_delete:
                raise httpx.ReadTimeout("lost cleanup response")
            return httpx.Response(200, json=dict(body, applied=True))
        assert path == "/v1/query"
        self.queries.append(body)
        if self.fail_query: raise httpx.ReadTimeout("query response lost")
        question = body["question"]
        value = dict(backend="milvus-hybrid", embedding_backend=BGE, degraded=False,
                     fallback_code="PROMPT_INJECTION_BLOCKED" if question in self.security else None,
                     hits=[dict(chunk_id=number, score=1.0) for number in self.gold.get(question, [])])
        if self.mutate_query: self.mutate_query(value, question)
        return httpx.Response(200, json=value)

    def client(self):
        return httpx.Client(base_url="http://owned-spec", transport=httpx.MockTransport(self.handle))


def assert_reports(directory, expected_pass):
    report = json.loads((directory / "report.json").read_text(encoding="utf-8"))
    assert report["status"] == ("passed" if expected_pass else "failed")
    xml = ET.parse(directory / "report.xml").getroot()
    cases = list(xml.iter("testcase"))
    failures, errors = list(xml.iter("failure")), list(xml.iter("error"))
    assert int(xml.attrib["tests"]) == len(cases)
    assert int(xml.attrib.get("failures", 0)) == len(failures)
    assert int(xml.attrib.get("errors", 0)) == len(errors)
    assert bool(failures or errors) is not expected_pass
    return report


def test_full_run_preserves_raw_results_all_rounds_and_one_miss_can_pass(tmp_path):
    missed_question = fixture()["cases"][0]["question"]
    def mutate(value, question):
        if question == missed_question: value["hits"] = []
    peer = Peer(mutate_query=mutate)
    with peer.client() as client:
        report = evaluation.run_evaluation(client, fixture(), tmp_path, rounds=3, warmup_rounds=3)
    assert evaluation.exit_code(report) == 0
    saved = assert_reports(tmp_path, True)
    assert len(saved["results"]) == 3 * 27
    assert {row["round"] for row in saved["results"]} == {1, 2, 3}
    assert len(saved["warmup_results"]) >= 3
    assert all("raw_payload" in row for row in saved["results"])
    assert saved["metadata"]["fixture_sha256"]
    assert len([row for row in peer.syncs if row["action"] == "DELETE"]) == 7
    assert saved["cleanup_errors"] == []


@pytest.mark.parametrize("overrides", [
    dict(embedding_backend="hashed", embedding_loaded=False),
    dict(embedding_backend="BAAI/wrong-model"),
    dict(backend="memory-hybrid"),
    dict(model=dict(name=BGE, onnx_files=[])),
    dict(dimension=384),
    dict(embedding_degraded=True),
])
def test_diagnostics_cannot_attest_hashed_wrong_model_empty_artifact_or_wrong_dimension(tmp_path, overrides):
    peer = Peer(metadata=diagnostics(**overrides))
    with peer.client() as client:
        report = evaluation.run_evaluation(client, fixture(), tmp_path)
    assert evaluation.exit_code(report) != 0
    assert_reports(tmp_path, False)


def test_each_query_identity_is_validated_even_after_successful_health(tmp_path):
    peer = Peer(mutate_query=lambda value, question: value.update(embedding_backend="hashed", degraded=False))
    with peer.client() as client:
        report = evaluation.run_evaluation(client, fixture(), tmp_path)
    assert evaluation.exit_code(report) != 0
    assert_reports(tmp_path, False)


def test_mutually_matching_health_and_diagnostics_cannot_replace_expected_owned_instance(tmp_path):
    peer = Peer(metadata=diagnostics(instance_id="unrelated-service-on-the-same-port"))
    with peer.client() as client:
        report = evaluation.run_evaluation(client, fixture(), tmp_path,
                                            metadata={"expected_instance_id": "our-random-child-instance"})
    assert evaluation.exit_code(report) != 0
    assert_reports(tmp_path, False)
    assert peer.syncs == [], "wrong instance must be rejected before importing any document"


def test_fewer_than_three_rounds_cannot_publish_a_successful_baseline(tmp_path):
    peer = Peer()
    with peer.client() as client:
        report = evaluation.run_evaluation(client, fixture(), tmp_path, rounds=1)
    assert evaluation.exit_code(report) != 0
    assert_reports(tmp_path, False)


def test_partial_import_lost_response_cleans_every_attempt_and_still_writes_failed_reports(tmp_path):
    peer = Peer(fail_import=2)
    with peer.client() as client:
        report = evaluation.run_evaluation(client, fixture(), tmp_path)
    assert evaluation.exit_code(report) != 0
    saved = assert_reports(tmp_path, False)
    attempted = [row["document_key"] for row in peer.syncs if row["action"] == "UPSERT"]
    deleted = [row["document_key"] for row in peer.syncs if row["action"] == "DELETE"]
    assert len(attempted) == 2
    assert deleted == attempted
    assert "ReadTimeout" in json.dumps(saved["errors"])


def test_cleanup_failure_is_independent_failure_and_does_not_skip_remaining_documents(tmp_path):
    peer = Peer(fail_delete=1)
    with peer.client() as client:
        report = evaluation.run_evaluation(client, fixture(), tmp_path)
    assert evaluation.exit_code(report) != 0
    saved = assert_reports(tmp_path, False)
    assert len([row for row in peer.syncs if row["action"] == "DELETE"]) == 7
    assert len(saved["cleanup_errors"]) == 1
    assert "ReadTimeout" in json.dumps(saved["cleanup_errors"])


def test_query_exception_has_failed_json_and_self_consistent_junit(tmp_path):
    peer = Peer(fail_query=True)
    with peer.client() as client:
        report = evaluation.run_evaluation(client, fixture(), tmp_path)
    assert evaluation.exit_code(report) != 0
    saved = assert_reports(tmp_path, False)
    assert "ReadTimeout" in json.dumps(saved["errors"])
    assert len([row for row in peer.syncs if row["action"] == "DELETE"]) == 7


def test_runner_stops_only_the_owned_process_handle_and_reaps_after_kill():
    import run_rag_baseline as runner
    class Process:
        def __init__(self): self.actions, self.waits, self.returncode = [], 0, None
        def poll(self): return self.returncode
        def terminate(self): self.actions.append("terminate")
        def kill(self): self.actions.append("kill")
        def wait(self, timeout=None):
            self.waits += 1; self.actions.append("wait")
            if self.waits == 1: raise subprocess.TimeoutExpired("owned", timeout)
            self.returncode = 0
            return self.returncode
    owned, unrelated = Process(), Process()
    runner.stop_owned_process(owned, timeout=0.01)
    assert owned.actions == ["terminate", "wait", "kill", "wait"]
    assert owned.poll() == 0
    assert unrelated.actions == []
    assert unrelated.poll() is None


@pytest.mark.parametrize("collection,prefix", [
    ("beadhouse_policy_chunks_v2", "silvercare_eval_" + "a" * 32),
    ("silvercare_eval_" + "b" * 32 + "_v2", "silvercare_eval_" + "a" * 32),
    ("silvercare_eval_" + "a" * 32 + "_v2", "silvercare_eval_"),
    ("silvercare_eval_" + "a" * 32 + "_v2_extra", "silvercare_eval_" + "a" * 32),
])
def test_runner_refuses_non_owned_or_broad_collection_cleanup(collection, prefix):
    import run_rag_baseline as runner
    class Client:
        def __init__(self): self.calls = []
        def has_collection(self, **kwargs): self.calls.append("has"); return True
        def drop_collection(self, **kwargs): self.calls.append("drop")
    client = Client()
    with pytest.raises(ValueError): runner.drop_owned_collection(client, collection, prefix)
    assert client.calls == [], "ownership must be checked before contacting any collection"


def test_runner_confirms_exact_owned_collection_was_dropped_and_reports_failure():
    import run_rag_baseline as runner
    prefix = "silvercare_eval_" + "a" * 32
    collection = prefix + "_v2"
    class Client:
        def __init__(self, sticky=False): self.exists, self.sticky, self.calls = True, sticky, []
        def has_collection(self, **kwargs):
            self.calls.append(("has", kwargs["collection_name"])); return self.exists
        def drop_collection(self, **kwargs):
            self.calls.append(("drop", kwargs["collection_name"]))
            if not self.sticky: self.exists = False
    good = Client()
    runner.drop_owned_collection(good, collection, prefix)
    assert good.calls == [("has", collection), ("drop", collection), ("has", collection)]
    sticky = Client(sticky=True)
    with pytest.raises(RuntimeError): runner.drop_owned_collection(sticky, collection, prefix)


def test_runner_uses_fresh_run_directory_and_cleanup_failure_changes_final_exit(tmp_path, monkeypatch):
    import run_rag_baseline as runner
    opened = []
    @contextmanager
    def owned(**kwargs):
        opened.append(kwargs)
        peer = Peer()
        errors, cleanup = [], {}
        with peer.client() as client:
            yield dict(client=client, metadata=diagnostics(), cleanup_errors=errors, cleanup=cleanup)
        errors.append(dict(phase="owned-process-stop", type="RuntimeError", message="owned child failed to terminate"))
    monkeypatch.setattr(runner, "create_owned_service", owned)
    # A stale arbitrary URL must never become a baseline import target.
    monkeypatch.setenv("RAG_EVAL_URL", "http://daily-service.invalid:8001")
    for _ in range(2):
        code = runner.run_baseline(report_root=tmp_path, fixture_path=FIXTURE_PATH,
                                   milvus_uri="http://dedicated.invalid:19530", milvus_token="test-only-token")
        assert code != 0
    assert len(opened) == 2
    assert Path(opened[0]["report_dir"]) != Path(opened[1]["report_dir"])
    for options in opened:
        directory = Path(options["report_dir"])
        assert directory.is_relative_to(tmp_path)
        report = assert_reports(directory, False)
        assert "owned child failed to terminate" in json.dumps(report["cleanup_errors"])


def test_runner_startup_exception_still_writes_failed_json_and_junit(tmp_path, monkeypatch):
    import run_rag_baseline as runner
    @contextmanager
    def unavailable(**kwargs):
        raise TimeoutError("owned child did not become ready")
        yield
    monkeypatch.setattr(runner, "create_owned_service", unavailable)
    code = runner.run_baseline(report_root=tmp_path, fixture_path=FIXTURE_PATH,
                               milvus_uri="http://dedicated.invalid:19530", milvus_token="test-only-token")
    assert code != 0
    reports = list(tmp_path.glob("*/report.json"))
    assert len(reports) == 1
    report = assert_reports(reports[0].parent, False)
    assert "TimeoutError" in json.dumps(report["errors"])


def test_actual_owned_context_preserves_startup_and_multiple_cleanup_failures_before_yield(tmp_path, monkeypatch):
    import run_rag_baseline as runner
    events = []
    class Process:
        pid = 477001
        def __init__(self, *args, **kwargs): events.append("owned-child-created")
        def poll(self): return None
    class Client:
        def __init__(self, **kwargs): pass
        def get(self, path): raise httpx.ConnectError("synthetic child not ready")
        def close(self):
            events.append("client-close-attempted")
            raise OSError("synthetic client cleanup failed")
    def stop(process, timeout=10):
        assert isinstance(process, Process)
        events.append("owned-process-stop-attempted")
        raise OSError("synthetic child cleanup failed")
    monkeypatch.setattr(runner, "source_metadata", lambda path: {"fixture_sha256": "fixture-owned-test"})
    monkeypatch.setattr(runner.subprocess, "Popen", Process)
    monkeypatch.setattr(runner.httpx, "Client", Client)
    monkeypatch.setattr(runner, "stop_owned_process", stop)
    code = runner.run_baseline(report_root=tmp_path, fixture_path=FIXTURE_PATH, backend="memory", startup_timeout=0)
    assert code != 0
    reports = list(tmp_path.glob("*/report.json"))
    assert len(reports) == 1
    report = assert_reports(reports[0].parent, False)
    assert events == ["owned-child-created", "client-close-attempted", "owned-process-stop-attempted"]
    assert any(error["type"] == "TimeoutError" for error in report["errors"])
    failures = {error["phase"]: error for error in report["cleanup_errors"]}
    assert set(failures) == {"http-client-close", "owned-process-stop"}
    assert "client cleanup failed" in failures["http-client-close"]["message"]
    assert "child cleanup failed" in failures["owned-process-stop"]["message"]
    assert report["cleanup"]["process_stopped"] is False
    assert report["cleanup"]["data_removed"] is True


def test_startup_failure_keeps_successful_cleanup_facts_even_before_context_yield(tmp_path, monkeypatch):
    import run_rag_baseline as runner
    class Process:
        pid = 477002
        returncode = None
        def __init__(self, *args, **kwargs): pass
        def poll(self): return self.returncode
    class Client:
        def __init__(self, **kwargs): pass
        def get(self, path): raise httpx.ConnectError("synthetic startup failure")
        def close(self): pass
    monkeypatch.setattr(runner, "source_metadata", lambda path: {})
    monkeypatch.setattr(runner.subprocess, "Popen", Process)
    monkeypatch.setattr(runner.httpx, "Client", Client)
    monkeypatch.setattr(runner, "stop_owned_process", lambda process, timeout=10: setattr(process, "returncode", 0))
    code = runner.run_baseline(report_root=tmp_path, fixture_path=FIXTURE_PATH, backend="memory", startup_timeout=0)
    assert code != 0
    report = assert_reports(next(tmp_path.glob("*/report.json")).parent, False)
    assert report["cleanup_errors"] == []
    assert report["cleanup"] == dict(process_stopped=True, collection_removed=True, data_removed=True)
    assert any(error["type"] == "TimeoutError" for error in report["errors"])


def test_identity_failure_retains_rejected_raw_query_payload(tmp_path):
    peer = Peer(mutate_query=lambda value, question: value.update(embedding_backend="hashed", degraded=False,
                                                                  unexpected_identity_marker="raw-evidence-fixture"))
    with peer.client() as client:
        report = evaluation.run_evaluation(client, fixture(), tmp_path)
    assert evaluation.exit_code(report) != 0
    saved = assert_reports(tmp_path, False)
    rejected = [row for row in saved["diagnostics"] if row.get("phase") == "query-identity"]
    assert len(rejected) == 1
    assert rejected[0]["raw_payload"]["embedding_backend"] == "hashed"
    assert rejected[0]["raw_payload"]["unexpected_identity_marker"] == "raw-evidence-fixture"
    assert rejected[0]["question"] == peer.queries[0]["question"]
    assert rejected[0]["latency_ms"] >= 0


def test_owned_child_freezes_retrieval_settings_and_reports_them_despite_parent_environment(tmp_path, monkeypatch):
    import run_rag_baseline as runner
    captured = {}
    class Process:
        pid = 477003
        returncode = None
        def __init__(self, *args, **kwargs): captured.update(kwargs["env"])
        def poll(self): return self.returncode
    class Client:
        def __init__(self, **kwargs): pass
        def close(self): pass
    expected = dict(semantic_dense_min_score=0.55, hashed_dense_min_score=0.05, top_k=5)
    monkeypatch.setenv("RAG_SEMANTIC_DENSE_MIN_SCORE", "-10")
    monkeypatch.setenv("RAG_HASHED_DENSE_MIN_SCORE", "-20")
    monkeypatch.setenv("RAG_DEFAULT_TOP_K", "20")
    monkeypatch.setattr(runner.subprocess, "Popen", Process)
    monkeypatch.setattr(runner.httpx, "Client", Client)
    monkeypatch.setattr(runner, "_http_identity", lambda client, instance, collection, backend: diagnostics(instance_id=instance))
    monkeypatch.setattr(runner, "stop_owned_process", lambda process, timeout=10: setattr(process, "returncode", 0))
    with runner.create_owned_service(report_dir=tmp_path, backend="memory", milvus_uri=None, milvus_token=None) as owned:
        assert captured["RAG_SEMANTIC_DENSE_MIN_SCORE"] == "0.55"
        assert captured["RAG_HASHED_DENSE_MIN_SCORE"] == "0.05"
        assert captured["RAG_DEFAULT_TOP_K"] == "5"
        assert owned["metadata"]["retrieval_config"] == expected


def test_invalid_sixth_citation_is_not_hidden_by_top_five_quality_metrics(tmp_path):
    rows = valid_results()
    rows[0]["hits"] = [dict(chunk_id=900001, score=1)] * 5 + [dict(chunk_id=999999999, score=0)]
    metrics = evaluation.calculate_metrics(rows)
    assert metrics["invalid_citations"] == 1
    assert metrics["citation_id_validity"] < 1.0
    peer = Peer(mutate_query=lambda value, question: value.update(hits=[dict(chunk_id=900001, score=1)] * 5
                                                                   + [dict(chunk_id=999999999, score=0)]))
    with peer.client() as client:
        report = evaluation.run_evaluation(client, fixture(), tmp_path)
    assert evaluation.exit_code(report) != 0
    assert_reports(tmp_path, False)


def test_owned_tokens_in_startup_exception_are_redacted_from_json_and_xml(tmp_path, monkeypatch):
    import run_rag_baseline as runner
    tokens = {}
    milvus_token = "synthetic-milvus-evaluation-token-DO-NOT-PUBLISH"
    class Process:
        pid = 477004
        returncode = None
        def __init__(self, *args, **kwargs): tokens["internal"] = kwargs["env"]["RAG_INTERNAL_TOKEN"]
        def poll(self): return self.returncode
    class Client:
        def __init__(self, **kwargs): pass
        def get(self, path): raise ValueError("rejected identity tokens: " + tokens["internal"] + " " + milvus_token)
        def close(self): pass
    monkeypatch.setattr(runner, "source_metadata", lambda path: {})
    monkeypatch.setattr(runner.subprocess, "Popen", Process)
    monkeypatch.setattr(runner.httpx, "Client", Client)
    monkeypatch.setattr(runner, "stop_owned_process", lambda process, timeout=10: setattr(process, "returncode", 0))
    code = runner.run_baseline(report_root=tmp_path, fixture_path=FIXTURE_PATH, backend="memory",
                               milvus_token=milvus_token, startup_timeout=0)
    assert code != 0
    directory = next(tmp_path.glob("*/report.json")).parent
    assert_reports(directory, False)
    for name in ("report.json", "report.xml"):
        raw = (directory / name).read_text(encoding="utf-8")
        assert tokens["internal"] not in raw
        assert milvus_token not in raw
        assert "redacted" in raw
