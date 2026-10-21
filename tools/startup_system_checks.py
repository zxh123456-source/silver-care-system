"""Real lifecycle fault checks, restricted to an existing isolated acceptance copy.

Requires the clean-copy runner's manifest. Never selects containers by a user
service name or removes volumes. Every fault is restored before returning.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
from types import SimpleNamespace
import urllib.request

from startup_acceptance import invoke, isolated_environment, validate_manifest

ROOT = Path(__file__).resolve().parents[1]


def process_key(item):
    created = datetime.fromisoformat(item["createdUtc"].replace("Z", "+00:00"))
    return item["pid"], created.astimezone(timezone.utc).isoformat(timespec="microseconds")


def run(manifest_path, phase):
    manifest_path = manifest_path.resolve()
    if not manifest_path.is_relative_to((ROOT / ".runtime/startup-check").resolve()):
        raise ValueError("Only this repository's disposable startup copies may be tested")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest, manifest_path)
    project = Path(manifest["projectRoot"]).resolve()
    if project != manifest_path.parent / "中文 空格 项目" or not manifest["composeProject"].startswith("sc-check-"):
        raise ValueError("Isolated manifest identity mismatch")
    state_path = project / f'.runtime/lifecycle-{manifest["composeProject"]}.json'
    docker = shutil.which("docker.exe") or shutil.which("docker")
    if not docker:
        raise ValueError("Docker executable required")
    checks = []
    observed_processes = {}
    pwsh = shutil.which("pwsh")
    if not pwsh:
        raise ValueError("PowerShell 7 required")

    def record(name):
        checks.append(name)
        print("PASS: " + name, flush=True)
        (manifest_path.parent / f"system-{phase}.json").write_text(
            json.dumps({"status": "IN_PROGRESS", "checks": checks}, ensure_ascii=False, indent=2), encoding="utf-8")

    def state():
        value = json.loads(state_path.read_text(encoding="utf-8"))
        for item in value["processes"]:
            observed_processes[process_key(item)] = item
        return value

    def identities(value):
        return sorted((p["name"], *process_key(p)) for p in value["processes"])

    def containers(value):
        results = []
        for item in value["containers"]:
            raw = subprocess.check_output([docker, "inspect", item["id"]])
            live = json.loads(raw)[0]
            labels = live["Config"]["Labels"]
            assert labels["com.docker.compose.project"] == manifest["composeProject"]
            assert Path(labels["com.docker.compose.project.working_dir"]).resolve() == project
            results.append((live["Id"], live["State"]["Running"]))
        return sorted(results)

    def direct(action, *, timeout=300, reuse_ports=False):
        script = {"start": "启动AI养老院.ps1", "restart": "重启AI养老院.ps1", "stop": "停止AI养老院.ps1"}[action]
        command = [pwsh, "-NoProfile", "-NonInteractive", "-File", str(project / script),
                   "-ComposeProject", manifest["composeProject"]]
        if action != "stop":
            command.extend(("-TimeoutSeconds", str(timeout)))
            if not reuse_ports:
                for key, value in manifest["ports"].items():
                    command.extend(("-" + key, str(value)))
        with tempfile.TemporaryFile() as output:
            process = subprocess.Popen(command, cwd=manifest_path.parent, env=isolated_environment(project),
                                       stdout=output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + timeout * 6 + 90
            while process.poll() is None:
                if state_path.exists():
                    try:
                        state()  # Observe identities before rollback or stop removes them.
                    except FileNotFoundError:
                        pass  # A successful stop atomically removed its state meanwhile.
                if time.monotonic() >= deadline:
                    process.kill()
                    process.wait(timeout=10)
                    # The product stop entry point validates every saved identity itself.
                    with (manifest_path.parent / "timeout-owned-stop.log").open("wb") as cleanup:
                        subprocess.run([pwsh, "-NoProfile", "-File", str(project / "停止AI养老院.ps1"),
                                        "-ComposeProject", manifest["composeProject"]],
                                       env=isolated_environment(project), stdout=cleanup,
                                       stderr=subprocess.STDOUT, timeout=90, check=True)
                    raise TimeoutError("Lifecycle command timed out; tracked services were stopped, evidence retained")
                time.sleep(.25)
            output.seek(0)
            return SimpleNamespace(returncode=process.returncode, stdout=output.read())

    def assert_stopped(value):
        assert not any(running for _, running in containers(value)), "An owned container remained running"
        env = isolated_environment(project)
        env["SC_CHECK_HELPER"] = str(project / "启停工具.ps1")
        env["SC_CHECK_IDENTITIES"] = json.dumps(list(observed_processes.values()))
        probe = subprocess.run([pwsh, "-NoProfile", "-Command",
            ". $env:SC_CHECK_HELPER; $items=$env:SC_CHECK_IDENTITIES | ConvertFrom-Json -AsHashtable; "
            "$live=@($items | Where-Object {Test-ProcessIdentity $_}); exit $live.Count"],
            env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        assert probe.returncode == 0, "A previously tracked application or log supervisor remained alive"
        deadline = time.monotonic() + 15
        while True:
            open_ports = []
            for port in manifest["ports"].values():
                with socket.socket() as client:
                    client.settimeout(.2)
                    if client.connect_ex(("127.0.0.1", port)) == 0:
                        open_ports.append(port)
            if not open_ports:
                return
            if time.monotonic() >= deadline:
                raise AssertionError(f"Owned ports remained listening: {open_ports}")
            time.sleep(.2)

    env_file = project / ".env"
    if phase == "running":
        before = state()
        expected_processes, expected_containers = identities(before), containers(before)
        invoke(manifest, "start", manifest_path)
        assert identities(state()) == expected_processes
        assert containers(state()) == expected_containers
        record("repeated start retained exact process creation identities and container IDs")

        original = env_file.read_bytes()
        try:
            env_file.write_bytes(original + b"\nJWT_SECRET=disposable-config-conflict-" + os.urandom(32).hex().encode() + b"\n")
            result = direct("start")
            (manifest_path.parent / "fault-config-conflict.log").write_bytes(result.stdout)
            assert result.returncode != 0, "Changed live configuration was silently accepted"
            assert identities(state()) == expected_processes
            assert containers(state()) == expected_containers
        finally:
            env_file.write_bytes(original)
        record("live configuration changes were rejected without changing any running resource")

        result = direct("restart", reuse_ports=True)
        (manifest_path.parent / "restart-recorded-ports.log").write_bytes(result.stdout)
        assert result.returncode == 0, "Restart without explicit port arguments failed"
        assert state()["ports"] == before["ports"]
        assert identities(state()) != expected_processes
        assert [c for c, _ in containers(state())] == [c for c, _ in expected_containers]
        invoke(manifest, "status", manifest_path)
        record("restart reused recorded non-default ports and volumes without explicit port arguments")
    elif phase == "repair":
        before = state()
        expected_containers = containers(before)
        survivors = {"rag", "rag-logger", "backend", "backend-logger"}
        expected_survivors = identities({"processes": [p for p in before["processes"] if p["name"] in survivors]})
        env = isolated_environment(project)
        env["SC_CHECK_HELPER"] = str(project / "启停工具.ps1")
        env["SC_CHECK_IDENTITIES"] = json.dumps([p for p in before["processes"] if p["name"] in {"frontend", "frontend-logger"}])
        stopped = subprocess.run([pwsh, "-NoProfile", "-Command",
            "$ErrorActionPreference='Stop'; . $env:SC_CHECK_HELPER; "
            "$items=$env:SC_CHECK_IDENTITIES | ConvertFrom-Json -AsHashtable; "
            "$items | Sort-Object {if($_.name -eq 'frontend'){0}else{1}} | ForEach-Object {Stop-OwnedProcess $_}"],
            env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=45)
        assert stopped.returncode == 0, "Could not stop this copy's frontend for the partial repair test"
        vue_config = project / "源码/beadhouse-frontend/vue.config.js"
        original = vue_config.read_bytes()
        frontend_error = project / f'.runtime/logs/{manifest["composeProject"]}/frontend.err.log'
        frontend_error.write_bytes(b"")
        try:
            vue_config.write_bytes(b"throw new Error('synthetic partial repair failure');\n" + original)
            failed = direct("start", timeout=20)
            (manifest_path.parent / "fault-partial-repair.log").write_bytes(failed.stdout)
            assert failed.returncode != 0
            assert b"synthetic partial repair failure" in frontend_error.read_bytes(), "Did not reach the injected repair fault"
            assert identities(state()) == expected_survivors, "Failed repair changed an originally healthy application"
            assert containers(state()) == expected_containers, "Failed repair changed originally running infrastructure"
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            for key, path, field, expected in (("RagPort", "/health", "backend", "milvus-hybrid"),
                                                ("BackendPort", "/v2/api-docs", "swagger", "2.0")):
                with opener.open(f'http://127.0.0.1:{manifest["ports"][key]}{path}', timeout=5) as response:
                    assert json.load(response)[field] == expected
        finally:
            vue_config.write_bytes(original)
        record("failed frontend repair preserved exact running Java/RAG identities and all five container IDs, confirmed by live HTTP")
        invoke(manifest, "start", manifest_path)
        invoke(manifest, "status", manifest_path)
        assert identities({"processes": [p for p in state()["processes"] if p["name"] in survivors]}) == expected_survivors
        record("frontend repair succeeded without restarting the original Java/RAG services")
    else:
        before = state()
        original = env_file.read_bytes()
        backup = project / ".runtime/acceptance-env.pending"
        assert not backup.exists()
        env_file.rename(backup)
        try:
            result = direct("stop")
            (manifest_path.parent / "stop-without-env.log").write_bytes(result.stdout)
            assert result.returncode == 0, "Stop required missing credentials"
            assert_stopped(before)
        finally:
            backup.rename(env_file)
        assert env_file.read_bytes() == original
        record("stop with .env missing stopped owned services and preserved credentials and volumes")

        # A real foreign TCP listener must survive preflight failure unchanged.
        listener = socket.socket()
        listener.bind(("127.0.0.1", manifest["ports"]["FrontendPort"]))
        listener.listen()
        try:
            result = direct("start", timeout=15)
            (manifest_path.parent / "fault-foreign-port.log").write_bytes(result.stdout)
            assert result.returncode != 0
            with socket.create_connection(listener.getsockname(), timeout=2):
                pass
            assert not any(running for _, running in containers(before))
        finally:
            listener.close()
        record("foreign listener caused preflight rejection and was left running; no infrastructure was started")

        # A genuine application failure after infrastructure startup exercises rollback.
        vue_config = project / "源码/beadhouse-frontend/vue.config.js"
        original_vue = vue_config.read_bytes()
        old_process_keys = set(observed_processes)
        frontend_error = project / f'.runtime/logs/{manifest["composeProject"]}/frontend.err.log'
        if frontend_error.exists():
            frontend_error.write_bytes(b"")
        try:
            vue_config.write_bytes(b"throw new Error('synthetic startup acceptance failure');\n" + original_vue)
            result = direct("start", timeout=20)
            (manifest_path.parent / "fault-frontend-rollback.log").write_bytes(result.stdout)
            assert result.returncode != 0, "Faulty frontend unexpectedly passed health checks"
            assert frontend_error.is_file() and b"synthetic startup acceptance failure" in frontend_error.read_bytes(), \
                "Startup failed before the injected frontend fault; that is not a rollback acceptance"
            new_names = {item["name"] for key, item in observed_processes.items() if key not in old_process_keys}
            assert {"rag", "backend"}.issubset(new_names), "Did not observe the new applications that rollback must stop"
            assert_stopped(state())
        finally:
            vue_config.write_bytes(original_vue)
        record("real frontend startup failure rolled back newly started applications and containers")
        invoke(manifest, "start", manifest_path)
        invoke(manifest, "status", manifest_path)
        record("the same preserved volumes and artifacts started successfully after removing the fault")
    (manifest_path.parent / f"system-{phase}.json").write_text(
        json.dumps({"status": "PASS", "checks": checks}, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--phase", choices=("running", "recovery", "repair"), required=True)
    args = parser.parse_args()
    run(args.manifest, args.phase)
