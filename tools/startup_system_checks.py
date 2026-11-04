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
import time
import urllib.request
import uuid

from startup_acceptance import invoke, isolated_environment, validate_manifest

ROOT = Path(__file__).resolve().parents[1]


def process_key(item):
    created = datetime.fromisoformat(item["createdUtc"].replace("Z", "+00:00"))
    return item["pid"], created.astimezone(timezone.utc).isoformat(timespec="microseconds")


def new_process_identities(before, observed):
    original = {process_key(item) for item in before}
    return [item for item in observed if process_key(item) not in original]


def running_project_containers(docker, compose_project, project):
    ids = subprocess.check_output([docker, "ps", "-aq", "--filter",
                                   "label=com.docker.compose.project=" + compose_project], text=True).split()
    if not ids:
        return []
    result = []
    for item in json.loads(subprocess.check_output([docker, "inspect", *ids])):
        labels = item["Config"]["Labels"]
        if (labels.get("com.docker.compose.project") != compose_project
                or Path(labels.get("com.docker.compose.project.working_dir", "")).resolve() != project):
            raise ValueError("Container ownership mismatch; timeout baseline was not captured")
        if item["State"]["Running"]:
            result.append(item["Id"])
    return result


def run_monitored_command(command, *, cwd, env, log, timeout, observe, on_timeout):
    """Keep output even on timeout; cleanup is restricted by the caller's baseline."""
    with log.open("wb") as output:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + timeout
        try:
            while process.poll() is None:
                observe()
                if time.monotonic() >= deadline:
                    raise TimeoutError(f"Lifecycle command exceeded {timeout}s; output retained: {log}")
                time.sleep(.25)
            observe()
        except BaseException:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=10)
            on_timeout()
            raise
    return subprocess.CompletedProcess(command, process.returncode, log.read_bytes())


def cleanup_timed_out_command(manifest, before, observed, running_before, pwsh, docker, log):
    """Stop only newly created resources, leaving original live services untouched."""
    project = Path(manifest["projectRoot"])
    env = isolated_environment(project)
    env.update(SC_CHECK_HELPER=str(project / "启停工具.ps1"), SC_CHECK_ROOT=str(project),
               SC_CHECK_PROJECT=manifest["composeProject"], SC_CHECK_DOCKER=docker,
               SC_CHECK_NEW=json.dumps(new_process_identities(before, observed)),
               SC_CHECK_RUNNING=json.dumps(running_before))
    script = """
$ErrorActionPreference='Stop'
. $env:SC_CHECK_HELPER
$items=@($env:SC_CHECK_NEW | ConvertFrom-Json -AsHashtable)
$running=@($env:SC_CHECK_RUNNING | ConvertFrom-Json)
foreach($item in @($items | Sort-Object {if($_.name -like '*-logger'){1}else{0}})) {
    Stop-OwnedProcess $item
}
foreach($container in @(Get-LifecycleContainers $env:SC_CHECK_DOCKER $env:SC_CHECK_PROJECT $env:SC_CHECK_ROOT)) {
    if($container.running -and $container.id -notin $running) { Stop-OwnedContainer $container $env:SC_CHECK_DOCKER }
}
$path=Join-Path $env:SC_CHECK_ROOT ".runtime/lifecycle-$env:SC_CHECK_PROJECT.json"
$state=Read-LifecycleState -Path $path -ProjectRoot $env:SC_CHECK_ROOT -ComposeProject $env:SC_CHECK_PROJECT
if($state) {
    $state.processes=@($state.processes | Where-Object {Test-ProcessIdentity $_})
    $registered=@($state.containers | ForEach-Object {$_.id})
    $state.containers=@(Get-LifecycleContainers $env:SC_CHECK_DOCKER $env:SC_CHECK_PROJECT $env:SC_CHECK_ROOT |
        Where-Object {$_.id -in $registered})
    Save-LifecycleState $state $path
}
"""
    with log.open("wb") as output:
        subprocess.run([pwsh, "-NoProfile", "-NonInteractive", "-Command", script], env=env,
                       stdout=output, stderr=subprocess.STDOUT, timeout=90, check=True)


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
        def observe():
            if state_path.exists():
                try:
                    state()
                except FileNotFoundError:
                    pass  # A successful stop atomically removed its state meanwhile.

        observe()
        before = list(observed_processes.values())
        running_before = running_project_containers(docker, manifest["composeProject"], project)
        log = manifest_path.parent / f"direct-{action}-{uuid.uuid4().hex}.log"

        def cleanup():
            observe()
            cleanup_timed_out_command(manifest, before, list(observed_processes.values()), running_before,
                                      pwsh, docker, log.with_suffix(".cleanup.log"))

        return run_monitored_command(command, cwd=manifest_path.parent, env=isolated_environment(project),
                                     log=log, timeout=timeout * 6 + 90, observe=observe, on_timeout=cleanup)

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
        vite_config = project / "源码/beadhouse-frontend/vite.config.mts"
        original = vite_config.read_bytes()
        frontend_error = project / f'.runtime/logs/{manifest["composeProject"]}/frontend.err.log'
        frontend_error.write_bytes(b"")
        try:
            vite_config.write_bytes(b"throw new Error('synthetic partial repair failure');\n" + original)
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
            vite_config.write_bytes(original)
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
        vite_config = project / "源码/beadhouse-frontend/vite.config.mts"
        original_vite = vite_config.read_bytes()
        old_process_keys = set(observed_processes)
        frontend_error = project / f'.runtime/logs/{manifest["composeProject"]}/frontend.err.log'
        if frontend_error.exists():
            frontend_error.write_bytes(b"")
        try:
            vite_config.write_bytes(b"throw new Error('synthetic startup acceptance failure');\n" + original_vite)
            result = direct("start", timeout=20)
            (manifest_path.parent / "fault-frontend-rollback.log").write_bytes(result.stdout)
            assert result.returncode != 0, "Faulty frontend unexpectedly passed health checks"
            assert frontend_error.is_file() and b"synthetic startup acceptance failure" in frontend_error.read_bytes(), \
                "Startup failed before the injected frontend fault; that is not a rollback acceptance"
            new_names = {item["name"] for key, item in observed_processes.items() if key not in old_process_keys}
            assert {"rag", "backend"}.issubset(new_names), "Did not observe the new applications that rollback must stop"
            assert_stopped(state())
        finally:
            vite_config.write_bytes(original_vite)
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
