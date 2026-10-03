from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="RAG_", env_file=".env", extra="ignore")

    service_name: str = "beadhouse-rag"
    internal_token: str = "local-dev-token"
    backend: str = "memory"
    data_file: Path = Path("data/policy_documents.json")
    dense_dimension: int = 512
    embedding_backend: str = "fastembed"
    embedding_model: str = "BAAI/bge-small-zh-v1.5"
    semantic_dense_min_score: float = 0.55
    hashed_dense_min_score: float = 0.05
    default_top_k: int = 5
    milvus_uri: str = "http://127.0.0.1:19530"
    milvus_token: str = "root:Milvus"
    milvus_collection: str = "beadhouse_policy_chunks_v2"


@lru_cache
def get_settings() -> Settings:
    return Settings()
