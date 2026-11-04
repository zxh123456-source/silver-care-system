"""Real encrypted backup, attachment access and BGE/Milvus recovery acceptance.

Only newly created ID/name/label-verified containers and processes are touched.
Requires a built backend JAR, Java 8, Docker, the backup requirements and an AI
Python runtime with requirements.txt installed. All endpoints use dynamic local
ports. Credentials and source records are never included in the safe reports.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zlib

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import database
import system_backup
import system_restore

LABEL = "silvercare.system-backup.drill"
PREFIX = "silvercare-system-backup-"
BGE_MODEL = "BAAI/bge-small-zh-v1.5"
AI_ROOT = ROOT / "源码/beadhouse-ai"
BACKEND_ROOT = ROOT / "源码/beadhouse-backend"
LIVE_TITLE, DEAD_TITLE = "恢复验收夜间巡视制度", "恢复验收废止探视制度"
LIVE_CONTENT = "夜间巡视必须每两小时检查一次。护理人员应记录巡视时间、老人状态和异常情况，发现异常立即通知值班护士。"
DEAD_CONTENT = "废止探视制度规定探视口令为紫色松鼠，所有访客必须登记后才能探视。"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def png_bytes():
    def chunk(kind, content):
        return struct.pack(">I", len(content)) + kind + content + struct.pack(">I", zlib.crc32(kind + content))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 8, 8, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress((b"\x00" + b"\x28\x86\x72" * 8) * 8)) + chunk(b"IEND", b""))


class Gate:
    def __init__(self, args):
        self.args, self.owner = args, uuid.uuid4().hex
        self.directory = ROOT / ".runtime/roadmap2-system-drill" / self.owner
        self.directory.mkdir(parents=True)
        # The schema's attachment.path is varchar(225). Keep owned source/restore
        # roots short even when the repository itself lives in a deep workspace.
        self.private = ROOT / ".runtime" / ("sd-" + self.owner[:12])
        self.private.mkdir(mode=0o700)
        (self.private / ".owner").write_text(self.owner, encoding="ascii")
        self.checks, self.events, self.containers, self.processes = [], [], {}, []
        self.network = None
        self.network_name = PREFIX + self.owner[:12]
        self.started, self.status, self.step = time.monotonic(), "running", "preflight"
        self.secrets = []
        self.handles = []
        self.cleanup_errors = []
        self.metadata = {}
        self.docker = database.Docker(self.directory / "docker-errors")
        self.java = args.java or (str(Path(os.environ["JAVA_HOME"]) / "bin" /
                                    ("java.exe" if os.name == "nt" else "java"))
                                  if os.environ.get("JAVA_HOME") else shutil.which("java"))
        self.jar = BACKEND_ROOT / "target/beadhouse-backend-0.0.1-SNAPSHOT.jar"
        require(self.java and self.jar.is_file(), "Java and the built backend JAR are required")
        self.flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def secret(self):
        value = secrets.token_hex(24)
        self.secrets.append(value)
        return value

    def event(self, name):
        self.step = name
        self.events.append({"step": name, "elapsedSeconds": round(time.monotonic() - self.started, 2)})
        print("STEP " + name, flush=True)
        self.save()

    def check(self, name):
        self.checks.append(name)
        print("PASS " + name, flush=True)
        self.save()

    def save(self):
        report = {"status": self.status, "owner": self.owner, "step": self.step,
                  "checks": self.checks, "events": self.events, "metadata": self.metadata,
                  "cleanupErrors": self.cleanup_errors,
                  "elapsedSeconds": round(time.monotonic() - self.started, 2)}
        (self.directory / "summary.json").write_text(json.dumps(report, indent=2), encoding="utf-8")

    def run(self, args, *, env=None, data=None, timeout=120):
        completed = subprocess.run(args, env=env, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   timeout=timeout, creationflags=self.flags)
        require(completed.returncode == 0, "Child command failed during " + self.step)
        return completed.stdout

    def docker_run(self, args, *, env=None, data=None, check=True, timeout=180):
        return self.docker.run(args, env=env, input_bytes=data, check=check, timeout=timeout)

    def owned(self, cid):
        require(cid in self.containers, "Unrecorded container cleanup refused")
        info = json.loads(self.docker_run(["inspect", cid]).stdout)[0]
        require(info["Id"] == cid and info["Name"].lstrip("/") == self.containers[cid]
                and info["Config"]["Labels"].get(LABEL) == self.owner,
                "Container ownership mismatch")
        require(not info["HostConfig"].get("Binds"), "Unexpected host mount")
        require(all(binding["HostIp"] == "127.0.0.1"
                    for bindings in (info["HostConfig"].get("PortBindings") or {}).values()
                    for binding in bindings), "Unexpected non-loopback port")
        return info

    def create(self, role, image, *, env=None, ports=(), command=(), aliases=(), before_start=None):
        name = PREFIX + role + "-" + self.owner[:12]
        args = ["create", "--name", name, "--label", LABEL + "=" + self.owner]
        if self.network:
            args += ["--network", self.network]
            for alias in aliases:
                args += ["--network-alias", alias]
        for port in ports:
            args += ["--publish", "127.0.0.1::" + str(port)]
        child_env = dict(os.environ)
        for key, value in (env or {}).items():
            child_env[key] = value
            args += ["--env", key]
        args += [image, *command]
        cid = self.docker_run(args, env=child_env).stdout.decode().strip()
        self.containers[cid] = name
        self.metadata.setdefault("ownedContainers", []).append({"id": cid, "name": name})
        self.owned(cid)
        if before_start:
            before_start(cid)
        self.docker_run(["start", cid])
        return cid

    def port(self, cid, port):
        info = self.owned(cid)
        binding = info["NetworkSettings"]["Ports"][str(port) + "/tcp"]
        require(len(binding) == 1 and binding[0]["HostIp"] == "127.0.0.1", "Invalid published endpoint")
        return int(binding[0]["HostPort"])

    def wait(self, predicate, *, timeout=120):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(.5)
        raise TimeoutError("Readiness timeout during " + self.step)

    def request(self, base, path, *, token=None, method="GET", payload=None, data=None, headers=None, timeout=60):
        headers = dict(headers or {})
        if token:
            headers["token"] = token
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(base + path, data=data, method=method, headers=headers)
        try:
            with self.http.open(request, timeout=timeout) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def api(self, base, path, **kwargs):
        status, raw = self.request(base, path, **kwargs)
        require(status == 200, "HTTP status " + str(status) + " rejected at " + path.split("?")[0])
        result = json.loads(raw)
        require(result.get("code") == 200, "Business code " + str(result.get("code")) + " rejected at " + path.split("?")[0])
        return result.get("data")

    def login(self, base, phone, password):
        value = self.api(base, "/account/login", method="POST", payload={"phone": phone, "pass": password})
        self.secrets.append(value["token"])
        return value["token"]

    def start_process(self, command, env, name, cwd):
        handle = (self.directory / (name + ".log")).open("wb")
        self.handles.append(handle)
        process = subprocess.Popen(command, env=env, cwd=cwd, stdin=subprocess.DEVNULL,
                                   stdout=handle, stderr=subprocess.STDOUT, creationflags=self.flags)
        self.processes.append(process)
        return process

    def stop(self, process):
        if process.poll() is None:
            process.terminate()
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
        require(process.poll() is not None, "Owned process did not stop")

    def backend_environment(self, dbport, dbpassword, redisport, redispassword, uploads, *, rag_url=None, rag_token=None):
        env = dict(os.environ)
        env.update(DB_URL=f"jdbc:mysql://127.0.0.1:{dbport}/db_beadhouse?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                   DB_USERNAME="root", DB_PASSWORD=dbpassword, REDIS_PASSWORD=redispassword,
                   SPRING_REDIS_HOST="127.0.0.1", SPRING_REDIS_PORT=str(redisport), SPRING_REDIS_DATABASE="1",
                   JWT_SECRET=self.secret(), PASSWORD_RESET_EMAIL_ENABLED="false", AI_PROVIDER_ENABLED="false",
                   AI_PROVIDER_URL="", AI_PROVIDER_API_KEY="", AI_ALERT_SCAN_ENABLED="false",
                   AI_RAG_ENABLED="true" if rag_url else "false", AI_RAG_URL=rag_url or "http://127.0.0.1:1",
                   AI_RAG_INTERNAL_TOKEN=rag_token or self.secret(), AI_RAG_RETRY_ENABLED="true" if rag_url else "false",
                   AI_RAG_RETRY_INTERVAL_MS="200", AI_RAG_LEASE_SECONDS="120",
                   AI_RAG_CONNECT_TIMEOUT_MS="10000", AI_RAG_READ_TIMEOUT_MS="60000",
                   AI_OCR_ENABLED="false", SPRING_QUARTZ_AUTO_STARTUP="false", MYBATIS_LOG_IMPL="org.apache.ibatis.logging.nologging.NoLoggingImpl",
                   FILESAVE_WINDOWS=str(uploads), FILESAVE_LINUX=str(uploads), FILESAVE_MACOS=str(uploads),
                   TZ="Asia/Shanghai", NO_PROXY="127.0.0.1,localhost")
        return env

    def backend(self, env, name):
        port = free_port()
        env = dict(env, SERVER_PORT=str(port), SERVER_ADDRESS="127.0.0.1")
        process = self.start_process([self.java, "-jar", str(self.jar)], env, name, BACKEND_ROOT)
        base = f"http://127.0.0.1:{port}"
        def ready():
            require(process.poll() is None, "Backend exited before readiness")
            try:
                status, raw = self.request(base, "/account/sendCode", method="POST", payload={"account": "invalid"}, timeout=2)
                return status == 200 and json.loads(raw).get("code") == 400
            except (OSError, ValueError):
                return False
        try:
            self.wait(ready)
        except BaseException:
            self.stop(process)
            raise
        return process, base

    def checksum(self, cid):
        tables = self.docker.sql(cid, "SELECT table_name FROM information_schema.tables WHERE table_schema='db_beadhouse' "
                                "AND table_type='BASE TABLE' ORDER BY table_name").splitlines()
        require(all(database.identifier(table) for table in tables), "Unsafe table identifier")
        return self.docker.sql(cid, "\n".join("CHECKSUM TABLE `" + table + "` EXTENDED;" for table in tables))

    def sanitize(self):
        for path in self.directory.rglob("*.log"):
            content = path.read_text(encoding="utf-8", errors="replace")
            for value in sorted(set(self.secrets), key=len, reverse=True):
                content = content.replace(value, "<redacted>")
            content = re.sub(r"[A-Za-z0-9_.+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", "<email>", content)
            content = re.sub(r"(?<!\d)1\d{10}(?!\d)", "<phone>", content)
            path.write_text(content, encoding="utf-8")

    def cleanup(self):
        for process in reversed(self.processes):
            try:
                self.stop(process)
            except Exception as error:
                self.cleanup_errors.append({"resource": "process", "errorClass": type(error).__name__})
        for handle in self.handles:
            handle.close()
        for cid in reversed(self.containers):
            try:
                self.owned(cid)
                self.docker_run(["rm", "-f", "-v", cid])
                require(self.docker_run(["inspect", cid], check=False).returncode != 0, "Container still exists")
            except Exception as error:
                self.cleanup_errors.append({"resource": "container", "errorClass": type(error).__name__})
        if self.network:
            try:
                info = json.loads(self.docker_run(["network", "inspect", self.network]).stdout)[0]
                require(info["Id"] == self.network and info["Name"] == self.network_name
                        and info["Labels"].get(LABEL) == self.owner and not info["Containers"], "Network ownership mismatch")
                self.docker_run(["network", "rm", self.network])
            except Exception as error:
                self.cleanup_errors.append({"resource": "network", "errorClass": type(error).__name__})
        try:
            require(self.private.resolve().parent == (ROOT / ".runtime").resolve()
                    and not self.private.is_symlink()
                    and (self.private / ".owner").read_text(encoding="ascii") == self.owner,
                    "Private cleanup boundary mismatch")
            shutil.rmtree(self.private)
            self.sanitize()
        except Exception as error:
            self.cleanup_errors.append({"resource": "private-files", "errorClass": type(error).__name__})
        self.metadata["ownedContainersRemoved"] = not self.cleanup_errors
        self.metadata["ownedProcessesStopped"] = all(process.poll() is not None for process in self.processes)
        if self.cleanup_errors:
            self.status = "failed"
        self.save()
        failed = self.status != "verified"
        report = ET.Element("testsuite", name="system-backup", tests=str(len(self.checks) + int(failed)), failures=str(int(failed)))
        for name in self.checks:
            ET.SubElement(report, "testcase", name=name, classname="system-backup")
        if failed:
            ET.SubElement(ET.SubElement(report, "testcase", name=self.step), "failure", message="See safe summary.json")
        ET.ElementTree(report).write(self.directory / "junit.xml", encoding="utf-8", xml_declaration=True)


def initialize_source(gate):
    gate.event("initialize-owned-source")
    dbpassword, redispassword = gate.secret(), gate.secret()
    source = gate.create("source", "mysql:8.0.40", env={"MYSQL_ROOT_PASSWORD": dbpassword, "MYSQL_ROOT_HOST": "%", "TZ": "Asia/Shanghai"}, ports=(3306,))
    redis = gate.create("redis", "redis:7.4.2-alpine", env={"REDIS_PASSWORD": redispassword}, ports=(6379,),
                        command=("sh", "-c", 'exec redis-server --appendonly no --requirepass "$REDIS_PASSWORD"'))
    def mysql_ready():
        try:
            return gate.docker.sql(source, "SELECT 1", database=None) == "1"
        except database.DatabaseError:
            return False
    gate.wait(mysql_ready)
    package = gate.private / "migration"
    shutil.copytree(ROOT / "数据库", package)
    for script in package.glob("*.sh"):
        script.write_bytes(script.read_bytes().replace(b"\r\n", b"\n"))
    gate.docker_run(["cp", str(package), source + ":/tmp/system-migrations"])
    for mode in ("initialize", "inspect"):
        gate.docker_run(["exec", source, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec bash /tmp/system-migrations/migrate.sh ' + mode])
    project = gate.private / "source-project"
    uploads = project / "uploads"
    uploads.mkdir(parents=True)
    for relative in system_backup.CONFIG_PATHS:
        if relative in (".env", "本地配置.ps1"):
            continue
        target = project / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / relative, target)
    # Synthetic source configuration only; never load the user's live .env.
    (project / ".env").write_text("MYSQL_ROOT_PASSWORD=" + dbpassword + "\nREDIS_PASSWORD=" + redispassword + "\n", encoding="utf-8")
    (project / "本地配置.ps1").write_text("$SystemDrillFixture = 'owned-source'\n", encoding="utf-8")
    # Retire missing legacy demo files explicitly while preserving reference history.
    # Every stored path is made relative to this synthetic source's upload root.
    old = gate.docker.sql(source, "SELECT id FROM base_attachment ORDER BY id").splitlines()
    statements = ["UPDATE attachment_reference SET active='N';", "UPDATE base_attachment SET del_flag='Y';"]
    for identifier in old:
        require(identifier.isdecimal(), "Invalid seed attachment identifier")
        statements.append("UPDATE base_attachment SET path=" + system_restore.sql_text(str(uploads / ("retired-" + identifier + ".png"))) + " WHERE id=" + identifier + ";")
    gate.docker.sql(source, "\n".join(statements))
    dbport, redisport = gate.port(source, 3306), gate.port(redis, 6379)
    gate.metadata["sourcePorts"] = {"mysql": dbport, "redis": redisport}
    env = gate.backend_environment(dbport, dbpassword, redisport, redispassword, uploads)
    password = gate.secret()
    env["DEMO_ACCOUNT_PASSWORD"] = password
    gate.run([gate.java, "-Dloader.main=com.shanzhu.beadhouse.tools.DemoAccountInitializer", "-cp", str(gate.jar),
              "org.springframework.boot.loader.PropertiesLauncher", "--confirm-demo"], env=env, timeout=60)
    gate.docker.sql(source, "INSERT INTO role(id,name,create_id,create_time,update_id,update_time) VALUES(990101,'no-grants',1,NOW(),1,NOW());"
                    "INSERT INTO staff(id,role_id,name,id_num,age,sex,phone,email,pass,avator,address,leave_flag,create_id,create_time,update_id,update_time) "
                    "SELECT 990102,990101,'denied','990101199001019999',30,'女','19900007001','drill@example.invalid',pass,'','fixture','N',1,NOW(),1,NOW() FROM staff WHERE id=1;")
    phone = gate.docker.sql(source, "SELECT phone FROM staff WHERE id=1")
    gate.secrets.append(phone)
    process, base = gate.backend(env, "source-backend")
    token = gate.login(base, phone, password)
    gate.check("real source administrator login")
    boundary = "system-drill-" + gate.owner
    image = png_bytes()
    body = (("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="recovery.png"\r\nContent-Type: image/png\r\n\r\n').encode()
            + image + ("\r\n--" + boundary + "--\r\n").encode())
    photo = gate.api(base, "/file/uploadImg", method="POST", token=token, data=body,
                     headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    staff_id = gate.docker.sql(source, "SELECT id FROM staff WHERE role_id<>1 AND leave_flag='N' AND id<>990102 ORDER BY id LIMIT 1")
    require(staff_id.isdecimal(), "Seed has no manageable staff fixture")
    staff = gate.api(base, "/staff/getStaffById?staffId=" + staff_id, token=token)
    profile = {field: staff.get(field) for field in ("id", "roleId", "name", "idNum", "age", "sex", "phone", "email", "address")}
    profile["avator"] = photo["url"]
    gate.api(base, "/staff/editStaff", method="PUT", token=token, payload=profile)
    token = gate.login(base, phone, password)
    require(gate.docker.sql(source, f"SELECT COUNT(*) FROM attachment_reference WHERE attachment_id={int(photo['id'])} AND active='Y' AND owner_type='STAFF'") == "1", "HTTP avatar binding did not persist")
    require(gate.request(base, f"/file/{int(photo['id'])}/content", token=token) == (200, image), "Uploaded attachment bytes differ")
    gate.check("real PNG upload binds to staff and authenticated content matches")
    for title, content in ((LIVE_TITLE, LIVE_CONTENT), (DEAD_TITLE, DEAD_CONTENT)):
        gate.api(base, "/ai/policy/import", method="POST", token=token,
                 payload={"title": title, "source": "system-drill.txt", "content": content})
    gate.api(base, "/ai/policy/document?" + urllib.parse.urlencode({"title": DEAD_TITLE, "source": "system-drill.txt"}), method="DELETE", token=token)
    gate.stop(process)
    gate.check("source application stopped after creating live policy and durable delete tombstone")
    return dict(container=source, redisport=redisport, redispassword=redispassword, project=project, uploads=uploads,
                photo=photo, image=image, phone=phone, password=password, token=token, dbpassword=dbpassword)


def rag_infrastructure(gate):
    gate.event("start-owned-rag-infrastructure")
    gate.network = gate.docker_run(["network", "create", "--label", LABEL + "=" + gate.owner, gate.network_name]).stdout.decode().strip()
    gate.create("etcd", "quay.io/coreos/etcd:v3.5.18", aliases=("etcd",),
                command=("etcd", "-advertise-client-urls=http://127.0.0.1:2379", "-listen-client-urls=http://0.0.0.0:2379", "--data-dir", "/etcd"))
    minio_password, milvus_password = gate.secret(), gate.secret()
    gate.create("minio", "silver-care-minio:RELEASE.2024-12-18T13-15-44Z", aliases=("minio",),
                env={"MINIO_ROOT_USER": "system-drill", "MINIO_ROOT_PASSWORD": minio_password}, command=("minio", "server", "/minio_data"))
    config = gate.private / "milvus-user.yaml"
    config.write_text('common:\n  security:\n    authorizationEnabled: true\n    defaultRootPassword: "' + milvus_password + '"\n', encoding="utf-8")
    milvus = gate.create("milvus", "milvusdb/milvus:v2.6.24", ports=(19530, 9091),
                         env={"ETCD_ENDPOINTS": "etcd:2379", "MINIO_ADDRESS": "minio:9000",
                              "MINIO_ACCESS_KEY_ID": "system-drill", "MINIO_SECRET_ACCESS_KEY": minio_password},
                         command=("milvus", "run", "standalone"),
                         before_start=lambda cid: gate.docker_run(["cp", str(config), cid + ":/milvus/configs/user.yaml"]))
    uri = "http://127.0.0.1:" + str(gate.port(milvus, 19530))
    health = "http://127.0.0.1:" + str(gate.port(milvus, 9091))
    def ready():
        try:
            return gate.request(health, "/healthz", timeout=2)[0] == 200
        except OSError:
            return False
    gate.wait(ready, timeout=180)
    return uri, "root:" + milvus_password


def verify_target(gate, source, package, key):
    gate.event("restore-authenticated-package")
    with system_restore.restored_system(package, key, gate.private, publish_database=True) as restored:
        gate.metadata["restore"] = restored.report
        gate.metadata["restoreContainer"] = restored.database.container
        gate.secrets.append(restored.database.password)
        require(restored.database.container != source["container"] and restored.uploads_root.resolve() != source["uploads"].resolve(), "Restore reused source resources")
        require((restored.config_root / ".env").read_bytes() == (source["project"] / ".env").read_bytes(), "Encrypted configuration was not restored byte-for-byte")
        rows = system_backup.snapshot_database(restored.database.docker, restored.database.container)
        original = {row["id"]: row for row in restored.snapshot["base_attachment"]}
        for row in rows["base_attachment"]:
            require({k: v for k, v in row.items() if k != "path"} == {k: v for k, v in original[row["id"]].items() if k != "path"}, "Relocation changed attachment metadata")
        require(rows["attachment_reference"] == restored.snapshot["attachment_reference"], "Reference history changed")
        require(rows["policy_rag_sync"] == restored.snapshot["policy_rag_sync"], "RAG authority changed before reindex")
        live = next(row for row in rows["base_attachment"] if row["id"] == int(source["photo"]["id"]))
        require(Path(live["path"]).read_bytes() == source["image"], "Recovered PNG bytes differ")
        gate.check("original SQL verified before path-only relocation; attachments, owner history and configuration recovered")
        # A later CAS failure must roll back an earlier path update in the same real transaction.
        plan = [{"id": live["id"], "old_path": live["path"], "new_path": live["path"] + ".probe"},
                {"id": live["id"], "old_path": "wrong-old-path", "new_path": live["path"] + ".bad"}]
        try:
            restored.database.docker.sql(restored.database.container, system_restore.relocation_sql(plan))
        except database.DatabaseError:
            pass
        else:
            raise AssertionError("CAS mismatch was accepted")
        require(system_backup.snapshot_database(restored.database.docker, restored.database.container) == rows, "CAS failure did not roll back every earlier relocation")
        gate.check("real MySQL CAS mismatch rolls back prior attachment relocation")
        before_json = json.loads(restored.rag_file.read_text(encoding="utf-8"))
        require(all(doc["indexed_version"] is None for doc in before_json["documents"].values()), "Restore trusted stale index acknowledgements")
        require(any(doc["action"] == "DELETE" for doc in before_json["documents"].values()), "Fresh JSON lost delete tombstone")
        uri, milvus_token = rag_infrastructure(gate)
        gate.event("start-real-bge-service")
        internal_token, instance_id, port = gate.secret(), uuid.uuid4().hex, free_port()
        env = dict(os.environ, RAG_INTERNAL_TOKEN=internal_token, RAG_INSTANCE_ID=instance_id,
                   RAG_BACKEND="milvus", RAG_DATA_FILE=str(restored.rag_file), RAG_MILVUS_COLLECTION=restored.rag_collection,
                   RAG_MILVUS_URI=uri, RAG_MILVUS_TOKEN=milvus_token, RAG_EMBEDDING_BACKEND="fastembed",
                   RAG_EMBEDDING_MODEL=BGE_MODEL, RAG_DENSE_DIMENSION="512", PYTHONUTF8="1",
                   RAG_SEMANTIC_DENSE_MIN_SCORE="0.55", RAG_HASHED_DENSE_MIN_SCORE="0.05", RAG_DEFAULT_TOP_K="5",
                   NO_PROXY="127.0.0.1,localhost", no_proxy="127.0.0.1,localhost", no_grpc_proxy="127.0.0.1,localhost")
        if gate.args.model_cache:
            env["FASTEMBED_CACHE_PATH"] = str(gate.args.model_cache)
        rag_process = gate.start_process([gate.args.rag_python, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(port)], env, "rag-service", AI_ROOT)
        rag_url = f"http://127.0.0.1:{port}"
        def rag_ready():
            require(rag_process.poll() is None, "RAG process exited before readiness")
            try:
                return gate.request(rag_url, "/health", timeout=2)[0] == 200
            except OSError:
                return False
        target_process = None
        try:
            gate.wait(rag_ready, timeout=600)
            target_redis_password = gate.secret()
            target_redis = gate.create("target-redis", "redis:7.4.2-alpine", env={"REDIS_PASSWORD": target_redis_password}, ports=(6379,),
                                      command=("sh", "-c", 'exec redis-server --appendonly no --requirepass "$REDIS_PASSWORD"'))
            backend_env = gate.backend_environment(restored.database.port, restored.database.password, gate.port(target_redis, 6379), target_redis_password, restored.uploads_root,
                                                   rag_url=rag_url, rag_token=internal_token)
            target_process, target_url = gate.backend(backend_env, "target-backend")
            require(gate.request(target_url, f"/file/{int(source['photo']['id'])}/content", token=source["token"])[0] == 401,
                    "A source session survived into the fresh recovery application")
            token = gate.login(target_url, source["phone"], source["password"])
            gate.event("application-reindex-and-retrieval")
            gate.api(target_url, "/ai/policy/reindex", method="POST", token=token, timeout=180)
            def synced():
                return restored.database.docker.sql(restored.database.container, "SELECT COUNT(*) FROM policy_rag_sync WHERE status <> 'SYNCED'") == "0"
            gate.wait(synced, timeout=180)
            status, raw = gate.request(rag_url, "/v2/diagnostics", headers={"X-Internal-Token": internal_token})
            require(status == 200, "Authenticated RAG diagnostics failed")
            diagnostics = json.loads(raw)
            require(diagnostics.get("instance_id") == instance_id and diagnostics.get("backend") == "milvus-hybrid"
                    and diagnostics.get("embedding_loaded") is True and diagnostics.get("embedding_backend") == BGE_MODEL
                    and diagnostics.get("dimension") == 512 and diagnostics.get("degraded") is False
                    and diagnostics.get("fallback_code") is None and diagnostics.get("embedding_fallback_code") is None
                    and diagnostics.get("milvus_collection") == restored.rag_collection + "_v2"
                    and diagnostics.get("milvus_collection_generation") and diagnostics.get("model", {}).get("onnx_files"),
                    "Real BGE/Milvus identity or healthy non-fallback state not established")
            identity = f"{uri}|{restored.rag_collection}_v2|{diagnostics['milvus_collection_generation']}|{BGE_MODEL}|{BGE_MODEL}|512"
            namespace = "milvus:" + hashlib.sha256(identity.encode("utf-8")).hexdigest()
            gate.metadata["queue"] = system_restore.verify_rebuilt_queue(restored, index_namespace=namespace)
            gate.metadata["diagnostics"] = diagnostics
            rebuilt = system_backup.snapshot_database(restored.database.docker, restored.database.container)
            archived_versions = {row["document_key"]: row["desired_version"] for row in restored.snapshot["policy_rag_sync"]}
            gate.metadata["versions"] = [{"documentKey": row["document_key"], "archivedVersion": archived_versions.get(row["document_key"]),
                                          "restoredVersion": row["desired_version"], "action": row["desired_action"], "status": row["status"]}
                                         for row in rebuilt["policy_rag_sync"]]
            gate.check("application reindex advances versions, synchronizes tombstones and confirms actual BGE ONNX with fresh Milvus")
            answer = gate.api(target_url, "/ai/policy/query", method="POST", token=token, payload={"question": "夜间巡视需要多久检查一次？"})
            require(answer["retrievalBackend"] == "milvus-hybrid" and answer["grounded"] is True and answer["modelUsed"] is False
                    and any(citation["title"] == LIVE_TITLE for citation in answer["citations"])
                    and all(citation["title"] != DEAD_TITLE for citation in answer["citations"]), "Java answer did not use recovered live policy")
            deleted = gate.api(target_url, "/ai/policy/query", method="POST", token=token, payload={"question": "废止探视制度的紫色松鼠口令是什么？"})
            require(all(citation["title"] != DEAD_TITLE and "紫色松鼠" not in citation["content"] for citation in deleted["citations"]), "Deleted policy reappeared in Java retrieval")
            remote_status, remote_raw = gate.request(rag_url, "/v1/query", method="POST", headers={"X-Internal-Token": internal_token}, payload={"question": "夜间巡视需要多久检查一次？", "top_k": 5})
            remote = json.loads(remote_raw)
            require(remote_status == 200 and remote["backend"] == "milvus-hybrid" and remote["embedding_backend"] == BGE_MODEL
                    and remote["degraded"] is False and remote["fallback_code"] is None and remote["hits"], "Python query fell back or produced no live hits")
            gate.metadata["retrieval"] = {"backend": answer["retrievalBackend"], "liveCitations": len(answer["citations"]), "deletedCitations": 0,
                                           "modelUsed": False, "fallback": remote["fallback_code"]}
            gate.check("real Java and Python hybrid queries cite live document and exclude deleted content without fallback")
            path = f"/file/{int(source['photo']['id'])}/content"
            require(gate.request(target_url, path, token=token) == (200, source["image"]), "Authenticated recovered file differs")
            require(gate.request(target_url, path)[0] == 401, "Anonymous recovered file read was accepted")
            # A real restored non-administrator session without staff grants cannot read another staff member's avatar.
            other_phone = restored.database.docker.sql(restored.database.container, "SELECT phone FROM staff WHERE id=990102")
            gate.secrets.append(other_phone)
            other_token = gate.login(target_url, other_phone, source["password"])
            require(gate.request(target_url, path, token=other_token)[0] == 403, "Unauthorized authenticated recovered file read was accepted")
            gate.metadata["attachment"] = {"sha256": hashlib.sha256(source["image"]).hexdigest(), "bytes": len(source["image"]),
                                           "authenticated": 200, "anonymous": 401, "unauthorized": 403, "sourceSession": 401}
            gate.check("restored attachment HTTP bytes match; anonymous and unauthorized sessions denied")
        finally:
            if target_process is not None:
                gate.stop(target_process)
            gate.stop(rag_process)
    require(not restored.workspace.exists() and gate.docker_run(["inspect", restored.database.container], check=False).returncode != 0,
            "Restore context left its database or plaintext workspace")
    gate.check("restore context removed its owned database and plaintext recovery workspace")


def exercise(gate):
    source = initialize_source(gate)
    gate.event("encrypted-whole-system-backup")
    before = gate.checksum(source["container"])
    gate.metadata["sourceChecksumBefore"] = hashlib.sha256(before.encode()).hexdigest()
    directory = gate.directory / "encrypted"
    keys = gate.directory / "keys"
    directory.mkdir(mode=0o700)
    keys.mkdir(mode=0o700)
    package, key = directory / "system.scb", keys / "recovery.key"
    # Missing live bytes are a hard failure, even though a retired historical row may be absent.
    path = Path(gate.docker.sql(source["container"], "SELECT path FROM base_attachment WHERE id=" + str(int(source["photo"]["id"]))))
    bytes_before = path.read_bytes()
    path.unlink()
    try:
        try:
            system_backup.create_backup(source["container"], source["project"], source["uploads"], source["project"] / "missing-rag.json", directory / "missing.scb", keys / "missing.key", writers_stopped=True)
        except system_backup.BackupError:
            pass
        else:
            raise AssertionError("Missing active attachment was accepted")
        require(not (directory / "missing.scb").exists() and not (keys / "missing.key").exists(), "Failed backup published outputs")
    finally:
        path.write_bytes(bytes_before)
    gate.check("missing active attachment prevents publication")
    result = system_backup.create_backup(source["container"], source["project"], source["uploads"], source["project"] / "missing-rag.json", package, key, writers_stopped=True)
    gate.metadata["backup"] = {k: v for k, v in result.items() if k not in ("package", "keyFile")}
    require(gate.checksum(source["container"]) == before, "Backup changed source tables")
    with system_backup.verified_package(package, key) as (_, manifest):
        require(manifest["rag"]["state"] == "missing" and manifest["rag"]["rebuildRequired"] is True, "Missing RAG state was concealed")
        require("Redis sessions" in manifest["excluded"] and "derived Milvus vectors" in manifest["excluded"], "Derived/session exclusions missing")
    gate.check("encrypted package verifies complete inventory and declares fresh RAG rebuild; source checksum unchanged")
    wrong_key = keys / "wrong.key"
    wrong_key.write_bytes(secrets.token_bytes(32))
    tampered = directory / "tampered.scb"
    content = bytearray(package.read_bytes())
    content[len(content) // 2] ^= 1
    tampered.write_bytes(content)
    for invalid_package, invalid_key in ((package, wrong_key), (tampered, key)):
        containers = gate.docker_run(["ps", "-aq", "--no-trunc", "--filter", "label=" + database.OWNER_LABEL]).stdout
        started = time.time()
        try:
            with system_restore.restored_system(invalid_package, invalid_key, gate.private, publish_database=True):
                raise AssertionError("Unauthenticated package reached restore target")
        except system_backup.BackupError:
            pass
        events = gate.docker_run(["events", "--since", f"{started:.9f}", "--until", f"{time.time():.9f}",
                                  "--filter", "type=container", "--filter", "label=" + database.OWNER_LABEL,
                                  "--format", "{{json .}}"], timeout=30).stdout.decode().splitlines()
        require(not any(json.loads(event).get("Action") in ("create", "start") for event in events), "Bad package created a target")
        require(containers == gate.docker_run(["ps", "-aq", "--no-trunc", "--filter", "label=" + database.OWNER_LABEL]).stdout, "Bad package left a restore target")
    gate.check("wrong key and modified ciphertext rejected before any restore container creation")
    verify_target(gate, source, package, key)
    after = gate.checksum(source["container"])
    require(after == before, "Restore or target requests modified source tables")
    gate.metadata["sourceChecksumAfter"] = hashlib.sha256(after.encode()).hexdigest()
    require(path.read_bytes() == bytes_before, "Restore changed source file bytes")
    gate.check("complete recovery and target requests leave source data and uploads unchanged")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--disposable-test", action="store_true")
    parser.add_argument("--java", default=os.environ.get("SYSTEM_DRILL_JAVA"))
    parser.add_argument("--rag-python", default=os.environ.get("SYSTEM_DRILL_RAG_PYTHON", sys.executable))
    parser.add_argument("--model-cache", type=Path, default=os.environ.get("FASTEMBED_CACHE_PATH"))
    args = parser.parse_args()
    if not args.disposable_test:
        parser.error("--disposable-test is required; no existing service is accepted")
    args.rag_python = str(Path(args.rag_python).resolve())
    if args.model_cache:
        args.model_cache = args.model_cache.resolve()
    gate = Gate(args)
    print("Evidence: " + str(gate.directory), flush=True)
    try:
        exercise(gate)
        gate.status = "verified"
    except Exception as error:
        gate.status = "failed"
        gate.events.append({"step": gate.step, "errorClass": type(error).__name__})
        # Assertion text is authored by this driver. Third-party exception strings
        # can contain SQL, credentials, endpoint values or source records.
        if isinstance(error, AssertionError):
            gate.events[-1]["assertion"] = str(error)
    finally:
        gate.cleanup()
    print(json.dumps({"status": gate.status, "checks": len(gate.checks), "evidence": str(gate.directory)}), flush=True)
    return 0 if gate.status == "verified" else 1


if __name__ == "__main__":
    raise SystemExit(main())
