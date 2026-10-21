"""Run lifecycle acceptance in a fresh, isolated copy of tracked working files.

The initial invocation prepares and starts the copy, then leaves it running for
business acceptance. Resume using the printed manifest for status/restart/stop.
No source .env, local config, dependencies, build products or data are copied.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import uuid


PORT_KEYS = ("MysqlPort", "RedisPort", "MilvusPort", "MilvusHealthPort",
             "RagPort", "BackendPort", "FrontendPort")
ENTRY_POINTS = {"start": "启动AI养老院.ps1", "restart": "重启AI养老院.ps1",
                "status": "查看运行状态.ps1", "stop": "停止AI养老院.ps1"}
FORBIDDEN_PARTS = {".runtime", ".venv", "node_modules", "target", "__pycache__", ".git"}
APP_ENV_PREFIXES = ("AI_", "RAG_", "DB_", "JWT_", "MAIL_", "MYSQL_", "REDIS_",
                    "MINIO_", "MILVUS_", "LEGACY_", "FILESAVE_", "SPRING_")


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def save_manifest(path, manifest):
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


def copy_tracked_worktree(source, destination):
    result = subprocess.run(["git", "ls-files", "-z"], cwd=source, check=True,
                            stdout=subprocess.PIPE)
    files = [Path(item.decode("utf-8")) for item in result.stdout.split(b"\0") if item]
    copied = []
    for relative in files:
        if relative.is_absolute() or ".." in relative.parts:
            raise RuntimeError(f"Unsafe tracked path: {relative}")
        if any(part in FORBIDDEN_PARTS for part in relative.parts):
            continue
        if relative.name == "本地配置.ps1" or (
                relative.name.startswith(".env") and relative.name != ".env.example"):
            continue
        original = source / relative
        if not original.is_file():
            continue  # A tracked deletion in the current worktree stays deleted.
        if original.is_symlink() or not original.resolve().is_relative_to(source):
            raise RuntimeError(f"Tracked link escapes clean-copy rules: {relative}")
        target = destination / relative
        if os.name == "nt" and len(str(target)) >= 260:
            raise RuntimeError(f"Clean-copy path reaches Windows MAX_PATH ({len(str(target))} characters); shorten source checkout path: {relative}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(original, target)
        copied.append(relative.as_posix())
    for required in ("启停工具.ps1", "启动AI养老院.ps1", ".env.example"):
        if not (destination / required).is_file():
            raise RuntimeError(f"Required file is not tracked: {required}; stage new implementation files first")
    for relative in (".env", "本地配置.ps1", "源码/beadhouse-ai/.venv",
                     "源码/beadhouse-backend/target", "源码/beadhouse-frontend/node_modules"):
        if (destination / relative).exists():
            raise RuntimeError(f"Clean-copy invariant failed: {relative}")
    return copied


def select_ports():
    # Hold all reservations at once so even sequential OS allocations are unique.
    sockets = []
    try:
        for _ in PORT_KEYS:
            listener = socket.socket()
            listener.bind(("127.0.0.1", 0))
            sockets.append(listener)
        return dict(zip(PORT_KEYS, (item.getsockname()[1] for item in sockets)))
    finally:
        for listener in sockets:
            listener.close()


def isolated_environment(root):
    env = os.environ.copy()
    # Runtime secrets/configuration come exclusively from the new copy's .env.
    example = root / ".env.example"
    keys = set(re.findall(r"^([A-Z][A-Z0-9_]*)=", example.read_text(encoding="utf-8"), re.MULTILINE))
    keys.update(("COMPOSE_PROJECT_NAME", "MYSQL_PORT", "REDIS_PORT", "MILVUS_PORT",
                 "MILVUS_HEALTH_PORT", "RAG_PORT", "BACKEND_PORT", "FRONTEND_PORT",
                 "AI_PROVIDER_API_KEY", "AI_PROVIDER_ENABLED", "RAG_MILVUS_URI"))
    for key in list(env):
        if key in keys or key.upper().startswith(APP_ENV_PREFIXES):
            env.pop(key, None)
    env["PYTHONIOENCODING"] = "utf-8"
    return env


def validate_manifest(manifest, manifest_path):
    if not isinstance(manifest, dict):
        raise RuntimeError("Acceptance manifest must be an object")
    if type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1:
        raise RuntimeError("Unsupported acceptance manifest schema")
    if any(not isinstance(manifest.get(key), str) or not manifest[key]
           for key in ("sourceRoot", "projectRoot", "composeProject")):
        raise RuntimeError("Acceptance manifest is missing its source/project identity")
    if not Path(manifest["sourceRoot"]).is_absolute():
        raise RuntimeError("Acceptance sourceRoot must be absolute")
    source = Path(manifest["sourceRoot"]).resolve()
    manifest_path = manifest_path.resolve()
    run_dir = manifest_path.parent
    expected_parent = source / ".runtime" / "startup-check"
    if (run_dir.parent != expected_parent or not re.fullmatch(r"[a-f0-9]{8}", run_dir.name)
            or manifest_path.name != "acceptance.json"):
        raise RuntimeError("Acceptance manifest must remain in its isolated startup-check run directory")
    project = Path(manifest["projectRoot"])
    expected_project = run_dir / "中文 空格 项目"
    if (not project.is_absolute() or project.resolve() != expected_project
            or expected_project.resolve() != expected_project or not project.is_dir()):
        raise RuntimeError("Acceptance projectRoot must be the run's fresh Chinese/space project copy")
    compose_project = manifest.get("composeProject", "")
    if (not re.fullmatch(r"sc-check-[a-f0-9]{16}", compose_project)
            or not compose_project.removeprefix("sc-check-").startswith(run_dir.name)):
        raise RuntimeError("Acceptance Compose project must match this unique isolated run")
    ports = manifest.get("ports")
    if (not isinstance(ports, dict) or set(ports) != set(PORT_KEYS)
            or any(type(port) is not int or not 0 < port < 65536 for port in ports.values())
            or len(set(ports.values())) != len(PORT_KEYS)):
        raise RuntimeError("Acceptance ports must be seven distinct valid integer ports")
    timeout = manifest.get("timeoutSeconds")
    if type(timeout) is not int or not 1 <= timeout <= 3600:
        raise RuntimeError("Acceptance health timeout must be 1..3600 seconds")
    if not isinstance(manifest.get("operations"), list):
        raise RuntimeError("Acceptance operation history is invalid")


def invoke(manifest, action, manifest_path, *, prepare=False, command_timeout=2400):
    validate_manifest(manifest, manifest_path)
    root = Path(manifest["projectRoot"])
    script = root / ENTRY_POINTS[action]
    if action == "status" and not script.is_file():
        alternatives = list(root.glob("*状态*.ps1"))
        if len(alternatives) == 1:
            script = alternatives[0]
    if not script.is_file():
        raise RuntimeError(f"Missing lifecycle entry point: {script.name}")
    pwsh = shutil.which("pwsh")
    if not pwsh:
        raise RuntimeError("PowerShell 7 (pwsh) is required")
    command = [pwsh, "-NoProfile", "-NonInteractive", "-File", str(script),
               "-ComposeProject", manifest["composeProject"]]
    if action in ("start", "restart"):
        for key, value in manifest["ports"].items():
            command.extend(("-" + key, str(value)))
    if action in ("start", "restart"):
        command.extend(("-TimeoutSeconds", str(manifest["timeoutSeconds"])))
    if prepare:
        command.extend(("-Prepare", "-InitializeDemo", "-AcknowledgeDemoOnly"))
    operation = {"action": "prepare-start" if prepare else action, "startedUtc": utc_now()}
    manifest["operations"].append(operation)
    log = manifest_path.parent / f"{len(manifest['operations']):02d}-{operation['action']}.log"
    operation["log"] = str(log)
    save_manifest(manifest_path, manifest)
    print(f"{operation['action']}: {log}", flush=True)
    try:
        with log.open("w", encoding="utf-8") as output:
            result = subprocess.run(command, cwd=manifest_path.parent,
                                    env=isolated_environment(root), stdout=output,
                                    stderr=subprocess.STDOUT, timeout=command_timeout)
        operation["exitCode"] = result.returncode
    except subprocess.TimeoutExpired:
        operation["timedOut"] = True
        raise RuntimeError(f"Lifecycle command exceeded {command_timeout}s; inspect {log}")
    finally:
        operation["finishedUtc"] = utc_now()
        save_manifest(manifest_path, manifest)
    if result.returncode:
        raise RuntimeError(f"Lifecycle {action} failed (exit {result.returncode}); inspect {log}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--manifest", type=Path, help="Resume an existing isolated acceptance copy")
    parser.add_argument("--action", choices=("fresh", "start", "status", "restart", "stop"), default="fresh")
    parser.add_argument("--timeout-seconds", type=int, default=300)
    parser.add_argument("--command-timeout-seconds", type=int, default=2400)
    parser.add_argument("--prepare", action="store_true", help="Retry initial dependency/demo preparation with --action start in the existing isolated copy")
    parser.add_argument("--keep-running", action="store_true", help="Explicitly document the default: retain services for business acceptance")
    args = parser.parse_args()
    if args.prepare and args.action not in ("fresh", "start"):
        parser.error("--prepare is valid only with fresh/start")
    if args.action == "fresh":
        if args.manifest:
            parser.error("fresh cannot reuse --manifest")
        source = args.source_root.resolve()
        run_id = uuid.uuid4().hex
        run_dir = source / ".runtime" / "startup-check" / run_id[:8]
        project = run_dir / "中文 空格 项目"
        project.mkdir(parents=True)
        manifest_path = run_dir / "acceptance.json"
        manifest = {"schemaVersion": 1, "createdUtc": utc_now(), "sourceRoot": str(source),
                    "projectRoot": str(project), "composeProject": "sc-check-" + run_id[:16],
                    "ports": select_ports(), "timeoutSeconds": args.timeout_seconds,
                    "pwsh": shutil.which("pwsh"), "operations": []}
        if not manifest["pwsh"]:
            raise RuntimeError("PowerShell 7 (pwsh) is required")
        manifest["copiedFiles"] = copy_tracked_worktree(source, project)
        save_manifest(manifest_path, manifest)
        print(f"MANIFEST={manifest_path}\nPROJECT_ROOT={project}", flush=True)
        invoke(manifest, "start", manifest_path, prepare=True,
               command_timeout=args.command_timeout_seconds)
        invoke(manifest, "status", manifest_path, command_timeout=args.command_timeout_seconds)
        print("Fresh startup completed; services remain running for business acceptance.", flush=True)
    else:
        if not args.manifest:
            parser.error("resuming requires --manifest")
        manifest_path = args.manifest.resolve()
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        invoke(manifest, args.action, manifest_path, prepare=args.prepare,
               command_timeout=args.command_timeout_seconds)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
        print(f"Acceptance failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
