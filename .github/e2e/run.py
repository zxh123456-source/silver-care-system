"""Provision only a disposable test database, start the built backend, and run browser checks."""
import argparse
import json
import os
import secrets
import shutil
import subprocess
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

from browser_smoke import run_browser

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--disposable-test", action="store_true")
    parser.add_argument("--dist", type=Path, default=ROOT / "源码/beadhouse-frontend/dist")
    args = parser.parse_args()
    if not args.disposable_test:
        parser.error("Requires --disposable-test: this runner replaces the demo database in the supplied test container")
    container = os.environ.get("E2E_MYSQL_CONTAINER", "")
    if not container:
        parser.error("Set E2E_MYSQL_CONTAINER to a disposable MySQL container")
    # Local runs must name their container explicitly as E2E; hosted Actions supplies its own service ID.
    if os.environ.get("GITHUB_ACTIONS") != "true" and not container.startswith("silvercare-e2e-"):
        parser.error("Local test container name must start with silvercare-e2e-")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else shutil.which("java")
    docker = shutil.which("docker")
    jar = ROOT / "源码/beadhouse-backend/target/beadhouse-backend-0.0.1-SNAPSHOT.jar"
    if not java or not docker or not jar.is_file() or not (args.dist / "index.html").is_file():
        parser.error("Requires Java 8, Docker, a built backend JAR and frontend dist")
    evidence = ROOT / ".runtime/e2e" / uuid.uuid4().hex
    evidence.mkdir(parents=True, exist_ok=True)
    print(f"Evidence: {evidence}", flush=True)
    env = dict(os.environ)
    env.update(DB_URL="jdbc:mysql://127.0.0.1:13318/db_beadhouse?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
               DB_USERNAME="root", DB_PASSWORD="scope-test-only", REDIS_PASSWORD="scope-redis-only",
               SPRING_REDIS_PORT="16390", JWT_SECRET=secrets.token_hex(32),
               DEMO_ACCOUNT_PASSWORD=secrets.token_hex(24), AI_RAG_ENABLED="false", AI_PROVIDER_ENABLED="false",
               PASSWORD_RESET_EMAIL_ENABLED="false", TZ="Asia/Shanghai", NO_PROXY="127.0.0.1,localhost")
    redis_name = "silvercare-e2e-redis-" + uuid.uuid4().hex
    redis_created, backend, failed = False, None, True
    started = time.monotonic()
    checks = []
    creationflags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    log = (evidence / "backend.log").open("w", encoding="utf-8")
    try:
        subprocess.run([docker, "inspect", container], check=True, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, creationflags=creationflags)
        for _ in range(60):
            ready = subprocess.run([docker, "exec", "-e", "MYSQL_PWD=scope-test-only", container,
                                    "mysql", "-h127.0.0.1", "-uroot", "-N", "-e", "SELECT 1"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=creationflags)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Disposable MySQL did not become ready with the test credentials")
        # Reset AI tables too; importing the legacy dump alone does not remove them on a rerun.
        subprocess.run([docker, "exec", "-e", "MYSQL_PWD=scope-test-only", container,
                        "mysql", "-uroot", "-e", "DROP DATABASE IF EXISTS db_beadhouse; CREATE DATABASE db_beadhouse CHARACTER SET utf8mb4;"],
                       check=True, stdout=subprocess.DEVNULL, creationflags=creationflags)
        for name in ("db_beadhouse.sql", "ai_care_upgrade.sql", "daily_task_upgrade.sql", "care_alert_upgrade.sql", "recharge_ledger_upgrade.sql"):
            subprocess.run([docker, "exec", "-i", "-e", "MYSQL_PWD=scope-test-only", container,
                            "mysql", "--default-character-set=utf8mb4", "-uroot", "db_beadhouse"],
                           input=(ROOT / "数据库" / name).read_bytes(), check=True, stdout=subprocess.DEVNULL,
                           creationflags=creationflags)
        subprocess.run([docker, "run", "-d", "--name", redis_name, "-p", "127.0.0.1:16390:6379",
                        "redis:7.4.2-alpine", "redis-server", "--appendonly", "no", "--requirepass", "scope-redis-only"],
                       check=True, stdout=subprocess.DEVNULL, creationflags=creationflags)
        redis_created = True
        for _ in range(30):
            ping = subprocess.run([docker, "exec", redis_name, "redis-cli", "--no-auth-warning", "-a", "scope-redis-only", "ping"],
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, creationflags=creationflags)
            if ping.returncode == 0 and b"PONG" in ping.stdout:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Test Redis did not become ready")
        subprocess.run([java, "-Dloader.main=com.shanzhu.beadhouse.tools.DemoAccountInitializer", "-cp", str(jar),
                        "org.springframework.boot.loader.PropertiesLauncher", "--confirm-demo"],
                       check=True, env=env, stdout=subprocess.DEVNULL, creationflags=creationflags, timeout=30)
        subprocess.run([docker, "exec", "-i", "-e", "MYSQL_PWD=scope-test-only", container,
                        "mysql", "--default-character-set=utf8mb4", "-uroot", "db_beadhouse"],
                       input=(ROOT / ".github/e2e/seed.sql").read_bytes(), check=True,
                       stdout=subprocess.DEVNULL, creationflags=creationflags)
        backend = subprocess.Popen([java, "-jar", str(jar), "--server.port=19001", "--spring.redis.port=16390",
                                    "--spring.quartz.auto-startup=false", "--filesave.windows=./target/e2e-files",
                                    "--filesave.linux=./target/e2e-files"], env=env,
                                   cwd=ROOT / "源码/beadhouse-backend", stdout=log, stderr=log,
                                   creationflags=creationflags)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        for _ in range(60):
            if backend.poll() is not None:
                raise RuntimeError("Test backend exited; inspect backend.log")
            try:
                # Invalid account format exercises readiness without sending an email.
                request = urllib.request.Request("http://127.0.0.1:19001/account/sendCode",
                                                 data=b'{"account":"invalid"}',
                                                 headers={"Content-Type": "application/json"})
                with opener.open(request, timeout=2) as response:
                    if json.load(response).get("code") == 400:
                        break
            except (OSError, ValueError):
                pass
            time.sleep(1)
        else:
            raise RuntimeError("Test backend did not become ready")
        checks = run_browser(args.dist.resolve(), "http://127.0.0.1:19001", env["DEMO_ACCOUNT_PASSWORD"], evidence)
        failed = False
        print(json.dumps({"status": "PASS", "checks": len(checks)}, ensure_ascii=False))
    except Exception as failure:
        (evidence / "failure.txt").write_text(str(failure), encoding="utf-8")
        raise
    finally:
        if backend is not None:
            backend.terminate()
            try:
                backend.wait(timeout=20)
            except subprocess.TimeoutExpired:
                backend.kill()
                backend.wait(timeout=10)
        log.close()
        if redis_created:
            subprocess.run([docker, "rm", "-f", redis_name], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, creationflags=creationflags)
        # Retain already completed checks even when a later browser assertion fails.
        check_file = evidence / "checks.json"
        if failed and check_file.exists():
            checks = json.loads(check_file.read_text(encoding="utf-8"))
        report = ET.Element("testsuite", name="browser-e2e", tests=str(len(checks) + int(failed)),
                            failures=str(int(failed)), time=str(round(time.monotonic() - started, 3)))
        for name in checks:
            ET.SubElement(report, "testcase", name=name, classname="browser-e2e")
        if failed:
            case = ET.SubElement(report, "testcase", name="unfinished workflow", classname="browser-e2e")
            ET.SubElement(case, "failure", message="See failure.txt and failure traces")
        ET.ElementTree(report).write(evidence / "junit.xml", encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    main()
