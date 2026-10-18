import json
import contextlib
import os
import tempfile
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
            updated = [item for item in self._chunks if item["document_key"] != document_key]
            for chunk in chunks:
                updated.append(
                    {
                        "document_key": document_key,
                        "chunk_id": int(chunk["chunk_id"]),
                        "title": title,
                        "source": source,
                        "section_no": int(chunk["section_no"]),
                        "content": chunk["content"],
                    }
                )
            self._validate(updated)
            self._save(updated)
            self._chunks = updated
            return len(chunks)

    def delete_document(self, document_key: str) -> int:
        with self._lock:
            before = len(self._chunks)
            updated = [item for item in self._chunks if item["document_key"] != document_key]
            self._save(updated)
            self._chunks = updated
            return before - len(updated)

    def _load(self) -> None:
        if not self.path.exists():
            return
        try:
            value = json.loads(self.path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as failure:
            raise ValueError("Invalid document store JSON; restore or rebuild from the source database") from failure
        self._validate(value)
        self._chunks = value

    @staticmethod
    def _validate(value) -> None:
        if not isinstance(value, list):
            raise ValueError("Invalid document store: expected a list")
        for item in value:
            if not isinstance(item, dict) or any(not isinstance(item.get(key), str)
                    for key in ("document_key", "title", "source", "content")):
                raise ValueError("Invalid document store record")
            if any(type(item.get(key)) is not int for key in ("chunk_id", "section_no")):
                raise ValueError("Invalid document store identifiers")

    def _save(self, chunks: list[dict]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=self.path.parent,
                                             prefix=self.path.name + ".", suffix=".tmp", delete=False) as output:
                temporary = Path(output.name)
                json.dump(chunks, output, ensure_ascii=False, indent=2)
                output.flush()
                os.fsync(output.fileno())
            temporary.replace(self.path)
        finally:
            if temporary is not None:
                with contextlib.suppress(OSError):
                    temporary.unlink(missing_ok=True)
