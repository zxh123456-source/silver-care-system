"""Embedding evidence uses actual loaded artifacts rather than requested configuration."""
import importlib
import hashlib

import pytest

from app.embedding import EmbeddingProvider
from app.settings import Settings, get_settings


TOKEN = "diagnostics-test-only-token-of-at-least-32-bytes"


def test_explicit_hashed_is_not_a_loaded_bge_model_even_without_fallback():
    provider = EmbeddingProvider(Settings(_env_file=None, internal_token=TOKEN, embedding_backend="hashed",
                                          embedding_model="BAAI/bge-small-zh-v1.5"))
    evidence = provider.diagnostics()
    assert evidence["embedding_backend"] == "hashed"
    assert evidence["embedding_requested_model"] == "BAAI/bge-small-zh-v1.5"
    assert evidence["embedding_loaded"] is False
    assert evidence["model"]["onnx_files"] == []
    assert evidence["dimension"] == len(provider.embed(["实际向量维度"])[0]) == 512
    assert evidence["embedding_degraded"] is False


def test_failed_fastembed_does_not_publish_requested_model_as_loaded(monkeypatch):
    import sys
    from types import SimpleNamespace
    class Failure:
        def __init__(self, **kwargs): raise RuntimeError("synthetic loading failure")
    monkeypatch.setitem(sys.modules, "fastembed", SimpleNamespace(TextEmbedding=Failure))
    provider = EmbeddingProvider(Settings(_env_file=None, internal_token=TOKEN, embedding_backend="fastembed"))
    evidence = provider.diagnostics()
    assert evidence["embedding_backend"] == "hashed"
    assert evidence["embedding_loaded"] is False
    assert evidence["embedding_degraded"] is True
    assert evidence["embedding_fallback_code"] == "FASTEMBED_INIT_FAILED"
    assert evidence["model"]["onnx_files"] == []


def test_loaded_evidence_hashes_actual_model_file_and_probes_actual_dimension(tmp_path, monkeypatch):
    import sys
    from types import SimpleNamespace
    path = tmp_path / "actual-model.onnx"
    raw = b"independent fake model artifact for deterministic digest"
    path.write_bytes(raw)
    calls = []
    class Vector:
        def tolist(self): return [0.0] * 384
    class Loaded:
        def __init__(self, **kwargs):
            calls.append(kwargs)
            self.model = SimpleNamespace(model_description=SimpleNamespace(model="actual-loaded-model", dim=384,
                                                                           model_file=path.name),
                                         _model_dir=tmp_path, model=object())
        def embed(self, texts): return [Vector() for _ in texts]
    monkeypatch.setitem(sys.modules, "fastembed", SimpleNamespace(TextEmbedding=Loaded))
    provider = EmbeddingProvider(Settings(_env_file=None, internal_token=TOKEN, embedding_backend="fastembed",
                                          embedding_model="requested-model", dense_dimension=512))
    first = provider.diagnostics()
    assert first["embedding_loaded"] is True
    assert first["model"]["name"] == "actual-loaded-model"
    assert first["dimension"] == 384
    assert first["configured_dimension"] == 512
    assert first["model"]["onnx_files"] == [dict(name=path.name, sha256=hashlib.sha256(raw).hexdigest(), size_bytes=len(raw))]
    path.unlink()
    assert provider.diagnostics() == first, "diagnostics must use cached loaded evidence rather than rereading or downloading"
    assert len(calls) == 1


def test_diagnostics_requires_authentication_and_health_binds_same_instance(tmp_path, monkeypatch):
    from fastapi.testclient import TestClient
    from app.backend import RetrievalBackend
    from app.store import JsonDocumentStore
    monkeypatch.setenv("RAG_INTERNAL_TOKEN", TOKEN)
    monkeypatch.setenv("RAG_BACKEND", "memory")
    monkeypatch.setenv("RAG_EMBEDDING_BACKEND", "hashed")
    monkeypatch.setenv("RAG_DATA_FILE", str(tmp_path / "import.json"))
    get_settings.cache_clear()
    api = importlib.import_module("app.main")
    settings = Settings(_env_file=None, internal_token=TOKEN, backend="memory", embedding_backend="hashed")
    backend = RetrievalBackend(settings, JsonDocumentStore(tmp_path / "owned.json"))
    monkeypatch.setattr(api, "settings", settings)
    monkeypatch.setattr(api, "backend", backend)
    monkeypatch.setattr(api, "store", backend.store)
    try:
        with TestClient(api.app) as client:
            assert client.get("/v2/diagnostics").status_code == 401
            assert client.get("/v2/diagnostics", headers={"X-Internal-Token": "wrong"}).status_code == 401
            result = client.get("/v2/diagnostics", headers={"X-Internal-Token": TOKEN})
            assert result.status_code == 200
            evidence, health = result.json(), client.get("/health").json()
            assert evidence["instance_id"] == health["instance_id"]
            assert evidence["instance_id"]
            assert evidence["backend"] == "memory-hybrid"
            assert evidence["embedding_backend"] == "hashed"
            assert evidence["embedding_loaded"] is False
            assert evidence["milvus_collection"] is None
            assert evidence["milvus_collection_generation"] is None
            assert evidence["dependencies"].keys() >= {"fastembed", "onnxruntime", "pymilvus"}
            assert str(tmp_path) not in result.text
            assert TOKEN not in result.text
    finally:
        get_settings.cache_clear()
