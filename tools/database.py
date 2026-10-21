#!/usr/bin/env python3
"""MySQL-only backup publication and isolated restore drills (Python standard library).

No restore target can be supplied: every drill creates its own container, without
host mounts or published ports. Docker context / DOCKER_HOST are respected.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
DATABASE = "db_beadhouse"
IMAGE = "mysql:8.0.40"
OWNER_LABEL = "silvercare.restore-drill"
MYSQL = (
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root '
    '--host=127.0.0.1 --protocol=TCP --default-character-set=utf8mb4 '
    '--batch --raw --skip-column-names'
)


class DatabaseError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise DatabaseError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Manifest contains duplicate JSON keys")
        result[key] = value
    return result


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def identifier(value):
    return isinstance(value, str) and bool(re.fullmatch(r"[A-Za-z0-9_]{1,64}", value))


def load_backup(folder):
    """Validate all inputs before touching Docker; never follow backup file symlinks."""
    folder = Path(folder).absolute()
    require(folder.is_dir() and not folder.is_symlink(), "Backup must be an existing real directory")
    manifest_path, sql_path = folder / "manifest.json", folder / "database.sql"
    require(manifest_path.is_file() and not manifest_path.is_symlink(), "Missing regular manifest.json")
    require(manifest_path.stat().st_size <= 16 * 1024 * 1024, "Manifest exceeds 16 MiB limit")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    except (ValueError, UnicodeError) as error:
        raise DatabaseError("Invalid manifest JSON") from error
    require(isinstance(manifest, dict), "Manifest must be an object")
    require(type(manifest.get("format")) is int and manifest["format"] == 1,
            "Unsupported manifest format")
    require(manifest.get("database") == DATABASE and manifest.get("file") == "database.sql",
            "Manifest must name db_beadhouse and database.sql")
    require(type(manifest.get("bytes")) is int and manifest["bytes"] > 0, "Invalid SQL byte length")
    require(isinstance(manifest.get("sha256"), str) and
            re.fullmatch(r"[a-f0-9]{64}", manifest["sha256"]), "Invalid SQL SHA256")
    require(isinstance(manifest.get("mysql_version"), str) and
            re.fullmatch(r"8\.0\.40(?:[-+][A-Za-z0-9_.-]+)?", manifest["mysql_version"]),
            "This restore tool supports MySQL 8.0.40 backups only")
    require(isinstance(manifest.get("created_at"), str) and
            re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", manifest["created_at"]),
            "Missing UTC backup timestamp")
    tables = manifest.get("tables")
    require(isinstance(tables, list) and bool(tables), "Manifest must list database tables")
    names = set()
    for table in tables:
        require(isinstance(table, dict) and identifier(table.get("name")), "Invalid table name")
        require(table["name"] not in names, "Manifest contains duplicate tables")
        names.add(table["name"])
        require(type(table.get("rows")) is int and table["rows"] >= 0, "Invalid table row count")
        checksum = table.get("checksum")
        require((type(checksum) is int and checksum >= 0) or
                (isinstance(checksum, str) and bool(re.fullmatch(r"\d+", checksum))),
                "Missing or invalid table checksum")
    migrations = manifest.get("migrations")
    require(isinstance(migrations, list), "Manifest must list migration records")
    versions = set()
    migration_fields = {"version", "filename", "checksum", "status", "run_id", "attempts",
                        "started_at", "finished_at"}
    for migration in migrations:
        require(isinstance(migration, dict) and set(migration) == migration_fields,
                "Invalid migration record fields")
        version = migration.get("version")
        require(isinstance(version, str) and re.fullmatch(r"\d{4}", version) and version not in versions,
                "Invalid or duplicate migration version")
        versions.add(version)
        require(isinstance(migration["checksum"], str) and
                re.fullmatch(r"[a-f0-9]{64}", migration["checksum"]), "Invalid migration checksum")
        require(migration["status"] in ("SUCCEEDED", "FAILED", "INTERRUPTED", "RUNNING") and
                type(migration["attempts"]) is int and migration["attempts"] > 0,
                "Invalid migration status or attempts")
    objects = manifest.get("objects")
    require(isinstance(objects, dict) and set(objects) == {"triggers", "routines", "events"},
            "Manifest must contain routines, events and triggers")
    for kind in ("triggers", "events"):
        require(isinstance(objects[kind], list) and all(identifier(name) for name in objects[kind]),
                "Invalid database object names")
        require(len(objects[kind]) == len(set(objects[kind])), "Duplicate database object names")
    require(isinstance(objects["routines"], list), "Invalid routines list")
    for routine in objects["routines"]:
        require(isinstance(routine, dict) and set(routine) == {"name", "type"} and
                identifier(routine["name"]) and routine["type"] in ("FUNCTION", "PROCEDURE"),
                "Invalid routine")
    require(len(canonical_records(objects["routines"])) == len(set(canonical_records(objects["routines"]))),
            "Duplicate routines")
    constraints = manifest.get("constraints")
    require(isinstance(constraints, list), "Manifest must list constraints")
    for constraint in constraints:
        require(isinstance(constraint, dict) and
                set(constraint) == {"table", "name", "type", "columns", "expression"} and
                identifier(constraint.get("table")) and constraint["table"] in names and
                identifier(constraint.get("name")) and
                constraint.get("type") in ("PRIMARY KEY", "UNIQUE", "FOREIGN KEY", "CHECK"),
                "Invalid constraint")
        require(all(constraint[field] is None or isinstance(constraint[field], str)
                    for field in ("columns", "expression")), "Invalid constraint definition")
    require(sql_path.is_file() and not sql_path.is_symlink(), "Missing regular database.sql")
    require(sql_path.stat().st_size == manifest["bytes"], "SQL byte length does not match manifest")
    require(sha256_file(sql_path) == manifest["sha256"], "SQL SHA256 does not match manifest")
    return manifest


class Docker:
    def __init__(self, log_dir):
        requested = os.environ.get("SILVERCARE_DOCKER", "docker")
        self.executable = shutil.which(requested)
        if not self.executable and os.name == "nt" and requested == "docker":
            candidate = Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / \
                "Docker/Docker/resources/bin/docker.exe"
            if candidate.is_file():
                self.executable = str(candidate)
        require(bool(self.executable), "Docker executable not found")
        self.log_dir = Path(log_dir)
        self.log_dir.mkdir(parents=True, exist_ok=False)
        self.sequence = 0

    def run(self, args, *, input_bytes=None, env=None, timeout=600, check=True):
        # Never log argv (SQL or credentials could be supplied by a caller).
        self.sequence += 1
        try:
            process = subprocess.run([self.executable, *args], input=input_bytes,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     env=env, timeout=timeout, check=False)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise DatabaseError(f"Docker {args[0]} could not complete ({type(error).__name__})") from error
        if process.returncode:
            (self.log_dir / f"{self.sequence:03d}-{args[0]}.log").write_bytes(
                process.stderr + b"\n" + process.stdout)
            if check:
                raise DatabaseError(f"Docker {args[0]} failed (exit {process.returncode}); see operation log")
        return process

    def inspect(self, container):
        result = self.run(["inspect", "--type", "container", "--format",
                           '{"id":{{json .Id}},"labels":{{json .Config.Labels}}}', "--", container])
        try:
            return json.loads(result.stdout)
        except (ValueError, UnicodeError) as error:
            raise DatabaseError("Docker did not return one container identity") from error

    def sql(self, container, sql, database=DATABASE):
        result = self.run(["exec", "-i", container, "sh", "-c", MYSQL +
                           (" " + database if database else "")],
                          input_bytes=sql.encode("utf-8"))
        return result.stdout.decode("utf-8").strip()


def new_log_dir(token):
    return ROOT / ".runtime" / "database-drills" / token


def publish_backup(staged, output):
    """Publish checked bytes, with exclusive creation and manifest written last."""
    manifest = load_backup(staged)
    # mkdir is the no-clobber boundary on both POSIX and Windows.
    output.mkdir()
    for name in ("database.sql", "manifest.json"):
        with (staged / name).open("rb") as source, (output / name).open("xb") as destination:
            shutil.copyfileobj(source, destination)
            destination.flush()
            os.fsync(destination.fileno())
    # If storage corruption occurred while publishing, keep the directory for diagnosis.
    require(load_backup(output) == manifest, "Published backup validation failed")
    return manifest


def backup(container, output):
    output = Path(output).absolute()
    require(not output.exists() and not output.is_symlink(), "Backup output already exists; never overwritten")
    require(output.parent.is_dir(), "Backup output parent directory must exist")
    token = uuid.uuid4().hex
    log_dir = new_log_dir(token)
    docker = Docker(log_dir)
    source = docker.inspect(container)["id"]
    require(re.fullmatch(r"[a-f0-9]{64}", source), "Invalid source container identity")
    scripts = f"/tmp/silvercare-db-{token}"
    target = f"/tmp/silvercare-backup-{token}"
    try:
        docker.run(["cp", str(ROOT / "数据库"), f"{source}:{scripts}"])
        # Normalize the script inside our new copy, independent of Git checkout EOLs.
        docker.run(["exec", source, "sh", "-c",
                    'sed -i \'s/\\r$//\' "$1/migrate.sh"; exec bash "$1/migrate.sh" backup "$2" --writers-stopped',
                    "silvercare-backup", scripts, target])
        with tempfile.TemporaryDirectory(prefix=".silvercare-backup-", dir=output.parent) as temporary:
            staged = Path(temporary)
            for name in ("database.sql", "manifest.json"):
                docker.run(["cp", f"{source}:{target}/{name}", str(staged / name)])
            manifest = publish_backup(staged, output)
        return {"status": "backed_up", "database": DATABASE, "backup": str(output),
                "bytes": manifest["bytes"], "sha256": manifest["sha256"],
                "log": str(log_dir), "scope": "MySQL only"}
    except BaseException as error:
        if isinstance(error, (KeyboardInterrupt, SystemExit)):
            raise
        raise DatabaseError(f"{error}; log: {log_dir}") from error
    finally:
        # These random paths were generated by this invocation; never remove a supplied path.
        try:
            docker.run(["exec", source, "rm", "-rf", "--", scripts, target], check=False, timeout=30)
        except DatabaseError:
            pass


def json_query(docker, container, sql):
    raw = docker.sql(container, sql)
    try:
        return json.loads(raw)
    except (ValueError, UnicodeError) as error:
        raise DatabaseError("Unexpected JSON from restored database") from error


def canonical_records(records):
    return sorted(json.dumps(item, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
                  for item in records)


def verify_restored(docker, container, manifest):
    version = docker.sql(container, "SELECT VERSION();")
    require(version == manifest["mysql_version"], "Restored server version differs from backup")
    names = docker.sql(container, "SELECT table_name FROM information_schema.tables "
                       "WHERE table_schema='db_beadhouse' AND table_type='BASE TABLE' ORDER BY table_name;").splitlines()
    expected = {table["name"]: table for table in manifest["tables"]}
    require(set(names) == set(expected), "Restored table inventory differs from backup")
    for name in names:
        require(identifier(name), "Unsupported restored table identifier")
        rows = int(docker.sql(container, f"SELECT COUNT(*) FROM `{name}`;"))
        checksum = docker.sql(container, f"CHECKSUM TABLE `{name}` EXTENDED;").split("\t")[-1]
        require(rows == expected[name]["rows"] and checksum == str(expected[name]["checksum"]),
                f"Restored table summary differs: {name}")
    migrations = []
    if "schema_migration" in expected:
        migrations = json_query(docker, container, """
SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT(
'version',version,'filename',filename,'checksum',checksum,'status',status,
'run_id',run_id,'attempts',attempts,'started_at',started_at,'finished_at',finished_at)),JSON_ARRAY())
FROM schema_migration;
""")
    require(canonical_records(migrations) == canonical_records(manifest["migrations"]),
            "Restored migration history differs from backup")
    objects = json_query(docker, container, """
SELECT JSON_OBJECT(
'routines',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('name',routine_name,'type',routine_type))
 FROM information_schema.routines WHERE routine_schema='db_beadhouse'),JSON_ARRAY()),
'events',COALESCE((SELECT JSON_ARRAYAGG(event_name)
 FROM information_schema.events WHERE event_schema='db_beadhouse'),JSON_ARRAY()),
'triggers',COALESCE((SELECT JSON_ARRAYAGG(trigger_name)
 FROM information_schema.triggers WHERE trigger_schema='db_beadhouse'),JSON_ARRAY()));
""")
    for kind in ("routines", "events", "triggers"):
        require(canonical_records(objects[kind]) == canonical_records(manifest["objects"][kind]),
                f"Restored {kind} inventory differs from backup")
    constraints = json_query(docker, container, CONSTRAINTS_SQL)
    require(canonical_records(constraints) == canonical_records(manifest["constraints"]),
            "Restored constraints differ from backup")
    if "origin" in manifest:
        origin = None
        if "schema_metadata" in expected:
            origin = json_query(docker, container, "SELECT JSON_OBJECT('origin',origin) "
                                "FROM schema_metadata WHERE id=1;")["origin"]
        require(origin == manifest["origin"], "Restored database origin differs from backup")
    require(docker.sql(container, "SELECT @@GLOBAL.event_scheduler;").upper() == "OFF",
            "Restore SQL changed event scheduler; isolated drill rejected")
    return {"status": "verified", "database": DATABASE, "tables": len(names),
            "source_readiness": manifest.get("source_readiness", "UNKNOWN"),
            "migrations": len(migrations), "constraints": len(constraints),
            "objects": {kind: len(items) for kind, items in objects.items()},
            "mysql_version": version}


CONSTRAINTS_SQL = """
SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT(
'table',tc.table_name,'name',tc.constraint_name,'type',tc.constraint_type,
'columns',(SELECT GROUP_CONCAT(kcu.column_name ORDER BY kcu.ordinal_position SEPARATOR ',')
 FROM information_schema.key_column_usage kcu
 WHERE kcu.constraint_schema=tc.constraint_schema AND kcu.table_name=tc.table_name
 AND kcu.constraint_name=tc.constraint_name),
'expression',(SELECT cc.check_clause FROM information_schema.check_constraints cc
 WHERE tc.constraint_type='CHECK' AND cc.constraint_schema=tc.constraint_schema
 AND cc.constraint_name=tc.constraint_name)
)),JSON_ARRAY())
FROM information_schema.table_constraints tc WHERE tc.constraint_schema='db_beadhouse';
"""


def remove_owned(docker, container, token):
    identity = docker.inspect(container)
    require(identity["id"] == container and (identity.get("labels") or {}).get(OWNER_LABEL) == token,
            "Restore cleanup refused: container ID or ownership label differs")
    docker.run(["rm", "-f", "-v", container], timeout=60)


def restore_drill(folder, keep_on_failure=False):
    folder = Path(folder).absolute()
    manifest = load_backup(folder)  # Fail before Docker or container creation.
    token = uuid.uuid4().hex
    name = f"silvercare-restore-{token}"
    log_dir = new_log_dir(token)
    docker = Docker(log_dir)
    container = None
    verified = False
    result = None
    failure = None
    try:
        # Stage an immutable private copy before creation; importing this copy avoids
        # using a user-edited backup after the initial validation.
        with tempfile.TemporaryDirectory(prefix="silvercare-restore-") as temporary:
            staged = Path(temporary)
            for filename in ("database.sql", "manifest.json"):
                shutil.copyfile(folder / filename, staged / filename)
            require(load_backup(staged) == manifest, "Backup changed during validation")
            environment = dict(os.environ)
            environment["MYSQL_ROOT_PASSWORD"] = secrets.token_urlsafe(36)
            # -e NAME obtains the value from the process environment, not command arguments.
            created = docker.run(["run", "--detach", "--name", name, "--network", "none",
                                  "--label", f"{OWNER_LABEL}={token}",
                                  "--env", "MYSQL_ROOT_PASSWORD", IMAGE,
                                  "--event-scheduler=OFF", "--local-infile=OFF",
                                  "--secure-file-priv=NULL"], env=environment, timeout=600)
            container = created.stdout.decode("ascii").strip()
            require(re.fullmatch(r"[a-f0-9]{64}", container), "Docker did not return full container ID")
            (log_dir / "container.json").write_text(
                json.dumps({"id": container, "name": name, "label": {OWNER_LABEL: token}}),
                encoding="utf-8")
            deadline = time.monotonic() + 180
            while True:
                ready = docker.run(["exec", "-i", container, "sh", "-c", MYSQL],
                                   input_bytes=b"SELECT 1;\n", timeout=10, check=False)
                if ready.returncode == 0 and ready.stdout.strip() == b"1":
                    break
                require(time.monotonic() < deadline, "Isolated MySQL did not become ready within 180 seconds")
                time.sleep(1)
            docker.sql(container, "CREATE DATABASE db_beadhouse CHARACTER SET utf8mb4 "
                       "COLLATE utf8mb4_0900_ai_ci;", database=None)
            docker.run(["cp", str(staged / "database.sql"), f"{container}:/tmp/silvercare-restore.sql"])
            docker.run(["exec", container, "sh", "-c",
                        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --host=127.0.0.1 '
                        '--protocol=TCP --default-character-set=utf8mb4 db_beadhouse '
                        '< /tmp/silvercare-restore.sql'], timeout=1200)
            result = verify_restored(docker, container, manifest)
            result["log"] = str(log_dir)
            verified = True
    except BaseException as error:
        failure = error
    finally:
        # A failed/timed-out `docker run` may have created the container before returning.
        if container is None:
            try:
                identity = docker.inspect(name)
                if (identity.get("labels") or {}).get(OWNER_LABEL) == token:
                    container = identity["id"]
            except DatabaseError:
                pass
        if container:
            if failure:
                try:
                    logs = docker.run(["logs", "--tail", "100", container], check=False, timeout=30)
                    (log_dir / "mysql.log").write_bytes(logs.stdout + logs.stderr)
                except DatabaseError:
                    pass
            if verified or not keep_on_failure:
                try:
                    remove_owned(docker, container, token)
                except DatabaseError as cleanup_error:
                    failure = DatabaseError(f"Container cleanup failed; retained {container}: {cleanup_error}")
            else:
                failure = DatabaseError(f"{failure}; retained isolated container {container} ({name})")
    if failure:
        (log_dir / "result.json").write_text(json.dumps(
            {"status": "failed", "error": str(failure), "container": container}), encoding="utf-8")
        if isinstance(failure, KeyboardInterrupt):
            raise failure
        raise DatabaseError(f"{failure}; log: {log_dir}") from failure
    (log_dir / "result.json").write_text(json.dumps(result), encoding="utf-8")
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    backup_parser = commands.add_parser("backup", help="Publish a verified MySQL-only backup")
    backup_parser.add_argument("--container", required=True, help="Source container name or ID")
    backup_parser.add_argument("--output", required=True, type=Path, help="New backup directory")
    backup_parser.add_argument("--writers-stopped", required=True, action="store_true",
                               help="Confirm application jobs and other DB writers are stopped")
    restore_parser = commands.add_parser("restore-drill", help="Verify a backup in a new isolated container")
    restore_parser.add_argument("--backup", required=True, type=Path)
    restore_parser.add_argument("--keep-on-failure", action="store_true")
    args = parser.parse_args(argv)
    try:
        result = backup(args.container, args.output) if args.command == "backup" else \
            restore_drill(args.backup, args.keep_on_failure)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (DatabaseError, OSError) as error:
        print(f"Database operation failed: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Database operation interrupted; inspect .runtime/database-drills for recovery evidence",
              file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
