"""Opt-in real Milvus v2 test; never reuses the application's normal collection."""
import contextlib
import hashlib
import json
import os
import uuid

import pytest

from app.backend import BackendUnavailable, RetrievalBackend
from app.settings import Settings
from app.store import JsonDocumentStore


def change(version, action="UPSERT", number=1, text="交班记录必须核对并签名。"):
    value = dict(document_key="version-test", version=version, action=action,
                 title="版本交班制度", source="test-only",
                 chunks=[] if action == "DELETE" else [dict(chunk_id=number, section_no=1, content=text)])
    canonical = {key: value[key] for key in ("action", "chunks", "document_key", "source", "title")}
    value["payload_hash"] = hashlib.sha256(json.dumps(canonical, ensure_ascii=False, sort_keys=True,
                                                     separators=(",", ":")).encode("utf-8")).hexdigest()
    return value


@pytest.fixture
def real_backend(tmp_path):
    uri, token = os.getenv("RAG_TEST_MILVUS_URI"), os.getenv("RAG_TEST_MILVUS_TOKEN")
    if not uri or not token:
        pytest.skip("Requires dedicated RAG_TEST_MILVUS_URI and RAG_TEST_MILVUS_TOKEN")
    from pymilvus import MilvusClient
    collection = "version_test_" + uuid.uuid4().hex + "_v2"
    settings = Settings(_env_file=None, internal_token="version-test-only-token-of-at-least-32-bytes",
                        backend="milvus", embedding_backend="hashed", milvus_uri=uri,
                        milvus_token=token, milvus_collection=collection)
    backend = RetrievalBackend(settings, JsonDocumentStore(tmp_path / "versions.json"))
    initial_index = backend._milvus
    probe = MilvusClient(uri=uri, token=token)
    try:
        assert backend.name == "milvus-hybrid", "A real Milvus connection is required, not fallback"
        assert backend._milvus.collection != "beadhouse_policy_chunks"
        yield backend, probe
    finally:
        if backend._milvus is not None:
            with contextlib.suppress(Exception): backend._milvus.client.close()
        if initial_index is not None and initial_index is not backend._milvus:
            with contextlib.suppress(Exception): initial_index.client.close()
        # Drop only this random, test-owned collection (or its v2-suffixed name).
        owned = {collection, collection + "_v2"}
        for name in owned:
            if probe.has_collection(collection_name=name):
                probe.drop_collection(collection_name=name)
        probe.close()


def rows(backend, probe):
    return probe.query(collection_name=backend._milvus.collection,
                       filter='doc_id == "version-test"', output_fields=["*"],
                       consistency_level="Strong")


def test_real_latest_version_survives_late_old_vector_write_and_old_delete(real_backend):
    backend, probe = real_backend
    older = change(1, number=101)
    newer = change(2, number=102, text="新版交班记录必须双人核对并签名。")
    backend.sync_document(**older)
    backend.sync_document(**newer)
    index = backend._milvus
    # A delayed worker can finish its immutable old index write after version 2.
    index.upsert_document(older["document_key"], older["title"], older["source"], older["chunks"],
                          older["version"], older["payload_hash"])
    index.delete_document("version-test", 1)
    raw = rows(backend, probe)
    assert any(row["chunk_id"] == 102 for row in raw), "old delete must not physically remove version 2"
    active, hits, degraded, fallback = backend.search("新版交班记录双人核对", 5)
    assert active == "milvus-hybrid" and not degraded and fallback is None
    assert hits and {hit["chunk_id"] for hit in hits} == {102}


def test_real_delete_tombstone_filters_late_immutable_upsert_after_restart(real_backend):
    backend, probe = real_backend
    older = change(1, number=101)
    backend.sync_document(**older)
    backend.sync_document(**change(3, "DELETE"))
    index = backend._milvus
    index.upsert_document(older["document_key"], older["title"], older["source"], older["chunks"],
                          older["version"], older["payload_hash"])
    restarted = RetrievalBackend(backend.settings, JsonDocumentStore(backend.store.path))
    try:
        active, hits, degraded, fallback = restarted.search("交班记录必须核对", 5)
        assert active == "milvus-hybrid" and not degraded
        assert hits == [], "persisted current tombstone must filter physically present late rows"
        with pytest.raises(ValueError):
            restarted.sync_document(**change(2, number=102))
        assert restarted.store.all_chunks() == []
    finally:
        if restarted._milvus is not None: restarted._milvus.client.close()


def test_real_failed_embedding_keeps_previous_rows_but_only_current_pending_text_is_returned(real_backend, monkeypatch):
    backend, probe = real_backend
    backend.sync_document(**change(1, number=101))
    index = backend._milvus
    with monkeypatch.context() as patch:
        def fail(*args): raise RuntimeError("synthetic model preparation fault")
        patch.setattr(backend.embedder, "embed", fail)
        with pytest.raises(BackendUnavailable):
            backend.sync_document(**change(2, number=102, text="新版交班必须签名后核对。"))
    backend._milvus = index
    raw = rows(backend, probe)
    assert any(row["chunk_id"] == 101 for row in raw)
    assert not any(row["chunk_id"] == 102 for row in raw)
    active, hits, degraded, fallback = backend.search("新版交班必须签名", 5)
    assert active == "memory-hybrid" and degraded and fallback
    assert hits and {hit["chunk_id"] for hit in hits} == {102}
    backend.sync_document(**change(2, number=102, text="新版交班必须签名后核对。"))
    active, hits, degraded, fallback = backend.search("新版交班必须签名", 5)
    assert active == "milvus-hybrid" and not degraded
    assert hits and {hit["chunk_id"] for hit in hits} == {102}


def test_real_recreated_collection_rejects_cached_ack_and_repairs_same_version(real_backend):
    from app.milvus_backend import MilvusHybridBackend
    backend, probe = real_backend
    value = change(1, number=101)
    backend.sync_document(**value)
    old_index = backend._milvus
    assert old_index.collection.startswith("version_test_")
    probe.drop_collection(collection_name=old_index.collection)
    replacement = MilvusHybridBackend(backend.settings, backend.embedder)
    try:
        assert replacement.collection == old_index.collection
        assert replacement.collection_id != old_index.collection_id
        with pytest.raises(BackendUnavailable):
            backend.sync_document(**value)
        backend._milvus = replacement
        active, hits, degraded, fallback = backend.search("交班记录必须核对", 5)
        assert active == "memory-hybrid" and degraded and fallback
        assert hits and {hit["chunk_id"] for hit in hits} == {101}
        backend.sync_document(**value)
        active, hits, degraded, fallback = backend.search("交班记录必须核对", 5)
        assert active == "milvus-hybrid" and not degraded
        assert hits and {hit["chunk_id"] for hit in hits} == {101}
    finally:
        if backend._milvus is not replacement:
            replacement.client.close()
