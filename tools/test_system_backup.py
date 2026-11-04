"""Real crypto/filesystem boundary tests; Docker export is the only substituted boundary."""
import copy
import hashlib
import json
import os
from pathlib import Path
import stat
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

try:
    from tools import system_backup as backup
except ModuleNotFoundError:
    import system_backup as backup


class SystemBackupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.project = self.root / 'project'
        self.project.mkdir()
        self.uploads = self.project / 'uploads'
        self.uploads.mkdir()
        self.asset = self.uploads / '护理资料.txt'
        self.asset.write_bytes('这是合成附件'.encode('utf-8'))
        (self.project / '.env').write_text('PRIVATE_TOKEN=synthetic-secret-13492', encoding='utf-8')
        self.rag = self.project / 'rag.json'
        self.rag.write_text('{"schema_version":2,"documents":[]}', encoding='utf-8')
        self.package, self.key = self.root / 'new.scbackup', self.root / 'new.key'
        self.snapshot = {
            'base_attachment': [{'id': 9, 'path': str(self.asset), 'size': self.asset.stat().st_size,
                                 'binding_state': 'BOUND', 'del_flag': 'N', 'media_type': 'text/plain'}],
            'attachment_reference': [{'id': 3, 'attachment_id': 9, 'active': 'Y',
                                      'owner_type': 'STAFF', 'owner_id': 4}],
            'policy_rag_sync': [],
        }
        self.sql = b'-- synthetic full database export\nCREATE TABLE example(id INT);\n'
        self.db_manifest = {
            'format': 1, 'database': 'db_beadhouse', 'file': 'database.sql',
            'sha256': hashlib.sha256(self.sql).hexdigest(), 'bytes': len(self.sql),
            'created_at': '2026-11-05T10:00:00Z', 'mysql_version': '8.0.40',
            'tables': [{'name': name, 'rows': len(rows), 'checksum': '1'}
                       for name, rows in self.snapshot.items()],
            'migrations': [], 'objects': {'triggers': [], 'routines': [], 'events': []},
            'constraints': [],
        }
        self.patches = [
            mock.patch.object(backup.database, 'backup', side_effect=self.export),
            mock.patch.object(backup.database, 'Docker'),
            mock.patch.object(backup, 'snapshot_database', side_effect=lambda *_: copy.deepcopy(self.snapshot)),
            mock.patch.object(backup, 'check_source_tables'),
        ]
        for patch in self.patches:
            patch.start()
        self.addCleanup(mock.patch.stopall)
        self.addCleanup(self.temporary.cleanup)

    def export(self, _container, folder):
        folder = Path(folder)
        folder.mkdir()
        (folder / 'database.sql').write_bytes(self.sql)
        (folder / 'manifest.json').write_text(json.dumps(self.db_manifest), encoding='utf-8')

    def create(self, **kwargs):
        return backup.create_backup('synthetic-source', self.project, self.uploads, self.rag,
                                    self.package, self.key, writers_stopped=True, **kwargs)

    def rewrite_archive(self, entries):
        archive = self.root / 'altered.zip'
        with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_STORED) as z:
            for name, payload in entries:
                z.writestr(name, payload)
        self.package.unlink()
        backup.encrypt_archive(archive, self.package, self.key.read_bytes())

    def entries(self):
        with backup.verified_package(self.package, self.key) as (folder, _manifest):
            return [(p.relative_to(folder).as_posix(), p.read_bytes())
                    for p in folder.rglob('*') if p.is_file()]

    def test_roundtrip_preserves_sql_chinese_attachment_config_and_metadata(self):
        self.create()
        encrypted = self.package.read_bytes()
        self.assertNotIn(b'synthetic-secret-13492', encrypted)
        self.assertNotIn(self.sql, encrypted)
        self.assertEqual(32, self.key.stat().st_size)
        with backup.verified_package(self.package, self.key) as (folder, manifest):
            self.assertEqual(self.sql, (folder / 'database/database.sql').read_bytes())
            self.assertEqual(self.asset.read_bytes(), (folder / 'uploads/护理资料.txt').read_bytes())
            self.assertEqual(self.snapshot, json.loads((folder / 'snapshot.json').read_text('utf-8')))
            self.assertIn('synthetic-secret-13492', (folder / 'config/.env').read_text('utf-8'))
            self.assertTrue(manifest['rag']['rebuildRequired'])
            self.assertEqual('BOUND', manifest['attachments'][0]['bindingState'])
        self.assertFalse(folder.exists())

    def test_tampered_header_nonce_ciphertext_tag_and_wrong_key_are_rejected(self):
        self.create()
        original = self.package.read_bytes()
        for index in (0, len(backup.MAGIC), len(backup.MAGIC) + 12 + 5, len(original) - 1):
            with self.subTest(index=index):
                changed = bytearray(original)
                changed[index] ^= 1
                self.package.write_bytes(changed)
                with self.assertRaises(backup.BackupError):
                    with backup.verified_package(self.package, self.key):
                        self.fail('Unauthenticated data escaped verification')
        self.package.write_bytes(original)
        self.key.write_bytes(os.urandom(32))
        with self.assertRaises(backup.BackupError):
            with backup.verified_package(self.package, self.key):
                self.fail('Wrong key accepted')

    def test_missing_live_attachment_fails_before_publication(self):
        self.asset.unlink()
        with self.assertRaises(backup.BackupError):
            self.create()
        self.assertFalse(self.package.exists())
        self.assertFalse(self.key.exists())

    def test_missing_deleted_inactive_attachment_is_recorded(self):
        self.snapshot['base_attachment'][0]['del_flag'] = 'Y'
        self.snapshot['attachment_reference'][0]['active'] = 'N'
        self.asset.unlink()
        self.create()
        with backup.verified_package(self.package, self.key) as (_, manifest):
            row = manifest['attachments'][0]
            self.assertFalse(row['present'])
            self.assertFalse(row['required'])
            self.assertEqual('护理资料.txt', row['relativePath'])

    def test_deleted_attachment_with_active_reference_is_still_required(self):
        self.snapshot['base_attachment'][0]['del_flag'] = 'Y'
        self.asset.unlink()
        with self.assertRaises(backup.BackupError):
            self.create()

    def invalid_attachment_metadata(self):
        return (
            ('inactive-orphan', 'attachment_reference', 'attachment_id', 88),
            ('invalid-active', 'attachment_reference', 'active', 'UNKNOWN'),
            ('invalid-binding-state', 'base_attachment', 'binding_state', 'UNKNOWN'),
            ('invalid-delete-flag', 'base_attachment', 'del_flag', 'UNKNOWN'),
        )

    def test_create_rejects_unrestorable_attachment_metadata_without_changing_source(self):
        original = copy.deepcopy(self.snapshot)
        for case, table, field, value in self.invalid_attachment_metadata():
            with self.subTest(case=case):
                self.snapshot = copy.deepcopy(original)
                self.snapshot['attachment_reference'][0]['active'] = 'N'
                self.snapshot[table][0][field] = value
                invalid = copy.deepcopy(self.snapshot)
                try:
                    with self.assertRaisesRegex(backup.BackupError, 'inspect source metadata'):
                        self.create()
                    self.assertFalse(self.package.exists())
                    self.assertFalse(self.key.exists())
                    backup.database.backup.assert_not_called()
                    self.assertEqual(invalid, self.snapshot)
                finally:
                    for path in (self.package, self.key):
                        if path.exists():
                            path.unlink()
                    backup.database.backup.reset_mock()

    def test_verify_rejects_authenticated_unrestorable_attachment_metadata(self):
        self.create()
        original = dict(self.entries())
        for case, table, field, value in self.invalid_attachment_metadata():
            with self.subTest(case=case):
                entries = dict(original)
                snapshot = json.loads(entries['snapshot.json'])
                snapshot['attachment_reference'][0]['active'] = 'N'
                snapshot[table][0][field] = value
                entries['snapshot.json'] = backup.canonical(snapshot)
                manifest = json.loads(entries['system-manifest.json'])
                row = snapshot['base_attachment'][0]
                manifest['attachments'][0].update(bindingState=row['binding_state'], delFlag=row['del_flag'])
                for entry in manifest['files']:
                    if entry['path'] == 'snapshot.json':
                        entry.update(bytes=len(entries['snapshot.json']),
                                     sha256=hashlib.sha256(entries['snapshot.json']).hexdigest())
                entries['system-manifest.json'] = backup.canonical(manifest)
                self.rewrite_archive(entries.items())
                with self.assertRaisesRegex(backup.BackupError, 'inspect source metadata'):
                    with backup.verified_package(self.package, self.key):
                        pass

    def test_supported_states_and_inactive_reference_survive_backup_and_restore_plan(self):
        try:
            from tools import system_restore as restore
        except ModuleNotFoundError:
            import system_restore as restore
        destination = self.root / 'restore-plan-uploads'
        destination.mkdir()
        self.snapshot['attachment_reference'][0]['active'] = 'N'
        for state in ('DRAFT', 'BOUND', 'LEGACY', 'CONFLICT'):
            for deleted in ('N', 'Y'):
                with self.subTest(binding_state=state, del_flag=deleted):
                    self.snapshot['base_attachment'][0].update(binding_state=state, del_flag=deleted)
                    original = copy.deepcopy(self.snapshot)
                    self.create()
                    with backup.verified_package(self.package, self.key) as (folder, manifest):
                        snapshot = json.loads((folder / 'snapshot.json').read_text('utf-8'))
                        self.assertEqual(original, snapshot)
                        plan = restore.attachment_plan(folder, manifest, snapshot, destination)
                        self.assertEqual([9], [item['id'] for item in plan])
                        self.assertEqual(original, snapshot)
                    self.package.unlink()
                    self.key.unlink()

    def test_missing_rag_is_explicitly_rebuild_required(self):
        self.rag.unlink()
        self.create()
        with backup.verified_package(self.package, self.key) as (_, manifest):
            self.assertEqual('missing', manifest['rag']['state'])
            self.assertIsNone(manifest['rag']['archivePath'])

    def test_rag_missing_requires_mysql_rebuild_payload(self):
        self.rag.unlink()
        self.snapshot['policy_rag_sync'] = [{'id': 1, 'desired_action': 'UPSERT',
                                           'desired_version': 2, 'payload_json': None}]
        with self.assertRaises(backup.BackupError):
            self.create()

    def test_rag_tombstone_exact_bytes_and_versions_are_preserved(self):
        try:
            from tools.system_restore import rag_modules
        except ModuleNotFoundError:
            from system_restore import rag_modules
        protocol, _ = rag_modules()
        payload = {'document_key': 'synthetic-deleted', 'version': 12, 'action': 'DELETE',
                   'title': '已删除制度', 'source': '合成测试', 'chunks': []}
        payload['payload_hash'] = protocol.canonical_payload_hash(
            payload['document_key'], payload['title'], payload['source'], [], 'DELETE')
        self.snapshot['policy_rag_sync'] = [{'id': 1, 'document_key': payload['document_key'],
            'desired_version': 12, 'desired_action': 'DELETE', 'title': payload['title'],
            'source': payload['source'], 'revision': payload['payload_hash'],
            'payload_json': json.dumps(payload, ensure_ascii=False)}]
        self.db_manifest['tables'][2]['rows'] = 1
        source_rag = {'schema_version': 2, 'documents': {
            payload['document_key']: dict(payload, version=14, indexed_version=14, index_namespace='old_v2')}}
        self.rag.write_text(json.dumps(source_rag, ensure_ascii=False), encoding='utf-8')
        self.create()
        with backup.verified_package(self.package, self.key) as (folder, _):
            self.assertEqual(self.rag.read_bytes(), (folder / 'rag/policy_documents.json').read_bytes())
            self.assertEqual(12, json.loads((folder / 'snapshot.json').read_text('utf-8'))['policy_rag_sync'][0]['desired_version'])
        self.package.unlink()
        self.key.unlink()
        self.snapshot['policy_rag_sync'][0]['revision'] = '0' * 64
        with self.assertRaises(backup.BackupError):
            self.create()

    def test_verified_workspace_does_not_relabel_consumer_errors(self):
        self.create()
        with self.assertRaisesRegex(backup.database.DatabaseError, 'owned cleanup failure'):
            with backup.verified_package(self.package, self.key):
                raise backup.database.DatabaseError('owned cleanup failure')

    def test_source_change_during_export_is_rejected(self):
        export = self.export
        def change(container, folder):
            export(container, folder)
            self.asset.write_bytes(b'x' * self.asset.stat().st_size)
        backup.database.backup.side_effect = change
        with self.assertRaises(backup.BackupError):
            self.create()
        self.assertFalse(self.package.exists())

    def test_database_snapshot_change_is_rejected(self):
        after = copy.deepcopy(self.snapshot)
        after['base_attachment'][0]['binding_state'] = 'CONFLICT'
        backup.snapshot_database.side_effect = [copy.deepcopy(self.snapshot), after]
        with self.assertRaises(backup.BackupError):
            self.create()

    def test_output_and_key_are_exclusive_and_cannot_be_inside_source(self):
        for target in (self.package, self.key):
            target.write_bytes(b'preserve')
            with self.assertRaises(backup.BackupError):
                self.create()
            self.assertEqual(b'preserve', target.read_bytes())
            target.unlink()
        self.key = self.uploads / 'not-allowed.key'
        with self.assertRaises(backup.BackupError):
            self.create()

    def test_writers_acknowledgement_is_mandatory(self):
        with self.assertRaises(backup.BackupError):
            backup.create_backup('source', self.project, self.uploads, self.rag, self.package, self.key)
        backup.database.backup.assert_not_called()

    def test_attachment_paths_outside_root_and_aliases_are_rejected(self):
        for bad in (str(self.root / 'outside'), str(self.uploads / '..' / 'outside')):
            self.snapshot['base_attachment'][0]['path'] = bad
            with self.assertRaises(backup.BackupError):
                self.create()

    def test_hardlinked_source_is_rejected(self):
        os.link(self.asset, self.uploads / 'alias.txt')
        with self.assertRaises(backup.BackupError):
            self.create()

    def test_source_junction_or_symlink_directory_is_rejected(self):
        outside = self.root / 'external'
        outside.mkdir()
        (outside / 'external.txt').write_bytes(b'external')
        link = self.uploads / 'linked'
        if os.name == 'nt':
            environment = dict(os.environ, SC_TEST_LINK=str(link), SC_TEST_TARGET=str(outside))
            subprocess.run([shutil.which('pwsh'), '-NoProfile', '-NonInteractive', '-Command',
                            'New-Item -ItemType Junction -Path $env:SC_TEST_LINK -Target $env:SC_TEST_TARGET | Out-Null'],
                           env=environment, check=True, capture_output=True)
        else:
            link.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(backup.BackupError):
            self.create()
        self.assertFalse(self.package.exists())

    @unittest.skipUnless(os.name == 'nt' and shutil.which('pwsh'), 'Windows lifecycle identity protocol')
    def test_live_source_lifecycle_process_is_rejected_without_stopping_it(self):
        writer = self.project / 'writer.py'
        writer.write_text('import time\ntime.sleep(90)\n', encoding='utf-8')
        process = subprocess.Popen([sys.executable, str(writer)], stdin=subprocess.DEVNULL,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            environment = dict(os.environ, SC_TEST_PID=str(process.pid), SC_TEST_PROJECT=str(self.project),
                               SC_TEST_HELPER=str(backup.database.ROOT / '启停工具.ps1'))
            result = subprocess.run([shutil.which('pwsh'), '-NoProfile', '-NonInteractive', '-Command',
                '. $env:SC_TEST_HELPER; Get-ProcessIdentity -ProcessId ([int]$env:SC_TEST_PID) '
                '-ProjectRoot $env:SC_TEST_PROJECT -Name backend | ConvertTo-Json -Depth 8 -Compress'],
                env=environment, check=True, capture_output=True)
            identity = json.loads(result.stdout)
            self.assertIsNotNone(identity)
            (self.project / '.runtime').mkdir()
            (self.project / '.runtime/lifecycle-test.json').write_text(json.dumps({
                'schemaVersion': 1, 'projectRoot': str(self.project), 'processes': [identity]}), encoding='utf-8')
            with self.assertRaises(backup.BackupError):
                self.create()
            self.assertIsNone(process.poll())
            backup.database.backup.assert_not_called()
        finally:
            process.terminate()
            process.wait(timeout=10)

    def test_central_directory_is_bounded_before_zipfile_parse(self):
        self.create()
        archive = self.root / 'forged.zip'
        # Authenticated input may still be a malicious archive; reject count before allocation.
        archive.write_bytes(b'PK\x05\x06' + struct.pack('<4H2LH', 0, 0, 500, 500, 1, 0, 0))
        self.package.unlink()
        backup.encrypt_archive(archive, self.package, self.key.read_bytes())
        with mock.patch.object(backup, 'MAX_FILES', 100), mock.patch.object(backup.zipfile, 'ZipFile') as parser:
            with self.assertRaises(backup.BackupError):
                with backup.verified_package(self.package, self.key):
                    self.fail('Oversized directory accepted')
            parser.assert_not_called()

    def test_file_growth_is_bounded_during_archiving(self):
        expected = backup.file_fingerprint(self.asset)
        self.asset.write_bytes(self.asset.read_bytes() + b'extra')
        with zipfile.ZipFile(self.root / 'growing.zip', 'w') as archive:
            with self.assertRaisesRegex(backup.BackupError, 'grew'):
                backup.archive_file(archive, self.asset, 'uploads/护理资料.txt', expected)

    def test_unreferenced_files_are_backed_up_and_listed(self):
        (self.uploads / 'orphan.txt').write_bytes(b'orphan')
        self.create()
        with backup.verified_package(self.package, self.key) as (folder, manifest):
            self.assertEqual(['orphan.txt'], manifest['orphanFiles'])
            self.assertEqual(b'orphan', (folder / 'uploads/orphan.txt').read_bytes())

    def test_archive_traversal_drives_ads_backslash_and_case_collisions_rejected(self):
        self.create()
        original = self.entries()
        for bad in ('../escape', '/escape', 'C:/escape', 'uploads/a:secret',
                    'uploads\\escape', 'uploads/NUL.txt', 'uploads/trailing. ',
                    'UPLOADS/护理资料.txt', 'uploads/护理资料.txt'):
            with self.subTest(path=bad):
                self.rewrite_archive(original + [(bad, b'bad')])
                with self.assertRaises(backup.BackupError):
                    with backup.verified_package(self.package, self.key):
                        self.fail('Malicious archive accepted')

    def test_archive_symlink_rejected(self):
        self.create()
        entries = self.entries()
        link = zipfile.ZipInfo('uploads/link')
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        self.rewrite_archive(entries + [(link, b'outside')])
        with self.assertRaises(backup.BackupError):
            with backup.verified_package(self.package, self.key):
                self.fail('Archive symlink accepted')

    def test_inner_hash_sql_manifest_and_attachment_semantics_are_verified(self):
        self.create()
        entries = self.entries()
        self.rewrite_archive([(name, b'corrupt' if name == 'database/database.sql' else data)
                              for name, data in entries])
        with self.assertRaises(backup.BackupError):
            with backup.verified_package(self.package, self.key):
                self.fail('Changed SQL accepted')
        for name, data in entries:
            if name == 'system-manifest.json':
                manifest = json.loads(data)
                manifest['attachments'][0]['sourcePath'] = str(self.root / 'wrong')
                data = json.dumps(manifest).encode()
            if name == 'system-manifest.json':
                modified = [(n, data if n == name else d) for n, d in entries]
        self.rewrite_archive(modified)
        with self.assertRaises(backup.BackupError):
            with backup.verified_package(self.package, self.key):
                self.fail('Contradictory relocation accepted')

    def test_limit_and_compressed_archives_are_rejected(self):
        self.create()
        with mock.patch.object(backup, 'MAX_PACKAGE_BYTES', 20):
            with self.assertRaises(backup.BackupError):
                with backup.verified_package(self.package, self.key):
                    self.fail('Oversize accepted')
        entries = self.entries()
        archive = self.root / 'compressed.zip'
        with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as z:
            for name, data in entries:
                z.writestr(name, data)
        self.package.unlink()
        backup.encrypt_archive(archive, self.package, self.key.read_bytes())
        with self.assertRaises(backup.BackupError):
            with backup.verified_package(self.package, self.key):
                self.fail('Compressed archive accepted')


if __name__ == '__main__':
    unittest.main()
