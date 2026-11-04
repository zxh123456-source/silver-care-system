from pydantic import BaseModel, Field, ConfigDict, model_validator
from typing import Literal
from .protocol import validate_payload


class ChunkInput(BaseModel):
    chunk_id: int
    section_no: int
    content: str = Field(min_length=1, max_length=5000)


class IndexDocumentRequest(BaseModel):
    document_key: str = Field(min_length=1, max_length=128)
    title: str = Field(min_length=1, max_length=200)
    source: str = Field(min_length=1, max_length=255)
    chunks: list[ChunkInput] = Field(min_length=1, max_length=2000)


class DeleteDocumentRequest(BaseModel):
    document_key: str = Field(min_length=1, max_length=128)


class QueryRequest(BaseModel):
    question: str = Field(min_length=1, max_length=1000)
    top_k: int = Field(default=5, ge=1, le=20)


class SearchHit(BaseModel):
    chunk_id: int
    score: float


class QueryResponse(BaseModel):
    backend: str
    degraded: bool = False
    fallback_code: str | None = None
    embedding_backend: str
    hits: list[SearchHit]


class IndexResponse(BaseModel):
    backend: str
    indexed_chunks: int


class DeleteResponse(BaseModel):
    backend: str
    deleted_chunks: int


class VersionedChunkInput(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    chunk_id: int = Field(ge=1, le=9223372036854775807)
    section_no: int = Field(ge=1, le=2147483647)
    content: str = Field(min_length=1, max_length=5000)


class SyncDocumentRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    document_key: str = Field(min_length=1, max_length=128)
    version: int = Field(ge=1, le=9223372036854775807)
    action: Literal["UPSERT", "DELETE"]
    title: str = Field(min_length=1, max_length=200)
    source: str = Field(min_length=1, max_length=255)
    chunks: list[VersionedChunkInput] = Field(max_length=2000)
    payload_hash: str = Field(pattern=r"^[0-9a-f]{64}$")

    @model_validator(mode="after")
    def validate_digest(self):
        validate_payload(self.document_key, self.version, self.action, self.payload_hash,
                         self.title, self.source, [chunk.model_dump() for chunk in self.chunks])
        return self


class SyncResponse(BaseModel):
    document_key: str
    version: int
    action: Literal["UPSERT", "DELETE"]
    payload_hash: str
    applied: bool
    backend: str
    indexed_chunks: int
