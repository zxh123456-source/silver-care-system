import copy
import hashlib
import logging
import math
from importlib import metadata
from pathlib import Path

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
                loaded = getattr(self._model, "model", None)
                description = getattr(loaded, "model_description", None)
                # Use the model selected by FastEmbed, never the requested setting as evidence.
                actual_name = (getattr(description, "model", None)
                               or getattr(loaded, "model_name", None)
                               or getattr(self._model, "model_name", None))
                self.name = actual_name if isinstance(actual_name, str) and actual_name else "unknown"
                self.minimum_score = settings.semantic_dense_min_score
            except Exception as exception:
                self._model = None
                self.name = "hashed"
                logger.warning("FastEmbed unavailable; using hashed vectors: %s", exception.__class__.__name__)
                self.degraded = True
                self.fallback_code = "FASTEMBED_INIT_FAILED"
        self._diagnostics = self._capture_diagnostics()

    def _capture_diagnostics(self) -> dict:
        dimension = None
        try:
            # One local inference establishes the actual output width, including hashed mode.
            # It reuses the already constructed model; diagnostics never constructs or downloads one.
            probe = self.embed(["护理检索维度校验"])
            if len(probe) == 1 and probe[0] and all(math.isfinite(value) for value in probe[0]):
                dimension = len(probe[0])
        except Exception as exception:
            logger.warning("Embedding dimension evidence unavailable: %s", exception.__class__.__name__)
        onnx_files = []
        if self._model is not None:
            try:
                loaded = self._model.model
                # FastEmbed passes exactly this path into its loaded ONNX InferenceSession.
                if loaded.model is not None:
                    path = Path(loaded._model_dir) / loaded.model_description.model_file
                    digest = hashlib.sha256()
                    size = 0
                    with path.open("rb") as artifact:
                        for block in iter(lambda: artifact.read(1024 * 1024), b""):
                            size += len(block)
                            digest.update(block)
                    onnx_files.append(dict(name=path.name, sha256=digest.hexdigest(), size_bytes=size))
            except Exception as exception:
                # Missing evidence must remain missing so evaluation cannot certify an unknown model.
                logger.warning("Embedding artifact evidence unavailable: %s", exception.__class__.__name__)
        dependencies = {}
        for package in ("fastembed", "onnxruntime", "pymilvus"):
            try:
                dependencies[package] = metadata.version(package)
            except metadata.PackageNotFoundError:
                dependencies[package] = None
        return {
            "embedding_backend": self.name,
            "embedding_requested_backend": self.settings.embedding_backend,
            "embedding_requested_model": self.settings.embedding_model,
            "embedding_loaded": self._model is not None,
            "dimension": dimension,
            "configured_dimension": self.settings.dense_dimension,
            "model": {"name": self.name, "onnx_files": onnx_files},
            "dependencies": dependencies,
            "embedding_degraded": self.degraded,
            "embedding_fallback_code": self.fallback_code,
        }

    def diagnostics(self) -> dict:
        # Return a copy: callers cannot mutate the immutable evidence captured at model load.
        return copy.deepcopy(self._diagnostics)

    def embed(self, texts: list[str]) -> list[list[float]]:
        if self._model is not None:
            return [vector.tolist() for vector in self._model.embed(texts)]
        return [hashed_vector(tokenize(text), self.settings.dense_dimension) for text in texts]
