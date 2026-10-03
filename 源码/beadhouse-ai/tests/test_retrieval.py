from pathlib import Path

from app.retrieval import HybridRetriever, tokenize
from app.store import JsonDocumentStore


def test_chinese_hybrid_retrieval_returns_grounded_chunk() -> None:
    chunks = [
        {
            "document_key": "fall",
            "chunk_id": 1,
            "title": "跌倒处理制度",
            "source": "护理部",
            "section_no": 1,
            "content": "发现老人跌倒后，应先确认现场安全并立即通知值班医护人员。",
        },
        {
            "document_key": "visit",
            "chunk_id": 2,
            "title": "来访制度",
            "source": "综合部",
            "section_no": 1,
            "content": "来访人员应登记身份信息并遵守探视时间。",
        },
    ]

    results = HybridRetriever().search("老人跌倒后先做什么", chunks, top_k=2)

    assert results
    assert results[0]["chunk_id"] == 1
    assert "现场安全" in results[0]["content"]


def test_store_replaces_document_version(tmp_path: Path) -> None:
    store = JsonDocumentStore(tmp_path / "documents.json")
    store.upsert_document("policy", "旧制度", "v1", [{"chunk_id": 1, "section_no": 1, "content": "旧内容"}])
    store.upsert_document("policy", "新制度", "v2", [{"chunk_id": 2, "section_no": 1, "content": "新内容"}])

    chunks = store.all_chunks()

    assert len(chunks) == 1
    assert chunks[0]["chunk_id"] == 2
    assert chunks[0]["title"] == "新制度"


def test_tokenize_keeps_chinese_bigrams_and_words() -> None:
    terms = tokenize("跌倒处理 Fall-01")
    assert "跌倒" in terms
    assert "处理" in terms
    assert "fall" in terms
    assert "01" in terms
