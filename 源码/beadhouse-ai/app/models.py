from pydantic import BaseModel, Field


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
