"""Opt-in real Milvus regression, confined to a uniquely named disposable collection."""
import contextlib
import os
import time
import uuid
from pathlib import Path
from urllib.parse import urlparse

import pytest

from app.backend import BackendUnavailable, RetrievalBackend
from app.settings import Settings
from app.store import JsonDocumentStore
from app.protocol import canonical_payload_hash
from app.milvus_backend import MilvusHybridBackend


def write(service, title, chunks, version, action="UPSERT"):
    digest = canonical_payload_hash("policy", title, "test", chunks, action)
    return service.sync_document("policy", version, action, digest, title, "test", chunks)


def row(version, chunk_id):
    return MilvusHybridBackend.row_key("policy", version, chunk_id)


def test_real_milvus_preparation_failure_and_disk_failure_preserve_index_then_retry(tmp_path, monkeypatch):
    uri, token = os.getenv("RAG_TEST_MILVUS_URI"), os.getenv("RAG_TEST_MILVUS_TOKEN")
    if not uri or not token:
        pytest.skip("Requires dedicated RAG_TEST_MILVUS_URI and RAG_TEST_MILVUS_TOKEN")
    hosts = {item for item in os.getenv("NO_PROXY", "").split(",") if item}
    hosts.update({urlparse(uri).hostname or "127.0.0.1", "127.0.0.1", "localhost"})
    monkeypatch.setenv("NO_PROXY", ",".join(sorted(hosts)))
    monkeypatch.setenv("no_proxy", ",".join(sorted(hosts)))
    monkeypatch.setenv("no_grpc_proxy", ",".join(sorted(hosts)))
    from pymilvus import MilvusClient
    probe = None
    for _ in range(30):
        try:
            probe = MilvusClient(uri=uri, token=token, timeout=2)
            probe.list_collections()
            break
        except Exception:
            if probe is not None:
                with contextlib.suppress(Exception): probe.close()
                probe = None
            time.sleep(1)
    assert probe is not None, "Dedicated Milvus did not become ready"
    collection = "write_test_" + uuid.uuid4().hex
    service = None
    try:
        settings = Settings(_env_file=None, internal_token="test-only-internal-token-at-least-32-bytes",
                            backend="milvus", embedding_backend="hashed", milvus_uri=uri,
                            milvus_token=token, milvus_collection=collection)
        service = RetrievalBackend(settings, JsonDocumentStore(tmp_path / "documents.json"))
        assert service.name == "milvus-hybrid", "Test must exercise real Milvus, not memory fallback"
        collection = service._milvus.collection
        old = [{"chunk_id": 1, "section_no": 1, "content": "交班记录应由工作人员核对。"}]
        new = [{"chunk_id": 2, "section_no": 1, "content": "新交班记录应由工作人员核对并签字。"}]
        write(service, "old", old, 1)
        def fail_embedding(texts): raise RuntimeError("synthetic embedding failure")
        with monkeypatch.context() as patch:
            patch.setattr(service.embedder, "embed", fail_embedding)
            with pytest.raises(BackendUnavailable, match="indexing"):
                write(service, "new", new, 2)
        assert probe.get(collection_name=collection, ids=[row(1, 1)], output_fields=["title"], consistency_level="Strong")[0]["title"] == "old"
        assert probe.get(collection_name=collection, ids=[row(2, 2)], consistency_level="Strong") == []
        write(service, "new", new, 2)
        assert probe.get(collection_name=collection, ids=[row(1, 1)], consistency_level="Strong") == []
        assert probe.get(collection_name=collection, ids=[row(2, 2)], output_fields=["title"], consistency_level="Strong")[0]["title"] == "new"
        before = service.store.all_chunks()
        with monkeypatch.context() as patch:
            def fail_replace(*args): raise OSError("synthetic persistence failure")
            patch.setattr(Path, "replace", fail_replace)
            with pytest.raises(BackendUnavailable, match="storage"):
                write(service, "new", [], 3, "DELETE")
        assert service.store.all_chunks() == before
        assert probe.get(collection_name=collection, ids=[row(2, 2)], consistency_level="Strong")
        backend, hits, degraded, _ = service.search("新交班记录", 5)
        assert backend == "milvus-hybrid" and not degraded
        assert any(hit["chunk_id"] == 2 for hit in hits)
        write(service, "new", [], 3, "DELETE")
        assert probe.get(collection_name=collection, ids=[row(2, 2)], consistency_level="Strong") == []
    finally:
        with contextlib.suppress(Exception): probe.drop_collection(collection_name=collection)
        if service is not None and service._milvus is not None:
            service._milvus.client.close()
        probe.close()
