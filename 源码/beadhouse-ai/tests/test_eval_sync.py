import httpx
import pytest

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


def test_evaluation_cleans_up_every_attempted_import_even_when_second_response_is_lost(monkeypatch):
    calls = []
    class FakeClient:
        def __init__(self, **kwargs): pass
        def __enter__(self): return self
        def __exit__(self, *args): pass
    def sync(client, doc, version, action):
        calls.append((doc["document_key"], version, action))
        if action == "UPSERT" and len(calls) == 2:
            raise httpx.ReadTimeout("lost import response")
    monkeypatch.setenv("RAG_INTERNAL_TOKEN", "eval-test-only-token")
    monkeypatch.setattr(evaluate_rag.httpx, "Client", FakeClient)
    monkeypatch.setattr(evaluate_rag, "sync_document", sync)
    with pytest.raises(httpx.ReadTimeout):
        evaluate_rag.main()
    assert len(calls) == 4
    assert [(key, version, action) for key, version, action in calls[2:]] == [
        (key, 2, "DELETE") for key, _, _ in calls[:2]]


def test_evaluation_reports_cleanup_failure_and_attempts_remaining_documents(monkeypatch):
    calls = []
    class FakeClient:
        def __init__(self, **kwargs): pass
        def __enter__(self): return self
        def __exit__(self, *args): pass
    def sync(client, doc, version, action):
        calls.append((doc["document_key"], action))
        if action == "UPSERT" and len(calls) == 2:
            raise httpx.ReadTimeout("lost import response")
        if action == "DELETE" and len(calls) == 3:
            raise httpx.ReadTimeout("lost cleanup response")
    monkeypatch.setenv("RAG_INTERNAL_TOKEN", "eval-test-only-token")
    monkeypatch.setattr(evaluate_rag.httpx, "Client", FakeClient)
    monkeypatch.setattr(evaluate_rag, "sync_document", sync)
    with pytest.raises(RuntimeError, match="cleanup incomplete"):
        evaluate_rag.main()
    assert [action for _, action in calls] == ["UPSERT", "UPSERT", "DELETE", "DELETE"]
