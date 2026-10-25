"""Offline safety checks; real MySQL backup/restore lives in .github/database/check.py."""
import contextlib
import hashlib
import importlib.util
import io
import json
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("silvercare_database", Path(__file__).with_name("database.py"))
database = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(database)


class BackupValidationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name)
        self.sql = b"CREATE TABLE example (id INT PRIMARY KEY);\n"
        self.manifest = {
            "format": 1, "database": "db_beadhouse", "file": "database.sql",
            "sha256": hashlib.sha256(self.sql).hexdigest(), "bytes": len(self.sql),
            "created_at": "2026-10-20T10:00:00Z", "mysql_version": "8.0.40",
            "migrations": [], "tables": [{"name": "example", "rows": 0, "checksum": "0"}],
            "objects": {"triggers": [], "routines": [], "events": []},
            "constraints": [{"table": "example", "name": "PRIMARY", "type": "PRIMARY KEY",
                             "columns": "id", "expression": None}],
        }
        self.write()

    def tearDown(self):
        self.temp.cleanup()

    def write(self):
        (self.folder / "database.sql").write_bytes(self.sql)
        (self.folder / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def test_complete_backup_is_accepted(self):
        self.assertEqual(database.load_backup(self.folder)["bytes"], len(self.sql))

    def test_truncated_and_same_length_tampered_sql_are_rejected(self):
        for corrupt in (self.sql[:-1], b"X" + self.sql[1:], b""):
            with self.subTest(corrupt=corrupt[:4]):
                (self.folder / "database.sql").write_bytes(corrupt)
                with self.assertRaises(database.DatabaseError):
                    database.load_backup(self.folder)

    def test_paths_cannot_escape_backup_directory(self):
        for path in ("../database.sql", "/tmp/database.sql", r"C:\database.sql"):
            with self.subTest(path=path):
                self.manifest["file"] = path
                self.write()
                with self.assertRaises(database.DatabaseError):
                    database.load_backup(self.folder)

    def test_manifest_requires_exact_supported_types_and_schema(self):
        for key, bad in (("format", True), ("format", 2), ("bytes", True),
                         ("database", "mysql"), ("sha256", "garbage"),
                         ("tables", []), ("mysql_version", "8.4.0"),
                         ("objects", {}), ("constraints", "missing")):
            with self.subTest(key=key, value=bad):
                original = self.manifest[key]
                self.manifest[key] = bad
                self.write()
                with self.assertRaises(database.DatabaseError):
                    database.load_backup(self.folder)
                self.manifest[key] = original

    def test_duplicate_tables_and_invalid_counts_are_rejected(self):
        for tables in ([self.manifest["tables"][0]] * 2,
                       [{"name": "example", "rows": -1, "checksum": "0"}],
                       [{"name": "example", "rows": 0, "checksum": None}]):
            self.manifest["tables"] = tables
            self.write()
            with self.assertRaises(database.DatabaseError):
                database.load_backup(self.folder)

    def test_duplicate_json_keys_are_rejected(self):
        path = self.folder / "manifest.json"
        path.write_text('{"format":1,' + path.read_text(encoding="utf-8")[1:], encoding="utf-8")
        with self.assertRaises(database.DatabaseError):
            database.load_backup(self.folder)

    def test_invalid_backup_does_not_start_docker(self):
        (self.folder / "database.sql").write_bytes(b"bad")
        with mock.patch.object(database, "Docker") as docker, contextlib.redirect_stderr(io.StringIO()):
            result = database.main(["restore-drill", "--backup", str(self.folder)])
        self.assertEqual(result, 1)
        docker.assert_not_called()

    def test_existing_backup_destination_is_not_modified(self):
        before = (self.folder / "database.sql").read_bytes()
        with mock.patch.object(database, "Docker") as docker, contextlib.redirect_stderr(io.StringIO()):
            result = database.main(["backup", "--container", "any", "--output", str(self.folder),
                                    "--writers-stopped"])
        self.assertEqual(result, 1)
        docker.assert_not_called()
        self.assertEqual((self.folder / "database.sql").read_bytes(), before)

    def test_restore_target_and_missing_writer_ack_are_not_accepted(self):
        for argv in (["restore-drill", "--backup", str(self.folder), "--container", "daily"],
                     ["backup", "--container", "daily", "--output", str(self.folder / "new")]):
            with self.subTest(argv=argv), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error:
                    database.main(argv)
                self.assertEqual(error.exception.code, 2)

    def test_cleanup_requires_both_original_id_and_unique_ownership_label(self):
        docker = mock.Mock()
        container = "a" * 64
        token = "test-drill-identifier"
        for identity in (
                {"id": "b" * 64, "labels": {database.OWNER_LABEL: token}},
                {"id": container, "labels": {database.OWNER_LABEL: "another-drill"}},
                {"id": container, "labels": None}):
            docker.inspect.return_value = identity
            with self.subTest(identity=identity), self.assertRaises(database.DatabaseError):
                database.remove_owned(docker, container, token)
            docker.run.assert_not_called()
        docker.inspect.return_value = {"id": container, "labels": {database.OWNER_LABEL: token}}
        database.remove_owned(docker, container, token)
        docker.run.assert_called_once_with(["rm", "-f", "-v", container], timeout=60)


class GateReadinessTests(unittest.TestCase):
    def test_unix_only_temporary_server_does_not_satisfy_final_server_readiness(self):
        spec = importlib.util.spec_from_file_location("silvercare_migration_gate",
                Path(__file__).resolve().parents[1] / ".github/database/check.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        gate = module.Gate.__new__(module.Gate)
        gate.docker, gate.password = "docker-fixture", "readiness-test-only"
        gate.containers = {"owned": "silvercare-migration-test-fixture"}
        tcp_attempts = []

        def run(arguments, *, data, expected):
            self.assertEqual(data, b"SELECT 1")
            self.assertIsNone(expected)
            if "--protocol=TCP" in arguments and "--host=127.0.0.1" in arguments:
                tcp_attempts.append(arguments)
                # The entrypoint's temporary server has only a Unix socket.
                # TCP becomes usable only when the final server is running.
                return subprocess.CompletedProcess(arguments, 1 if len(tcp_attempts) == 1 else 0,
                        b"1\n" if len(tcp_attempts) > 1 else b"", b"")
            return subprocess.CompletedProcess(arguments, 0, b"1\n", b"")

        gate.run = run
        with mock.patch.object(module.time, "sleep") as sleep:
            gate.ready("owned")
        self.assertEqual(len(tcp_attempts), 2,
                "a successful temporary Unix socket must not let the gate run migrations early")
        sleep.assert_called_once_with(1)


if __name__ == "__main__":
    unittest.main()
