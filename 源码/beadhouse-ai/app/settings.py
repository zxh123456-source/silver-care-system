from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import field_validator, model_validator


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="RAG_", env_file=".env", extra="ignore", hide_input_in_errors=True)

    service_name: str = "beadhouse-rag"
    internal_token: str
    backend: str = "memory"
    data_file: Path = Path("data/policy_documents.json")
    dense_dimension: int = 512
    embedding_backend: str = "fastembed"
    embedding_model: str = "BAAI/bge-small-zh-v1.5"
    semantic_dense_min_score: float = 0.55
    hashed_dense_min_score: float = 0.05
    default_top_k: int = 5
    milvus_uri: str = "http://127.0.0.1:19530"
    milvus_token: str = ""
    milvus_collection: str = "beadhouse_policy_chunks_v2"

    @field_validator("internal_token")
    @classmethod
    def validate_token(cls, value: str) -> str:
        if len(value.encode("utf-8")) < 32 or value.startswith(("replace-", "change-")) or value == "local-dev-token":
            raise ValueError("Configure a random RAG_INTERNAL_TOKEN of at least 32 bytes")
        return value

    @model_validator(mode="after")
    def validate_milvus_credentials(self):
        if self.backend.lower() == "milvus" and (not self.milvus_token or self.milvus_token == "root:Milvus"):
            raise ValueError("Configure RAG_MILVUS_TOKEN for authenticated Milvus")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
