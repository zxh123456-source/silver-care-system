"""Independent versioned-write specifications. All stores are disposable tmp_path files."""
import contextlib
import hashlib
import importlib
import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

import pytest

from app.backend import BackendUnavailable, RetrievalBackend
from app.settings import Settings, get_settings
from app.store import JsonDocumentStore


TOKEN = "versioned-test-only-internal-token-at-least-32-bytes"


def payload(key="policy", version=1, action="UPSERT", text="交班后核对记录并签名。", number=1):
    value = dict(document_key=key, version=version, action=action, title="交班制度", source="test",
                 chunks=[] if action == "DELETE" else [dict(chunk_id=number, section_no=1, content=text)])
    value["payload_hash"] = digest(value)
    return value


def digest(value):
    canonical = {key: value[key] for key in ("action", "chunks", "document_key", "source", "title")}
    canonical["chunks"] = sorted(canonical["chunks"], key=lambda row: (row["section_no"], row["chunk_id"]))
    return hashlib.sha256(json.dumps(canonical, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode("utf-8")).hexdigest()


def apply(store, value):
    return store.apply_sync(**value)


def service(tmp_path):
    settings = Settings(_env_file=None, internal_token=TOKEN, backend="memory", embedding_backend="hashed")
    return RetrievalBackend(settings, JsonDocumentStore(tmp_path / "documents.json"))


def test_canonical_digest_is_cross_language_unicode_and_order_stable():
    from app.protocol import canonical_payload_hash
    value = payload()
    value["chunks"] = [dict(chunk_id=4, section_no=2, content="换行\n与引号\""),
                       dict(chunk_id=2, section_no=1, content="中文 Ω 😀")]
    expected = digest(value)
    assert canonical_payload_hash(value["document_key"], value["title"], value["source"],
                                  value["chunks"], value["action"]) == expected
    assert canonical_payload_hash(value["document_key"], value["title"], value["source"],
                                  list(reversed(value["chunks"])), value["action"]) == expected


def test_shared_java_python_protocol_fixture_retains_exact_long_and_unicode():
    from app.protocol import canonical_payload_hash
    fixture = Path(__file__).resolve().parents[3] / ".github" / "rag" / "sync_protocol_fixture.json"
    for value in json.loads(fixture.read_text(encoding="utf-8"))["requests"]:
        assert digest(value) == value["payload_hash"]
        assert canonical_payload_hash(value["document_key"], value["title"], value["source"],
                                      value["chunks"], value["action"]) == value["payload_hash"]


def test_tombstone_survives_restart_and_rejects_late_upsert(tmp_path):
    path = tmp_path / "docs.json"
    store = JsonDocumentStore(path)
    apply(store, payload(version=1))
    deletion = payload(version=3, action="DELETE")
    apply(store, deletion)
    restarted = JsonDocumentStore(path)
    assert restarted.all_chunks() == []
    assert restarted.snapshot()["documents"]["policy"]["version"] == 3
    assert restarted.snapshot()["documents"]["policy"]["action"] == "DELETE"
    with pytest.raises(ValueError):
        apply(restarted, payload(version=2, text="迟到的旧正文"))
    assert restarted.all_chunks() == []
    assert JsonDocumentStore(path).snapshot()["documents"]["policy"]["payload_hash"] == deletion["payload_hash"]


def test_same_version_conflict_and_invalid_digest_preserve_committed_document(tmp_path):
    store = JsonDocumentStore(tmp_path / "docs.json")
    first = payload(version=7)
    apply(store, first)
    before = store.path.read_bytes()
    with pytest.raises(ValueError):
        apply(store, payload(version=7, text="same version with different content"))
    bad = payload(version=8)
    bad["payload_hash"] = "0" * 64
    with pytest.raises(ValueError):
        apply(store, bad)
    assert store.path.read_bytes() == before
    assert store.snapshot()["documents"]["policy"]["version"] == 7


def test_old_instance_reloads_another_process_commit(tmp_path):
    path = tmp_path / "docs.json"
    reader = JsonDocumentStore(path)
    apply(reader, payload(version=1))
    result = run_writer(path, payload(version=2, number=2, text="进程二的新制度"))
    assert result.returncode == 0, result.stderr
    assert reader.all_chunks()[0]["chunk_id"] == 2
    assert reader.snapshot()["documents"]["policy"]["version"] == 2


@pytest.mark.skipif(not hasattr(os, "fork"), reason="Real POSIX fork is unavailable on Windows")
def test_forked_backend_resets_inherited_lock_and_reconnects_inherited_remote(tmp_path, monkeypatch):
    """Exercise a real inherited held lock, not a monkeypatched PID comparison."""
    import select
    import signal
    backend = service(tmp_path)
    backend.sync_document(**payload(version=1))
    backend.settings.backend = "milvus"
    class InheritedRemote:
        collection_id = 1
        def upsert_document(self, *args): raise AssertionError("inherited remote must never be reused")
    class ChildRemote:
        collection_id = 2
        def __init__(self): self.pid, self.calls = os.getpid(), 0
        def upsert_document(self, *args): self.calls += 1
    backend._milvus = InheritedRemote()
    def reconnect(): backend._milvus = ChildRemote()
    monkeypatch.setattr(backend, "_connect_milvus", reconnect)
    held, release = threading.Event(), threading.Event()
    def hold_parent_lock():
        with backend._operation_lock:
            held.set()
            assert release.wait(15)
    holder = threading.Thread(target=hold_parent_lock, daemon=True)
    holder.start()
    assert held.wait(5)
    result_read, result_write = os.pipe()
    command_read, command_write = os.pipe()
    child = None
    reaped = False
    try:
        child = os.fork()
        if child == 0:
            os.close(result_read); os.close(command_write)
            try:
                assert os.read(command_read, 1) == b"G"
                before = backend.store.snapshot()["documents"]["policy"]["version"]
                backend.sync_document(**payload(version=3, number=3))
                message = dict(ok=True, before=before, version=backend.store.snapshot()["documents"]["policy"]["version"],
                               remote_pid=backend._milvus.pid, pid=os.getpid(), calls=backend._milvus.calls)
            except BaseException as failure:
                message = dict(ok=False, error=repr(failure))
            os.write(result_write, json.dumps(message).encode("utf-8"))
            os._exit(0)
        os.close(result_write); os.close(command_read)
        apply(backend.store, payload(version=2, number=2))
        os.write(command_write, b"G")
        ready, _, _ = select.select([result_read], [], [], 8)
        assert ready, "forked child deadlocked on a lock held by a vanished parent thread"
        raw = os.read(result_read, 8192)
        assert raw, "child exited before returning executable fork evidence"
        message = json.loads(raw)
        _, status = os.waitpid(child, 0); reaped = True
        assert os.waitstatus_to_exitcode(status) == 0
        assert message.get("ok"), message
        assert message["before"] == 2
        assert message["version"] == 3
        assert message["remote_pid"] == message["pid"] != os.getpid()
        assert message["calls"] == 1
    finally:
        release.set(); holder.join(timeout=5)
        if child and not reaped:
            with contextlib.suppress(ProcessLookupError): os.kill(child, signal.SIGKILL)
            os.waitpid(child, 0)
        for descriptor in (result_read, result_write, command_read, command_write):
            with contextlib.suppress(OSError): os.close(descriptor)


WRITER = r'''
import json, pathlib, sys, time
from app.store import JsonDocumentStore
path, value, ready, release = sys.argv[1:]
if ready:
    original = pathlib.Path.replace
    def barrier(self, target):
        pathlib.Path(ready).write_text("ready", encoding="utf-8")
        deadline = time.monotonic() + 15
        while not pathlib.Path(release).exists():
            if time.monotonic() >= deadline: raise TimeoutError("release barrier not reached")
            time.sleep(0.02)
        return original(self, target)
    pathlib.Path.replace = barrier
JsonDocumentStore(pathlib.Path(path)).apply_sync(**json.loads(value))
'''


def writer_args(path, value, ready="", release=""):
    return [sys.executable, "-c", WRITER, str(path), json.dumps(value, ensure_ascii=True), str(ready), str(release)]


def run_writer(path, value):
    return subprocess.run(writer_args(path, value), cwd=Path(__file__).resolve().parents[1],
                          capture_output=True, text=True, timeout=20)


def wait_file(path, process, timeout=10):
    deadline = time.monotonic() + timeout
    while not path.exists():
        if process.poll() is not None:
            out, err = process.communicate()
            pytest.fail(f"writer exited before barrier: {out}\n{err}")
        if time.monotonic() >= deadline:
            pytest.fail("independent writer did not reach disk barrier")
        time.sleep(0.02)


@pytest.mark.parametrize("second_key", ["policy", "other-policy"])
def test_real_process_file_lock_serializes_reloaded_updates_without_lost_documents(tmp_path, second_key):
    path = tmp_path / "docs.json"
    ready, release = tmp_path / "ready", tmp_path / "release"
    first = subprocess.Popen(writer_args(path, payload(version=1), ready, release),
                             cwd=Path(__file__).resolve().parents[1], stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, text=True)
    second = None
    try:
        wait_file(ready, first)
        second = subprocess.Popen(writer_args(path, payload(key=second_key, version=2, number=2)),
                                  cwd=Path(__file__).resolve().parents[1], stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, text=True)
        time.sleep(0.3)
        assert second.poll() is None, "a second OS process must wait for the first store commit"
    finally:
        release.write_text("release", encoding="utf-8")
        for process in (first, second):
            if process is not None:
                try:
                    out, err = process.communicate(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    out, err = process.communicate()
                    pytest.fail(f"writer deadlocked: {out}\n{err}")
                assert process.returncode == 0, out + err
    snapshot = JsonDocumentStore(path).snapshot()["documents"]
    assert snapshot[second_key]["version"] == 2
    if second_key != "policy":
        assert snapshot["policy"]["version"] == 1
        assert {chunk["document_key"] for chunk in JsonDocumentStore(path).all_chunks()} == {"policy", second_key}


def test_process_restart_rejects_old_version_after_a_separate_delete_process(tmp_path):
    path = tmp_path / "docs.json"
    for value in (payload(version=1), payload(version=4, action="DELETE")):
        done = run_writer(path, value)
        assert done.returncode == 0, done.stderr
    stale = run_writer(path, payload(version=2))
    assert stale.returncode != 0
    assert JsonDocumentStore(path).snapshot()["documents"]["policy"]["version"] == 4
    assert JsonDocumentStore(path).all_chunks() == []


def test_legacy_store_is_backed_up_exactly_once_and_migrated_without_claiming_indexed(tmp_path):
    path = tmp_path / "docs.json"
    original = [dict(document_key="legacy", title="旧制度", source="fixture", chunk_id=9,
                     section_no=1, content="原始片段")]
    raw = json.dumps(original, ensure_ascii=False, indent=2).encode("utf-8")
    path.write_bytes(raw)
    migrated = JsonDocumentStore(path)
    assert migrated.all_chunks() == original
    state = migrated.snapshot()["documents"]["legacy"]
    assert state["version"] == 0
    assert state["indexed_version"] != state["version"]
    backups = [candidate for candidate in tmp_path.iterdir() if candidate != path and candidate.is_file()
               and candidate.read_bytes() == raw]
    assert len(backups) == 1, "legacy data must have one byte-identical backup before v2 publication"
    JsonDocumentStore(path)
    assert [candidate for candidate in tmp_path.iterdir() if candidate != path and candidate.is_file()
            and candidate.read_bytes() == raw] == backups


@pytest.mark.parametrize("raw", ['{"format_version":2,"documents":{}} trailing',
                                 '{"format_version":2,"documents":{"policy":{}}}', '{broken'])
def test_corrupt_v2_json_fails_closed_without_replacing_bytes(tmp_path, raw):
    path = tmp_path / "docs.json"
    path.write_text(raw, encoding="utf-8")
    with pytest.raises(ValueError):
        JsonDocumentStore(path)
    assert path.read_text(encoding="utf-8") == raw


def test_existing_instance_does_not_serve_cached_documents_after_disk_corruption(tmp_path):
    store = JsonDocumentStore(tmp_path / "docs.json")
    apply(store, payload())
    corrupt = b'{"schema_version":2,"documents":{"policy": {broken'
    store.path.write_bytes(corrupt)
    with pytest.raises(ValueError):
        store.all_chunks()
    with pytest.raises(ValueError):
        apply(store, payload(version=2))
    assert store.path.read_bytes() == corrupt


def test_legacy_migration_never_overwrites_a_different_existing_backup(tmp_path):
    path = tmp_path / "docs.json"
    raw = json.dumps([dict(document_key="legacy", title="old", source="test", chunk_id=1,
                           section_no=1, content="old")]).encode("utf-8")
    path.write_bytes(raw)
    backup = path.with_name(path.name + ".legacy.bak")
    backup.write_bytes(b"previous distinct evidence")
    with pytest.raises(ValueError, match="backup"):
        JsonDocumentStore(path)
    assert backup.read_bytes() == b"previous distinct evidence"
    assert path.read_bytes() == raw


def test_disk_failure_before_publish_keeps_old_version_and_never_calls_remote(tmp_path, monkeypatch):
    backend = service(tmp_path)
    backend.sync_document(**payload(version=1))
    before = backend.store.path.read_bytes()
    class Remote:
        calls = 0
        def upsert_document(self, *args): self.calls += 1
    remote = Remote()
    backend.settings.backend = "milvus"
    backend._milvus = remote
    def fail(*args): raise OSError("synthetic before-publication fault")
    with monkeypatch.context() as patch:
        patch.setattr(Path, "replace", fail)
        with pytest.raises(BackendUnavailable, match="storage"):
            backend.sync_document(**payload(version=2, number=2))
    assert remote.calls == 0
    assert backend.store.path.read_bytes() == before
    assert JsonDocumentStore(backend.store.path).snapshot()["documents"]["policy"]["version"] == 1


def test_lost_response_after_atomic_replace_is_pending_and_same_version_retry_repairs_it(tmp_path, monkeypatch):
    backend = service(tmp_path)
    backend.sync_document(**payload(version=1))
    original = Path.replace
    replacement = payload(version=2, number=2)
    def replace_then_fail(self, target):
        original(self, target)
        raise OSError("synthetic lost completion after atomic publication")
    with monkeypatch.context() as patch:
        patch.setattr(Path, "replace", replace_then_fail)
        with pytest.raises(BackendUnavailable):
            backend.sync_document(**replacement)
    restarted = service(tmp_path)
    state = restarted.store.snapshot()["documents"]["policy"]
    assert state["version"] == 2
    assert state["indexed_version"] != 2
    restarted.sync_document(**replacement)
    assert restarted.store.snapshot()["documents"]["policy"]["indexed_version"] == 2


def test_vector_succeeded_but_index_marker_failed_retries_same_version_and_never_marks_newer(tmp_path, monkeypatch):
    backend = service(tmp_path)
    backend.settings.backend = "milvus"
    class Remote:
        calls = []
        def upsert_document(self, *args): self.calls.append(args)
    remote = Remote()
    backend._milvus = remote
    value = payload(version=3)
    with monkeypatch.context() as patch:
        patch.setattr(backend.store, "mark_indexed", lambda *args, **kwargs: (_ for _ in ()).throw(OSError("marker fault")))
        with pytest.raises(BackendUnavailable):
            backend.sync_document(**value)
    assert len(remote.calls) == 1
    assert backend.store.snapshot()["documents"]["policy"]["indexed_version"] != 3
    backend._milvus = remote
    backend.sync_document(**value)
    assert len(remote.calls) == 2
    assert backend.store.snapshot()["documents"]["policy"]["indexed_version"] == 3
    newer = payload(version=4, action="DELETE")
    apply(backend.store, newer)
    assert not backend.store.mark_indexed("policy", 3, value["payload_hash"])
    assert backend.store.snapshot()["documents"]["policy"]["version"] == 4
    assert backend.store.snapshot()["documents"]["policy"]["indexed_version"] != 4


def test_remote_completion_after_another_process_update_cannot_acknowledge_old_version(tmp_path):
    backend = service(tmp_path)
    backend.settings.backend = "milvus"
    newer = payload(version=2, number=2, text="进程二的新交班记录。")
    class Remote:
        def upsert_document(self, *args):
            result = run_writer(backend.store.path, newer)
            assert result.returncode == 0, result.stderr
    backend._milvus = Remote()
    with pytest.raises(ValueError):
        backend.sync_document(**payload(version=1))
    current = backend.store.snapshot()["documents"]["policy"]
    assert current["version"] == 2
    assert current["payload_hash"] == newer["payload_hash"]
    assert current["indexed_version"] != 2


def test_indexing_failure_searches_current_pending_chunks_with_explicit_fallback(tmp_path):
    backend = service(tmp_path)
    backend.sync_document(**payload(version=1))
    backend.settings.backend = "milvus"
    class Remote:
        def upsert_document(self, *args): raise RuntimeError("index interrupted")
        def search(self, *args): return [{"chunk_id": 1, "score": 10}]
    backend._milvus = Remote()
    with pytest.raises(BackendUnavailable):
        backend.sync_document(**payload(version=2, number=2, text="新版交班记录必须由责任人签名。"))
    backend._milvus = Remote()
    active, hits, degraded, fallback = backend.search("新版交班记录", 5)
    assert active == "memory-hybrid"
    assert degraded and fallback, "pending index must be observable instead of pretending a complete vector index"
    assert hits and {hit["chunk_id"] for hit in hits} == {2}


def test_memory_completion_does_not_claim_a_new_milvus_namespace_is_indexed(tmp_path):
    backend = service(tmp_path)
    value = payload(version=1)
    backend.sync_document(**value)
    backend.settings.backend = "milvus"
    class Remote:
        calls = 0
        def upsert_document(self, *args): self.calls += 1
        def search(self, *args): return []
    remote = Remote()
    backend._milvus = remote
    active, hits, degraded, fallback = backend.search("交班记录", 5)
    assert active == "memory-hybrid" and degraded and fallback
    assert hits and remote.calls == 0
    backend.sync_document(**value)
    assert remote.calls == 1
    active, hits, degraded, fallback = backend.search("交班记录", 5)
    assert active == "milvus-hybrid" and not degraded and fallback is None


def test_new_collection_generation_requires_same_version_repair(tmp_path):
    backend = service(tmp_path)
    backend.settings.backend = "milvus"
    class Remote:
        def __init__(self, generation): self.collection_id, self.calls = generation, 0
        def upsert_document(self, *args): self.calls += 1
        def search(self, *args): return []
    first = Remote(101)
    backend._milvus = first
    value = payload()
    backend.sync_document(**value)
    assert first.calls == 1
    replacement = Remote(102)
    backend._milvus = replacement
    active, hits, degraded, fallback = backend.search("交班记录", 5)
    assert active == "memory-hybrid" and degraded and fallback
    assert hits and replacement.calls == 0
    backend.sync_document(**value)
    assert replacement.calls == 1
    assert backend.store.snapshot()["documents"]["policy"]["index_namespace"] == backend.index_namespace


def test_idempotent_sync_checks_live_generation_before_acknowledging_existing_index(tmp_path):
    backend = service(tmp_path)
    backend.settings.backend = "milvus"
    class Remote:
        collection_id = 101
        recreated = False
        def _assert_generation(self):
            if self.recreated: raise RuntimeError("collection was recreated")
        def upsert_document(self, *args): self._assert_generation()
    remote = Remote()
    backend._milvus = remote
    value = payload()
    backend.sync_document(**value)
    remote.recreated = True
    with pytest.raises(BackendUnavailable):
        backend.sync_document(**value)


@pytest.fixture
def api_client(tmp_path, monkeypatch):
    from fastapi.testclient import TestClient
    monkeypatch.setenv("RAG_INTERNAL_TOKEN", TOKEN)
    monkeypatch.setenv("RAG_DATA_FILE", str(tmp_path / "api.json"))
    monkeypatch.setenv("RAG_BACKEND", "memory")
    monkeypatch.setenv("RAG_EMBEDDING_BACKEND", "hashed")
    get_settings.cache_clear()
    api = importlib.import_module("app.main")
    backend = service(tmp_path)
    monkeypatch.setattr(api, "backend", backend)
    monkeypatch.setattr(api, "store", backend.store)
    monkeypatch.setattr(api, "settings", backend.settings)
    try:
        with TestClient(api.app) as client:
            yield client, backend
    finally:
        get_settings.cache_clear()


def test_v2_echoes_accepted_identity_and_v1_unversioned_writes_are_rejected(api_client):
    client, backend = api_client
    value = payload()
    headers = {"X-Internal-Token": TOKEN}
    assert client.post("/v2/documents/sync", json=value).status_code == 401
    accepted = client.post("/v2/documents/sync", json=value, headers=headers)
    assert accepted.status_code == 200, accepted.text
    for name in ("document_key", "version", "action", "payload_hash"):
        assert accepted.json()[name] == value[name]
    assert accepted.json()["applied"] is True
    before = backend.store.path.read_bytes()
    for route, body in (("index", {key: value[key] for key in ("document_key", "title", "source", "chunks")}),
                        ("delete", {"document_key": "policy"})):
        result = client.post("/v1/documents/" + route, json=body, headers=headers)
        assert result.status_code in (409, 410, 422, 426), result.text
    assert backend.store.path.read_bytes() == before
    query = client.post("/v1/query", json={"question": "交班记录", "top_k": 5}, headers=headers)
    assert query.status_code == 200
    assert query.json()["hits"]


def test_api_old_version_same_version_conflict_and_bad_hash_are_not_acknowledged(api_client):
    client, backend = api_client
    headers = {"X-Internal-Token": TOKEN}
    assert client.post("/v2/documents/sync", json=payload(version=4), headers=headers).status_code == 200
    before = backend.store.path.read_bytes()
    for value in (payload(version=3), payload(version=4, text="same-version changed")):
        result = client.post("/v2/documents/sync", json=value, headers=headers)
        assert result.status_code == 409, result.text
    bad = payload(version=5)
    bad["payload_hash"] = "0" * 64
    assert client.post("/v2/documents/sync", json=bad, headers=headers).status_code in (400, 409, 422)
    assert backend.store.path.read_bytes() == before
