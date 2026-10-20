from fastapi import Depends, FastAPI, Header, HTTPException
import secrets

from .models import (
    SearchHit,
    SyncDocumentRequest,
    SyncResponse,
    DeleteDocumentRequest,
    IndexDocumentRequest,
    QueryRequest,
    QueryResponse,
)
from .backend import BackendUnavailable, RetrievalBackend
from .protocol import VersionConflict
from .settings import get_settings
from .store import JsonDocumentStore
from .security import prompt_injection_detected

settings = get_settings()
store = JsonDocumentStore(settings.data_file)
backend = RetrievalBackend(settings, store)
app = FastAPI(title="银龄智慧康护 RAG 服务", version="2.0.0")


def verify_token(x_internal_token: str | None = Header(default=None)) -> None:
    if x_internal_token is None or not secrets.compare_digest(x_internal_token.encode(), settings.internal_token.encode()):
        raise HTTPException(status_code=401, detail="invalid internal token")


@app.get("/health")
def health() -> dict:
    snapshot = store.snapshot()
    pending = backend.settings.backend.lower() == "milvus" and backend._pending(snapshot)
    return {"status": "ok", "backend": backend.name, "embedding_backend": backend.embedder.name,
            "degraded": backend.embedder.degraded or pending or (backend.settings.backend.lower() == "milvus" and backend._milvus is None),
            "fallback_code": "INDEX_SYNC_PENDING" if pending else backend.embedder.fallback_code,
            "milvus_collection": backend.collection_name if backend.settings.backend.lower() == "milvus" else None,
            "chunks": len(store.chunks_from(snapshot))}


@app.post("/v1/documents/index", dependencies=[Depends(verify_token)])
def index_document(request: IndexDocumentRequest):
    raise HTTPException(status_code=409, detail={"code": "VERSION_REQUIRED"})


@app.post("/v1/documents/delete", dependencies=[Depends(verify_token)])
def delete_document(request: DeleteDocumentRequest):
    raise HTTPException(status_code=409, detail={"code": "VERSION_REQUIRED"})


@app.post("/v2/documents/sync", response_model=SyncResponse, dependencies=[Depends(verify_token)])
def sync_document(request: SyncDocumentRequest):
    try:
        count = backend.sync_document(request.document_key, request.version, request.action,
                                      request.payload_hash, request.title, request.source,
                                      [chunk.model_dump() for chunk in request.chunks])
    except VersionConflict as exception:
        raise HTTPException(status_code=409, detail={"code": exception.code}) from exception
    except BackendUnavailable as exception:
        raise HTTPException(status_code=503, detail=str(exception)) from exception
    return SyncResponse(document_key=request.document_key, version=request.version, action=request.action,
                        payload_hash=request.payload_hash, applied=True, backend=backend.name, indexed_chunks=count)


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
