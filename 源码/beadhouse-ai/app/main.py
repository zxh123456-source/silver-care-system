from fastapi import Depends, FastAPI, Header, HTTPException
import secrets

from .models import (
    SearchHit,
    DeleteDocumentRequest,
    DeleteResponse,
    IndexDocumentRequest,
    IndexResponse,
    QueryRequest,
    QueryResponse,
)
from .backend import BackendUnavailable, RetrievalBackend
from .settings import get_settings
from .store import JsonDocumentStore
from .security import prompt_injection_detected

settings = get_settings()
store = JsonDocumentStore(settings.data_file)
backend = RetrievalBackend(settings, store)
app = FastAPI(title="银龄智慧康护 RAG 服务", version="1.0.0")


def verify_token(x_internal_token: str | None = Header(default=None)) -> None:
    if x_internal_token is None or not secrets.compare_digest(x_internal_token.encode(), settings.internal_token.encode()):
        raise HTTPException(status_code=401, detail="invalid internal token")


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "backend": backend.name, "embedding_backend": backend.embedder.name,
            "degraded": backend.embedder.degraded, "chunks": len(store.all_chunks())}


@app.post("/v1/documents/index", response_model=IndexResponse, dependencies=[Depends(verify_token)])
def index_document(request: IndexDocumentRequest) -> IndexResponse:
    try:
        indexed = backend.upsert_document(
            request.document_key,
            request.title,
            request.source,
            [chunk.model_dump() for chunk in request.chunks],
        )
    except BackendUnavailable as exception:
        raise HTTPException(status_code=503, detail=str(exception)) from exception
    return IndexResponse(backend=backend.name, indexed_chunks=indexed)


@app.post("/v1/documents/delete", response_model=DeleteResponse, dependencies=[Depends(verify_token)])
def delete_document(request: DeleteDocumentRequest) -> DeleteResponse:
    try:
        deleted = backend.delete_document(request.document_key)
    except BackendUnavailable as exception:
        raise HTTPException(status_code=503, detail=str(exception)) from exception
    return DeleteResponse(backend=backend.name, deleted_chunks=deleted)


@app.post("/v1/query", response_model=QueryResponse, dependencies=[Depends(verify_token)])
def query(request: QueryRequest) -> QueryResponse:
    if prompt_injection_detected(request.question):
        return QueryResponse(backend=backend.name, degraded=False, fallback_code="PROMPT_INJECTION_BLOCKED",
                             embedding_backend=backend.embedder.name, hits=[])
    active_backend, results, degraded, fallback_code = backend.search(request.question, request.top_k)
    hits = [
        SearchHit(
            chunk_id=item["chunk_id"],
            score=item["score"],
        )
        for item in results
    ]
    return QueryResponse(backend=active_backend, degraded=degraded, fallback_code=fallback_code,
                         embedding_backend=backend.embedder.name, hits=hits)
