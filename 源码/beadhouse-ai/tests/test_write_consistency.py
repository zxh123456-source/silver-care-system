import json
import importlib
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest

from app.backend import BackendUnavailable, RetrievalBackend
from app.milvus_backend import MilvusHybridBackend
from app.settings import Settings
from app.store import JsonDocumentStore
from app.protocol import canonical_payload_hash


def chunk(text="old", number=1):
    return {"chunk_id": number, "section_no": 1, "content": text}


def write(target, key, title, source, chunks, version=1, action="UPSERT"):
    digest = canonical_payload_hash(key, title, source, chunks, action)
    operation = target.apply_sync if isinstance(target, JsonDocumentStore) else target.sync_document
    return operation(key, version, action, digest, title, source, chunks)


@pytest.mark.parametrize("operation", ["upsert", "delete"])
def test_failed_file_replace_keeps_memory_and_disk_unchanged(tmp_path, monkeypatch, operation):
    store = JsonDocumentStore(tmp_path / "docs.json")
    write(store, "policy", "old title", "source", [chunk()])
    before, saved = store.all_chunks(), store.path.read_bytes()
    def fail_replace(*args):
        raise OSError("test replacement failure")
    with monkeypatch.context() as patch:
        patch.setattr(Path, "replace", fail_replace)
        with pytest.raises(OSError):
            if operation == "upsert":
                write(store, "policy", "new title", "source", [chunk("new", 2)], 2)
            else:
                write(store, "policy", "old title", "source", [], 2, "DELETE")
    assert store.all_chunks() == before
    assert store.path.read_bytes() == saved
    assert JsonDocumentStore(store.path).all_chunks() == before
    assert not list(tmp_path.glob("*.tmp"))


def test_invalid_new_chunk_does_not_remove_the_previous_document(tmp_path):
    store = JsonDocumentStore(tmp_path / "docs.json")
    write(store, "policy", "title", "source", [chunk()])
    before = store.all_chunks()
    with pytest.raises(ValueError):
        write(store, "policy", "new title", "source", [chunk("bad", "invalid-id")], 2)
    assert store.all_chunks() == before


@pytest.mark.parametrize("content", ["{broken", '{"unexpected":"object"}', '[{}]'])
def test_corrupt_store_fails_explicitly_without_overwriting_file(tmp_path, content):
    path = tmp_path / "docs.json"
    path.write_text(content, encoding="utf-8")
    with pytest.raises(ValueError):
        JsonDocumentStore(path)
    assert path.read_text(encoding="utf-8") == content


def test_embedding_failure_does_not_delete_the_existing_milvus_document():
    class EmbeddingFailure:
        def embed(self, texts):
            raise RuntimeError("test model failure")
    class Client:
        def __init__(self): self.deleted = []
        def delete(self, **kwargs): self.deleted.append(kwargs); return {"delete_count": 1}
    index = MilvusHybridBackend.__new__(MilvusHybridBackend)
    index.client, index.collection, index.embedder = Client(), "test", EmbeddingFailure()
    with pytest.raises(RuntimeError, match="test model failure"):
        index.upsert_document("policy", "title", "source", [chunk()], 1, "a" * 64)
    assert index.client.deleted == []


def backend(tmp_path):
    settings = Settings(_env_file=None, internal_token="test-only-internal-token-at-least-32-bytes",
                        backend="memory", embedding_backend="hashed")
    result = RetrievalBackend(settings, JsonDocumentStore(tmp_path / "docs.json"))
    settings.backend = "milvus"  # Synthetic index below, without opening any network connection.
    return result


def test_failed_local_persistence_never_mutates_remote_index(tmp_path, monkeypatch):
    service = backend(tmp_path)
    class Remote:
        calls = 0
        def upsert_document(self, *args): self.calls += 1
    remote = Remote()
    service._milvus = remote
    def fail_replace(*args): raise OSError("test disk failure")
    monkeypatch.setattr(Path, "replace", fail_replace)
    with pytest.raises(BackendUnavailable, match="storage"):
        write(service, "policy", "title", "source", [chunk()])
    assert remote.calls == 0
    assert service.store.all_chunks() == []


@pytest.mark.parametrize("operation", ["index", "delete"])
def test_api_storage_failures_return503_without_changing_committed_data(tmp_path, monkeypatch, operation):
    from fastapi.testclient import TestClient
    from app.settings import get_settings
    token = "api-test-only-internal-token-at-least-32-bytes"
    monkeypatch.setenv("RAG_INTERNAL_TOKEN", token)
    monkeypatch.setenv("RAG_EMBEDDING_BACKEND", "hashed")
    monkeypatch.setenv("RAG_BACKEND", "memory")
    monkeypatch.setenv("RAG_DATA_FILE", str(tmp_path / "api.json"))
    get_settings.cache_clear()
    api = importlib.import_module("app.main")
    service = RetrievalBackend(Settings(_env_file=None, internal_token=token, embedding_backend="hashed"),
                               JsonDocumentStore(tmp_path / "committed.json"))
    write(service, "policy", "old", "source", [chunk()])
    before = service.store.all_chunks()
    monkeypatch.setattr(api, "backend", service)
    monkeypatch.setattr(api, "settings", service.settings)
    def fail_replace(*args): raise OSError("private filesystem detail must not be returned")
    monkeypatch.setattr(Path, "replace", fail_replace)
    payload = dict(document_key="policy", version=2, action="UPSERT" if operation == "index" else "DELETE",
                   title="new", source="source", chunks=[chunk("new", 2)] if operation == "index" else [])
    payload["payload_hash"] = canonical_payload_hash(payload['document_key'], payload['title'], payload['source'],
                                                      payload['chunks'], payload['action'])
    try:
        with TestClient(api.app) as http:
            assert http.post("/v2/documents/sync", json=payload).status_code == 401
            response = http.post("/v2/documents/sync", json=payload, headers={"X-Internal-Token": token})
            assert response.status_code == 503
            assert response.json() == {"detail": "Document storage unavailable"}
        assert service.store.all_chunks() == before
        assert JsonDocumentStore(service.store.path).all_chunks() == before
    finally:
        get_settings.cache_clear()


def test_overlapping_document_updates_cannot_publish_an_older_remote_version(tmp_path, monkeypatch):
    service = backend(tmp_path)
    first_entered, release_first, second_started, second_published = (threading.Event() for _ in range(4))
    original = service.store.apply_sync
    def observed_upsert(key, version, action, payload_hash, title, source, chunks):
        count = original(key, version, action, payload_hash, title, source, chunks)
        if title == "second": second_published.set()
        return count
    monkeypatch.setattr(service.store, "apply_sync", observed_upsert)
    class Remote:
        title = None
        def upsert_document(self, key, title, source, chunks, version, payload_hash):
            if title == "first":
                first_entered.set()
                assert release_first.wait(5)
            self.title = title
    remote = Remote()
    service._milvus = remote
    def second():
        second_started.set()
        return write(service, "policy", "second", "source", [chunk("second", 2)], 2)
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(write, service, "policy", "first", "source", [chunk("first")])
        assert first_entered.wait(5)
        later = pool.submit(second)
        assert second_started.wait(5)
        try:
            second_published.wait(1)
            midflight = service.store.all_chunks()
        finally:
            release_first.set()
        first.result(timeout=5); later.result(timeout=5)
    assert midflight[0]["title"] == "first"
    assert remote.title == service.store.all_chunks()[0]["title"] == "second"
    assert json.loads(service.store.path.read_text(encoding="utf-8"))["documents"]["policy"]["title"] == "second"
