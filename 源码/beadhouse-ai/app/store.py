import json
import threading
from pathlib import Path


class JsonDocumentStore:
    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.RLock()
        self._chunks: list[dict] = []
        self._load()

    def all_chunks(self) -> list[dict]:
        with self._lock:
            return [dict(item) for item in self._chunks]

    def upsert_document(self, document_key: str, title: str, source: str, chunks: list[dict]) -> int:
        with self._lock:
            self._chunks = [item for item in self._chunks if item["document_key"] != document_key]
            for chunk in chunks:
                self._chunks.append(
                    {
                        "document_key": document_key,
                        "chunk_id": int(chunk["chunk_id"]),
                        "title": title,
                        "source": source,
                        "section_no": int(chunk["section_no"]),
                        "content": chunk["content"],
                    }
                )
            self._save()
            return len(chunks)

    def delete_document(self, document_key: str) -> int:
        with self._lock:
            before = len(self._chunks)
            self._chunks = [item for item in self._chunks if item["document_key"] != document_key]
            self._save()
            return before - len(self._chunks)

    def _load(self) -> None:
        if not self.path.exists():
            return
        try:
            value = json.loads(self.path.read_text(encoding="utf-8"))
            if isinstance(value, list):
                self._chunks = value
        except (OSError, json.JSONDecodeError):
            self._chunks = []

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(json.dumps(self._chunks, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(self.path)
