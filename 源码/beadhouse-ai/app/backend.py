import logging

from .embedding import EmbeddingProvider
from .retrieval import HybridRetriever, cosine, tokenize
from .settings import Settings
from .store import JsonDocumentStore

logger = logging.getLogger(__name__)


class BackendUnavailable(RuntimeError):
    pass


class RetrievalBackend:
    def __init__(self, settings: Settings, store: JsonDocumentStore):
        self.settings = settings
        self.store = store
        self.embedder = EmbeddingProvider(settings)
        self.memory = HybridRetriever(settings.dense_dimension, self.embedder, self.embedder.minimum_score)
        self._milvus = None
        if settings.backend.lower() == "milvus":
            self._connect_milvus()

    @property
    def name(self) -> str:
        return "milvus-hybrid" if self._milvus is not None else "memory-hybrid"

    def upsert_document(self, document_key: str, title: str, source: str, chunks: list[dict]) -> int:
        count = self.store.upsert_document(document_key, title, source, chunks)
        if self.settings.backend.lower() == "milvus":
            milvus = self._require_milvus()
            try:
                milvus.upsert_document(document_key, title, source, chunks)
            except Exception as exception:
                self._milvus = None
                raise BackendUnavailable("Milvus indexing unavailable") from exception
        return count

    def delete_document(self, document_key: str) -> int:
        count = self.store.delete_document(document_key)
        if self.settings.backend.lower() == "milvus":
            milvus = self._require_milvus()
            try:
                milvus.delete_document(document_key)
            except Exception as exception:
                self._milvus = None
                raise BackendUnavailable("Milvus deletion unavailable") from exception
        return count

    def search(self, question: str, top_k: int) -> tuple[str, list[dict], bool, str | None]:
        if self.settings.backend.lower() == "milvus":
            try:
                milvus = self._require_milvus()
                hits = milvus.search(question, min(max(top_k * 3, 10), 50))
                return "milvus-hybrid", self._validate_hits(question, hits, top_k), self.embedder.degraded, self.embedder.fallback_code
            except Exception as exception:
                logger.warning("Milvus query failed; using memory fallback: %s", exception.__class__.__name__)
                self._milvus = None
                return "memory-hybrid", self.memory.search(question, self.store.all_chunks(), top_k), True, "MILVUS_QUERY_FAILED"
        return "memory-hybrid", self.memory.search(question, self.store.all_chunks(), top_k), self.embedder.degraded, self.embedder.fallback_code

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

    def _validate_hits(self, question: str, hits: list[dict], top_k: int) -> list[dict]:
        chunks = {int(item["chunk_id"]): item for item in self.store.all_chunks()}
        valid = [(hit, chunks.get(int(hit["chunk_id"]))) for hit in hits]
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
