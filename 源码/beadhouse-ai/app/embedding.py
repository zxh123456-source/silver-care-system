import logging

from .retrieval import hashed_vector, tokenize
from .settings import Settings

logger = logging.getLogger(__name__)


class EmbeddingProvider:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._model = None
        self.name = "hashed"
        self.degraded = False
        self.fallback_code = None
        self.minimum_score = settings.hashed_dense_min_score
        if settings.embedding_backend.lower() == "fastembed":
            try:
                from fastembed import TextEmbedding

                self._model = TextEmbedding(model_name=settings.embedding_model)
                self.name = settings.embedding_model
                self.minimum_score = settings.semantic_dense_min_score
            except Exception as exception:
                logger.warning("FastEmbed unavailable; using hashed vectors: %s", exception.__class__.__name__)
                self.degraded = True
                self.fallback_code = "FASTEMBED_INIT_FAILED"

    def embed(self, texts: list[str]) -> list[list[float]]:
        if self._model is not None:
            return [vector.tolist() for vector in self._model.embed(texts)]
        return [hashed_vector(tokenize(text), self.settings.dense_dimension) for text in texts]
