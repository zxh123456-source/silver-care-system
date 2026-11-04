import logging
import os
import threading
from functools import wraps

from .embedding import EmbeddingProvider
from .retrieval import HybridRetriever, cosine, tokenize
from .settings import Settings
from .store import JsonDocumentStore

logger = logging.getLogger(__name__)


class BackendUnavailable(RuntimeError):
    pass


def serialized(operation):
    @wraps(operation)
    def guarded(self, *args, **kwargs):
        # A POSIX child must not reuse a vanished thread's lock or a forked gRPC channel.
        if self._process_id != os.getpid():
            self._operation_lock = threading.RLock()
            self._milvus = None
            self._process_id = os.getpid()
        # FastAPI sync endpoints run in multiple threads; serialize this instance's publication.
        with self._operation_lock:
            return operation(self, *args, **kwargs)
    return guarded


class RetrievalBackend:
    def __init__(self, settings: Settings, store: JsonDocumentStore):
        self.settings = settings
        self.store = store
        self._operation_lock = threading.RLock()
        self._process_id = os.getpid()
        self.embedder = EmbeddingProvider(settings)
        self.memory = HybridRetriever(settings.dense_dimension, self.embedder, self.embedder.minimum_score)
        self._milvus = None
        if settings.backend.lower() == "milvus":
            self._connect_milvus()

    @property
    def collection_name(self):
        # One physical namespace for the versioned schema, never the legacy collection.
        return self.settings.versioned_milvus_collection

    @property
    def index_namespace(self):
        if self.settings.backend.lower() != "milvus":
            return "memory"
        import hashlib
        generation = getattr(self._milvus, "collection_id", "unconnected")
        identity = f"{self.settings.milvus_uri}|{self.collection_name}|{generation}|{self.embedder.name}|{self.settings.embedding_model}|{self.settings.dense_dimension}"
        return "milvus:" + hashlib.sha256(identity.encode('utf-8')).hexdigest()

    @property
    def name(self) -> str:
        return "milvus-hybrid" if self._milvus is not None else "memory-hybrid"

    def upsert_document(self, *args, **kwargs):
        raise ValueError("VERSION_REQUIRED")

    def delete_document(self, *args, **kwargs):
        raise ValueError("VERSION_REQUIRED")

    @serialized
    def sync_document(self, document_key, version, action, payload_hash, title, source, chunks):
        try:
            document = self.store.apply_sync(document_key, version, action, payload_hash, title, source, chunks)
        except OSError as failure:
            raise BackendUnavailable("Document storage unavailable") from failure
        if document['indexed_version'] != version or document.get('index_namespace') != self.index_namespace:
            if self.settings.backend.lower() == "milvus":
                milvus = self._require_milvus()
                try:
                    if action == "UPSERT":
                        milvus.upsert_document(document_key, title, source, chunks, version, payload_hash)
                    else:
                        milvus.delete_document(document_key, version)
                except Exception as exception:
                    self._milvus = None
                    raise BackendUnavailable("Milvus indexing unavailable") from exception
            try:
                if not self.store.mark_indexed(document_key, version, payload_hash, self.index_namespace):
                    from .protocol import VersionConflict
                    raise VersionConflict("STALE_VERSION")
            except OSError as failure:
                raise BackendUnavailable("Document storage unavailable") from failure
        else:
            if self.settings.backend.lower() == "milvus":
                try:
                    milvus = self._require_milvus()
                    verify_generation = getattr(milvus, "_assert_generation", None)
                    if verify_generation is not None:
                        verify_generation()
                except Exception as exception:
                    self._milvus = None
                    raise BackendUnavailable("Milvus indexing unavailable") from exception
            # Even an idempotent response must re-check after a concurrent process update.
            current = self.store.snapshot()['documents'].get(document_key)
            if not current or current['version'] != version or current['payload_hash'] != payload_hash:
                from .protocol import VersionConflict
                raise VersionConflict("STALE_VERSION")
        return len(chunks)

    @serialized
    def search(self, question: str, top_k: int) -> tuple[str, list[dict], bool, str | None]:
        snapshot = self.store.snapshot()
        if self.settings.backend.lower() == "milvus":
            try:
                milvus = self._require_milvus()
                if self._pending(snapshot):
                    return self._pending_search(question, top_k, snapshot)
                hits = milvus.search(question, min(max(top_k * 3, 10), 50), snapshot['documents'])
                current = self.store.snapshot()
                if self._pending(current):
                    return self._pending_search(question, top_k, current)
                # A new completed version may have arrived after the RPC captured its
                # filter. Use the manifest as authority instead of returning stale hits.
                if current != snapshot:
                    return self._pending_search(question, top_k, current)
                return "milvus-hybrid", self._validate_hits(question, hits, top_k, current), self.embedder.degraded, self.embedder.fallback_code
            except Exception as exception:
                logger.warning("Milvus query failed; using memory fallback: %s", exception.__class__.__name__)
                self._milvus = None
                current = self.store.snapshot()
                return "memory-hybrid", self.memory.search(question, self.store.chunks_from(current), top_k), True, "MILVUS_QUERY_FAILED"
        return "memory-hybrid", self.memory.search(question, self.store.chunks_from(snapshot), top_k), self.embedder.degraded, self.embedder.fallback_code

    def _pending(self, snapshot):
        return any(doc['indexed_version'] != doc['version'] or doc.get('index_namespace') != self.index_namespace
                   for doc in snapshot['documents'].values())

    def _pending_search(self, question, top_k, snapshot):
        return "memory-hybrid", self.memory.search(question, self.store.chunks_from(snapshot), top_k), True, "INDEX_SYNC_PENDING"

    def _require_milvus(self):
        if self._milvus is None:
            self._connect_milvus()
        if self._milvus is None:
            raise BackendUnavailable("Milvus unavailable")
        return self._milvus

    def _connect_milvus(self) -> None:
        try:
            from .milvus_backend import MilvusHybridBackend

            self._milvus = MilvusHybridBackend(self.settings, self.embedder)
        except Exception as exception:
            logger.warning("Milvus connection failed; memory fallback active: %s", exception.__class__.__name__)
            self._milvus = None

    def _validate_hits(self, question: str, hits: list[dict], top_k: int, snapshot=None) -> list[dict]:
        snapshot = snapshot or self.store.snapshot()
        chunks = {(item["document_key"], item["version"], item["payload_hash"], int(item["chunk_id"])): item
                  for item in self.store.chunks_from(snapshot)}
        valid = [(hit, chunks.get((hit.get("doc_id"), hit.get("version"), hit.get("payload_hash"), int(hit["chunk_id"]))))
                 for hit in hits]
        valid = [(hit, chunk) for hit, chunk in valid if chunk is not None]
        if not valid:
            return []
        question_terms = set(tokenize(question))
        query_vector = self.embedder.embed([question])[0]
        document_vectors = self.embedder.embed([chunk["title"] + " " + chunk["content"] for _, chunk in valid])
        filtered = []
        for (hit, chunk), vector in zip(valid, document_vectors):
            semantic = cosine(query_vector, vector)
            lexical_match = bool(question_terms.intersection(tokenize(chunk["title"] + " " + chunk["content"])))
            if lexical_match or semantic >= self.embedder.minimum_score:
                filtered.append({"chunk_id": hit["chunk_id"], "score": round(float(hit["score"]) + semantic, 8)})
        return sorted(filtered, key=lambda item: item["score"], reverse=True)[:top_k]
