#!/usr/bin/env python3
"""Authenticated system restore drills into disposable, tool-owned resources.

The CLI verifies MySQL/attachments and prepares a fresh RAG namespace. It does not
claim a usable search index; application reindex and real retrieval are separate
acceptance steps inside the restored_system context.
"""
import argparse
from contextlib import contextmanager
import copy
import importlib
import json
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import shutil
import sys
import tempfile
import types
import uuid

try:
    from . import database
except ImportError:
    import database


class RestoreError(database.DatabaseError):
    pass


def require(condition, message):
    if not condition:
        raise RestoreError(message)


def backup_module():
    if __package__:
        return importlib.import_module('.system_backup', __package__)
    return importlib.import_module('system_backup')


def rag_modules():
    # These modules are standard-library-only. Reuse the production protocol/store
    # without importing FastAPI, model weights or a possibly unrelated `app` package.
    name = '_silvercare_restore_rag'
    directory = database.ROOT / '源码/beadhouse-ai/app'
    require(all((directory / filename).is_file() and not linked(directory / filename)
                for filename in ('protocol.py', 'store.py')), 'Trusted repository RAG protocol/store is missing')
    if name not in sys.modules:
        package = types.ModuleType(name)
        package.__path__ = [str(directory)]
        sys.modules[name] = package
    return (importlib.import_module(name + '.protocol'),
            importlib.import_module(name + '.store'))


def safe_relative(value):
    require(isinstance(value, str) and value and '\\' not in value,
            'Invalid recovery relative path')
    parts = value.split('/')
    require(not PurePosixPath(value).is_absolute() and all(
        part not in ('', '.', '..') and ':' not in part and
        not any(ord(char) < 32 or char in '<>"|?*' for char in part) and not part.endswith((' ', '.')) and
        not re.fullmatch(r'(?i)(CON|PRN|AUX|NUL|CLOCK\$|COM[1-9]|LPT[1-9])(?:\..*)?', part)
        for part in parts), 'Unsafe recovery relative path')
    return PurePosixPath(value)


def linked(path):
    return path.is_symlink() or bool(getattr(path, 'is_junction', lambda: False)())


def beneath(root, relative):
    parts = safe_relative(relative).parts
    require(root.is_dir() and not linked(root), 'Recovery root is not a real directory')
    target = root.joinpath(*parts)
    require(target.resolve().is_relative_to(root.resolve()), 'Recovery path escapes root')
    current = root
    for part in parts:
        current = current / part
        require(not linked(current), 'Recovery path contains a link')
    return target


def source_relative(source_root, source_path):
    require(isinstance(source_root, str) and isinstance(source_path, str), 'Invalid source path')
    flavor = PureWindowsPath if PureWindowsPath(source_root).drive else PurePosixPath
    root, path = flavor(source_root), flavor(source_path)
    require(root.is_absolute() and path.is_absolute() and '..' not in root.parts and
            '..' not in path.parts, 'Source attachment path is not canonical and absolute')
    try:
        value = path.relative_to(root).as_posix()
    except ValueError:
        raise RestoreError('Source attachment path escapes source uploads root') from None
    safe_relative(value)
    return value


def load_snapshot(unpacked, manifest):
    path = beneath(unpacked, manifest['snapshotPath'])
    require(path.is_file(), 'Missing snapshot')
    try:
        value = json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=database.unique_object)
    except (ValueError, UnicodeError):
        raise RestoreError('Invalid recovery snapshot') from None
    require(isinstance(value, dict) and set(value) == {
        'base_attachment', 'attachment_reference', 'policy_rag_sync'}, 'Invalid recovery snapshot tables')
    for rows in value.values():
        require(isinstance(rows, list) and all(isinstance(row, dict) for row in rows),
                'Invalid recovery snapshot rows')
    return value


def attachment_plan(unpacked, manifest, snapshot, uploads_root):
    """Bind each archive mapping to its full original metadata, before any SQL write."""
    rows = snapshot['base_attachment']
    ids = [row.get('id') for row in rows]
    require(all(type(value) is int and value > 0 for value in ids) and len(set(ids)) == len(ids),
            'Invalid or duplicate attachment ID')
    entries = manifest.get('attachments')
    require(isinstance(entries, list) and len(entries) == len(rows), 'Attachment inventory differs')
    mapped = {}
    for entry in entries:
        require(isinstance(entry, dict) and type(entry.get('id')) is int and entry['id'] not in mapped,
                'Invalid or duplicate attachment mapping')
        mapped[entry['id']] = entry
    require(set(mapped) == set(ids), 'Attachment IDs differ from database snapshot')
    references = snapshot['attachment_reference']
    active = set()
    for reference in references:
        require(reference.get('attachment_id') in mapped and reference.get('active') in ('Y', 'N'),
                'Attachment reference is invalid or orphaned')
        if reference['active'] == 'Y':
            active.add(reference['attachment_id'])
    plan = []
    for row in rows:
        entry = mapped[row['id']]
        require(row.get('del_flag') in ('N', 'Y') and
                row.get('binding_state') in ('DRAFT', 'BOUND', 'LEGACY', 'CONFLICT'),
                'Unsupported attachment state')
        require(entry.get('sourcePath') == row.get('path') and
                entry.get('bindingState') == row['binding_state'] and entry.get('delFlag') == row['del_flag'],
                'Attachment mapping disagrees with original metadata')
        relative = source_relative(manifest['source']['uploadsRoot'], row['path'])
        require(relative == entry.get('relativePath'), 'Attachment source and relative path disagree')
        required = row['del_flag'] != 'Y' or row['id'] in active
        require(type(entry.get('required')) is bool and entry['required'] == required and
                type(entry.get('present')) is bool, 'Attachment recovery requirement differs')
        require(not required or entry['present'], 'Required attachment bytes are missing')
        require(type(row.get('size')) is int and row['size'] >= 0 and entry.get('bytes') == row['size'],
                'Attachment byte count differs from metadata')
        if entry['present']:
            require(entry.get('archivePath') == 'uploads/' + relative, 'Attachment archive path differs')
            source = beneath(unpacked, entry['archivePath'])
            require(source.is_file() and source.stat().st_size == row['size'] and
                    database.sha256_file(source) == entry.get('sha256'), 'Attachment bytes failed verification')
        else:
            require(entry.get('archivePath') is None and entry.get('sha256') is None,
                    'Missing historical attachment has unexpected bytes')
        target = beneath(uploads_root, relative)
        require(len(str(target)) <= 225,
                'Relocated attachment path exceeds 225 characters; choose a shorter --work-parent')
        plan.append({'id': row['id'], 'old_path': row['path'], 'new_path': str(target),
                     'relative': relative, 'present': entry['present']})
    return plan


def sql_text(value):
    # UTF-8 hex literals avoid SQL quoting and NO_BACKSLASH_ESCAPES differences.
    return "CONVERT(X'" + value.encode('utf-8').hex() + "' USING utf8mb4)"


def relocation_sql(plan):
    statements = ['START TRANSACTION;',
                  'CREATE TEMPORARY TABLE restore_attachment_guard '
                  '(matched INT NOT NULL CHECK (matched=1));']
    for item in plan:
        require(type(item['id']) is int and item['id'] > 0, 'Invalid relocation attachment ID')
        statements.extend([
            'UPDATE base_attachment SET path=' + sql_text(item['new_path']) +
            ' WHERE id=' + str(item['id']) + ' AND BINARY path=BINARY ' + sql_text(item['old_path']) + ';',
            'INSERT INTO restore_attachment_guard VALUES (ROW_COUNT());',
            'DELETE FROM restore_attachment_guard;'])
    statements.extend(['COMMIT;', 'DROP TEMPORARY TABLE restore_attachment_guard;'])
    return '\n'.join(statements)


def relocate_attachments(restored, snapshot, plan):
    module = backup_module()
    before = module.snapshot_database(restored.docker, restored.container)
    require(all(database.canonical_records(before[table]) == database.canonical_records(rows)
                for table, rows in snapshot.items()), 'Restored rows differ from authenticated snapshot')
    if plan:
        restored.docker.sql(restored.container, relocation_sql(plan))
    after = module.snapshot_database(restored.docker, restored.container)
    expected = copy.deepcopy(snapshot)
    paths = {item['id']: item['new_path'] for item in plan}
    for row in expected['base_attachment']:
        row['path'] = paths[row['id']]
    require(all(database.canonical_records(after[table]) == database.canonical_records(rows)
                for table, rows in expected.items()),
            'Relocation changed metadata beyond attachment paths or did not affect every row')


def payloads(rows):
    protocol, _ = rag_modules()
    result, seen = [], set()
    for row in rows:
        key, version = row.get('document_key'), row.get('desired_version')
        require(isinstance(key, str) and key and key not in seen and
                type(version) is int and 0 <= version < 9223372036854775807 and
                row.get('desired_action') in ('UPSERT', 'DELETE'),
                'Invalid RAG identity or exhausted version')
        seen.add(key)
        if version == 0 and row.get('payload_json') is None:
            result.append((row, None))  # Existing unversioned source needs application reindex.
            continue
        try:
            payload = json.loads(row['payload_json'], object_pairs_hook=database.unique_object)
            require(isinstance(payload, dict) and set(payload) == {
                'document_key', 'version', 'action', 'payload_hash', 'title', 'source', 'chunks'},
                'Invalid immutable RAG payload fields')
            protocol.validate_payload(**payload)
        except (KeyError, TypeError, ValueError):
            raise RestoreError('Invalid immutable RAG snapshot') from None
        require(payload['document_key'] == key and payload['version'] == version and
                payload['action'] == row.get('desired_action') and payload['payload_hash'] == row.get('revision') and
                payload['title'] == row.get('title') and payload['source'] == row.get('source'),
                'RAG queue and immutable snapshot disagree')
        result.append((row, payload))
    return result


def prepare_rag(snapshot, workspace, token):
    rows = payloads(snapshot['policy_rag_sync'])
    _, store_module = rag_modules()
    rag_root = workspace / 'rag-live'
    rag_root.mkdir()
    path = rag_root / 'policy_documents.json'
    with path.open('x', encoding='utf-8') as output:
        json.dump({'schema_version': 2, 'documents': {}}, output)
    store = store_module.JsonDocumentStore(path)
    for _, payload in rows:
        if payload is not None:
            store.apply_sync(**payload)
    collection = 'silvercare_restore_' + token
    return {
        'status': 'REBUILD_REQUIRED', 'authority': 'restored MySQL policy_doc and policy_rag_sync',
        'dataFile': str(path), 'collection': collection, 'physicalCollection': collection + '_v2',
        'environment': {'RAG_DATA_FILE': str(path), 'RAG_MILVUS_COLLECTION': collection},
        'documents': len(rows), 'tombstones': sum(row.get('desired_action') == 'DELETE' for row, _ in rows),
        'legacyDocuments': sum(payload is None for _, payload in rows),
        'reindex': 'POST /ai/policy/reindex on the isolated restored application',
        'acceptance': ['all original keys retained with desired_version increased', 'queue SYNCED',
                       'JSON versions/actions/hashes and indexed namespace match live Milvus generation',
                       'real BGE/Milvus active document retrieval; deleted document absent; no fallback'],
    }


class RestoredSystem:
    def __init__(self, restored, workspace, snapshot, plan, report):
        self.database, self.workspace, self.snapshot = restored, workspace, snapshot
        self.uploads_root, self.config_root = workspace / 'uploads', workspace / 'config'
        self.rebuild_plan, self.report = plan, report
        self.rag_file = Path(plan['dataFile'])
        self.rag_collection = plan['collection']


@contextmanager
def owned_workspace(parent):
    parent = Path(parent).absolute()
    require(parent.is_dir() and not linked(parent), 'Recovery parent must be an existing real directory')
    parent = parent.resolve()
    token = uuid.uuid4().hex
    workspace = Path(tempfile.mkdtemp(prefix='system-restore-', dir=parent))
    marker = workspace / '.restore-owner'
    marker.write_text(token, encoding='ascii')
    try:
        yield workspace, token
    finally:
        require(workspace.resolve().parent == parent and not linked(workspace) and
                marker.is_file() and not linked(marker) and marker.read_text(encoding='ascii') == token,
                'Recovery workspace cleanup refused: ownership or resolved path changed')
        shutil.rmtree(workspace)


@contextmanager
def restored_system(package, key_file, work_parent, *, publish_database=False):
    module = backup_module()
    # Authentication, full inventory/hash/link/path validation precede Docker creation.
    with module.verified_package(package, key_file, workspace_parent=work_parent) as (unpacked, manifest):
        snapshot = load_snapshot(unpacked, manifest)
        payloads(snapshot['policy_rag_sync'])
        with owned_workspace(work_parent) as (workspace, token):
            uploads = workspace / 'uploads'
            uploads.mkdir()
            plan = attachment_plan(unpacked, manifest, snapshot, uploads)
            for name in ('uploads', 'config'):
                source = unpacked / name
                if source.is_dir():
                    shutil.copytree(source, workspace / name, dirs_exist_ok=True)
                else:
                    (workspace / name).mkdir(exist_ok=True)
            # Validate the copied bytes as well, before any target database exists.
            for entry in manifest['attachments']:
                if entry['present']:
                    copied = beneath(uploads, entry['relativePath'])
                    require(copied.is_file() and copied.stat().st_size == entry['bytes'] and
                            database.sha256_file(copied) == entry['sha256'], 'Copied attachment failed verification')
            rebuild = prepare_rag(snapshot, workspace, token)
            (workspace / 'rebuild-plan.json').write_text(json.dumps(rebuild, ensure_ascii=False), encoding='utf-8')
            with database.owned_restored_database(unpacked / 'database',
                                                 publish_localhost=publish_database) as restored:
                relocate_attachments(restored, snapshot, plan)
                report = {'status': 'RESTORED_REBUILD_REQUIRED', 'database': restored.result,
                          'attachments': len(plan), 'presentAttachments': sum(item['present'] for item in plan),
                          'ragDocuments': rebuild['documents'], 'ragTombstones': rebuild['tombstones'],
                          'configurationRestored': True, 'redisSessionsRestored': False}
                yield RestoredSystem(restored, workspace, snapshot, rebuild, report)


def verify_rebuilt_queue(restored, *, index_namespace):
    """Verify real queue/store completion; the caller still must verify retrieval.

    index_namespace must come from the live Milvus generation and embedding
    identity, not from a persisted manifest's claim about itself.
    """
    require(isinstance(index_namespace, str) and re.fullmatch(r'milvus:[0-9a-f]{64}', index_namespace),
            'A live Milvus generation namespace is required')
    current = backup_module().snapshot_database(restored.database.docker, restored.database.container)
    originals = {row['document_key']: row['desired_version'] for row in restored.snapshot['policy_rag_sync']}
    current_rows = payloads(current['policy_rag_sync'])
    require(set(originals).issubset({row['document_key'] for row, _ in current_rows}),
            'Rebuilt queue lost original documents or tombstones')
    _, module = rag_modules()
    documents = module.JsonDocumentStore(restored.rag_file).snapshot()['documents']
    require(set(documents) == {row['document_key'] for row, _ in current_rows}, 'RAG JSON and queue inventory differ')
    for row, payload in current_rows:
        require(row.get('status') == 'SYNCED' and payload is not None and
                row['desired_version'] > originals.get(row['document_key'], -1),
                'RAG rebuild is pending or did not advance the restored version')
        document = documents[row['document_key']]
        require(all(document.get(field) == payload[field]
                    for field in ('version', 'action', 'payload_hash', 'title', 'source', 'chunks')) and
                document.get('indexed_version') == payload['version'] and
                document.get('index_namespace') == index_namespace,
                'RAG indexed state differs from MySQL or live namespace')
    return {'status': 'QUEUE_AND_STORE_VERIFIED', 'documents': len(documents),
            'retrievalAcceptanceRequired': True}


def restore_drill(package, key_file, work_parent=None):
    if work_parent is None:
        work_parent = database.ROOT / '.runtime/system-restores'
        work_parent.mkdir(parents=True, exist_ok=True)
    with restored_system(package, key_file, work_parent) as restored:
        result = dict(restored.report)
    result['cleanup'] = 'completed'
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package', required=True, type=Path)
    parser.add_argument('--key-file', required=True, type=Path)
    parser.add_argument('--work-parent', required=True, type=Path,
                        help='Existing parent; only a new randomly named child is used and cleaned')
    args = parser.parse_args(argv)
    try:
        print(json.dumps(restore_drill(args.package, args.key_file, args.work_parent), ensure_ascii=False))
        return 0
    except (backup_module().BackupError, database.DatabaseError, OSError, ValueError) as error:
        # Configuration, paths and callback exception text are not CLI diagnostics.
        print('System restore failed (' + type(error).__name__ + '); owned resources cleanup attempted',
              file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print('System restore interrupted; owned resources cleanup attempted', file=sys.stderr)
        return 130


if __name__ == '__main__':
    raise SystemExit(main())
