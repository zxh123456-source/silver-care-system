"""Recovery lifecycle and relocation checks; real Docker/HTTP acceptance is separate."""
import contextlib
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import database
import system_restore as restore


class OwnedDatabaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.backup = self.root / 'backup'
        self.backup.mkdir()
        sql = b'SELECT 1;\n'
        (self.backup / 'database.sql').write_bytes(sql)
        self.manifest = {
            'format': 1, 'database': 'db_beadhouse', 'file': 'database.sql',
            'sha256': hashlib.sha256(sql).hexdigest(), 'bytes': len(sql),
            'created_at': '2026-10-20T10:00:00Z', 'mysql_version': '8.0.40',
            'migrations': [], 'tables': [{'name': 'example', 'rows': 0, 'checksum': '0'}],
            'objects': {'triggers': [], 'routines': [], 'events': []}, 'constraints': [],
        }
        (self.backup / 'manifest.json').write_text(json.dumps(self.manifest), encoding='utf-8')
        self.container = 'a' * 64
        self.calls = []
        self.token = None
        self.fake = mock.Mock()
        self.fake.run.side_effect = self.run_docker
        self.fake.inspect.side_effect = lambda name: {
            'id': self.container, 'labels': {database.OWNER_LABEL: self.token}}
        self.stack = contextlib.ExitStack()
        self.stack.enter_context(mock.patch.object(database, 'Docker', return_value=self.fake))
        self.stack.enter_context(mock.patch.object(database, 'new_log_dir', self.new_log))
        self.stack.enter_context(mock.patch.object(database, 'verify_restored', return_value={'status': 'verified'}))

    def new_log(self, token):
        self.token = token
        folder = self.root / token
        folder.mkdir()
        return folder

    def run_docker(self, args, **kwargs):
        self.calls.append((args, kwargs))
        output = b''
        if args[0] == 'run':
            output = self.container.encode('ascii')
        elif args[0] == 'port':
            output = b'127.0.0.1:49151\n'
        elif args[:2] == ['exec', '-i']:
            output = b'1\n'
        return subprocess.CompletedProcess(args, 0, output, b'')

    def tearDown(self):
        self.stack.close()
        self.temp.cleanup()

    def test_default_context_has_no_network_ports_mounts_and_secret_is_not_argv_or_repr(self):
        with database.owned_restored_database(self.backup) as restored:
            args, kwargs = next(call for call in self.calls if call[0][0] == 'run')
            self.assertEqual(args[args.index('--network') + 1], 'none')
            for option in ('--publish', '-p', '--mount', '-v', '--volume'):
                self.assertNotIn(option, args)
            self.assertNotIn(restored.password, ' '.join(args))
            self.assertNotIn(restored.password, repr(restored))
            self.assertEqual(kwargs['env']['MYSQL_ROOT_PASSWORD'], restored.password)
            self.assertIsNone(restored.port)
            self.assertFalse(any(call[0][0] == 'rm' for call in self.calls))
        self.assertEqual(self.calls[-1][0], ['rm', '-f', '-v', self.container])

    def test_internal_published_context_uses_only_random_loopback_port(self):
        with database.owned_restored_database(self.backup, publish_localhost=True) as restored:
            args = next(call[0] for call in self.calls if call[0][0] == 'run')
            self.assertEqual(args[args.index('--publish') + 1], '127.0.0.1::3306')
            self.assertEqual(restored.port, 49151)
            self.assertEqual(restored.host, '127.0.0.1')

    def test_body_failure_cleans_owned_container_without_recording_callback_secret(self):
        with self.assertRaises(database.DatabaseError):
            with database.owned_restored_database(self.backup) as restored:
                raise ValueError('callback-secret-must-not-be-logged')
        self.assertEqual(self.calls[-1][0], ['rm', '-f', '-v', self.container])
        self.assertNotIn('callback-secret', (self.root / self.token / 'result.json').read_text())

    def test_invalid_backup_does_not_create_docker_target(self):
        (self.backup / 'database.sql').write_bytes(b'bad')
        with self.assertRaises(database.DatabaseError):
            with database.owned_restored_database(self.backup):
                self.fail('invalid input yielded')
        self.assertEqual(self.calls, [])

    def test_changed_owner_rejects_cleanup(self):
        with self.assertRaisesRegex(database.DatabaseError, 'cleanup'):
            with database.owned_restored_database(self.backup):
                self.fake.inspect.return_value = {'id': self.container, 'labels': {}}
                self.fake.inspect.side_effect = None
        self.assertFalse(any(call[0][0] == 'rm' for call in self.calls))

    def test_old_drill_keeps_no_port_default(self):
        self.assertEqual(database.restore_drill(self.backup)['status'], 'verified')
        args = next(call[0] for call in self.calls if call[0][0] == 'run')
        self.assertEqual(args[args.index('--network') + 1], 'none')
        self.assertNotIn('--publish', args)

    def test_lost_creation_response_recovers_owned_id_and_cleans(self):
        original = self.fake.run.side_effect
        def lost_response(args, **kwargs):
            if args[0] == 'run':
                raise database.DatabaseError('Docker run timed out')
            return original(args, **kwargs)
        self.fake.run.side_effect = lost_response
        with self.assertRaises(database.DatabaseError):
            with database.owned_restored_database(self.backup):
                self.fail('creation failure yielded')
        self.assertEqual(self.calls[-1][0], ['rm', '-f', '-v', self.container])

    def test_unexpected_nonloopback_publication_is_rejected_and_cleaned(self):
        original = self.fake.run.side_effect
        def wrong_port(args, **kwargs):
            if args[0] == 'port':
                return subprocess.CompletedProcess(args, 0, b'0.0.0.0:49151\n', b'')
            return original(args, **kwargs)
        self.fake.run.side_effect = wrong_port
        with self.assertRaises(database.DatabaseError):
            with database.owned_restored_database(self.backup, publish_localhost=True):
                self.fail('nonloopback target yielded')
        self.assertEqual(self.calls[-1][0], ['rm', '-f', '-v', self.container])


class SystemRestoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.unpacked = self.root / 'unpacked'
        self.unpacked.mkdir()
        self.uploads = self.root / 'target-uploads'
        self.uploads.mkdir()
        data = b'owned attachment bytes'
        source = self.unpacked / 'uploads/nested/photo.png'
        source.parent.mkdir(parents=True)
        source.write_bytes(data)
        self.row = {'id': 7, 'path': 'C:/source/uploads/nested/photo.png', 'size': len(data),
                    'del_flag': 'N', 'binding_state': 'LEGACY', 'url': '/api/file/7/content',
                    'create_id': 11, 'update_id': 12, 'update_time': '2026-11-05 12:00:00'}
        self.snapshot = {'base_attachment': [self.row], 'attachment_reference': [], 'policy_rag_sync': []}
        self.entry = {'id': 7, 'sourcePath': self.row['path'], 'relativePath': 'nested/photo.png',
                      'archivePath': 'uploads/nested/photo.png', 'bytes': len(data),
                      'sha256': hashlib.sha256(data).hexdigest(), 'present': True, 'required': True,
                      'bindingState': 'LEGACY', 'delFlag': 'N'}
        self.manifest = {'source': {'uploadsRoot': 'C:/source/uploads'}, 'attachments': [self.entry]}

    def tearDown(self):
        self.temp.cleanup()

    def plan(self):
        return restore.attachment_plan(self.unpacked, self.manifest, self.snapshot, self.uploads)

    def test_nested_path_relocates_inside_new_root_preserving_original_metadata(self):
        before = copy.deepcopy(self.snapshot)
        result = self.plan()
        self.assertEqual(result[0]['new_path'], str(self.uploads / 'nested/photo.png'))
        self.assertEqual(result[0]['old_path'], self.row['path'])
        self.assertEqual(self.snapshot, before)
        self.assertEqual(self.row['binding_state'], 'LEGACY')

    def test_relocated_path_accepts_225_characters_and_rejects_226_before_sql(self):
        original = copy.deepcopy(self.snapshot)
        for length in (225, 226):
            padding = length - len(str(self.root)) - len(str(Path('nested') / 'photo.png')) - 2
            self.uploads = self.root / ('p' * padding)
            self.uploads.mkdir()
            with self.subTest(length=length):
                if length == 225:
                    self.assertEqual(len(self.plan()[0]['new_path']), 225)
                else:
                    with self.assertRaisesRegex(restore.RestoreError, 'shorter --work-parent'):
                        self.plan()
        self.assertEqual(self.snapshot, original)

    def test_missing_live_legacy_file_is_not_treated_as_optional(self):
        self.entry.update(present=False, archivePath=None, sha256=None)
        with self.assertRaisesRegex(restore.RestoreError, 'missing'):
            self.plan()

    def test_deleted_inactive_missing_file_retains_metadata_without_inventing_bytes(self):
        self.row['del_flag'] = self.entry['delFlag'] = 'Y'
        self.entry.update(present=False, required=False, archivePath=None, sha256=None)
        (self.unpacked / 'uploads/nested/photo.png').unlink()
        self.snapshot['attachment_reference'] = [{'id': 1, 'attachment_id': 7,
                                                  'owner_type': 'STAFF', 'owner_id': 13, 'active': 'N'}]
        self.assertFalse(self.plan()[0]['present'])
        self.assertEqual(self.row['binding_state'], 'LEGACY')
        self.snapshot['attachment_reference'][0]['active'] = 'Y'
        with self.assertRaises(restore.RestoreError):
            self.plan()

    def test_mismatched_ids_source_path_relative_path_and_state_are_rejected(self):
        for field, bad in [('id', 8), ('relativePath', '../outside'), ('sourcePath', 'C:/other/file'),
                           ('relativePath', 'nested/other.png'), ('bindingState', 'DRAFT'),
                           ('delFlag', 'Y'), ('sha256', 'f' * 64)]:
            with self.subTest(field=field):
                original = self.entry[field]
                self.entry[field] = bad
                with self.assertRaises(restore.RestoreError):
                    self.plan()
                self.entry[field] = original

    def test_path_escape_windows_devices_and_links_are_rejected(self):
        for path in ('../x', '/x', 'C:/x', 'a\\x', 'a/../x', 'a//x', 'NUL.txt', 'a. ', 'a?b'):
            with self.subTest(path=path), self.assertRaises(restore.RestoreError):
                restore.safe_relative(path)
        for path in ('C:/source/outside/photo.png', 'C:/source/uploads/../secret'):
            with self.subTest(path=path), self.assertRaises(restore.RestoreError):
                restore.source_relative('C:/source/uploads', path)
        with mock.patch.object(restore, 'linked', side_effect=lambda path: path.name == 'nested'):
            with self.assertRaisesRegex(restore.RestoreError, 'link'):
                self.plan()

    def test_sql_compares_id_and_binary_old_path_and_checks_every_row_atomically(self):
        sql = restore.relocation_sql(self.plan())
        self.assertIn('START TRANSACTION;', sql)
        self.assertIn('WHERE id=7 AND BINARY path=BINARY', sql)
        self.assertIn('CHECK (matched=1)', sql)
        self.assertIn('VALUES (ROW_COUNT())', sql)
        self.assertIn('COMMIT;', sql)
        self.assertNotIn('binding_state=', sql)
        injection = "x'; DELETE FROM staff; --\\"
        self.assertNotIn(injection, restore.sql_text(injection))
        self.assertIn(injection.encode().hex(), restore.sql_text(injection))

    def test_snapshot_mismatch_stops_before_any_update(self):
        fake_db = mock.Mock()
        wrong = copy.deepcopy(self.snapshot)
        wrong['base_attachment'][0]['id'] = 19
        fake_package = mock.Mock()
        fake_package.snapshot_database.return_value = wrong
        with mock.patch.object(restore, 'backup_module', return_value=fake_package):
            with self.assertRaisesRegex(restore.RestoreError, 'snapshot'):
                restore.relocate_attachments(fake_db, self.snapshot, self.plan())
        fake_db.docker.sql.assert_not_called()

    def test_after_relocation_owner_state_or_audit_changes_fail_verification(self):
        for field, value in [('binding_state', 'DRAFT'), ('create_id', 88), ('update_id', 88),
                              ('url', 'changed'), ('del_flag', 'Y')]:
            plan = self.plan()
            after = copy.deepcopy(self.snapshot)
            after['base_attachment'][0]['path'] = plan[0]['new_path']
            after['base_attachment'][0][field] = value
            module = mock.Mock()
            module.snapshot_database.side_effect = [self.snapshot, after]
            with self.subTest(field=field), mock.patch.object(restore, 'backup_module', return_value=module):
                with self.assertRaisesRegex(restore.RestoreError, 'metadata'):
                    restore.relocate_attachments(mock.Mock(), self.snapshot, plan)

    def test_workspace_cleanup_only_removes_its_new_child_on_error(self):
        sentinel = self.root / 'user.txt'
        sentinel.write_text('keep')
        owned = None
        with self.assertRaises(ValueError):
            with restore.owned_workspace(self.root) as (owned, _):
                (owned / 'private-config').write_text('sensitive synthetic fixture')
                raise ValueError('injected')
        self.assertFalse(owned.exists())
        self.assertEqual(sentinel.read_text(), 'keep')

    def test_modified_workspace_owner_refuses_recursive_delete(self):
        owned = None
        with self.assertRaisesRegex(restore.RestoreError, 'cleanup'):
            with restore.owned_workspace(self.root) as (owned, _):
                (owned / '.restore-owner').write_text('another-owner')
        self.assertTrue(owned.exists())

    def make_rag_row(self, key='document', version=4, action='UPSERT'):
        protocol, _ = restore.rag_modules()
        chunks = [{'chunk_id': 4, 'section_no': 1, 'content': '真实制度内容'}] if action == 'UPSERT' else []
        payload = {'document_key': key, 'version': version, 'action': action,
                   'title': '制度标题', 'source': '合成来源', 'chunks': chunks}
        payload['payload_hash'] = protocol.canonical_payload_hash(key, payload['title'], payload['source'], chunks, action)
        return {'id': version, 'document_key': key, 'desired_version': version, 'desired_action': action,
                'title': payload['title'], 'source': payload['source'], 'revision': payload['payload_hash'],
                'payload_json': json.dumps(payload), 'status': 'SYNCED'}

    def test_fresh_store_uses_mysql_versions_and_tombstones_without_index_claims(self):
        self.snapshot['policy_rag_sync'] = [self.make_rag_row(), self.make_rag_row('removed', 9, 'DELETE')]
        plan = restore.prepare_rag(self.snapshot, self.root, 'a' * 32)
        _, module = restore.rag_modules()
        documents = module.JsonDocumentStore(Path(plan['dataFile'])).snapshot()['documents']
        self.assertEqual(documents['document']['version'], 4)
        self.assertEqual(documents['removed']['version'], 9)
        self.assertEqual(documents['removed']['action'], 'DELETE')
        self.assertEqual(documents['removed']['chunks'], [])
        self.assertTrue(all(doc['indexed_version'] is None for doc in documents.values()))
        self.assertEqual(plan['status'], 'REBUILD_REQUIRED')
        self.assertEqual(plan['physicalCollection'], 'silvercare_restore_' + 'a' * 32 + '_v2')
        self.assertEqual(plan['tombstones'], 1)

    def test_rag_payload_hash_version_action_and_missing_tombstone_snapshot_are_rejected(self):
        for field, value in [('desired_version', 5), ('revision', 'bad'), ('desired_action', 'DELETE'),
                              ('title', 'wrong'), ('payload_json', '{}')]:
            row = self.make_rag_row()
            row[field] = value
            with self.subTest(field=field), self.assertRaises(restore.RestoreError):
                restore.payloads([row])
        row = self.make_rag_row('removed', 9, 'DELETE')
        row['payload_json'] = None
        with self.assertRaises(restore.RestoreError):
            restore.payloads([row])

    def test_core_rag_loads_only_trusted_stdlib_protocol_and_store(self):
        protocol, store = restore.rag_modules()
        self.assertEqual(Path(protocol.__file__).resolve(), database.ROOT / '源码/beadhouse-ai/app/protocol.py')
        self.assertEqual(Path(store.__file__).resolve(), database.ROOT / '源码/beadhouse-ai/app/store.py')
        self.assertNotIn('_silvercare_restore_rag.settings', sys.modules)
        with mock.patch.object(database, 'ROOT', self.root):
            with self.assertRaisesRegex(restore.RestoreError, 'Trusted'):
                restore.rag_modules()

    def test_queue_verification_requires_reindex_advance_and_live_milvus_namespace(self):
        row = self.make_rag_row()
        self.snapshot['policy_rag_sync'] = [row]
        plan = restore.prepare_rag(self.snapshot, self.root, 'b' * 32)
        result = restore.RestoredSystem(mock.Mock(), self.root, self.snapshot, plan, {})
        module = mock.Mock()
        module.snapshot_database.return_value = self.snapshot
        with mock.patch.object(restore, 'backup_module', return_value=module):
            with self.assertRaises(restore.RestoreError):
                restore.verify_rebuilt_queue(result, index_namespace='memory')
            with self.assertRaisesRegex(restore.RestoreError, 'pending'):
                restore.verify_rebuilt_queue(result, index_namespace='milvus:' + 'a' * 64)

    def test_queue_and_store_match_after_real_protocol_sync_and_reject_lost_tombstone(self):
        self.snapshot['policy_rag_sync'] = [self.make_rag_row(), self.make_rag_row('removed', 9, 'DELETE')]
        plan = restore.prepare_rag(self.snapshot, self.root, 'c' * 32)
        result = restore.RestoredSystem(mock.Mock(), self.root, self.snapshot, plan, {})
        current = copy.deepcopy(self.snapshot)
        current['policy_rag_sync'] = [self.make_rag_row(version=5), self.make_rag_row('removed', 10, 'DELETE')]
        _, store_module = restore.rag_modules()
        store = store_module.JsonDocumentStore(result.rag_file)
        namespace = 'milvus:' + 'f' * 64
        for row, payload in restore.payloads(current['policy_rag_sync']):
            store.apply_sync(**payload)
            store.mark_indexed(row['document_key'], row['desired_version'], row['revision'], namespace)
        module = mock.Mock()
        module.snapshot_database.return_value = current
        with mock.patch.object(restore, 'backup_module', return_value=module):
            checked = restore.verify_rebuilt_queue(result, index_namespace=namespace)
            self.assertEqual(checked['status'], 'QUEUE_AND_STORE_VERIFIED')
            self.assertTrue(checked['retrievalAcceptanceRequired'])
            with self.assertRaisesRegex(restore.RestoreError, 'namespace'):
                restore.verify_rebuilt_queue(result, index_namespace='milvus:' + 'b' * 64)
            current['policy_rag_sync'].pop()
            with self.assertRaisesRegex(restore.RestoreError, 'tombstones'):
                restore.verify_rebuilt_queue(result, index_namespace=namespace)

    def test_successful_context_restores_config_and_leaves_only_encrypted_source_after_cleanup(self):
        self.manifest['snapshotPath'] = 'snapshot.json'
        (self.unpacked / 'snapshot.json').write_text(json.dumps(self.snapshot), encoding='utf-8')
        (self.unpacked / 'config').mkdir()
        (self.unpacked / 'config/.env').write_text('SYNTHETIC_SECRET=keep-private\n')
        @contextlib.contextmanager
        def verified(*args, **kwargs):
            yield self.unpacked, self.manifest
        @contextlib.contextmanager
        def target(*args, **kwargs):
            yield mock.Mock(result={'status': 'verified'})
        fake_module = mock.Mock(verified_package=verified)
        workspace = None
        with mock.patch.object(restore, 'backup_module', return_value=fake_module), \
                mock.patch.object(database, 'owned_restored_database', target), \
                mock.patch.object(restore, 'relocate_attachments') as relocate:
            with restore.restored_system('encrypted-package', 'separate-key', self.root) as restored:
                workspace = restored.workspace
                self.assertEqual((restored.config_root / '.env').read_text(), 'SYNTHETIC_SECRET=keep-private\n')
                self.assertEqual((restored.uploads_root / 'nested/photo.png').read_bytes(), b'owned attachment bytes')
                self.assertEqual(restored.report['status'], 'RESTORED_REBUILD_REQUIRED')
                self.assertNotIn('SYNTHETIC_SECRET', json.dumps(restored.report))
                relocate.assert_called_once()
        self.assertFalse(workspace.exists())
        self.assertTrue((self.unpacked / 'uploads/nested/photo.png').exists())

    def test_invalid_authenticated_input_never_creates_database_and_cleans_workspace(self):
        self.manifest['snapshotPath'] = 'snapshot.json'
        (self.unpacked / 'snapshot.json').write_text(json.dumps(self.snapshot), encoding='utf-8')
        self.entry['relativePath'] = '../bad'
        @contextlib.contextmanager
        def verified(*args, **kwargs):
            yield self.unpacked, self.manifest
        module = mock.Mock(verified_package=verified)
        with mock.patch.object(restore, 'backup_module', return_value=module), \
                mock.patch.object(database, 'owned_restored_database') as target:
            with self.assertRaises(restore.RestoreError):
                with restore.restored_system('unused', 'unused', self.root):
                    self.fail('unsafe manifest yielded')
        target.assert_not_called()
        self.assertEqual(list(self.root.glob('system-restore-*')), [])

    def test_cli_rejects_existing_target_or_retention_switches(self):
        import io
        for option in ('--container', '--restore-target', '--keep-on-failure'):
            with self.subTest(option=option), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as failure:
                    restore.main(['--package', 'p', '--key-file', 'k', '--work-parent', str(self.root), option, 'x'])
                self.assertEqual(failure.exception.code, 2)


if __name__ == '__main__':
    unittest.main()
