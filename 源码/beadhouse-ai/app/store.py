"""Durable manifest guarded by a stable cross-process sidecar lock."""
import contextlib
import copy
import json
import os
import tempfile
import threading
from pathlib import Path
from .protocol import VersionConflict, canonical_payload_hash, validate_payload

_registry = {}
_registry_guard = threading.Lock()
_registry_pid = os.getpid()


def _thread_lock(path):
    global _registry_pid, _registry, _registry_guard
    if _registry_pid != os.getpid():
        _registry, _registry_guard, _registry_pid = {}, threading.Lock(), os.getpid()
    with _registry_guard:
        return _registry.setdefault(str(path.resolve()), threading.RLock())


class JsonDocumentStore:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.lock_path = self.path.with_name(self.path.name + '.lock')
        with self._locked():
            self._load()

    @contextlib.contextmanager
    def _locked(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with _thread_lock(self.lock_path):
            with self.lock_path.open('a+b') as handle:
                if os.name == 'nt':
                    import msvcrt
                    import time
                    handle.seek(0, 2)
                    if handle.tell() == 0:
                        handle.write(b'\0'); handle.flush()
                    handle.seek(0)
                    while True:
                        try:
                            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                            break
                        except OSError as failure:
                            if failure.errno not in (13, 36):
                                raise
                            time.sleep(.02)
                else:
                    import fcntl
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
                try:
                    yield
                finally:
                    if os.name == 'nt':
                        handle.seek(0); msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                    else:
                        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)

    def snapshot(self):
        with self._locked():
            return copy.deepcopy(self._load())

    @staticmethod
    def chunks_from(snapshot):
        return [dict(chunk, document_key=key, title=doc['title'], source=doc['source'],
                     version=doc['version'], payload_hash=doc['payload_hash'])
                for key, doc in snapshot['documents'].items() if doc['action'] == 'UPSERT'
                for chunk in doc['chunks']]

    def all_chunks(self):
        return [{k: v for k, v in item.items() if k not in ("version", "payload_hash")}
                for item in self.chunks_from(self.snapshot())]

    def apply_sync(self, document_key, version, action, payload_hash, title, source, chunks):
        validate_payload(document_key, version, action, payload_hash, title, source, chunks)
        with self._locked():
            value = self._load()
            previous = value['documents'].get(document_key)
            if previous:
                if version < previous['version']:
                    raise VersionConflict('STALE_VERSION')
                if version == previous['version']:
                    if payload_hash != previous['payload_hash']:
                        raise VersionConflict('VERSION_CONFLICT')
                    return copy.deepcopy(previous)
            document = dict(version=version, action=action, payload_hash=payload_hash,
                            title=title, source=source,
                            chunks=sorted(copy.deepcopy(chunks), key=lambda c: (c['section_no'], c['chunk_id'])),
                            indexed_version=None)
            value['documents'][document_key] = document
            self._save(value)
            return copy.deepcopy(document)

    def mark_indexed(self, document_key, version, payload_hash, index_namespace=None):
        with self._locked():
            value = self._load()
            doc = value['documents'].get(document_key)
            if not doc or doc['version'] != version or doc['payload_hash'] != payload_hash:
                return False
            if doc['indexed_version'] != version or doc.get('index_namespace') != index_namespace:
                doc['indexed_version'] = version
                doc['index_namespace'] = index_namespace
                self._save(value)
            return True

    def upsert_document(self, *args, **kwargs):
        raise ValueError('VERSION_REQUIRED')

    def delete_document(self, *args, **kwargs):
        raise ValueError('VERSION_REQUIRED')

    def _load(self):
        if not self.path.exists():
            return {'schema_version': 2, 'documents': {}}
        try:
            raw = self.path.read_bytes()
            value = json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError) as failure:
            raise ValueError('Invalid document store JSON; restore or rebuild from the source database') from failure
        if isinstance(value, list):
            value = self._migrate_legacy(value, raw)
        self._validate(value)
        return value

    def _migrate_legacy(self, rows, raw):
        value = {'schema_version': 2, 'documents': {}}
        for row in rows:
            if (not isinstance(row, dict) or any(not isinstance(row.get(k), str)
                    for k in ('document_key', 'title', 'source', 'content'))
                    or any(type(row.get(k)) is not int or row[k] < 1 for k in ('chunk_id', 'section_no'))):
                raise ValueError('Invalid legacy document store record')
            key = row['document_key']
            doc = value['documents'].setdefault(key, dict(version=0, action='UPSERT', title=row['title'],
                    source=row['source'], chunks=[], indexed_version=None))
            if doc['title'] != row['title'] or doc['source'] != row['source']:
                raise ValueError('Conflicting legacy document metadata')
            doc['chunks'].append({k: row[k] for k in ('chunk_id', 'section_no', 'content')})
        for key, doc in value['documents'].items():
            doc['chunks'].sort(key=lambda c: (c['section_no'], c['chunk_id']))
            doc['payload_hash'] = canonical_payload_hash(key, doc['title'], doc['source'], doc['chunks'], 'UPSERT')
        self._validate(value)
        backup = self.path.with_name(self.path.name + '.legacy.bak')
        if backup.exists():
            if backup.read_bytes() != raw:
                raise ValueError('Legacy backup already exists with different contents')
        else:
            with backup.open('xb') as output:
                output.write(raw); output.flush(); os.fsync(output.fileno())
        self._save(value)
        return value

    @staticmethod
    def _validate(value):
        if (not isinstance(value, dict) or value.get('schema_version') != 2
                or not isinstance(value.get('documents'), dict)):
            raise ValueError('Invalid document store envelope')
        for key, doc in value['documents'].items():
            try:
                validate_payload(key, max(doc['version'], 1), doc['action'], doc['payload_hash'],
                                 doc['title'], doc['source'], doc['chunks'])
                if (type(doc['version']) is not int or doc['version'] < 0
                        or (doc['indexed_version'] is not None and
                            (type(doc['indexed_version']) is not int or doc['indexed_version'] != doc['version']))):
                    raise ValueError('Invalid stored version')
            except (KeyError, TypeError) as failure:
                raise ValueError('Invalid document store record') from failure

    def _save(self, value):
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=self.path.parent,
                    prefix=self.path.name + '.', suffix='.tmp', delete=False) as output:
                temporary = Path(output.name)
                json.dump(value, output, ensure_ascii=False, separators=(',', ':'), allow_nan=False)
                output.flush(); os.fsync(output.fileno())
            temporary.replace(self.path)
            if os.name != 'nt':
                descriptor = os.open(self.path.parent, os.O_RDONLY)
                try:
                    os.fsync(descriptor)
                finally:
                    os.close(descriptor)
        finally:
            if temporary is not None:
                with contextlib.suppress(OSError):
                    temporary.unlink(missing_ok=True)
