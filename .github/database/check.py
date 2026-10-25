"""Exercise migrations and backup recovery against owned, disposable MySQL containers.

No Python packages or host MySQL installation are needed. This script deliberately
does not accept an existing container or a database URL as its target.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import uuid


ROOT = Path(__file__).resolve().parents[2]
LABEL = "codex.task=silvercare-migration-tests-20261020"
IMAGE = "mysql:8.0.40"
PREFIX = "silvercare-migration-test-"
LOCK = "db_beadhouse.schema_migration"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def read_baseline_manifest():
    entries = []
    for line in (ROOT / "数据库/migrations.tsv").read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        require(len(fields) == 4, "Baseline migration manifest must have four columns")
        require(fields[0] == f"{len(entries) + 1:04d}",
                "Baseline migration versions must be ordered and contiguous from 0001")
        entries.append(tuple(fields))
    require(len(entries) >= 3, "The gate requires baseline history with an intermediate version")
    require(len(entries) + 4 < 9999, "Fault versions would collide with the unknown-history fixture")
    return tuple(entries)


BASELINE = read_baseline_manifest()
BASE_COUNT = len(BASELINE)
LAST_VERSION = BASELINE[-1][0]
BASE_VERSIONS = [entry[0] for entry in BASELINE]


def docker_executable():
    explicit = os.environ.get("SILVERCARE_DOCKER") or os.environ.get("DOCKER_EXECUTABLE")
    candidates = [explicit, shutil.which("docker")]
    if os.name == "nt":
        candidates.append(str(Path(os.environ.get("ProgramFiles", "C:/Program Files"))
                              / "Docker/Docker/resources/bin/docker.exe"))
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    raise RuntimeError("Docker executable not found; set SILVERCARE_DOCKER")


class Gate:
    def __init__(self, evidence):
        self.docker = docker_executable()
        self.evidence = evidence
        self.password = secrets.token_hex(24)
        self.containers = {}
        self.checks = []
        self.outcome = "running"
        self.started = time.monotonic()
        self.creationflags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        self.counter = 0

    def run(self, args, *, data=None, expected=0, timeout=180, env=None):
        result = subprocess.run(args, input=data, capture_output=True, timeout=timeout,
                                creationflags=self.creationflags, env=env)
        self.counter += 1
        # Output can contain fake fixture values, but never real application secrets:
        # all database instances and credentials are created by this runner.
        (self.evidence / f"{self.counter:03d}.log").write_bytes(result.stdout + result.stderr)
        if expected is not None:
            require(result.returncode == expected,
                    f"Command failed ({result.returncode}), evidence {self.counter:03d}.log: "
                    + result.stderr.decode("utf-8", errors="replace")[-1500:])
        return result

    def docker_run(self, *args, **kwargs):
        return self.run([self.docker, *args], **kwargs)

    def owned(self, cid):
        require(cid in self.containers, "Refusing an unrecorded container ID")
        result = self.docker_run("inspect", cid)
        info = json.loads(result.stdout)[0]
        require(info["Id"] == cid, "Container ID changed")
        require(info["Name"].lstrip("/") == self.containers[cid], "Container name changed")
        require(info["Name"].lstrip("/").startswith(PREFIX), "Container prefix mismatch")
        require(info["Config"]["Labels"].get("codex.task") == LABEL.split("=", 1)[1],
                "Container ownership label mismatch")
        require(not info["HostConfig"]["PortBindings"], "Test container unexpectedly exposes ports")
        return info

    def create(self):
        name = PREFIX + uuid.uuid4().hex[:12]
        result = self.docker_run(
            "run", "-d", "--name", name, "--label", LABEL, "--network", "none",
            "-e", f"MYSQL_ROOT_PASSWORD={self.password}", "-e", "TZ=Asia/Shanghai", IMAGE)
        cid = result.stdout.decode().strip()
        self.containers[cid] = name
        self.owned(cid)
        self.ready(cid)
        return cid

    def ready(self, cid):
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            result = self.mysql(cid, "SELECT 1", expected=None)
            if result.returncode == 0:
                return
            time.sleep(1)
        raise RuntimeError(f"MySQL readiness timeout for owned {self.containers[cid]}")

    def mysql_args(self, cid):
        return [self.docker, "exec", "-i", "-e", f"MYSQL_PWD={self.password}", cid,
                # The entrypoint's temporary Unix-only server is not final readiness.
                "mysql", "--host=127.0.0.1", "--protocol=TCP",
                "--default-character-set=utf8mb4", "-uroot", "-N", "-B"]

    def mysql(self, cid, sql, *, expected=0):
        return self.run(self.mysql_args(cid), data=sql.encode("utf-8"), expected=expected)

    def scalar(self, cid, sql):
        return self.mysql(cid, sql).stdout.decode("utf-8").strip()

    def reset(self, cid):
        self.owned(cid)
        self.mysql(cid, "DROP DATABASE IF EXISTS db_beadhouse;"
                        "CREATE DATABASE db_beadhouse CHARACTER SET utf8mb4;")

    def copy_package(self, cid, directory, destination="/tmp/migration-package"):
        # Every package lives under an owned container, never in the original source.
        self.owned(cid)
        self.docker_run("cp", str(directory), f"{cid}:{destination}")
        return destination

    def migrate(self, cid, mode, *options, directory="/tmp/migration-package", expected=0):
        return self.docker_run(
            "exec", "-e", f"MYSQL_PWD={self.password}", "-e", "MYSQL_USER=root", cid,
            "bash", directory + "/migrate.sh", mode, *options, expected=expected)

    def snapshot(self, cid, *, registry=True):
        tables = self.scalar(cid, "SELECT table_name FROM information_schema.tables "
                             "WHERE table_schema='db_beadhouse' AND table_type='BASE TABLE' "
                             "ORDER BY table_name").splitlines()
        if not registry:
            tables = [name for name in tables if name not in ("schema_migration", "schema_metadata")]
        sql = []
        for table in tables:
            safe = table.replace("`", "``")
            sql.extend([f"SELECT COUNT(*) FROM db_beadhouse.`{safe}`;",
                        f"CHECKSUM TABLE db_beadhouse.`{safe}`;"])
        lines = self.scalar(cid, "\n".join(sql)).splitlines()
        require(len(lines) == len(tables) * 2, "Incomplete table snapshot")
        return {table: (int(lines[index * 2]), lines[index * 2 + 1].split("\t")[-1])
                for index, table in enumerate(tables)}

    def check(self, name):
        self.checks.append(name)
        print(f"PASS {name}", flush=True)
        self.save()

    def save(self):
        (self.evidence / "summary.json").write_text(json.dumps({
            "status": self.outcome, "checks": self.checks,
            "elapsed_seconds": round(time.monotonic() - self.started, 2),
            "containers": self.containers,
        }, indent=2), encoding="utf-8")

    def cleanup(self):
        for cid in list(self.containers):
            self.owned(cid)
            self.docker_run("rm", "-f", "-v", cid)
        self.save()


def canonical_sha(path):
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def append_migration(package, number, sql):
    filename = f"gate_{number:04d}.sql"
    path = package / filename
    path.write_text(sql, encoding="utf-8", newline="\n")
    manifest = package / "migrations.tsv"
    with manifest.open("a", encoding="utf-8", newline="\n") as stream:
        stream.write(f"{number:04d}\t{filename}\t{canonical_sha(path)}\ttrue\n")


def history(gate, cid):
    return gate.scalar(cid, "SELECT CONCAT(version,'|',filename,'|',checksum,'|',status) "
                       "FROM db_beadhouse.schema_migration ORDER BY version")


def business_snapshot(gate, cid):
    result = gate.snapshot(cid, registry=False)
    # These two migrations intentionally stamp newly created audit references.
    # Compare their actual relationships, not wall-clock creation timestamps
    # from two different executions.
    for table, columns in (
            ("role_auth", "id,role_id,auth_id,create_id,update_id"),
            ("attachment_reference", "attachment_id,owner_type,owner_id,active")):
        if table in result:
            rows = gate.scalar(cid, f"SELECT {columns} FROM db_beadhouse.`{table}` ORDER BY 1,2,3")
            result[table] = (result[table][0], hashlib.sha256(rows.encode()).hexdigest())
    return result


def status(gate, cid, version):
    return gate.scalar(cid, "SELECT status FROM db_beadhouse.schema_migration "
                       f"WHERE version='{version:04d}'")


def failure(result, message):
    require(result.returncode != 0, message)


def wait_connection(gate, cid, marker):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        found = gate.scalar(cid, "SELECT ID FROM information_schema.processlist "
                            "WHERE INFO LIKE 'SELECT SLEEP(60)%' AND ID="
                            f"IS_USED_LOCK('{LOCK}')")
        if found:
            return int(found)
        time.sleep(0.2)
    raise AssertionError(f"Real MySQL statement barrier was never reached: {marker}")


def check_lock(gate, cid):
    holder = subprocess.Popen(
        gate.mysql_args(cid), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        creationflags=gate.creationflags)
    try:
        holder.stdin.write(
            f"SELECT GET_LOCK('{LOCK}',0); SELECT SLEEP(60) /* migration_gate_lock */;"
            .encode("ascii"))
        holder.stdin.close()
        connection = wait_connection(gate, cid, "migration_gate_lock")
        before = gate.snapshot(cid)
        failure(gate.migrate(cid, "upgrade", expected=None),
                "Second runner acquired a lock already held by a live MySQL session")
        require(gate.snapshot(cid) == before, "Lock contention changed the database")
        gate.mysql(cid, f"KILL CONNECTION {connection}")
        holder.wait(timeout=10)
        gate.check("a second runner refuses the same live MySQL named lock")
    finally:
        if holder.poll() is None:
            holder.kill()
            holder.wait(timeout=10)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--disposable-test", action="store_true",
                        help="Authorize creating and deleting this runner's own test containers")
    args = parser.parse_args()
    if not args.disposable_test:
        parser.error("--disposable-test is required; no existing database is ever used")
    require((ROOT / "数据库/migrate.sh").is_file(), "Migration runner has not been implemented")
    evidence = ROOT / ".runtime/database-check" / uuid.uuid4().hex
    evidence.mkdir(parents=True)
    print(f"Evidence: {evidence}", flush=True)
    gate = Gate(evidence)
    try:
        with tempfile.TemporaryDirectory(prefix="silvercare-migrations-") as tmp:
            package = Path(tmp) / "package"
            shutil.copytree(ROOT / "数据库", package)
            for script in package.glob("*.sh"):
                script.write_bytes(script.read_bytes().replace(b"\r\n", b"\n"))
            exercise(gate, package)
            gate.outcome = "verified"
    except Exception as exc:
        gate.outcome = "failed"
        (evidence / "failure.txt").write_text(str(exc), encoding="utf-8")
        raise
    finally:
        gate.cleanup()
    print(json.dumps({"status": "verified", "checks": len(gate.checks),
                      "evidence": str(evidence)}, ensure_ascii=False), flush=True)


def exercise(gate, package):
    cid = gate.create()
    directory = gate.copy_package(cid, package)
    failure(gate.migrate(cid, "inspect", expected=None),
            "An empty server was incorrectly reported as ready")
    require(gate.scalar(cid, "SELECT COUNT(*) FROM information_schema.tables "
                        "WHERE table_schema='db_beadhouse'") == "0",
            "Inspect wrote tables to an empty database")
    gate.migrate(cid, "initialize")
    initial_history = history(gate, cid)
    require(len(initial_history.splitlines()) == BASE_COUNT,
            f"Fresh initialization did not apply all {BASE_COUNT} baseline versions")
    require(all(line.endswith("|SUCCEEDED") for line in initial_history.splitlines()),
            "Fresh initialization contains a non-applied migration")
    require(initial_history.splitlines() == [
        "|".join((version, filename, checksum, "SUCCEEDED"))
        for version, filename, checksum, _ in BASELINE
    ], "Fresh initialization history does not exactly match the complete baseline manifest")
    initial_business = business_snapshot(gate, cid)
    initial_metadata = gate.scalar(cid, "SELECT origin FROM db_beadhouse.schema_metadata")
    require(initial_metadata == "DEMO_SEEDED", "Fresh origin is not explicitly recorded")
    gate.check(f"empty server initializes all {BASE_COUNT} tracked baseline migrations")

    before = gate.snapshot(cid)
    gate.migrate(cid, "upgrade")
    require(gate.snapshot(cid) == before, "Repeated upgrade changed existing data or registry")
    failure(gate.migrate(cid, "initialize", expected=None),
            "Initialize accepted a nonempty database")
    require(gate.snapshot(cid) == before, "Rejected initialize changed existing data")
    inspection = gate.migrate(cid, "inspect")
    report = json.loads(inspection.stdout)
    require(report["database"] == "db_beadhouse", "Inspect reported a wrong database")
    require(gate.snapshot(cid) == before, "Inspect modified database state")
    gate.check("repeat upgrade is a no-op; initialize refuses nonempty; inspect is read-only")

    check_lock(gate, cid)
    backup_checks(gate, cid)

    # A different file and a matching manifest checksum must still be rejected
    # against immutable applied history, rather than silently accepting new SQL.
    drift = package.parent / "drift"
    shutil.copytree(package, drift)
    manifest = (drift / "migrations.tsv").read_text(encoding="utf-8")
    lines = manifest.splitlines()
    first = next(i for i, line in enumerate(lines) if line and not line.startswith("#"))
    parts = lines[first].split("\t")
    sql_path = drift / parts[1]
    sql_path.write_bytes(sql_path.read_bytes() + b"\n-- immutable-history drift fixture\n")
    parts[2] = canonical_sha(sql_path)
    lines[first] = "\t".join(parts)
    (drift / "migrations.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    gate.copy_package(cid, drift, "/tmp/migration-drift")
    failure(gate.migrate(cid, "upgrade", directory="/tmp/migration-drift", expected=None),
            "Changed SQL with a self-consistent manifest bypassed applied history")
    require(gate.snapshot(cid) == before, "Rejected history drift mutated the database")
    gate.check("applied SQL drift is rejected even with a newly matching manifest checksum")
    rescue = "/tmp/silvercare-backup-" + uuid.uuid4().hex
    gate.migrate(cid, "backup", rescue, "--writers-stopped", directory="/tmp/migration-drift")
    rescue_manifest = json.loads(gate.docker_run(
        "exec", cid, "cat", rescue + "/manifest.json").stdout)
    require(rescue_manifest["source_readiness"] == "NOT_READY",
            "Rescue backup concealed incompatible applied history")
    require(len(rescue_manifest["migrations"]) == BASE_COUNT,
            "Rescue backup omitted actual version history")
    require(sorted(item["version"] for item in rescue_manifest["migrations"]) == BASE_VERSIONS,
            "Rescue backup did not preserve every baseline version")
    require(gate.snapshot(cid) == before, "Rescue backup modified incompatible source history")
    gate.check("an incompatible schema remains recoverably backed up with explicit NOT_READY metadata")

    gate.mysql(cid, f"UPDATE db_beadhouse.schema_migration SET version='9999' WHERE version='{LAST_VERSION}'")
    unknown = gate.snapshot(cid)
    failure(gate.migrate(cid, "upgrade", expected=None), "Unknown historical version was accepted")
    require(gate.snapshot(cid) == unknown, "Unknown history rejection mutated the database")
    gate.mysql(cid, f"UPDATE db_beadhouse.schema_migration SET version='{LAST_VERSION}' WHERE version='9999'")
    gate.mysql(cid, "CREATE TABLE db_beadhouse.gate_history_copy LIKE db_beadhouse.schema_migration;"
                    "INSERT INTO db_beadhouse.gate_history_copy SELECT * FROM db_beadhouse.schema_migration;"
                    "DELETE FROM db_beadhouse.schema_migration WHERE version='0003';")
    gap = gate.snapshot(cid)
    failure(gate.migrate(cid, "upgrade", expected=None), "A history gap was silently filled")
    require(gate.snapshot(cid) == gap, "History gap rejection mutated the database")
    gate.mysql(cid, "INSERT INTO db_beadhouse.schema_migration SELECT * FROM "
                    "db_beadhouse.gate_history_copy WHERE version='0003';"
                    "DROP TABLE db_beadhouse.gate_history_copy;")
    require(gate.snapshot(cid) == before, "History fixture cleanup did not restore the database")
    gate.check("unknown applied versions and missing intermediate versions are rejected")

    # Destructive reset is limited to our ID/name/label-verified disposable target.
    gate.reset(cid)
    gate.run(gate.mysql_args(cid), data=b"USE db_beadhouse;\n" +
             (package / "db_beadhouse.sql").read_bytes())
    gate.migrate(cid, "upgrade")
    require(history(gate, cid) == initial_history, "Legacy upgrade has different migration versions")
    require(business_snapshot(gate, cid) == initial_business,
            "Fresh and legacy paths have different business data")
    legacy_metadata = gate.scalar(cid, "SELECT origin FROM db_beadhouse.schema_metadata")
    require(legacy_metadata == "LEGACY_ADOPTED", "Legacy origin is not explicitly recorded")
    gate.check("original legacy dump upgrades to the same versions and business data")

    fault_checks(gate, cid, package, directory)


def fault_checks(gate, cid, package, directory):
    failed_version, successor_version, interrupted_version, large_version = range(BASE_COUNT + 1, BASE_COUNT + 5)
    gate.mysql(cid, "CREATE TABLE db_beadhouse.gate_fault_control(allowed BOOLEAN NOT NULL);"
                    "INSERT INTO db_beadhouse.gate_fault_control VALUES (FALSE);")
    append_migration(package, failed_version, f"""USE db_beadhouse;
CREATE TABLE IF NOT EXISTS gate_ddl_survives(id BIGINT PRIMARY KEY);
DROP PROCEDURE IF EXISTS gate_controlled_failure;
DELIMITER $$
CREATE PROCEDURE gate_controlled_failure()
BEGIN
 IF (SELECT allowed FROM gate_fault_control LIMIT 1) = FALSE THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled migration failure after DDL';
 END IF;
END$$
DELIMITER ;
CALL gate_controlled_failure();
DROP PROCEDURE gate_controlled_failure;
INSERT IGNORE INTO gate_ddl_survives VALUES ({failed_version});
""")
    append_migration(package, successor_version, f"""USE db_beadhouse;
CREATE TABLE IF NOT EXISTS gate_after_failure(id BIGINT PRIMARY KEY);
INSERT IGNORE INTO gate_after_failure VALUES ({successor_version});
""")
    # Copy individual package contents rather than nesting a second directory.
    gate.docker_run("cp", str(package) + "/.", f"{cid}:{directory}")
    failure(gate.migrate(cid, "upgrade", expected=None), "DDL followed by SIGNAL reported success")
    require(status(gate, cid, failed_version) in ("FAILED", "INTERRUPTED"), "Failed migration was not recorded")
    require(gate.scalar(cid, "SELECT COUNT(*) FROM information_schema.tables WHERE "
                        "table_schema='db_beadhouse' AND table_name='gate_ddl_survives'") == "1",
            "The test did not exercise a committed DDL before failure")
    require(gate.scalar(cid, "SELECT COUNT(*) FROM information_schema.tables WHERE "
                        "table_schema='db_beadhouse' AND table_name='gate_after_failure'") == "0",
            "A later migration ran past the failed version")
    before = gate.snapshot(cid)
    failure(gate.migrate(cid, "inspect", expected=None), "Inspect claimed a failed schema was ready")
    require(gate.snapshot(cid) == before, "Inspect changed failed migration state")
    failure(gate.migrate(cid, "upgrade", expected=None), "Implicit retry accepted a failed migration")
    require(gate.snapshot(cid) == before, "Implicit retry modified a failed migration")
    gate.mysql(cid, "UPDATE db_beadhouse.gate_fault_control SET allowed=TRUE")
    gate.migrate(cid, "upgrade", "--retry")
    require(status(gate, cid, failed_version) == "SUCCEEDED" and status(gate, cid, successor_version) == "SUCCEEDED",
            "Explicit retry did not apply the repaired same-checksum migration and successor")
    require(gate.scalar(cid, "SELECT attempts FROM db_beadhouse.schema_migration "
                        f"WHERE version='{failed_version:04d}'") == "2", "Retry attempt count is inaccurate")
    gate.check("DDL failure remains visible, blocks later versions, and requires explicit same-hash retry")

    append_migration(package, interrupted_version, f"""USE db_beadhouse;
CREATE TABLE IF NOT EXISTS gate_interrupted(id BIGINT PRIMARY KEY);
SELECT IF((SELECT allowed FROM gate_fault_control LIMIT 1),0,SLEEP(60)) /* migration_gate_crash */;
INSERT IGNORE INTO gate_interrupted VALUES ({interrupted_version});
""")
    gate.mysql(cid, "UPDATE db_beadhouse.gate_fault_control SET allowed=FALSE")
    gate.docker_run("cp", str(package) + "/.", f"{cid}:{directory}")
    output = (gate.evidence / "interrupted-runner.log").open("wb")
    process = subprocess.Popen(
        [gate.docker, "exec", "-e", f"MYSQL_PWD={gate.password}", cid,
         "bash", "-c", 'echo $$ > /tmp/gate-runner.pid; exec bash "$1" upgrade',
         "gate", directory + "/migrate.sh"],
        stdout=output, stderr=subprocess.STDOUT, creationflags=gate.creationflags)
    try:
        deadline = time.monotonic() + 30
        connection = None
        while time.monotonic() < deadline:
            found = gate.scalar(cid, "SELECT ID FROM information_schema.processlist "
                                "WHERE INFO LIKE 'SELECT IF(%gate_fault_control%'")
            if found:
                connection = int(found)
                break
            time.sleep(0.2)
        require(connection is not None, "Interruption fixture never reached its SQL execution barrier")
        require(status(gate, cid, interrupted_version) == "RUNNING", "Active migration was not durably RUNNING")
        runner_pid = gate.docker_run("exec", cid, "cat", "/tmp/gate-runner.pid").stdout.decode().strip()
        require(runner_pid.isdecimal() and int(runner_pid) > 1, "Invalid owned runner PID")
        gate.docker_run("exec", cid, "bash", "-c", 'kill -9 "$1"', "gate", runner_pid)
        gate.mysql(cid, f"KILL CONNECTION {connection}")
        require(process.wait(timeout=20) != 0, "Killed MySQL connection still reported migration success")
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
        output.close()
    require(status(gate, cid, interrupted_version) == "RUNNING", "Crash unexpectedly claimed an orderly migration outcome")
    before = gate.snapshot(cid)
    failure(gate.migrate(cid, "inspect", expected=None), "Interrupted migration was reported as ready")
    require(gate.snapshot(cid) == before, "Inspect rewrote crash recovery state")
    failure(gate.migrate(cid, "upgrade", "--retry", expected=None),
            "First encounter silently retried a crashed RUNNING migration")
    require(status(gate, cid, interrupted_version) == "INTERRUPTED", "Crashed RUNNING migration was not classified INTERRUPTED")
    gate.mysql(cid, "UPDATE db_beadhouse.gate_fault_control SET allowed=TRUE")
    gate.migrate(cid, "upgrade", "--retry")
    require(status(gate, cid, interrupted_version) == "SUCCEEDED", "Explicit recovery did not finish interrupted migration")
    before = gate.snapshot(cid)
    gate.owned(cid)
    gate.docker_run("restart", cid)
    gate.ready(cid)
    gate.migrate(cid, "inspect")
    require(gate.snapshot(cid) == before, "Restart lost migration history or business data")
    gate.check("a killed SQL session leaves recoverable state and the repaired database survives restart")
    append_migration(package, large_version, "USE db_beadhouse;\nSELECT REPEAT('x',131072);\n-- "
                     + ("large migration input " * 16000)
                     + "\nCREATE TABLE gate_large_stream(id BIGINT PRIMARY KEY);\n")
    gate.docker_run("cp", str(package) + "/.", f"{cid}:{directory}")
    gate.migrate(cid, "upgrade")
    require(status(gate, cid, large_version) == "SUCCEEDED",
            "Large migration input and output did not complete")
    require(gate.scalar(cid, "SELECT COUNT(*) FROM information_schema.tables WHERE "
                        "table_schema='db_beadhouse' AND table_name='gate_large_stream'") == "1",
            "Large stream never reached the final DDL")
    gate.check("large SQL input and early result output drain without a pipe deadlock")


def backup_checks(gate, cid):
    gate.mysql(cid, """USE db_beadhouse;
CREATE TABLE gate_backup_probe(id BIGINT PRIMARY KEY, value INT NOT NULL);
CREATE PROCEDURE gate_backup_count() SELECT COUNT(*) FROM gate_backup_probe;
CREATE TRIGGER gate_backup_normalize BEFORE INSERT ON gate_backup_probe
 FOR EACH ROW SET NEW.value=ABS(NEW.value);
CREATE EVENT gate_backup_disabled ON SCHEDULE EVERY 1 DAY DISABLE
 DO INSERT IGNORE INTO gate_backup_probe VALUES (999,999);
INSERT INTO gate_backup_probe VALUES (1,-5);
""")
    require(gate.scalar(cid, "SELECT value FROM db_beadhouse.gate_backup_probe WHERE id=1") == "5",
            "Backup trigger fixture did not execute")
    destination = gate.evidence / "backup"
    env = dict(os.environ, MYSQL_PWD=gate.password, MYSQL_ROOT_PASSWORD=gate.password,
               SILVERCARE_DOCKER=gate.docker)
    cli = [sys.executable, str(ROOT / "tools/database.py")]
    before = gate.snapshot(cid)
    gate.run(cli + ["backup", "--container", cid, "--output", str(destination), "--writers-stopped"],
             env=env, timeout=240)
    require(gate.snapshot(cid) == before, "Backup changed source data or migration history")
    manifest = json.loads((destination / "manifest.json").read_text(encoding="utf-8"))
    published = {table["name"]: (table["rows"], str(table["checksum"]))
                 for table in manifest["tables"]}
    require(published == before, "Published backup summaries differ from independent source queries")
    constraints = {item["name"] for item in manifest["constraints"]}
    require({"uk_elder_active_bed", "uk_elder_active_identity", "uk_reserve_active_bed",
             "uk_retreat_pending_elder", "chk_reserve_lifecycle"}.issubset(constraints),
            "Backup omitted required bed lifecycle constraints")
    restored = gate.run(cli + ["restore-drill", "--backup", str(destination)], env=env, timeout=240)
    result = json.loads(restored.stdout.decode("utf-8").splitlines()[-1])
    require(result["status"] == "verified", "Restore drill did not verify the recovered database")
    require(int(result["tables"]) == len(before), "Restore did not verify every source table")
    require(int(result["migrations"]) == BASE_COUNT, "Restore did not verify all applied migrations")
    require(all(int(result["objects"][kind]) >= 1 for kind in ("routines", "events", "triggers")),
            "Restore did not verify the stored procedure, disabled event and trigger")
    require(gate.snapshot(cid) == before, "Restore drill modified the source")
    gate.check("a real backup restores every table, migration version, and required schema constraint")

    for label, mutation in (
            ("tampered", lambda value: value + b"\n-- modified after backup\n"),
            ("truncated", lambda value: value[:len(value) // 2])):
        broken = gate.evidence / label
        shutil.copytree(destination, broken)
        sql = broken / "database.sql"
        sql.write_bytes(mutation(sql.read_bytes()))
        containers_before = gate.docker_run(
            "ps", "-aq", "--filter", "label=silvercare.restore-drill").stdout
        started = time.time()
        rejected = gate.run(cli + ["restore-drill", "--backup", str(broken)],
                            expected=None, env=env, timeout=30)
        failure(rejected, f"{label} SQL was imported as a valid backup")
        events = gate.docker_run(
            "events", "--since", f"{started:.9f}", "--until", f"{time.time():.9f}",
            "--filter", "type=container", "--filter", "label=silvercare.restore-drill",
            "--format", "{{json .}}").stdout.decode().splitlines()
        require(not any(json.loads(event).get("Action") in ("create", "start") for event in events),
                "Damaged backup was not rejected before creation of a restore target")
        require(gate.docker_run(
            "ps", "-aq", "--filter", "label=silvercare.restore-drill").stdout
                == containers_before, "A damaged backup left a recovery target behind")
    gate.check("tampered and truncated backups are rejected before restoration")
    gate.mysql(cid, """USE db_beadhouse;
DROP EVENT gate_backup_disabled;
DROP TRIGGER gate_backup_normalize;
DROP PROCEDURE gate_backup_count;
DROP TABLE gate_backup_probe;
""")


if __name__ == "__main__":
    main()
