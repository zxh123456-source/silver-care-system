import hashlib
import json


class VersionConflict(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def canonical_payload_hash(document_key, title, source, chunks, action):
    payload = dict(action=action, chunks=sorted(chunks, key=lambda c: (c['section_no'], c['chunk_id'])),
                   document_key=document_key, source=source, title=title)
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True,
                                    separators=(',', ':'), allow_nan=False).encode('utf-8')).hexdigest()


def validate_payload(document_key, version, action, payload_hash, title, source, chunks):
    if (not isinstance(document_key, str) or not 1 <= len(document_key) <= 128
            or type(version) is not int or not 1 <= version <= 9223372036854775807
            or action not in ('UPSERT', 'DELETE')
            or not isinstance(title, str) or not 1 <= len(title) <= 200
            or not isinstance(source, str) or not 1 <= len(source) <= 255
            or not isinstance(chunks, list) or len(chunks) > 2000):
        raise ValueError('Invalid versioned document payload')
    if (action == 'DELETE' and chunks) or (action == 'UPSERT' and not chunks):
        raise ValueError('Invalid document action chunks')
    seen = set()
    for chunk in chunks:
        if (not isinstance(chunk, dict) or set(chunk) != {'chunk_id', 'section_no', 'content'}
                or type(chunk['chunk_id']) is not int or not 1 <= chunk['chunk_id'] <= 9223372036854775807
                or type(chunk['section_no']) is not int or not 1 <= chunk['section_no'] <= 2147483647
                or not isinstance(chunk['content'], str) or not 1 <= len(chunk['content']) <= 5000
                or chunk['chunk_id'] in seen):
            raise ValueError('Invalid document chunk')
        seen.add(chunk['chunk_id'])
    if payload_hash != canonical_payload_hash(document_key, title, source, chunks, action):
        raise ValueError('Payload hash mismatch')
