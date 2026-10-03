from typing import Any
import os
from urllib.parse import urlparse

from .settings import Settings


class MilvusHybridBackend:
    def __init__(self, settings: Settings, embedder):
        from pymilvus import DataType, Function, FunctionType, MilvusClient

        self.settings = settings
        self.embedder = embedder
        host = urlparse(settings.milvus_uri).hostname or "127.0.0.1"
        no_proxy = {item.strip() for item in os.getenv("NO_PROXY", "").split(",") if item.strip()}
        no_proxy.update({host, "127.0.0.1", "localhost"})
        os.environ["NO_PROXY"] = ",".join(sorted(no_proxy))
        os.environ["no_proxy"] = os.environ["NO_PROXY"]
        self.client = MilvusClient(uri=settings.milvus_uri, token=settings.milvus_token)
        self.collection = settings.milvus_collection
        if not self.client.has_collection(collection_name=self.collection):
            schema = self.client.create_schema(auto_id=False, enable_dynamic_field=False)
            schema.add_field(field_name="pk", datatype=DataType.VARCHAR, max_length=200, is_primary=True)
            schema.add_field(field_name="chunk_id", datatype=DataType.INT64)
            schema.add_field(field_name="doc_id", datatype=DataType.VARCHAR, max_length=128)
            schema.add_field(field_name="title", datatype=DataType.VARCHAR, max_length=512)
            schema.add_field(field_name="source", datatype=DataType.VARCHAR, max_length=1024)
            schema.add_field(field_name="section_no", datatype=DataType.INT64)
            schema.add_field(
                field_name="content",
                datatype=DataType.VARCHAR,
                max_length=8192,
                enable_analyzer=True,
                analyzer_params={"type": "chinese"},
            )
            schema.add_field(
                field_name="dense_vector",
                datatype=DataType.FLOAT_VECTOR,
                dim=settings.dense_dimension,
            )
            schema.add_field(field_name="sparse_vector", datatype=DataType.SPARSE_FLOAT_VECTOR)
            schema.add_function(
                Function(
                    name="content_bm25",
                    function_type=FunctionType.BM25,
                    input_field_names=["content"],
                    output_field_names=["sparse_vector"],
                )
            )
            index_params = self.client.prepare_index_params()
            index_params.add_index(
                field_name="dense_vector",
                index_name="dense_vector_idx",
                index_type="AUTOINDEX",
                metric_type="COSINE",
            )
            index_params.add_index(
                field_name="sparse_vector",
                index_name="sparse_vector_idx",
                index_type="SPARSE_INVERTED_INDEX",
                metric_type="BM25",
                params={"inverted_index_algo": "DAAT_MAXSCORE", "bm25_k1": 1.2, "bm25_b": 0.75},
            )
            self.client.create_collection(
                collection_name=self.collection,
                schema=schema,
                index_params=index_params,
                consistency_level="Strong",
            )
        self.client.load_collection(collection_name=self.collection)

    def upsert_document(self, document_key: str, title: str, source: str, chunks: list[dict]) -> int:
        self.delete_document(document_key)
        rows = []
        for chunk in chunks:
            content = chunk["content"]
            rows.append(
                {
                    "pk": f"{document_key}:{chunk['chunk_id']}",
                    "chunk_id": int(chunk["chunk_id"]),
                    "doc_id": document_key,
                    "title": title,
                    "source": source,
                    "section_no": int(chunk["section_no"]),
                    "content": content,
                    "dense_vector": self.embedder.embed([title + " " + content])[0],
                }
            )
        if rows:
            self.client.insert(collection_name=self.collection, data=rows)
        return len(rows)

    def delete_document(self, document_key: str) -> int:
        result = self.client.delete(
            collection_name=self.collection,
            filter="doc_id == {doc_id}",
            filter_params={"doc_id": document_key},
        )
        return int(result.get("delete_count", 0))

    def search(self, question: str, top_k: int) -> list[dict[str, Any]]:
        from pymilvus import AnnSearchRequest, RRFRanker

        candidate_k = min(max(top_k * 4, 20), 100)
        dense_request = AnnSearchRequest(
            data=[self.embedder.embed([question])[0]],
            anns_field="dense_vector",
            param={"metric_type": "COSINE", "params": {}},
            limit=candidate_k,
        )
        sparse_request = AnnSearchRequest(
            data=[question],
            anns_field="sparse_vector",
            param={"metric_type": "BM25", "params": {}},
            limit=candidate_k,
        )
        results = self.client.hybrid_search(
            collection_name=self.collection,
            reqs=[dense_request, sparse_request],
            ranker=RRFRanker(k=60),
            limit=top_k,
            output_fields=["chunk_id"],
            consistency_level="Strong",
        )[0]
        hits: list[dict[str, Any]] = []
        for result in results:
            entity = result.get("entity", {})
            hits.append(
                {
                    "chunk_id": int(entity["chunk_id"]),
                    "score": round(float(result.get("distance", result.get("score", 0.0))), 8),
                }
            )
        return hits
