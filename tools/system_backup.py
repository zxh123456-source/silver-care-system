#!/usr/bin/env python3
"""Authenticated whole-system backup; no service is stopped or restored by create.

The encrypted payload is a ZIP_STORED archive with a complete SHA256 inventory.
Limits: 16 GiB package/total payload, 8 GiB per file, 100,000 files, 32 MiB JSON.
Redis sessions and derived Milvus vectors are excluded. Restore requires a fresh
RAG JSON and collection built from MySQL, retaining DELETE versions/tombstones.
"""
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import secrets
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import time
import zipfile

try:
    from . import database
except ImportError:
    import database

MAGIC = b'SCBACKUP\x00\x01AES256GCM\x00'
MAX_PACKAGE_BYTES = 16 * 1024 ** 3
MAX_FILE_BYTES = 8 * 1024 ** 3
MAX_JSON_BYTES = 32 * 1024 ** 2
MAX_FILES = 100000
BLOCK = 1024 * 1024
TABLES = ('base_attachment', 'attachment_reference', 'policy_rag_sync')
CONFIG_PATHS = ('.env', '本地配置.ps1', 'docker-compose.yml',
                '源码/beadhouse-backend/src/main/resources/application.yml',
                '源码/beadhouse-backend/src/main/resources/application-dev.yml',
                '源码/beadhouse-ai/app/settings.py')


class BackupError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise BackupError(message)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate JSON key')
        result[key] = value
    return result


def read_json(path):
    require(path.stat().st_size <= MAX_JSON_BYTES, 'JSON exceeds 32 MiB limit')
    try:
        return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique_object)
    except (ValueError, UnicodeError) as error:
        raise BackupError('Invalid JSON') from error


def safe_relative(value):
    require(isinstance(value, str) and value and '\\' not in value and ':' not in value,
            'Unsafe archive relative path')
    require(not PurePosixPath(value).is_absolute(), 'Absolute archive path refused')
    parts = value.split('/')
    reserved = {'CON', 'PRN', 'AUX', 'NUL', 'CLOCK$',
                *(f'COM{i}' for i in range(1, 10)), *(f'LPT{i}' for i in range(1, 10))}
    for part in parts:
        require(part not in ('', '.', '..') and part == part.rstrip('. ') and
                not any(ord(c) < 32 or c in '<>"|?*' for c in part) and
                part.split('.')[0].upper() not in reserved, 'Unsafe archive path component')
    return value


def absolute_path(value):
    path = Path(value)
    require('..' not in path.parts, 'Parent traversal in source path refused')
    return path.absolute()


def is_link(path):
    info = path.lstat()
    return stat.S_ISLNK(info.st_mode) or bool(getattr(info, 'st_file_attributes', 0) & 0x400)


def check_path(path, boundary=None, *, missing=False):
    path = absolute_path(path)
    if boundary is not None:
        boundary = absolute_path(boundary)
        try:
            path.relative_to(boundary)
        except ValueError as error:
            raise BackupError('Source path is outside its explicit root') from error
    current = path
    while True:
        if current.exists() or current.is_symlink():
            require(not is_link(current), 'Symlink/junction/reparse source refused')
        else:
            require(missing, 'Required source path is missing')
        if boundary is None or current == boundary:
            break
        current = current.parent
    return path


def file_fingerprint(path, boundary=None):
    path = check_path(path, boundary)
    before = path.stat()
    require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1,
            'Source must be a regular file without hardlink aliases')
    require(before.st_size <= MAX_FILE_BYTES, 'File exceeds 8 GiB limit')
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        opened = os.fstat(stream.fileno())
        require((opened.st_dev, opened.st_ino) == (before.st_dev, before.st_ino),
                'Source file changed while opening')
        for chunk in iter(lambda: stream.read(BLOCK), b''):
            digest.update(chunk)
    check_path(path, boundary)
    after = path.stat()
    require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) ==
            (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns),
            'Source file changed while hashing')
    return {'bytes': after.st_size, 'sha256': digest.hexdigest(),
            'identity': [after.st_dev, after.st_ino, after.st_mtime_ns, after.st_ctime_ns]}


def source_inventory(project, uploads, rag):
    inventory = {}
    paths = {}
    def add(path, name, kind, boundary):
        safe_relative(name)
        folded = name.casefold()
        require(folded not in paths, 'Case-insensitive source path collision')
        require(len(inventory) < MAX_FILES - 4, 'Too many source files')
        paths[folded] = name
        inventory[name] = {'source': path, 'boundary': boundary, 'kind': kind,
                           **file_fingerprint(path, boundary)}
    def unreadable(_error):
        raise BackupError('Uploads inventory contains an unreadable directory')
    for directory, subdirs, files in os.walk(uploads, followlinks=False, onerror=unreadable):
        for name in subdirs:
            check_path(Path(directory) / name, uploads)
        for name in files:
            path = Path(directory) / name
            add(path, 'uploads/' + path.relative_to(uploads).as_posix(), 'attachment', uploads)
    for relative in CONFIG_PATHS:
        path = project / relative
        check_path(path, project, missing=True)
        if path.exists():
            add(path, 'config/' + relative, 'configuration', project)
    check_path(rag, project if rag.is_relative_to(project) else rag.parent, missing=True)
    if rag.exists():
        add(rag, 'rag/policy_documents.json', 'rag-state', rag.parent)
        read_json(rag)  # Preserve exact bytes, but never claim malformed state was captured successfully.
    require(sum(item['bytes'] for item in inventory.values()) <= MAX_PACKAGE_BYTES,
            'Source payload exceeds 16 GiB limit')
    return inventory


def assert_writers_stopped(project):
    """Read-only lifecycle identity check; explicit operator acknowledgement is also required."""
    runtime = project / '.runtime'
    check_path(runtime, project, missing=True)
    if not runtime.exists():
        return
    states = []
    for path in runtime.glob('lifecycle-*.json'):
        check_path(path, project)
        state = read_json(path)
        require(isinstance(state, dict) and state.get('schemaVersion') == 1 and
                Path(state.get('projectRoot', '')).absolute() == project and
                isinstance(state.get('processes'), list), 'Invalid source lifecycle state')
        for identity in state['processes']:
            require(isinstance(identity, dict) and identity.get('projectRoot') == state['projectRoot'] and
                    type(identity.get('pid')) is int and identity['pid'] > 0 and
                    all(isinstance(identity.get(k), str) and identity[k]
                        for k in ('createdUtc', 'executablePath', 'commandLine')),
                    'Invalid source lifecycle process ownership')
        states.extend(state['processes'])
    if not states:
        return
    require(os.name == 'nt', 'Lifecycle identities require Windows verification before backup')
    executable = shutil.which('pwsh')
    require(executable is not None, 'PowerShell 7 required to verify lifecycle writers')
    env = dict(os.environ)
    env['SC_BACKUP_HELPER'] = str(database.ROOT / '启停工具.ps1')
    script = ("$ErrorActionPreference='Stop'; . $env:SC_BACKUP_HELPER; "
              "$identities=[Console]::In.ReadToEnd() | ConvertFrom-Json -AsHashtable; "
              "foreach($identity in $identities) { if(Test-ProcessIdentity $identity) { exit 3 } }; exit 0")
    result = subprocess.run([executable, '-NoProfile', '-NonInteractive', '-Command', script],
                            input=canonical(states), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            env=env, timeout=45, check=False)
    require(result.returncode == 0, 'Source lifecycle application is live or could not be verified; stop writers first')


def snapshot_database(docker, container):
    """Read bounded complete rows, including metadata and immutable RAG DELETE payloads."""
    result = {}
    total = 0
    for table in TABLES:
        columns = docker.sql(container, 'SELECT column_name FROM information_schema.columns '
                             f"WHERE table_schema='db_beadhouse' AND table_name='{table}' "
                             'ORDER BY ordinal_position;').splitlines()
        require(columns and all(database.identifier(c) for c in columns) and 'id' in columns,
                'Required source table schema is absent or unsupported')
        expression = 'JSON_OBJECT(' + ','.join(f"'{c}',`{c}`" for c in columns) + ')'
        counts = docker.sql(container, f'SELECT COUNT(*),COALESCE(SUM(OCTET_LENGTH({expression})),0) '
                            f'FROM `{table}`;').split('\t')
        require(len(counts) == 2 and all(c.isdigit() for c in counts), 'Invalid source table size')
        total += int(counts[1])
        require(int(counts[0]) <= MAX_FILES and total <= MAX_JSON_BYTES,
                'Source snapshot exceeds row/32 MiB metadata limit')
        raw = docker.sql(container, f'SELECT {expression} FROM `{table}` ORDER BY id;')
        require(len(raw.encode('utf-8')) <= MAX_JSON_BYTES, 'Source snapshot changed beyond size limit')
        try:
            result[table] = [json.loads(line, object_pairs_hook=unique_object) for line in raw.splitlines()]
        except (ValueError, UnicodeError) as error:
            raise BackupError('Invalid source snapshot JSON') from error
        require(len(result[table]) == int(counts[0]), 'Source snapshot changed while reading')
    require(len(canonical(result)) <= MAX_JSON_BYTES, 'Source snapshot exceeds 32 MiB limit')
    return result


def check_source_tables(docker, container, manifest):
    for table in manifest['tables']:
        name = table['name']
        rows = docker.sql(container, f'SELECT COUNT(*) FROM `{name}`;')
        checksum = docker.sql(container, f'CHECKSUM TABLE `{name}` EXTENDED;').split('\t')[-1]
        require(rows == str(table['rows']) and checksum == str(table['checksum']),
                'Source database changed since SQL export')


def validate_attachment_metadata(snapshot):
    """Apply the restore contract to current and historical rows without repairing them."""
    identifiers, active = set(), set()
    for row in snapshot['base_attachment']:
        require(type(row.get('id')) is int and row['id'] > 0 and row['id'] not in identifiers,
                'Invalid or duplicate attachment ID; inspect source metadata before retrying')
        identifiers.add(row['id'])
        require(row.get('del_flag') in ('N', 'Y') and
                row.get('binding_state') in ('DRAFT', 'BOUND', 'LEGACY', 'CONFLICT'),
                'Unsupported attachment state; inspect source metadata before retrying')
    for reference in snapshot['attachment_reference']:
        require(type(reference.get('attachment_id')) is int and reference['attachment_id'] in identifiers and
                reference.get('active') in ('Y', 'N'),
                'Invalid or orphaned attachment reference; inspect source metadata before retrying')
        if reference['active'] == 'Y':
            active.add(reference['attachment_id'])
    return active


def attachment_mapping(snapshot, uploads, inventory):
    active = validate_attachment_metadata(snapshot)
    result, referenced = [], set()
    for row in snapshot['base_attachment']:
        require(isinstance(row.get('path'), str) and Path(row['path']).is_absolute(),
                'Attachment path must be absolute')
        path = check_path(row['path'], uploads, missing=True)
        relative = safe_relative(path.relative_to(uploads).as_posix())
        name = 'uploads/' + relative
        require(name.casefold() not in referenced, 'Attachment path aliases are not allowed')
        referenced.add(name.casefold())
        present = name in inventory
        required = row.get('del_flag') != 'Y' or row['id'] in active
        require(present or not required, 'Required live attachment file is missing')
        require(type(row.get('size')) is int and row['size'] >= 0, 'Invalid attachment size metadata')
        item = inventory.get(name)
        require(not present or item['bytes'] == row['size'], 'Attachment byte length differs from database')
        result.append({'id': row['id'], 'sourcePath': row['path'], 'relativePath': relative,
                       'archivePath': name if present else None, 'bytes': row['size'],
                       'sha256': item['sha256'] if present else None, 'present': present,
                       'required': required, 'bindingState': row.get('binding_state'),
                       'delFlag': row.get('del_flag')})
    orphan = sorted(name.removeprefix('uploads/') for name in inventory
                    if name.startswith('uploads/') and name.casefold() not in referenced)
    return result, orphan


def validate_rebuild(snapshot):
    require(isinstance(snapshot, dict) and set(snapshot) == set(TABLES) and
            all(isinstance(snapshot[t], list) and all(isinstance(r, dict) for r in snapshot[t])
                for t in TABLES), 'Invalid system snapshot')
    # One restoration contract, sharing the real application's hash/field validation.
    # Import lazily because the restore entry point itself imports this package module.
    try:
        from .system_restore import payloads
    except ImportError:
        from system_restore import payloads
    try:
        payloads(snapshot['policy_rag_sync'])
    except database.DatabaseError as error:
        raise BackupError('MySQL RAG state cannot be reconstructed from its immutable payloads') from error


def crypto():
    try:
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
        from cryptography.exceptions import InvalidTag
        return Cipher, algorithms, modes, InvalidTag
    except ImportError as error:
        raise BackupError('Install tools/requirements-system-backup.txt in the tooling virtual environment') from error


def exclusive_file(path):
    return os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb')


def archive_file(archive, source, name, expected):
    """Bound writes while copying, so a growing file cannot exhaust staging storage."""
    require(0 <= expected['bytes'] <= MAX_FILE_BYTES, 'File exceeds 8 GiB limit')
    digest, copied = hashlib.sha256(), 0
    with source.open('rb') as incoming, archive.open(name, 'w', force_zip64=True) as outgoing:
        for block in iter(lambda: incoming.read(BLOCK), b''):
            copied += len(block)
            require(copied <= expected['bytes'], 'Source grew during archive creation')
            digest.update(block)
            outgoing.write(block)
    require(copied == expected['bytes'] and digest.hexdigest() == expected['sha256'],
            'Source changed during archive creation')


def encrypt_archive(archive, output, key):
    Cipher, algorithms, modes, _ = crypto()
    require(len(key) == 32, 'Recovery key must contain exactly 32 bytes')
    require(archive.stat().st_size + len(MAGIC) + 28 <= MAX_PACKAGE_BYTES, 'Package exceeds 16 GiB limit')
    nonce = secrets.token_bytes(12)
    header = MAGIC + nonce
    encryptor = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
    encryptor.authenticate_additional_data(header)
    with archive.open('rb') as source, exclusive_file(output) as destination:
        destination.write(header)
        for chunk in iter(lambda: source.read(BLOCK), b''):
            destination.write(encryptor.update(chunk))
        destination.write(encryptor.finalize())
        destination.write(encryptor.tag)
        destination.flush()
        os.fsync(destination.fileno())


def decrypt_archive(package, destination, key):
    Cipher, algorithms, modes, InvalidTag = crypto()
    size = package.stat().st_size
    require(len(MAGIC) + 28 < size <= MAX_PACKAGE_BYTES, 'Invalid package length or package exceeds 16 GiB limit')
    with package.open('rb') as source, exclusive_file(destination) as target:
        header = source.read(len(MAGIC) + 12)
        require(header.startswith(MAGIC), 'Unsupported encrypted package format')
        source.seek(-16, os.SEEK_END)
        tag = source.read(16)
        source.seek(len(header))
        decryptor = Cipher(algorithms.AES(key), modes.GCM(header[-12:], tag)).decryptor()
        decryptor.authenticate_additional_data(header)
        remaining = size - len(header) - 16
        try:
            while remaining:
                block = source.read(min(BLOCK, remaining))
                require(bool(block), 'Encrypted package was truncated')
                remaining -= len(block)
                target.write(decryptor.update(block))
            target.write(decryptor.finalize())
        except InvalidTag as error:
            raise BackupError('Package authentication failed (damaged package or wrong key)') from error
        # No plaintext parser/caller sees data before successful GCM authentication.


def validate_manifest(folder, manifest):
    require(isinstance(manifest, dict) and type(manifest.get('format')) is int and manifest['format'] == 1,
            'Unsupported system manifest')
    files = manifest.get('files')
    require(isinstance(files, list) and 3 <= len(files) < MAX_FILES, 'Invalid file inventory')
    expected = set()
    inventory = {}
    for item in files:
        require(isinstance(item, dict), 'Invalid file entry')
        name = safe_relative(item.get('path'))
        require(name.casefold() not in expected and name != 'system-manifest.json', 'Duplicate manifest file')
        expected.add(name.casefold())
        kind = ('database' if name in ('database/database.sql', 'database/manifest.json') else
                'snapshot' if name == 'snapshot.json' else
                'rag-state' if name == 'rag/policy_documents.json' else
                'attachment' if name.startswith('uploads/') else
                'configuration' if name.startswith('config/') and name[7:] in CONFIG_PATHS else None)
        require(kind is not None and item.get('kind') == kind, 'File is outside archive whitelist')
        require(type(item.get('bytes')) is int and 0 <= item['bytes'] <= MAX_FILE_BYTES and
                isinstance(item.get('sha256'), str) and re.fullmatch('[a-f0-9]{64}', item['sha256']),
                'Invalid file length or digest')
        path = folder / name
        require(path.is_file() and path.stat().st_size == item['bytes'] and
                database.sha256_file(path) == item['sha256'], 'Archived file failed inventory hash/size verification')
        inventory[name] = item
    actual = {p.relative_to(folder).as_posix().casefold() for p in folder.rglob('*') if p.is_file()}
    require(actual == expected | {'system-manifest.json'}, 'Archive contains missing or unlisted files')
    require(manifest.get('snapshotPath') == 'snapshot.json', 'Invalid snapshot path')
    snapshot = read_json(folder / 'snapshot.json')
    validate_rebuild(snapshot)
    db_manifest = database.load_backup(folder / 'database')
    for table in TABLES:
        counts = [r['rows'] for r in db_manifest['tables'] if r['name'] == table]
        require(counts == [len(snapshot[table])], 'Snapshot count differs from SQL manifest')
    source = manifest.get('source')
    require(isinstance(source, dict) and source.get('writersStopped') is True and
            isinstance(source.get('uploadsRoot'), str), 'Invalid source roots')
    # Revalidate relocation without consulting source filesystem; source may no longer exist.
    old_root = PureWindowsPath(source['uploadsRoot']) if PureWindowsPath(source['uploadsRoot']).drive else PurePosixPath(source['uploadsRoot'])
    require(old_root.is_absolute() and '..' not in old_root.parts, 'Invalid original uploads root')
    mappings = manifest.get('attachments')
    require(isinstance(mappings, list) and len(mappings) == len(snapshot['base_attachment']), 'Invalid attachment mappings')
    active = validate_attachment_metadata(snapshot)
    by_id = {r['id']: r for r in snapshot['base_attachment']}
    seen, paths = set(), set()
    for mapping in mappings:
        require(isinstance(mapping, dict) and type(mapping.get('id')) is int and
                mapping['id'] in by_id and mapping['id'] not in seen, 'Invalid attachment ID mapping')
        seen.add(mapping['id'])
        row = by_id[mapping['id']]
        relative = safe_relative(mapping.get('relativePath'))
        require(relative.casefold() not in paths, 'Duplicate attachment path mapping')
        paths.add(relative.casefold())
        original = type(old_root)(mapping.get('sourcePath', ''))
        require(original.is_absolute() and '..' not in original.parts and
                original == old_root.joinpath(*relative.split('/')) and row['path'] == mapping['sourcePath'],
                'Attachment source/relative path mismatch')
        required = row.get('del_flag') != 'Y' or row['id'] in active
        name = 'uploads/' + relative
        present = name in inventory
        require(mapping.get('present') is present and mapping.get('required') is required and
                (present or not required) and mapping.get('bytes') == row['size'] and
                mapping.get('bindingState') == row.get('binding_state') and
                mapping.get('delFlag') == row.get('del_flag'), 'Attachment metadata mapping mismatch')
        require(mapping.get('archivePath') == (name if present else None) and
                mapping.get('sha256') == (inventory[name]['sha256'] if present else None) and
                (not present or inventory[name]['bytes'] == row['size']), 'Attachment inventory mismatch')
    require(manifest.get('orphanFiles') == sorted(name[8:] for name in inventory
                if name.startswith('uploads/') and name[8:].casefold() not in paths), 'Invalid orphan file inventory')
    rag = manifest.get('rag')
    present = 'rag/policy_documents.json' in inventory
    require(isinstance(rag, dict) and rag.get('state') == ('present' if present else 'missing') and
            rag.get('archivePath') == ('rag/policy_documents.json' if present else None) and
            rag.get('rebuildRequired') is True and rag.get('authority') == 'MySQL policy_rag_sync' and
            rag.get('restorePolicy') == 'fresh-json-and-collection', 'Invalid RAG restoration policy')
    if present:
        read_json(folder / rag['archivePath'])


def check_zip_directory(archive):
    """Bound the central directory before zipfile allocates entries (including ZIP64)."""
    with archive.open('rb') as stream:
        size = archive.stat().st_size
        stream.seek(max(0, size - 65557))
        tail = stream.read(65557)
        offset = tail.rfind(b'PK\x05\x06')
        require(offset >= 0 and len(tail) - offset >= 22, 'Missing ZIP end record')
        record = struct.unpack_from('<4s4H2LH', tail, offset)
        _, disk, directory_disk, disk_count, count, directory_size, directory_offset, comment_size = record
        require(offset + 22 + comment_size == len(tail) and disk == directory_disk == 0,
                'Multipart or trailing-data ZIP refused')
        end_position = size - len(tail) + offset
        if count == 0xffff or directory_size == 0xffffffff or directory_offset == 0xffffffff:
            require(end_position >= 20, 'Missing ZIP64 locator')
            stream.seek(end_position - 20)
            locator = stream.read(20)
            signature, zip_disk, zip_offset, disks = struct.unpack('<4sLQL', locator)
            require(signature == b'PK\x06\x07' and zip_disk == 0 and disks == 1,
                    'Invalid ZIP64 locator')
            require(zip_offset + 56 <= end_position - 20, 'Invalid ZIP64 directory position')
            stream.seek(zip_offset)
            raw = stream.read(56)
            signature, length, _made, _needed, disk, directory_disk, disk_count, count, directory_size, directory_offset = struct.unpack('<4sQ2H2L4Q', raw)
            require(signature == b'PK\x06\x06' and length == 44 and disk == directory_disk == 0,
                    'Unsupported ZIP64 directory')
        require(count == disk_count and 0 < count <= MAX_FILES and directory_size <= MAX_JSON_BYTES and
                directory_offset + directory_size <= end_position, 'ZIP central directory exceeds limits')


def unpack_archive(archive, folder):
    check_zip_directory(archive)
    with zipfile.ZipFile(archive, 'r') as z:
        entries = z.infolist()
        require(0 < len(entries) <= MAX_FILES, 'Archive entry count exceeds limit')
        seen, total = set(), 0
        for entry in entries:
            name = safe_relative(entry.filename)
            mode = entry.external_attr >> 16
            require(name.casefold() not in seen and not entry.is_dir() and
                    (not stat.S_IFMT(mode) or stat.S_ISREG(mode)) and not (entry.external_attr & 0x400),
                    'Archive contains duplicate paths, directories, links or special files')
            seen.add(name.casefold())
            require(entry.compress_type == zipfile.ZIP_STORED and not entry.flag_bits & 1 and
                    entry.file_size == entry.compress_size and 0 <= entry.file_size <= MAX_FILE_BYTES,
                    'Compressed/encrypted/oversize archive entry refused')
            total += entry.file_size
            require(total <= MAX_PACKAGE_BYTES, 'Archive total size exceeds limit')
            require(name == 'system-manifest.json' or name in ('database/database.sql', 'database/manifest.json',
                    'snapshot.json', 'rag/policy_documents.json') or name.startswith('uploads/') or
                    (name.startswith('config/') and name[7:] in CONFIG_PATHS), 'Archive path is outside whitelist')
        require('system-manifest.json' in seen, 'System manifest is missing')
        for entry in entries:
            destination = folder / entry.filename
            destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            with z.open(entry, 'r') as source, exclusive_file(destination) as target:
                written = 0
                for chunk in iter(lambda: source.read(BLOCK), b''):
                    written += len(chunk)
                    require(written <= entry.file_size, 'Archive entry exceeds declared size')
                    target.write(chunk)
                require(written == entry.file_size, 'Archive entry truncated')
    manifest = read_json(folder / 'system-manifest.json')
    validate_manifest(folder, manifest)
    return manifest


@contextmanager
def verified_package(package, key_file, *, workspace_parent=None):
    """Yield private (root, manifest) only after authentication and full validation."""
    package, key_file = check_path(package), check_path(key_file)
    require(package.is_file() and key_file.is_file() and key_file.stat().st_size == 32,
            'Package and 32-byte recovery key must be regular files')
    require(package.stat().st_nlink == key_file.stat().st_nlink == 1, 'Package/key hardlink aliases refused')
    if workspace_parent is not None:
        check_path(workspace_parent)
    with tempfile.TemporaryDirectory(prefix='silvercare-verified-', dir=workspace_parent) as temporary:
        temporary = Path(temporary)
        os.chmod(temporary, 0o700)
        try:
            archive = temporary / 'authenticated.zip'
            decrypt_archive(package, archive, key_file.read_bytes())
            folder = temporary / 'content'
            folder.mkdir(mode=0o700)
            manifest = unpack_archive(archive, folder)
            archive.unlink()
        except (zipfile.BadZipFile, OSError, ValueError, KeyError, TypeError, struct.error, database.DatabaseError) as error:
            raise BackupError('Package validation or private workspace operation failed') from error
        yield folder, manifest


def create_backup(container, project_root, uploads_root, rag_file, output, key_file, *, writers_stopped=False):
    require(writers_stopped is True, 'Explicit --writers-stopped acknowledgement is required')
    project = check_path(project_root)
    uploads = absolute_path(uploads_root)
    uploads = check_path(uploads, project if uploads.is_relative_to(project) else None)
    rag = absolute_path(rag_file)
    require(project.is_dir() and uploads.is_dir(), 'Project and uploads roots must be directories')
    output, key_file = absolute_path(output), absolute_path(key_file)
    require(output.resolve() != key_file.resolve(), 'Package and key paths must be different')
    for target in (output, key_file):
        require(not target.exists() and not target.is_symlink(), 'Output/key exists; never overwritten')
        require(check_path(target.parent).is_dir(), 'Output/key parent must already exist')
        for root in (uploads,):
            require(not target.resolve().is_relative_to(root.resolve()), 'Output/key cannot be inside source uploads')
        require(target.resolve() != rag.resolve() and target.resolve() not in
                {(project / p).resolve() for p in CONFIG_PATHS}, 'Output/key cannot alias source configuration')
    assert_writers_stopped(project)
    before = source_inventory(project, uploads, rag)
    with tempfile.TemporaryDirectory(prefix='silvercare-system-create-') as temporary:
        temporary = Path(temporary)
        os.chmod(temporary, 0o700)
        docker = database.Docker(temporary / 'source-check-log')
        snapshot = snapshot_database(docker, container)
        validate_rebuild(snapshot)
        mapping, orphan = attachment_mapping(snapshot, uploads, before)
        database.backup(container, temporary / 'database')
        db_manifest = database.load_backup(temporary / 'database')
        check_source_tables(docker, container, db_manifest)
        require(canonical(snapshot) == canonical(snapshot_database(docker, container)), 'Source database metadata changed')
        (temporary / 'snapshot.json').write_bytes(canonical(snapshot))
        files = []
        archive = temporary / 'payload.zip'
        with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_STORED, allowZip64=True) as z:
            for name, item in before.items():
                archive_file(z, item['source'], name, item)
                files.append({'path': name, 'bytes': item['bytes'], 'sha256': item['sha256'], 'kind': item['kind']})
            for name, kind in (('database/database.sql', 'database'), ('database/manifest.json', 'database'),
                               ('snapshot.json', 'snapshot')):
                path = temporary / name
                item = {'path': name, 'bytes': path.stat().st_size,
                        'sha256': database.sha256_file(path), 'kind': kind}
                files.append(item)
                require(sum(f['bytes'] for f in files) <= MAX_PACKAGE_BYTES, 'Archive total exceeds 16 GiB limit')
                archive_file(z, path, name, item)
            manifest = {'format': 1, 'createdAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                        'source': {'projectRoot': str(project), 'uploadsRoot': str(uploads), 'ragFile': str(rag),
                                   'writersStopped': True},
                        'files': files, 'attachments': mapping, 'orphanFiles': orphan,
                        'snapshotPath': 'snapshot.json',
                        'rag': {'state': 'present' if rag.exists() else 'missing',
                                'archivePath': 'rag/policy_documents.json' if rag.exists() else None,
                                'rebuildRequired': True, 'authority': 'MySQL policy_rag_sync',
                                'restorePolicy': 'fresh-json-and-collection'},
                        'excluded': ['Redis sessions', 'derived Milvus vectors']}
            z.writestr('system-manifest.json', canonical(manifest))
        after = source_inventory(project, uploads, rag)
        require(before == after, 'Source files changed during backup; package was not published')
        assert_writers_stopped(project)
        check_source_tables(docker, container, db_manifest)
        checked = temporary / 'checked'
        checked.mkdir(mode=0o700)
        unpack_archive(archive, checked)
        recovery_key = secrets.token_bytes(32)
        # The key is created exclusively first; a failed publication may leave a recovery
        # key, but can never leave a valid package with a silently overwritten key.
        with exclusive_file(key_file) as destination:
            destination.write(recovery_key)
            destination.flush()
            os.fsync(destination.fileno())
        encrypt_archive(archive, output, recovery_key)
        with verified_package(output, key_file) as (_, verified):
            require(verified == manifest, 'Published package differs from checked source')
    return {'status': 'backed_up', 'package': str(output), 'keyFile': str(key_file),
            'sha256': database.sha256_file(output), 'files': len(files), 'attachments': len(mapping),
            'orphanFiles': len(orphan), 'ragRebuildRequired': True}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    create = commands.add_parser('create')
    for name in ('container', 'project-root', 'uploads-root', 'rag-file', 'output', 'key-file'):
        create.add_argument('--' + name, required=True)
    create.add_argument('--writers-stopped', action='store_true')
    verify = commands.add_parser('verify')
    verify.add_argument('--package', required=True)
    verify.add_argument('--key-file', required=True)
    restore = commands.add_parser('restore-drill')
    restore.add_argument('--package', required=True)
    restore.add_argument('--key-file', required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == 'create':
            result = create_backup(args.container, args.project_root, args.uploads_root, args.rag_file,
                                   args.output, args.key_file, writers_stopped=args.writers_stopped)
        elif args.command == 'verify':
            with verified_package(args.package, args.key_file) as (_, manifest):
                result = {'status': 'verified', 'files': len(manifest['files']),
                          'attachments': len(manifest['attachments']), 'ragRebuildRequired': True}
        else:
            try:
                from .system_restore import restore_drill
            except ImportError:
                from system_restore import restore_drill
            result = restore_drill(args.package, args.key_file)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (BackupError, database.DatabaseError, OSError, subprocess.SubprocessError) as error:
        # Never stringify OS/subprocess errors: they may include source credentials or argv.
        message = str(error) if isinstance(error, BackupError) else type(error).__name__
        print('Whole-system backup failed: ' + message, file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
