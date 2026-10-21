import httpx
import pytest
import json
from pathlib import Path
import xml.etree.ElementTree as ET

import evaluate_rag


def document():
    return dict(document_key="eval-owned", title="测试", source="test",
                chunks=[dict(chunk_id=1, section_no=1, content="核对记录。")])


def test_evaluation_requires_matching_version_echo_and_sends_hashed_tombstone():
    captured = []

    def handle(request):
        import json
        body = json.loads(request.content)
        captured.append(body)
        assert request.url.path == "/v2/documents/sync"
        return httpx.Response(200, json=dict(body, applied=True))

    original = document()
    with httpx.Client(transport=httpx.MockTransport(handle), base_url="http://test") as client:
        evaluate_rag.sync_document(client, original, 1, "UPSERT")
        evaluate_rag.sync_document(client, original, 2, "DELETE")
    assert original == document()
    assert captured[1]["chunks"] == []
    assert captured[0]["payload_hash"] != captured[1]["payload_hash"]


@pytest.mark.parametrize("field,value", [("version", 99), ("payload_hash", "0" * 64), ("applied", False)])
def test_evaluation_rejects_success_status_with_unconfirmed_sync(field, value):
    def handle(request):
        import json
        body = json.loads(request.content)
        return httpx.Response(200, json=dict(body, applied=True) | {field: value})
    with httpx.Client(transport=httpx.MockTransport(handle), base_url="http://test") as client:
        with pytest.raises(ValueError, match="does not confirm"):
            evaluate_rag.sync_document(client, document(), 1, "UPSERT")


def evidence_client():
    """Explicit injected peer keeps lifecycle tests away from the real CLI runner."""
    model = "BAAI/bge-small-zh-v1.5"
    evidence = dict(instance_id="eval-sync-fixture", backend="milvus-hybrid", requested_backend="milvus",
                    embedding_backend=model, embedding_requested_backend="fastembed", embedding_requested_model=model,
                    embedding_loaded=True, dimension=512, configured_dimension=512, degraded=False,
                    embedding_degraded=False, fallback_code=None, embedding_fallback_code=None,
                    model=dict(name=model, onnx_files=[dict(name="model.onnx", sha256="1a" * 32, size_bytes=100)]),
                    dependencies=dict(fastembed="0.8.1", onnxruntime="1.30.0", pymilvus="2.6.17"),
                    milvus_collection="eval-sync-fixture_v2", milvus_collection_generation=1)
    return httpx.Client(base_url="http://injected-eval-fixture", transport=httpx.MockTransport(
        lambda request: httpx.Response(200, json=dict(evidence, status="ok"))))


def assert_failed_artifacts(tmp_path):
    saved = json.loads((tmp_path / "report.json").read_text(encoding="utf-8"))
    assert saved["status"] == "failed"
    xml = ET.parse(tmp_path / "report.xml").getroot()
    assert list(xml.iter("failure")) or list(xml.iter("error"))
    return saved


def test_evaluation_cleans_up_every_attempted_import_even_when_second_response_is_lost(monkeypatch, tmp_path):
    calls = []
    def sync(client, doc, version, action):
        calls.append((doc["document_key"], version, action))
        if action == "UPSERT" and len(calls) == 2:
            raise httpx.ReadTimeout("lost import response")
    monkeypatch.setattr(evaluate_rag, "sync_document", sync)
    fixture = json.loads((Path(__file__).resolve().parents[1] / "evals/policy_rag_cases.json").read_text(encoding="utf-8"))
    with evidence_client() as client:
        report = evaluate_rag.run_evaluation(client, fixture, tmp_path)
    assert report["status"] == "failed"
    assert "ReadTimeout" in json.dumps(report["errors"])
    assert_failed_artifacts(tmp_path)
    assert len(calls) == 4
    assert [(key, version, action) for key, version, action in calls[2:]] == [
        (key, 2, "DELETE") for key, _, _ in calls[:2]]


def test_evaluation_reports_cleanup_failure_and_attempts_remaining_documents(monkeypatch, tmp_path):
    calls = []
    def sync(client, doc, version, action):
        calls.append((doc["document_key"], action))
        if action == "UPSERT" and len(calls) == 2:
            raise httpx.ReadTimeout("lost import response")
        if action == "DELETE" and len(calls) == 3:
            raise httpx.ReadTimeout("lost cleanup response")
    monkeypatch.setattr(evaluate_rag, "sync_document", sync)
    fixture = json.loads((Path(__file__).resolve().parents[1] / "evals/policy_rag_cases.json").read_text(encoding="utf-8"))
    with evidence_client() as client:
        report = evaluate_rag.run_evaluation(client, fixture, tmp_path)
    assert report["status"] == "failed"
    assert "ReadTimeout" in json.dumps(report["errors"])
    assert "ReadTimeout" in json.dumps(report["cleanup_errors"])
    assert_failed_artifacts(tmp_path)
    assert [action for _, action in calls] == ["UPSERT", "UPSERT", "DELETE", "DELETE"]
