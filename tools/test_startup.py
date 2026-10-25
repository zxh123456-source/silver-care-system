"""Offline lifecycle safety regressions; never start Docker or the application.

Run with Python 3.10+ and PowerShell 7 on Windows:
    python -m unittest discover -s tools -p test_startup.py -v
Only processes created by these tests are terminated.
"""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import venv
from unittest import mock

import startup_acceptance
from startup_system_checks import process_key


ROOT = Path(__file__).resolve().parents[1]
PWSH = shutil.which("pwsh")


class EvidenceIdentityTests(unittest.TestCase):
    def test_same_creation_time_survives_json_precision_and_timezone_formatting(self):
        original = {"pid": 9, "createdUtc": "2026-10-21T14:40:18.1995150Z"}
        restored = {"pid": 9, "createdUtc": "2026-10-21T22:40:18.199515+08:00"}
        self.assertEqual(process_key(original), process_key(restored))

    def test_pid_or_real_creation_time_change_is_not_an_idempotent_start(self):
        original = {"pid": 9, "createdUtc": "2026-10-21T14:40:18.199515Z"}
        for changed in ({"pid": 10, "createdUtc": original["createdUtc"]},
                        {"pid": 9, "createdUtc": "2026-10-21T14:40:18.199516Z"}):
            self.assertNotEqual(process_key(original), process_key(changed))


class CleanAcceptanceCopyTests(unittest.TestCase):
    def test_copy_uses_tracked_worktree_changes_but_excludes_secrets_and_old_products(self):
        with tempfile.TemporaryDirectory(prefix="silvercare-copy-") as folder:
            source = Path(folder).resolve() / "source"
            destination = Path(folder).resolve() / "中文 空格 项目"
            source.mkdir()
            subprocess.run(["git", "init", "-q", str(source)], check=True)
            fixtures = {"启停工具.ps1": "helper", "启动AI养老院.ps1": "start",
                        ".env.example": "JWT_SECRET=\n", ".env": "private-secret",
                        ".env.local": "other-private-secret", "本地配置.ps1": "private",
                        "tools/source.py": "original", "源码/beadhouse-backend/target/old.jar": "old-build",
                        "源码/beadhouse-frontend/node_modules/old.js": "old-dependency",
                        "源码/beadhouse-ai/.venv/old.exe": "old-interpreter",
                        ".runtime/database/old.sql": "old-data"}
            for name, content in fixtures.items():
                path = source / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            subprocess.run(["git", "add", "-f", "."], cwd=source, check=True)
            (source / "tools/source.py").write_text("current worktree", encoding="utf-8")
            (source / "untracked.txt").write_text("not reviewed", encoding="utf-8")
            copied = startup_acceptance.copy_tracked_worktree(source, destination)
            self.assertEqual((destination / "tools/source.py").read_text(encoding="utf-8"), "current worktree")
            self.assertEqual(set(copied), {"启停工具.ps1", "启动AI养老院.ps1", ".env.example", "tools/source.py"})
            self.assertEqual(sorted(path.relative_to(destination).as_posix() for path in destination.rglob("*") if path.is_file()), sorted(copied))

    def test_generated_ports_are_distinct_and_valid(self):
        ports = startup_acceptance.select_ports()
        self.assertEqual(set(ports), set(startup_acceptance.PORT_KEYS))
        self.assertEqual(len(set(ports.values())), 7)
        self.assertTrue(all(0 < value < 65536 for value in ports.values()))

    def test_acceptance_environment_discards_inherited_secrets_and_keeps_tool_paths(self):
        with tempfile.TemporaryDirectory(prefix="silvercare-env-") as folder:
            root = Path(folder)
            (root / ".env.example").write_text("JWT_SECRET=\nDB_PASSWORD=\n", encoding="utf-8")
            with mock.patch.dict(os.environ, {"JWT_SECRET": "daily-instance-secret",
                                              "DB_PASSWORD": "daily-instance-password",
                                              "COMPOSE_PROJECT_NAME": "daily-instance",
                                              "AI_PROVIDER_BASE_URL": "daily-provider",
                                              "RAG_UNKNOWN_OPTION": "daily-rag-option",
                                              "SILVERCARE_JAVA": "jdk-path",
                                              "PIP_CACHE_DIR": "shared-cache"}):
                env = startup_acceptance.isolated_environment(root)
                self.assertNotIn("JWT_SECRET", env)
                self.assertNotIn("DB_PASSWORD", env)
                self.assertNotIn("COMPOSE_PROJECT_NAME", env)
                self.assertNotIn("AI_PROVIDER_BASE_URL", env)
                self.assertNotIn("RAG_UNKNOWN_OPTION", env)
                self.assertEqual(env["SILVERCARE_JAVA"], "jdk-path")
                self.assertEqual(env["PIP_CACHE_DIR"], "shared-cache")
                self.assertEqual(os.environ["DB_PASSWORD"], "daily-instance-password")

    def test_manifest_cannot_redirect_resume_to_daily_project_or_foreign_compose(self):
        with tempfile.TemporaryDirectory(prefix="silvercare-manifest-") as folder:
            source = Path(folder).resolve()
            run_dir = source / ".runtime/startup-check/1234abcd"
            project = run_dir / "中文 空格 项目"
            project.mkdir(parents=True)
            path = run_dir / "acceptance.json"
            manifest = {"schemaVersion": 1, "sourceRoot": str(source), "projectRoot": str(project),
                        "composeProject": "sc-check-1234abcd1234abcd", "ports": startup_acceptance.select_ports(),
                        "timeoutSeconds": 300, "operations": []}
            startup_acceptance.validate_manifest(manifest, path)
            variants = [{**manifest, "projectRoot": str(source)},
                        {**manifest, "sourceRoot": str(source.parent)},
                        {**manifest, "composeProject": "daily-project"},
                        {**manifest, "composeProject": "sc-check-ffffffffffffffff"},
                        {**manifest, "schemaVersion": True},
                        {**manifest, "timeoutSeconds": 0},
                        {**manifest, "ports": {**manifest["ports"], "RagPort": True}},
                        {**manifest, "ports": {**manifest["ports"], "RagPort": manifest["ports"]["BackendPort"]}},
                        {**manifest, "ports": {"RagPort": 8001}}]
            for index, altered in enumerate(variants):
                with self.subTest(case=index):
                    with self.assertRaises(RuntimeError):
                        startup_acceptance.validate_manifest(altered, path)
            with self.assertRaises(RuntimeError):
                startup_acceptance.validate_manifest(manifest, source / "acceptance.json")


@unittest.skipUnless(os.name == "nt" and PWSH, "Windows and PowerShell 7 required")
class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="silvercare-startup-")
        self.folder = Path(self.temp.name).resolve()
        self.root = self.folder / "中文 空格 项目"
        self.root.mkdir()
        self.workers = []

    def tearDown(self):
        for worker in self.workers:
            if worker.poll() is None:
                worker.terminate()
            worker.wait(timeout=10)
            worker.stdout.close()
            worker.stderr.close()
        self.temp.cleanup()

    def ps(self, body, *, success=True, extra=None, detached_output=False):
        script = self.folder / "外部 调用.ps1"
        script.write_text(
            "$ErrorActionPreference='Stop'\n"
            ". $env:SC_HELPER\n" + body, encoding="utf-8")
        env = os.environ.copy()
        env.update(SC_HELPER=str(ROOT / "启停工具.ps1"),
                   SC_ROOT=str(self.root), SC_PYTHON=sys.executable)
        if extra:
            env.update({key: str(value) for key, value in extra.items()})
        command = [PWSH, "-NoProfile", "-NonInteractive", "-File", str(script)]
        if detached_output:
            # An orphan logger inherits handles. Files let us wait for just the
            # killed caller, then recover the logger in a separate invocation.
            with (self.folder / "caller.stdout").open("w+b") as out, (self.folder / "caller.stderr").open("w+b") as err:
                result = subprocess.run(command, cwd=self.folder, env=env, stdout=out, stderr=err, timeout=40)
                out.seek(0)
                err.seek(0)
                result.stdout = out.read().decode("utf-8")
                result.stderr = err.read().decode("utf-8")
        else:
            result = subprocess.run(command, cwd=self.folder, env=env, text=True, encoding="utf-8",
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=40)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def result(self, body, **kwargs):
        result = self.ps(body + '\n"SC_RESULT:" + ($answer | ConvertTo-Json -Depth 15 -Compress)', **kwargs)
        lines = [line[10:] for line in result.stdout.splitlines() if line.startswith("SC_RESULT:")]
        self.assertEqual(len(lines), 1, result.stdout + result.stderr)
        return json.loads(lines[0])

    def dependency_fixture(self):
        path = self.root / "命令 子树.py"
        path.write_text(
            "import json,os,subprocess,sys,time\nfrom pathlib import Path\n"
            "mode,marker=sys.argv[1:3]\n"
            "if mode=='leaf': time.sleep(120); raise SystemExit(0)\n"
            "if mode=='exit7':\n"
            " print(json.dumps(sys.argv[3:],ensure_ascii=False),flush=True)\n"
            " print('ERROR_SENTINEL',file=sys.stderr,flush=True)\n"
            " raise SystemExit(7)\n"
            "child=subprocess.Popen([sys.executable,__file__,'leaf',marker],stdout=sys.stdout,stderr=sys.stderr)\n"
            "Path(marker).write_text(json.dumps(dict(root=os.getpid(),child=child.pid)),encoding='utf-8')\n"
            "print('BEFORE_WAIT',flush=True)\n"
            "if mode=='pipe': raise SystemExit(0)\n"
            "time.sleep(120)\n", encoding="utf-8")
        return path

    def dependency_caller(self, mode, *, budget=3, log=None, arguments=None):
        fixture = self.dependency_fixture()
        marker = self.root / f"{mode}-pids.json"
        log = log or self.root / f"{mode}.log"
        argfile = self.root / f"{mode}-arguments.json"
        argfile.write_text(json.dumps(arguments or [], ensure_ascii=False), encoding="utf-8")
        script = self.folder / f"{mode}-caller.ps1"
        script.write_text(
            "$ErrorActionPreference='Stop'\n. $env:SC_HELPER\n"
            "$arguments=@($env:SC_FIXTURE,$env:SC_MODE,$env:SC_MARKER) + @(Get-Content -LiteralPath $env:SC_ARGUMENTS -Raw | ConvertFrom-Json)\n"
            "Invoke-LifecycleCommand -FilePath $env:SC_PYTHON -Arguments $arguments -WorkingDirectory $env:SC_ROOT -LogPath $env:SC_LOG -TimeoutSeconds ([int]$env:SC_BUDGET)\n",
            encoding="utf-8")
        env = os.environ.copy()
        env.update(SC_HELPER=str(ROOT / "启停工具.ps1"), SC_ROOT=str(self.root),
                   SC_PYTHON=sys.executable, SC_FIXTURE=str(fixture), SC_MODE=mode,
                   SC_MARKER=str(marker), SC_LOG=str(log), SC_ARGUMENTS=str(argfile),
                   SC_BUDGET=str(budget), PYTHONIOENCODING="utf-8")
        return [PWSH, "-NoProfile", "-NonInteractive", "-File", str(script)], env, marker, log

    def dependency_alive_and_cleanup(self, marker, *, cleanup=False):
        if not marker.exists():
            return {}
        identities = json.loads(marker.read_text(encoding="utf-8"))
        # These exact PIDs came from our fixture. The helper additionally verifies
        # each live process's creation time, executable and project command line.
        result = self.result("""
$pids=Get-Content -LiteralPath $env:SC_MARKER -Raw | ConvertFrom-Json
$answer=@{}
foreach($property in $pids.PSObject.Properties) {
    $identity=Get-ProcessIdentity -ProcessId $property.Value -ProjectRoot $env:SC_ROOT -Name 'dependency-fixture'
    $answer[$property.Name]=[bool](Test-ProcessIdentity $identity)
    if($env:SC_CLEANUP -eq '1' -and $identity) {Stop-OwnedProcess $identity}
}
""", extra={"SC_MARKER": marker, "SC_CLEANUP": "1" if cleanup else "0"})
        return result

    def test_dependency_command_timeout_kills_owned_tree_and_preserves_foreign_process(self):
        foreign, _ = self.worker()
        command, env, marker, log = self.dependency_caller("tree")
        started = time.monotonic()
        try:
            with (self.folder / "tree-caller.log").open("w+b") as output:
                caller = subprocess.Popen(command, env=env, stdout=output, stderr=subprocess.STDOUT)
                try:
                    caller.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    caller.kill(); caller.wait(timeout=5)
                    self.fail("Dependency command exceeded its own deadline; caller had to be killed")
            self.assertLess(time.monotonic() - started, 12)
            self.assertNotEqual(caller.returncode, 0)
            self.assertTrue(marker.exists(), "A real command tree must have started before timing out")
            self.assertFalse(any(self.dependency_alive_and_cleanup(marker).values()))
            self.assertIsNone(foreign.poll())
            events = [json.loads(line) for line in Path(str(log) + ".events.jsonl").read_text(encoding="utf-8").splitlines()]
            self.assertEqual(events[-1]["event"], "timeout")
        finally:
            self.dependency_alive_and_cleanup(marker, cleanup=True)

    def test_dependency_command_dead_root_with_pipe_holder_is_bounded(self):
        command, env, marker, log = self.dependency_caller("pipe")
        try:
            with (self.folder / "pipe-caller.log").open("w+b") as output:
                caller = subprocess.Popen(command, env=env, stdout=output, stderr=subprocess.STDOUT)
                try:
                    caller.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    caller.kill(); caller.wait(timeout=5)
                    self.fail("Exited command root left a pipe holder waiting beyond the command deadline")
            self.assertNotEqual(caller.returncode, 0)
            self.assertTrue(marker.exists())
            self.assertFalse(any(self.dependency_alive_and_cleanup(marker).values()))
        finally:
            self.dependency_alive_and_cleanup(marker, cleanup=True)

    def test_dependency_command_log_initialization_failure_never_spawns_child(self):
        log = self.root / "blocked-command.log"
        log.mkdir()
        command, env, marker, _ = self.dependency_caller("blocked", log=log)
        try:
            result = subprocess.run(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=12)
            self.assertNotEqual(result.returncode, 0)
            time.sleep(.3)
            self.assertFalse(marker.exists(), "A command must not start before both log streams can be opened")
        finally:
            self.dependency_alive_and_cleanup(marker, cleanup=True)

    def test_dependency_command_preserves_stderr_exit_arguments_and_redacts_events(self):
        arguments = ["", "中文 空格", 'a"b', "ends with slash\\", "semi;colon", "PRIVATE_ARG_SENTINEL"]
        command, env, _, log = self.dependency_caller("exit7", budget=0, arguments=arguments)
        result = subprocess.run(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=12)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(json.loads(log.read_text(encoding="utf-8")), arguments)
        self.assertIn("ERROR_SENTINEL", Path(str(log) + ".stderr").read_text(encoding="utf-8"))
        events_text = Path(str(log) + ".events.jsonl").read_text(encoding="utf-8")
        self.assertNotIn("PRIVATE_ARG_SENTINEL", events_text)
        self.assertNotIn("PRIVATE_ARG_SENTINEL", result.stderr.decode("utf-8"))
        terminal = json.loads(events_text.splitlines()[-1])
        self.assertEqual((terminal["event"], terminal["exitCode"]), ("failed", 7))

    def test_dependency_command_parent_kill_reclaims_job_tree(self):
        command, env, marker, _ = self.dependency_caller("parent-kill", budget=30)
        try:
            with (self.folder / "parent-kill-caller.log").open("w+b") as output:
                caller = subprocess.Popen(command, env=env, stdout=output, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 10
                while not marker.exists() and caller.poll() is None and time.monotonic() < deadline:
                    time.sleep(.05)
                self.assertTrue(marker.exists(), "The fixture must actually be running before killing its caller")
                caller.kill(); caller.wait(timeout=5)
                time.sleep(.3)
                self.assertFalse(any(self.dependency_alive_and_cleanup(marker).values()))
        finally:
            if 'caller' in locals() and caller.poll() is None:
                caller.kill(); caller.wait(timeout=5)
            self.dependency_alive_and_cleanup(marker, cleanup=True)

    def test_dependency_command_without_arguments_preserves_empty_native_argument_list(self):
        # Read the actual Windows argv before Python's empty-script normalization
        # makes both `python` and `python ""` appear as sys.argv == [''].
        (self.root / "sitecustomize.py").write_text(
            "import ctypes,json,os\nfrom pathlib import Path\n"
            "ctypes.windll.kernel32.GetCommandLineW.restype=ctypes.c_wchar_p\n"
            "parse=ctypes.windll.shell32.CommandLineToArgvW\n"
            "parse.argtypes=[ctypes.c_wchar_p,ctypes.POINTER(ctypes.c_int)]\n"
            "parse.restype=ctypes.POINTER(ctypes.c_wchar_p)\n"
            "count=ctypes.c_int()\n"
            "parts=parse(ctypes.windll.kernel32.GetCommandLineW(),ctypes.byref(count))\n"
            "Path(os.environ['SC_NATIVE_ARGV']).write_text(json.dumps([parts[i] for i in range(count.value)]),encoding='utf-8')\n"
            "os._exit(0)\n", encoding="utf-8")
        for argument_clause in ("", "-Arguments $null", "-Arguments @()"):
            with self.subTest(arguments=argument_clause or "omitted"):
                native_argv = self.root / "native-argv.json"
                native_argv.unlink(missing_ok=True)
                answer = self.result("""
$log=Join-Path $env:SC_ROOT 'no-arguments.log'
Invoke-LifecycleCommand -FilePath $env:SC_PYTHON -WorkingDirectory $env:SC_ROOT -LogPath $log """ + argument_clause + """
$answer=@{arguments=@(Get-Content -LiteralPath $env:SC_NATIVE_ARGV -Raw | ConvertFrom-Json)}
""", extra={"PYTHONPATH": self.root, "SC_NATIVE_ARGV": native_argv})
                self.assertEqual(answer["arguments"][1:], [])

    def test_dependency_command_unicode_protocol_ignores_console_code_page(self):
        fixture = self.root / "编码 记录.py"
        fixture.write_text(
            "import json,os,sys\n"
            "print(json.dumps(dict(arguments=sys.argv[1:],cwd=os.getcwd())))\n", encoding="utf-8")
        arguments = ["中文 空格", "éuro €", "", 'a"b', "ends with slash\\"]
        (self.root / "unicode-arguments.json").write_text(json.dumps(arguments, ensure_ascii=False), encoding="utf-8")
        answer = self.result("""
# Model an English Windows OEM console independently of the host locale.
$originalInputEncoding=[Console]::InputEncoding
$originalOutputEncoding=[Console]::OutputEncoding
try {
[Console]::InputEncoding=[Text.Encoding]::GetEncoding(437)
$originalSupervisor=${function:Start-LifecycleSupervisor}
function Start-LifecycleSupervisor([Diagnostics.ProcessStartInfo]$StartInfo) {
    $StartInfo.ArgumentList.Clear()
    foreach($argument in @('-NoProfile','-NonInteractive','-Command',
        '[Console]::InputEncoding=[Text.Encoding]::GetEncoding(437); . $env:SC_HELPER; exit (Invoke-LifecycleCommandWorker)')) {
        $StartInfo.ArgumentList.Add($argument)
    }
    & $originalSupervisor $StartInfo
}
$arguments=@($env:SC_FIXTURE) + @(Get-Content -LiteralPath $env:SC_ARGUMENTS -Raw | ConvertFrom-Json)
$log=Join-Path $env:SC_ROOT 'unicode-protocol.log'
Invoke-LifecycleCommand -FilePath $env:SC_PYTHON -Arguments $arguments -WorkingDirectory $env:SC_ROOT -LogPath $log -TimeoutSeconds 10
$answer=@{reported=(Get-Content -LiteralPath $log -Raw | ConvertFrom-Json);callerCodePage=[Console]::InputEncoding.CodePage}
} finally {
    [Console]::InputEncoding=$originalInputEncoding
    [Console]::OutputEncoding=$originalOutputEncoding
}
""", extra={"SC_FIXTURE": fixture, "SC_ARGUMENTS": self.root / "unicode-arguments.json"})
        self.assertEqual(answer["reported"], {"arguments": arguments, "cwd": str(self.root)})
        self.assertEqual(answer["callerCodePage"], 437)

    def test_dependency_worker_gives_command_eof_while_control_pipe_stays_open(self):
        # The JSON channel belongs only to the worker. Its child must receive
        # EOF independently of whether the caller has closed that channel.
        payload = dict(filePath=sys.executable, workingDirectory=str(self.root),
                       arguments=["-c", "import sys; print('READER_STARTED',flush=True); print('EOF:'+repr(sys.stdin.read()),flush=True)"])
        stdout = self.folder / "worker-stdin.stdout"
        stderr = self.folder / "worker-stdin.stderr"
        with stdout.open("wb") as output, stderr.open("wb") as error:
            worker = subprocess.Popen([PWSH, "-NoProfile", "-NonInteractive", "-File",
                                       str(ROOT / "启停工具.ps1"), "-CommandWorker"],
                                      stdin=subprocess.PIPE, stdout=output, stderr=error)
            try:
                worker.stdin.write((json.dumps(payload) + "\n").encode("utf-8"))
                worker.stdin.flush()
                try:
                    worker.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.assertIn("READER_STARTED", stdout.read_text(encoding="utf-8"),
                                  "The reader must have started before diagnosing a blocked stdin")
                    self.fail("Command inherited the still-open JSON control pipe instead of receiving EOF")
            finally:
                worker.stdin.close()
                try:
                    worker.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    worker.kill()
                    worker.wait(timeout=5)
        self.assertEqual(worker.returncode, 0, stderr.read_text(encoding="utf-8"))
        self.assertEqual(stdout.read_text(encoding="utf-8").splitlines(), ["READER_STARTED", "EOF:''"])

    def test_dependency_command_closes_stdin_after_its_single_payload(self):
        answer = self.result("""
$log=Join-Path $env:SC_ROOT 'stdin-eof.log'
Invoke-LifecycleCommand -FilePath $env:SC_PYTHON -Arguments @('-c','import sys; print("EOF:"+repr(sys.stdin.read()),flush=True)') -WorkingDirectory $env:SC_ROOT -LogPath $log -TimeoutSeconds 3
$answer=Get-Content -LiteralPath $log -Raw
""")
        self.assertEqual(answer.strip(), "EOF:''")

    def worker(self, *, root=None):
        root = root or self.root
        root.mkdir(exist_ok=True)
        script = root / "自有 进程.py"
        script.write_text(
            "import socket,sys,time\n"
            "s=socket.socket()\n"
            "s.bind(('127.0.0.1',0))\n"
            "s.listen()\n"
            "print(s.getsockname()[1],flush=True)\n"
            "time.sleep(120)\n", encoding="utf-8")
        worker = subprocess.Popen([sys.executable, str(script)], stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, text=True)
        self.workers.append(worker)
        port = int(worker.stdout.readline())
        return worker, port

    def test_live_identity_and_owned_stop_are_idempotent(self):
        worker, _ = self.worker()
        answer = self.result("""
$identity=Get-ProcessIdentity -ProcessId $env:SC_PID -ProjectRoot $env:SC_ROOT -Name 'test'
$before=Test-ProcessIdentity $identity
Stop-OwnedProcess $identity
Stop-OwnedProcess $identity
$answer=@{before=$before; after=(Test-ProcessIdentity $identity)}
""", extra={"SC_PID": worker.pid})
        self.assertEqual(answer, {"before": True, "after": False})
        worker.wait(timeout=10)

    def test_stale_or_tampered_identity_never_stops_live_process(self):
        worker, _ = self.worker()
        answer = self.result("""
$identity=Get-ProcessIdentity -ProcessId $env:SC_PID -ProjectRoot $env:SC_ROOT -Name 'test'
$checks=@()
foreach($field in @('createdUtc','commandLine','executablePath','projectRoot')) {
    $bad=$identity.Clone()
    $bad[$field]=if($field -eq 'createdUtc') {'2001-01-01T00:00:00.0000000Z'} else {'incorrect'}
    $checks+=Test-ProcessIdentity $bad
    Stop-OwnedProcess $bad
}
$answer=@{checks=$checks; original=(Test-ProcessIdentity $identity)}
""", extra={"SC_PID": worker.pid})
        self.assertEqual(answer, {"checks": [False] * 4, "original": True})
        self.assertIsNone(worker.poll())

    def test_process_identity_survives_json_state_roundtrip(self):
        worker, _ = self.worker()
        answer = self.result("""
$identity=Get-ProcessIdentity -ProcessId $env:SC_PID -ProjectRoot $env:SC_ROOT -Name 'test'
$loaded=$identity | ConvertTo-Json | ConvertFrom-Json -AsHashtable
$answer=@{direct=(Test-ProcessIdentity $identity);roundtrip=(Test-ProcessIdentity $loaded)}
""", extra={"SC_PID": worker.pid})
        self.assertEqual(answer, {"direct": True, "roundtrip": True})

    def test_sibling_directory_does_not_establish_process_ownership(self):
        worker, _ = self.worker(root=self.folder / (self.root.name + "-other"))
        answer = self.result("""
$identity=Get-ProcessIdentity -ProcessId $env:SC_PID -ProjectRoot $env:SC_ROOT -Name 'test'
$answer=Test-ProcessIdentity $identity
""", extra={"SC_PID": worker.pid})
        self.assertFalse(answer, "A project path prefix must not own a sibling directory process")

    def test_missing_root_never_enumerates_or_stops_unrelated_descendants(self):
        worker, _ = self.worker()
        answer = self.result("""
$identity=Get-ProcessIdentity -ProcessId $env:SC_PID -ProjectRoot $env:SC_ROOT -Name 'test'
$identity.pid=2147483647
function Get-CimInstance {
    param($ClassName,$Filter,$ErrorAction)
    if($Filter -like 'ParentProcessId*') {throw 'Must not enumerate children without a verified root'}
    return $null
}
Stop-OwnedProcess $identity
$answer=$true
""", extra={"SC_PID": worker.pid})
        self.assertTrue(answer)
        self.assertIsNone(worker.poll())

    def test_foreign_listener_is_conflict_and_is_left_running(self):
        worker, port = self.worker()
        result = self.ps("Assert-LifecyclePort -Port $env:SC_PORT", success=False,
                         extra={"SC_PORT": port})
        self.assertIn("被其他进程占用", result.stderr)
        self.assertIsNone(worker.poll())
        with socket.create_connection(("127.0.0.1", port), timeout=3):
            pass

    def test_owned_listener_is_accepted_without_restarting_it(self):
        worker, port = self.worker()
        answer = self.result("""
$identity=Get-ProcessIdentity -ProcessId $env:SC_PID -ProjectRoot $env:SC_ROOT -Name 'test'
Assert-LifecyclePort -Port $env:SC_PORT -Identity $identity
$answer=Test-ProcessIdentity $identity
""", extra={"SC_PORT": port, "SC_PID": worker.pid})
        self.assertTrue(answer)

    def test_owned_child_listener_is_accepted_and_stopped_with_its_parent(self):
        child = self.root / "child listener.py"
        child.write_text("import socket,time\ns=socket.socket()\ns.bind(('127.0.0.1',0))\ns.listen()\nprint(s.getsockname()[1],flush=True)\ntime.sleep(120)\n", encoding="utf-8")
        parent = self.root / "parent process.py"
        parent.write_text("import json,subprocess,sys,time\nchild=subprocess.Popen([sys.executable,sys.argv[1]],stdout=subprocess.PIPE,text=True)\nprint(json.dumps({'pid':child.pid,'port':int(child.stdout.readline())}),flush=True)\ntime.sleep(120)\n", encoding="utf-8")
        process = subprocess.Popen([sys.executable, str(parent), str(child)], stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True)
        self.workers.append(process)
        details = json.loads(process.stdout.readline())
        answer = self.result("""
$identity=Get-ProcessIdentity -ProcessId $env:SC_PID -ProjectRoot $env:SC_ROOT -Name 'test-tree'
try {
    Assert-LifecyclePort -Port $env:SC_PORT -Identity $identity
    $answer=@{inTree=(Test-ProcessInOwnedTree -ProcessId $env:SC_CHILD_PID -RootIdentity $identity)}
} finally {Stop-OwnedProcess $identity}
$answer.parentAlive=[bool](Get-Process -Id $env:SC_PID -ErrorAction SilentlyContinue)
$answer.childAlive=[bool](Get-Process -Id $env:SC_CHILD_PID -ErrorAction SilentlyContinue)
""", extra={"SC_PID": process.pid, "SC_CHILD_PID": details["pid"], "SC_PORT": details["port"]})
        self.assertEqual(answer, {"inTree": True, "parentAlive": False, "childAlive": False})

    def test_configuration_parameter_precedence_and_validation(self):
        answer = self.result("""
$env:RAG_PORT='11001'
$env:COMPOSE_PROJECT_NAME='environment-project'
$config=Get-LifecycleConfiguration -ProjectRoot $env:SC_ROOT -Parameters @{ComposeProject='explicit-project';RagPort=11002}
$answer=@{project=$config.composeProject;rag=$config.ports.RagPort;root=$config.projectRoot}
""")
        self.assertEqual(answer, {"project": "explicit-project", "rag": 11002, "root": str(self.root)})
        for expression in ("@{RagPort=0}", "@{RagPort=65536}", "@{RagPort='invalid'}",
                           "@{RagPort=12000;BackendPort=12000}", "@{ComposeProject='INVALID'}"):
            with self.subTest(expression=expression):
                self.ps("Get-LifecycleConfiguration -ProjectRoot $env:SC_ROOT -Parameters " + expression,
                        success=False)

    def test_environment_import_from_external_cwd_and_unicode_path(self):
        (self.root / ".env").write_text('SC_ENV_TEST="中文 value with spaces"\n', encoding="utf-8")
        (self.root / "本地配置.ps1").write_text("$env:SC_LOCAL_TEST='local value'\n", encoding="utf-8")
        answer = self.result("""
Import-LifecycleEnvironment -ProjectRoot $env:SC_ROOT
$answer=@{dotenv=$env:SC_ENV_TEST;local=$env:SC_LOCAL_TEST}
""")
        self.assertEqual(answer, {"dotenv": "中文 value with spaces", "local": "local value"})

    def check_venv_tool_path(self, *, short_names_available):
        environment = self.root / ".venv"
        venv.EnvBuilder(with_pip=False).create(environment)
        prelude = "" if short_names_available else """
Add-Type -TypeDefinition @'
using System.Text;
namespace SilverCare { public static class NativePaths {
    public static uint GetShortPathName(string path, StringBuilder result, uint size) { return 0; }
} }
'@
"""
        answer = self.result(prelude + """
$original=Join-Path $env:SC_ROOT '.venv/Scripts/python.exe'
$tool=Get-WindowsToolPath $original
$log=Join-Path $env:SC_ROOT 'python-tool.log'
Invoke-LifecycleCommand -FilePath $tool -Arguments @('-c','import json,sys,os;print(json.dumps(dict(prefix=sys.prefix,executable=sys.executable,cwd=os.getcwd(),root_argument=sys.argv[1])))',$env:SC_ROOT) -WorkingDirectory $env:SC_ROOT -LogPath $log
$configuration=Get-LifecycleConfiguration -ProjectRoot $env:SC_ROOT -Parameters @{ComposeProject='tool-test'}
$state=@{schemaVersion=1;projectRoot=$configuration.projectRoot;composeProject='tool-test';processes=@();containers=@()}
Save-LifecycleState -State $state -Path $configuration.statePath
$loaded=Read-LifecycleState -Path $configuration.statePath -ProjectRoot $env:SC_ROOT -ComposeProject 'tool-test'
$answer=@{tool=$tool;original=$original;reported=(Get-Content -LiteralPath $log -Raw | ConvertFrom-Json);stateRoot=$loaded.projectRoot}
""")
        self.assertEqual(Path(answer["reported"]["prefix"]).resolve(), environment)
        self.assertEqual(Path(answer["reported"]["cwd"]).resolve(), self.root)
        self.assertEqual(answer["reported"]["root_argument"], str(self.root))
        self.assertEqual(answer["stateRoot"], str(self.root))
        self.assertEqual(Path(answer["reported"]["executable"]).resolve(), environment / "Scripts/python.exe")
        if short_names_available:
            self.assertLess(len(answer["tool"]), len(answer["original"]), "This Windows acceptance host must provide 8.3 aliases for the regression")
            self.assertLess(len(answer["reported"]["prefix"]), len(str(environment)))
        else:
            self.assertEqual(answer["tool"], answer["original"])

    def test_short_python_tool_path_changes_real_venv_prefix_but_preserves_project_root(self):
        self.check_venv_tool_path(short_names_available=True)

    def test_python_tool_falls_back_and_runs_when_native_short_names_are_unavailable(self):
        self.check_venv_tool_path(short_names_available=False)

    def test_state_roundtrip_rejects_foreign_root_project_and_corruption(self):
        answer = self.result("""
$path=Join-Path $env:SC_ROOT '.runtime/lifecycle-test.json'
$state=@{schemaVersion=1;projectRoot=$env:SC_ROOT;composeProject='test';instanceId='fresh';processes=@();containers=@();ports=@{RagPort=12000}}
Save-LifecycleState -State $state -Path $path
$loaded=Read-LifecycleState -Path $path -ProjectRoot $env:SC_ROOT -ComposeProject 'test'
$rejected=0
foreach($pair in @(@{root=($env:SC_ROOT+'-other');project='test'},@{root=$env:SC_ROOT;project='other'})) {
    try {Read-LifecycleState -Path $path -ProjectRoot $pair.root -ComposeProject $pair.project | Out-Null} catch {$rejected++}
}
Set-Content -LiteralPath $path -Value '{broken'
try {Read-LifecycleState -Path $path -ProjectRoot $env:SC_ROOT -ComposeProject 'test' | Out-Null} catch {$rejected++}
$answer=@{loaded=$loaded;rejected=$rejected;temporaryFiles=@(Get-ChildItem (Split-Path $path) -Filter '*.tmp').Count}
""")
        self.assertEqual(answer["loaded"]["instanceId"], "fresh")
        self.assertEqual(answer["rejected"], 3)
        self.assertEqual(answer["temporaryFiles"], 0)

    def test_state_rejects_nested_process_and_container_ownership_mismatch(self):
        answer = self.result("""
$path=Join-Path $env:SC_ROOT '.runtime/lifecycle-test.json'
$base=@{schemaVersion=1;projectRoot=$env:SC_ROOT;composeProject='test';processes=@();containers=@()}
$cases=@(
    @{processes=@(@{projectRoot=($env:SC_ROOT+'-foreign')});containers=@()},
    @{processes=@();containers=@(@{workingDirectory=($env:SC_ROOT+'-foreign');composeProject='test'})},
    @{processes=@();containers=@(@{workingDirectory=$env:SC_ROOT;composeProject='foreign'})}
)
$rejected=0
foreach($case in $cases) {
    $state=$base.Clone(); $state.processes=$case.processes; $state.containers=$case.containers
    Save-LifecycleState -State $state -Path $path
    try {Read-LifecycleState -Path $path -ProjectRoot $env:SC_ROOT -ComposeProject 'test' | Out-Null} catch {$rejected++}
}
$answer=$rejected
""")
        self.assertEqual(answer, 3)

    def test_project_resolution_accepts_unique_state_and_rejects_ambiguous_states(self):
        answer = self.result("""
$directory=Join-Path $env:SC_ROOT '.runtime'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$env:COMPOSE_PROJECT_NAME='unrelated-environment'
Set-Content -LiteralPath (Join-Path $directory 'lifecycle-custom.json') -Value '{}'
$unique=Resolve-LifecycleProject -ProjectRoot $env:SC_ROOT
Set-Content -LiteralPath (Join-Path $directory 'lifecycle-second.json') -Value '{}'
$ambiguousRejected=$false
try {Resolve-LifecycleProject -ProjectRoot $env:SC_ROOT | Out-Null} catch {$ambiguousRejected=$true}
$explicit=Resolve-LifecycleProject -ProjectRoot $env:SC_ROOT -ComposeProject 'second'
$env:COMPOSE_PROJECT_NAME='custom'
$matched=Resolve-LifecycleProject -ProjectRoot $env:SC_ROOT
$answer=@{unique=$unique;ambiguousRejected=$ambiguousRejected;explicit=$explicit;matched=$matched}
""")
        self.assertEqual(answer, {"unique": "custom", "ambiguousRejected": True,
                                  "explicit": "second", "matched": "custom"})

    def test_process_launcher_preserves_quoted_arguments_and_logs_in_unicode_path(self):
        shutil.copyfile(ROOT / "启停工具.ps1", self.root / "启停工具.ps1")
        script = self.root / "argument recorder.py"
        script.write_text("import json,sys,time\nprint(json.dumps(sys.argv[1:],ensure_ascii=False),flush=True)\ntime.sleep(120)\n", encoding="utf-8")
        arguments = ["", "中文 空格", 'a"b', "ends with slash\\", "semi;colon", "$(literal)"]
        payload = self.root / "arguments.json"
        payload.write_text(json.dumps(arguments, ensure_ascii=False), encoding="utf-8")
        answer = self.result("""
$logs=Join-Path $env:SC_ROOT '.runtime/logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$arguments=@((Join-Path $env:SC_ROOT 'argument recorder.py')) + @(Get-Content (Join-Path $env:SC_ROOT 'arguments.json') -Raw | ConvertFrom-Json)
$identities=@()
$checkpointState=@{schemaVersion=1;projectRoot=$env:SC_ROOT;composeProject='quoting';processes=@();containers=@()}
$checkpointPath=Join-Path $env:SC_ROOT '.runtime/lifecycle-quoting.json'
$checkpointProcesses=[Collections.Generic.List[object]]::new()
$checkpointCounts=[Collections.Generic.List[int]]::new()
$saveCheckpoint=${function:Save-LifecycleState}
$checkpoint={param($identity)
    $checkpointState.processes=@($checkpointState.processes)+@($identity)
    $checkpointProcesses.Add($identity)
    & $saveCheckpoint $checkpointState $checkpointPath
    $persisted=Get-Content -LiteralPath $checkpointPath -Raw | ConvertFrom-Json -AsHashtable
    $checkpointCounts.Add($persisted.processes.Count)
}.GetNewClosure()
try {
    $identities=@(Start-LifecycleProcess -Name 'quoting' -FilePath $env:SC_PYTHON -Arguments $arguments -WorkingDirectory $env:SC_ROOT -ProjectRoot $env:SC_ROOT -LogDirectory $logs -OnIdentity $checkpoint)
    Wait-LifecycleCondition -TimeoutSeconds 10 -Description 'test output' -Condition {(Get-Item (Join-Path $logs 'quoting.log')).Length -gt 0}
    $values=Get-Content (Join-Path $logs 'quoting.log') -Raw -Encoding UTF8 | ConvertFrom-Json
    $saved=Read-LifecycleState -Path $checkpointPath -ProjectRoot $env:SC_ROOT -ComposeProject 'quoting'
    $answer=@{arguments=@($values);identityCount=$identities.Count;allOwned=(@($identities | Where-Object {-not (Test-ProcessIdentity $_)}).Count -eq 0);checkpointNames=@($saved.processes | ForEach-Object name);checkpointCounts=$checkpointCounts.ToArray();newProcessCount=$checkpointProcesses.Count}
} catch {
    Get-ChildItem -LiteralPath $logs -Filter '*.log' | ForEach-Object {Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 | Out-Host}
    throw
} finally {
    foreach($identity in $identities) {Stop-OwnedProcess $identity}
}
""", extra={"PYTHONIOENCODING": "utf-8"})
        self.assertEqual(answer["arguments"], arguments)
        self.assertEqual(answer["identityCount"], 2)
        self.assertTrue(answer["allOwned"])
        self.assertEqual(answer["checkpointNames"], ["quoting-logger", "quoting"])
        self.assertEqual(answer["checkpointCounts"], [1, 2])
        self.assertEqual(answer["newProcessCount"], 2)

    def gated_launcher_fixture(self):
        shutil.copyfile(ROOT / "启停工具.ps1", self.root / "启停工具.ps1")
        script = self.root / "gated app.py"
        script.write_text("import os,sys,time\nfrom pathlib import Path\nPath(sys.argv[1]).write_text(str(os.getpid()),encoding='utf-8')\nprint('application ready',flush=True)\ntime.sleep(120)\n", encoding="utf-8")
        return """
$logs=Join-Path $env:SC_ROOT '.runtime/logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$statePath=Join-Path $env:SC_ROOT '.runtime/lifecycle-gated.json'
$state=@{schemaVersion=1;projectRoot=$env:SC_ROOT;composeProject='gated';processes=@();containers=@()}
$saveCheckpoint=${function:Save-LifecycleState}
$marker=Join-Path $env:SC_ROOT 'app-started.txt'
$observed=Join-Path $env:SC_ROOT 'observed-identity.json'
$launch=@{Name='gated';FilePath=$env:SC_PYTHON;Arguments=@((Join-Path $env:SC_ROOT 'gated app.py'),$marker);WorkingDirectory=$env:SC_ROOT;ProjectRoot=$env:SC_ROOT;LogDirectory=$logs}
"""

    def test_launcher_application_stdin_survives_caller_exit_and_input_close(self):
        setup = self.gated_launcher_fixture()
        (self.root / "gated app.py").write_text(
            "import os,sys,time\nfrom pathlib import Path\n"
            "Path(sys.argv[1]).write_text(str(os.getpid()),encoding='utf-8')\n"
            "print('application ready',flush=True)\n"
            "while not Path(sys.argv[3]).exists(): time.sleep(.05)\n"
            "if sys.stdin is None:\n"
            " Path(sys.argv[2]).write_text('stdin unavailable',encoding='utf-8')\n"
            " raise SystemExit(0)\n"
            "sys.stdin.buffer.read()\n"
            "Path(sys.argv[2]).write_text('stdin EOF',encoding='utf-8')\n", encoding="utf-8")
        script = self.folder / "stdin-owner-caller.ps1"
        script.write_text("$ErrorActionPreference='Stop'\n. $env:SC_HELPER\n" + setup + """
$launch.Arguments+=@((Join-Path $env:SC_ROOT 'stdin-eof-observed.txt'),(Join-Path $env:SC_ROOT 'read-stdin.txt'))
$checkpoint={param($identity)
    $state.processes=@($state.processes)+@($identity)
    & $saveCheckpoint $state $statePath
}.GetNewClosure()
Start-LifecycleProcess @launch -OnIdentity $checkpoint | Out-Null
Wait-LifecycleCondition -TimeoutSeconds 10 -Description 'real stdin fixture ready' -Condition {Test-Path -LiteralPath $marker}
""", encoding="utf-8")
        env = os.environ.copy()
        env.update(SC_HELPER=str(ROOT / "启停工具.ps1"), SC_ROOT=str(self.root), SC_PYTHON=sys.executable)
        caller = None
        try:
            with (self.folder / "stdin-owner-caller.log").open("w+b") as output:
                caller = subprocess.Popen([PWSH, "-NoProfile", "-NonInteractive", "-File", str(script)],
                                          env=env, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT)
                caller.wait(timeout=20)
                output.seek(0)
                diagnostics = output.read().decode("utf-8", errors="replace")
                error_log = self.root / ".runtime/logs/gated.err.log"
                if error_log.exists():
                    diagnostics += error_log.read_text(encoding="utf-8", errors="replace")
                self.assertEqual(caller.returncode, 0, diagnostics)
                self.assertFalse(caller.stdin.closed, "The test must retain the original writer after caller exit")
                self.assertTrue((self.root / "app-started.txt").exists())
                self.assertFalse((self.root / "stdin-eof-observed.txt").exists())
                caller.stdin.close()
                (self.root / "read-stdin.txt").write_text("read input now", encoding="utf-8")
                deadline = time.monotonic() + 3
                while time.monotonic() < deadline and not (self.root / "stdin-eof-observed.txt").exists():
                    time.sleep(.05)
                answer = self.result("""
$state=Get-Content -LiteralPath (Join-Path $env:SC_ROOT '.runtime/lifecycle-gated.json') -Raw | ConvertFrom-Json -AsHashtable
$answer=@{appAlive=(Test-ProcessIdentity ($state.processes | Where-Object name -EQ 'gated'));
    loggerAlive=(Test-ProcessIdentity ($state.processes | Where-Object name -EQ 'gated-logger'));
    eofObserved=(Test-Path -LiteralPath (Join-Path $env:SC_ROOT 'stdin-eof-observed.txt'));
    inputObservation=$(if(Test-Path -LiteralPath (Join-Path $env:SC_ROOT 'stdin-eof-observed.txt')){Get-Content -LiteralPath (Join-Path $env:SC_ROOT 'stdin-eof-observed.txt') -Raw}else{$null})}
""")
                self.assertEqual(answer, {"appAlive": True, "loggerAlive": True, "eofObserved": False, "inputObservation": None})
        finally:
            if caller:
                if caller.stdin and not caller.stdin.closed:
                    caller.stdin.close()
                if caller.poll() is None:
                    caller.kill(); caller.wait(timeout=5)
            self.ps("""
$path=Join-Path $env:SC_ROOT '.runtime/lifecycle-gated.json'
if(Test-Path -LiteralPath $path) {
    $state=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
    foreach($identity in @($state.processes | Sort-Object {if($_.name -like '*-logger'){1}else{0}})) {Stop-OwnedProcess $identity}
}
""")

    def test_launcher_supervisor_events_record_normal_exit_without_sensitive_metadata(self):
        setup = self.gated_launcher_fixture()
        (self.root / "gated app.py").write_text(
            "import os,sys,time\nfrom pathlib import Path\n"
            "Path(sys.argv[1]).write_text(str(os.getpid()),encoding='utf-8')\n"
            "print(sys.argv[2],flush=True)\nprint(sys.argv[2],file=sys.stderr,flush=True)\n"
            "while not Path(sys.argv[3]).exists(): time.sleep(.05)\n", encoding="utf-8")
        answer = self.result(setup + """
$sentinel='PRIVATE_SUPERVISOR_SENTINEL'
$exitGate=Join-Path $env:SC_ROOT 'normal-exit.txt'
$launch.Arguments+=@($sentinel,$exitGate)
$identities=@()
try {
    $identities=@(Start-LifecycleProcess @launch)
    Wait-LifecycleCondition -TimeoutSeconds 10 -Description 'application marker' -Condition {Test-Path -LiteralPath $marker}
    Set-Content -LiteralPath $exitGate -Value 'exit'
    Wait-LifecycleCondition -TimeoutSeconds 10 -Description 'natural application and logger exit' -Condition {@($identities | Where-Object {Test-ProcessIdentity $_}).Count -eq 0}
    $eventsPath=Join-Path $logs 'gated.log.supervisor.events.jsonl'
    $text=Get-Content -LiteralPath $eventsPath -Raw
    $records=@(Get-Content -LiteralPath $eventsPath | ForEach-Object {$_ | ConvertFrom-Json -AsHashtable})
    $answer=@{records=$records;metadataContainsSentinel=$text.Contains($sentinel);
        stdoutContainsSentinel=(Get-Content -LiteralPath (Join-Path $logs 'gated.log') -Raw).Contains($sentinel);
        stderrContainsSentinel=(Get-Content -LiteralPath (Join-Path $logs 'gated.err.log') -Raw).Contains($sentinel)}
} finally {foreach($identity in $identities){Stop-OwnedProcess $identity}}
""")
        self.assertEqual([record["event"] for record in answer["records"]], ["waiting", "child-started", "child-exited", "finished"])
        started, exited = answer["records"][1:3]
        self.assertGreater(started["childPid"], 0)
        self.assertGreater(started["loggerPid"], 0)
        self.assertRegex(started["launchConfig"], r"^gated-[a-f0-9]{32}\.launch\.json$")
        self.assertEqual(exited["exitCode"], 0)
        self.assertFalse(answer["metadataContainsSentinel"])
        self.assertTrue(answer["stdoutContainsSentinel"] and answer["stderrContainsSentinel"])

    def test_launcher_parent_killed_before_permission_never_spawns_app_and_logger_exits(self):
        setup = self.gated_launcher_fixture()
        self.ps(setup + """
$callback={param($identity)
    $identity | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $observed
    Stop-Process -Id $PID -Force
}.GetNewClosure()
Start-LifecycleProcess @launch -OnIdentity $callback
""", success=False, detached_output=True)
        answer = self.result("""
$identity=Get-Content -LiteralPath (Join-Path $env:SC_ROOT 'observed-identity.json') -Raw | ConvertFrom-Json -AsHashtable
try {
    Wait-LifecycleCondition -TimeoutSeconds 25 -Description 'unpermitted logger exit' -Condition {-not (Test-ProcessIdentity $identity)}
    $answer=@{loggerAlive=(Test-ProcessIdentity $identity);appStarted=(Test-Path -LiteralPath (Join-Path $env:SC_ROOT 'app-started.txt'));stateExists=(Test-Path -LiteralPath (Join-Path $env:SC_ROOT '.runtime/lifecycle-gated.json'))}
} finally {Stop-OwnedProcess $identity}
""")
        self.assertEqual(answer, {"loggerAlive": False, "appStarted": False, "stateExists": False})

    def test_launcher_parent_killed_after_permission_is_recoverable_from_saved_logger(self):
        setup = self.gated_launcher_fixture()
        self.ps(setup + """
$callback={param($identity)
    if ($identity.name -like '*-logger') {
        $state.processes+=@($identity)
        & $saveCheckpoint $state $statePath
    } else {
        Wait-LifecycleCondition -TimeoutSeconds 10 -Description 'self-owned app marker' -Condition {Test-Path -LiteralPath $marker}
        $identity | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $observed
        Stop-Process -Id $PID -Force
    }
}.GetNewClosure()
Start-LifecycleProcess @launch -OnIdentity $callback
""", success=False)
        answer = self.result("""
$saved=Read-LifecycleState -Path (Join-Path $env:SC_ROOT '.runtime/lifecycle-gated.json') -ProjectRoot $env:SC_ROOT -ComposeProject 'gated'
$logger=$saved.processes[0]
$app=Get-Content -LiteralPath (Join-Path $env:SC_ROOT 'observed-identity.json') -Raw | ConvertFrom-Json -AsHashtable
$answer=@{savedCount=$saved.processes.Count;loggerName=$logger.name;loggerBefore=(Test-ProcessIdentity $logger);appBefore=(Test-ProcessIdentity $app);appStarted=(Test-Path -LiteralPath (Join-Path $env:SC_ROOT 'app-started.txt'))}
try {
    Stop-OwnedProcess $logger
    $answer.loggerAfter=Test-ProcessIdentity $logger
    $answer.appAfter=Test-ProcessIdentity $app
} finally {
    Stop-OwnedProcess $app
    Stop-OwnedProcess $logger
}
""")
        self.assertEqual(answer, {"savedCount": 1, "loggerName": "gated-logger", "loggerBefore": True,
                                  "appBefore": True, "appStarted": True, "loggerAfter": False, "appAfter": False})

    def test_launcher_checkpoint_failure_before_permission_does_not_spawn_app(self):
        setup = self.gated_launcher_fixture()
        (self.root / "blocked-directory").write_text("a file cannot contain a state file", encoding="utf-8")
        answer = self.result(setup + """
$callback={param($identity)
    $identity | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $observed
    & $saveCheckpoint $state (Join-Path $env:SC_ROOT 'blocked-directory/state.json')
}.GetNewClosure()
$failed=$false
try {Start-LifecycleProcess @launch -OnIdentity $callback | Out-Null} catch {$failed=$true}
$logger=Get-Content -LiteralPath $observed -Raw | ConvertFrom-Json -AsHashtable
try {$answer=@{failed=$failed;appStarted=(Test-Path -LiteralPath $marker);loggerAlive=(Test-ProcessIdentity $logger)}}
finally {Stop-OwnedProcess $logger}
""")
        self.assertEqual(answer, {"failed": True, "appStarted": False, "loggerAlive": False})

    def test_launcher_checkpoint_failure_after_spawn_rolls_back_logger_and_app(self):
        setup = self.gated_launcher_fixture()
        (self.root / "blocked-directory").write_text("a file cannot contain a state file", encoding="utf-8")
        answer = self.result(setup + """
$callback={param($identity)
    if ($identity.name -like '*-logger') {
        $state.processes+=@($identity)
        & $saveCheckpoint $state $statePath
    } else {
        Wait-LifecycleCondition -TimeoutSeconds 10 -Description 'self-owned app marker' -Condition {Test-Path -LiteralPath $marker}
        $identity | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $observed
        & $saveCheckpoint $state (Join-Path $env:SC_ROOT 'blocked-directory/state.json')
    }
}.GetNewClosure()
$failed=$false
try {Start-LifecycleProcess @launch -OnIdentity $callback | Out-Null} catch {$failed=$true}
$logger=$state.processes[0]
$app=Get-Content -LiteralPath $observed -Raw | ConvertFrom-Json -AsHashtable
try {$answer=@{failed=$failed;appStarted=(Test-Path -LiteralPath $marker);loggerAlive=(Test-ProcessIdentity $logger);appAlive=(Test-ProcessIdentity $app)}}
finally {Stop-OwnedProcess $app; Stop-OwnedProcess $logger}
""")
        self.assertEqual(answer, {"failed": True, "appStarted": True, "loggerAlive": False, "appAlive": False})

    def io_fault_launcher(self, fault):
        setup = self.gated_launcher_fixture()
        # A silent server can survive a logger's broken output pipes. Observe
        # its own marker before cleanup, so cleanup cannot mask an orphan.
        (self.root / "gated app.py").write_text("import os,sys,time\nfrom pathlib import Path\nPath(sys.argv[1]).write_text(str(os.getpid()),encoding='utf-8')\ntime.sleep(120)\n", encoding="utf-8")
        (self.root / "app-started.txt").unlink(missing_ok=True)
        return self.result(setup + """
$callback={param($identity)
    $state.processes=@($state.processes)+@($identity)
    & $saveCheckpoint $state $statePath
    if ($identity.name -like '*-logger' -and $env:SC_IO_FAULT -eq 'identity') {
        New-Item -ItemType Directory -Path ($identity.launchConfigPath+'.identity.tmp') | Out-Null
    }
}.GetNewClosure()
$app=$null
$failed=$false
try {
    try {Start-LifecycleProcess @launch -OnIdentity $callback | Out-Null} catch {$failed=$true}
    if (Test-Path -LiteralPath $marker) {
        $applicationId=[int](Get-Content -LiteralPath $marker -Raw)
        $app=Get-ProcessIdentity -ProcessId $applicationId -ProjectRoot $env:SC_ROOT -Name 'io-probe'
    }
    $answer=@{failed=$failed;appAlive=(Test-ProcessIdentity $app);loggerAlive=(@($state.processes | Where-Object {Test-ProcessIdentity $_}).Count -gt 0)}
} finally {
    if ($app) {Stop-OwnedProcess $app}
    foreach ($identity in $state.processes) {Stop-OwnedProcess $identity}
}
""", extra={"SC_IO_FAULT": fault})

    def test_launcher_log_open_failure_does_not_leave_application_alive(self):
        for name in ("gated.log", "gated.err.log"):
            with self.subTest(blocked_stream=name):
                blocked = self.root / ".runtime/logs" / name
                blocked.mkdir(parents=True)
                try:
                    answer = self.io_fault_launcher("logs")
                    self.assertEqual(answer, {"failed": True, "appAlive": False, "loggerAlive": False})
                    events = [json.loads(line) for line in (self.root / ".runtime/logs/gated.log.supervisor.events.jsonl").read_text(encoding="utf-8").splitlines()]
                    failures = [record for record in events if record["event"] == "logger-catch"]
                    self.assertTrue(failures)
                    self.assertIn("errorType", failures[-1])
                    self.assertIsInstance(failures[-1]["hresult"], int)
                    self.assertEqual((failures[-1]["outTaskStatus"], failures[-1]["errTaskStatus"]), ("not-started", "not-started"))
                    self.assertEqual(events[-1]["event"], "finished")
                finally:
                    blocked.rmdir()

    def test_launcher_identity_publication_failure_does_not_leave_application_alive(self):
        answer = self.io_fault_launcher("identity")
        self.assertEqual(answer, {"failed": True, "appAlive": False, "loggerAlive": False})
        events = [json.loads(line) for line in (self.root / ".runtime/logs/gated.log.supervisor.events.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual([record["event"] for record in events], ["waiting", "child-started", "logger-catch", "logger-killing-child", "logger-killed-child", "finished"])
        failure = events[2]
        self.assertGreater(failure["childPid"], 0)
        self.assertFalse(failure["childHasExited"])
        self.assertTrue(failure["errorType"])
        self.assertIsInstance(failure["hresult"], int)

    def test_container_labels_are_verified_before_stop_using_fake_docker(self):
        identity = {"id": "a" * 64, "service": "mysql", "composeProject": "test-project",
                    "workingDirectory": str(self.root)}
        fixture = [{"Id": identity["id"], "Config": {"Labels": {
            "com.docker.compose.project": identity["composeProject"],
            "com.docker.compose.service": identity["service"],
            "com.docker.compose.project.working_dir": identity["workingDirectory"]}}}]
        fixture_path = self.root / "docker-fixture.json"
        fixture_path.write_text(json.dumps(fixture, ensure_ascii=False), encoding="utf-8")
        fake = self.root / "fake docker.ps1"
        fake.write_text("if($args[0] -eq 'inspect') {Get-Content -LiteralPath (Join-Path $env:SC_ROOT 'docker-fixture.json') -Raw; exit 0}\n"
                        "if($args[0] -eq 'stop') {Add-Content -LiteralPath (Join-Path $env:SC_ROOT 'stops.txt') -Value $args[1]; exit 0}\nexit 1\n", encoding="utf-8")
        answer = self.result("""
$docker=Join-Path $env:SC_ROOT 'fake docker.ps1'
$id=@{id=('a'*64);service='mysql';composeProject='test-project';workingDirectory=$env:SC_ROOT}
$checks=@(Test-ContainerIdentity $id $docker)
foreach($field in @('id','service','composeProject','workingDirectory')) {
    $bad=$id.Clone(); $bad[$field]=if($field -eq 'id') {'b'*64} else {'foreign'}
    $checks+=Test-ContainerIdentity $bad $docker
    Stop-OwnedContainer $bad $docker
}
Stop-OwnedContainer $id $docker
$answer=@{checks=$checks;stops=@(Get-Content (Join-Path $env:SC_ROOT 'stops.txt'))}
""")
        self.assertEqual(answer["checks"], [True, False, False, False, False])
        self.assertEqual(answer["stops"], ["a" * 64])

    def fake_infrastructure(self):
        def container(identifier, service, running):
            return {"Id": identifier * 64, "Config": {"Labels": {
                "com.docker.compose.project": "test", "com.docker.compose.service": service,
                "com.docker.compose.project.working_dir": str(self.root)}}, "State": {"Running": running}}

        (self.root / "docker-live.json").write_text(json.dumps([container("a", "redis", True)]), encoding="utf-8")
        (self.root / "docker-created.json").write_text(json.dumps(container("b", "mysql", False)), encoding="utf-8")
        fake = self.root / "fake infrastructure.ps1"
        fake.write_text("""
$livePath=Join-Path $env:SC_ROOT 'docker-live.json'
$events=Join-Path $env:SC_ROOT 'docker-events.txt'
$containers=@(Get-Content -LiteralPath $livePath -Raw | ConvertFrom-Json -AsHashtable)
if ($args[0] -eq 'ps') {
    $containers | ForEach-Object {$_.Id}
    exit 0
}
if ($args[0] -eq 'inspect') {
    $found=@($containers | Where-Object Id -EQ $args[1])
    if ($found.Count -ne 1) {exit 8}
    @($found) | ConvertTo-Json -Depth 10 -AsArray
    exit 0
}
if ($args[0] -eq 'compose' -and $args[1] -eq 'create') {
    Add-Content -LiteralPath $events -Value 'create'
    if (@($containers | Where-Object Id -EQ ('b'*64)).Count -eq 0) {
        $containers+=Get-Content -LiteralPath (Join-Path $env:SC_ROOT 'docker-created.json') -Raw | ConvertFrom-Json -AsHashtable
    }
    $containers | ConvertTo-Json -Depth 10 -AsArray | Set-Content -LiteralPath $livePath
    if ($env:SC_FAKE_MODE -eq 'create-failure') {exit 17}
    exit 0
}
if ($args[0] -eq 'compose' -and $args[1] -eq 'start') {
    Add-Content -LiteralPath $events -Value 'start'
    $statePath=Join-Path $env:SC_ROOT '.runtime/lifecycle-test.json'
    if (-not (Test-Path -LiteralPath $statePath)) {throw 'start occurred before any state was persisted'}
    Copy-Item -LiteralPath $statePath -Destination (Join-Path $env:SC_ROOT 'state-before-start.json') -Force
    $saved=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json -AsHashtable
    if (@($saved.containers | Where-Object id -EQ ('b'*64)).Count -ne 1) {throw 'new exact container ID was absent before start'}
    if ($env:SC_FAKE_MODE -eq 'interrupt') {Stop-Process -Id $PID -Force; exit 99}
    foreach ($container in $containers) {$container.State.Running=$true}
    $containers | ConvertTo-Json -Depth 10 -AsArray | Set-Content -LiteralPath $livePath
    if ($env:SC_FAKE_MODE -eq 'start-failure') {exit 18}
    exit 0
}
if ($args[0] -eq 'stop') {
    Add-Content -LiteralPath $events -Value 'unexpected-stop'
    foreach ($container in $containers) {if ($container.Id -eq $args[1]) {$container.State.Running=$false}}
    $containers | ConvertTo-Json -Depth 10 -AsArray | Set-Content -LiteralPath $livePath
    exit 0
}
exit 19
""", encoding="utf-8")

    def infrastructure_body(self, *, resume=False):
        state = ("$state=Read-LifecycleState -Path $path -ProjectRoot $env:SC_ROOT -ComposeProject 'test'" if resume else
                 "$state=@{schemaVersion=1;projectRoot=$env:SC_ROOT;composeProject='test';processes=@();containers=@()}")
        return """
$path=Join-Path $env:SC_ROOT '.runtime/lifecycle-test.json'
""" + state + """
$failed=$false
try {Start-LifecycleInfrastructure -State $state -StatePath $path -DockerPath (Join-Path $env:SC_ROOT 'fake infrastructure.ps1') -ComposeArguments @('compose') -LogPath (Join-Path $env:SC_ROOT 'compose.log')}
catch {$failed=$true}
$snapshotPath=Join-Path $env:SC_ROOT 'state-before-start.json'
$snapshot=if (Test-Path -LiteralPath $snapshotPath) {Get-Content -LiteralPath $snapshotPath -Raw | ConvertFrom-Json -AsHashtable} else {$null}
$answer=@{failed=$failed;state=(Read-LifecycleState -Path $path -ProjectRoot $env:SC_ROOT -ComposeProject 'test');events=@(Get-Content -LiteralPath (Join-Path $env:SC_ROOT 'docker-events.txt'));beforeStart=$snapshot;live=@(Get-Content -LiteralPath (Join-Path $env:SC_ROOT 'docker-live.json') -Raw | ConvertFrom-Json -AsHashtable)}
"""

    def test_infrastructure_persists_exact_ids_before_start_and_preserves_running_containers(self):
        self.fake_infrastructure()
        answer = self.result(self.infrastructure_body(), extra={"SC_FAKE_MODE": "success"})
        self.assertFalse(answer["failed"])
        self.assertEqual(answer["events"], ["create", "start"])
        before = {item["id"]: item["running"] for item in answer["beforeStart"]["containers"]}
        self.assertEqual(before, {"a" * 64: True, "b" * 64: False})
        self.assertTrue(all(item["running"] for item in answer["state"]["containers"]))
        self.assertEqual([item["Id"] for item in answer["live"]], ["a" * 64, "b" * 64])

    def test_infrastructure_create_failure_never_starts_or_stops_existing_resources(self):
        self.fake_infrastructure()
        answer = self.result(self.infrastructure_body(), extra={"SC_FAKE_MODE": "create-failure"})
        self.assertTrue(answer["failed"])
        self.assertEqual(answer["events"], ["create"])
        self.assertIsNone(answer["beforeStart"])
        self.assertEqual({item["id"]: item["running"] for item in answer["state"]["containers"]},
                         {"a" * 64: True, "b" * 64: False})

    def test_infrastructure_partial_start_failure_persists_running_ids_for_recovery(self):
        self.fake_infrastructure()
        answer = self.result(self.infrastructure_body(), extra={"SC_FAKE_MODE": "start-failure"})
        self.assertTrue(answer["failed"])
        self.assertEqual(answer["events"], ["create", "start"])
        self.assertEqual({item["id"]: item["running"] for item in answer["state"]["containers"]},
                         {"a" * 64: True, "b" * 64: True})

    def test_infrastructure_shell_interruption_keeps_ids_and_retry_reuses_them(self):
        self.fake_infrastructure()
        self.ps(self.infrastructure_body(), success=False, extra={"SC_FAKE_MODE": "interrupt"})
        saved = json.loads((self.root / ".runtime/lifecycle-test.json").read_text(encoding="utf-8-sig"))
        self.assertEqual({item["id"]: item["running"] for item in saved["containers"]},
                         {"a" * 64: True, "b" * 64: False})
        answer = self.result(self.infrastructure_body(resume=True), extra={"SC_FAKE_MODE": "success"})
        self.assertFalse(answer["failed"])
        self.assertEqual(answer["events"], ["create", "start", "create", "start"])
        self.assertEqual([item["Id"] for item in answer["live"]], ["a" * 64, "b" * 64])
        self.assertTrue(all(item["running"] for item in answer["state"]["containers"]))

    def test_local_http_probes_ignore_inherited_proxy_without_changing_environment(self):
        proxy_requests = []

        class Target(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                status, content_type = 200, "application/json"
                if self.path in ("/protected-401", "/ai/daily/overview", "/api/ai/daily/overview"):
                    status, value = 401, {"code": 401}
                elif self.path == "/protected-403":
                    status, value = 403, {"code": 403}
                elif self.path == "/v2/diagnostics":
                    if self.headers.get("X-Internal-Token") != "fixture-token":
                        status, value = 401, {"code": 401}
                    else:
                        value = {"backend": "milvus-hybrid", "embedding_loaded": True,
                                 "embedding_backend": "BAAI/bge-small-zh-v1.5", "dimension": 512,
                                 "model": {"name": "BAAI/bge-small-zh-v1.5", "onnx_files": ["fixture.onnx"]},
                                 "degraded": False}
                elif self.path == "/health":
                    value = {"status": "ok", "instance_id": "fixture-instance", "degraded": False}
                elif self.path in ("/v2/api-docs", "/api/v2/api-docs"):
                    value = {"swagger": "2.0", "paths": {"/ai/daily/overview": {}}}
                elif self.path == "/":
                    value, content_type = '<div id="app"></div>', "text/html"
                else:
                    value = {"status": "ok"}
                body = (value if isinstance(value, str) else json.dumps(value)).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        class RejectingProxy(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                proxy_requests.append(self.path)
                self.send_response(502)
                self.send_header("Content-Length", "0")
                self.end_headers()

        target = ThreadingHTTPServer(("127.0.0.1", 0), Target)
        proxy = ThreadingHTTPServer(("127.0.0.1", 0), RejectingProxy)
        threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in (target, proxy)]
        for thread in threads:
            thread.start()
        try:
            proxy_url = f"http://127.0.0.1:{proxy.server_port}"
            with mock.patch.dict(os.environ, {"HTTP_PROXY": proxy_url, "HTTPS_PROXY": proxy_url}):
                os.environ.pop("NO_PROXY", None)
                os.environ.pop("no_proxy", None)
                answer = self.result("""
$proxyBefore=$env:HTTP_PROXY
$ports=@{MilvusHealthPort=[int]$env:SC_HTTP_PORT;RagPort=[int]$env:SC_HTTP_PORT;BackendPort=[int]$env:SC_HTTP_PORT;FrontendPort=[int]$env:SC_HTTP_PORT}
$state=@{instanceId='fixture-instance'}
$env:AI_RAG_INTERNAL_TOKEN='fixture-token'; $env:RAG_INTERNAL_TOKEN='fixture-token'
$baseUrl="http://127.0.0.1:$env:SC_HTTP_PORT"
$control=Invoke-WebRequest "$baseUrl/public" -TimeoutSec 5 -SkipHttpErrorCheck
$tokens=$null; $errors=$null
$statusTree=[Management.Automation.Language.Parser]::ParseFile($env:SC_STATUS,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Invalid status entry point'}
$declaration=$statusTree.Find({param($item)
    $item -is [Management.Automation.Language.AssignmentStatementAst] -and
    $item.Left -is [Management.Automation.Language.VariableExpressionAst] -and
    $item.Left.VariablePath.UserPath -eq 'probes'
},$true)
if(-not $declaration){throw 'Missing actual status probes'}
. ([scriptblock]::Create($declaration.Extent.Text))
$statusResults=@{}
foreach($name in $probes.Keys){try{$statusResults[$name]=[bool](& $probes[$name])}catch{$statusResults[$name]=$false}}
$startupTree=[Management.Automation.Language.Parser]::ParseFile($env:SC_STARTUP,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Invalid startup entry point'}
$conditions=@($startupTree.FindAll({param($item)
    $item -is [Management.Automation.Language.ScriptBlockExpressionAst] -and
    $item.Extent.Text -match 'Invoke-(WebRequest|RestMethod)'
},$true))
$startupResults=@(foreach($condition in $conditions){try{& $condition.ScriptBlock.GetScriptBlock()}catch{$false}})
$answer=@{control=[int]$control.StatusCode;status=$statusResults;startup=$startupResults;
    unauthorized=(Test-LifecycleAuthorization "$baseUrl/protected-401");
    forbidden=(Test-LifecycleAuthorization "$baseUrl/protected-403");
    public=(Test-LifecycleAuthorization "$baseUrl/public");
    nonlocal=(Test-LifecycleAuthorization 'http://proxy-check.invalid/should-use-proxy');
    proxyUnchanged=($env:HTTP_PROXY -ceq $proxyBefore);noProxyAbsent=(-not(Test-Path Env:NO_PROXY))}
""", extra={"SC_HTTP_PORT": target.server_port, "SC_STATUS": ROOT / "查看运行状态.ps1",
                                    "SC_STARTUP": ROOT / "启动AI养老院.ps1"})
            self.assertEqual(answer["control"], 502, "The rejecting proxy must actually intercept ordinary local requests")
            self.assertEqual(set(answer["status"]), {"milvus-http", "rag-diagnostics", "backend-http", "frontend-proxy"})
            self.assertTrue(all(answer["status"].values()), answer["status"])
            self.assertEqual(answer["startup"], [True] * 4)
            self.assertTrue(answer["unauthorized"])
            self.assertTrue(answer["forbidden"])
            self.assertFalse(answer["public"], "A successful public response is not authorization enforcement")
            self.assertFalse(answer["nonlocal"])
            self.assertTrue(answer["proxyUnchanged"] and answer["noProxyAbsent"])
            self.assertEqual(len(proxy_requests), 2, "Only the control and non-local requests may use the configured proxy")
            self.assertTrue(any("proxy-check.invalid" in request for request in proxy_requests))
        finally:
            for server in (target, proxy):
                server.shutdown()
                server.server_close()
            for thread in threads:
                thread.join(timeout=5)

    def test_frontend_entry_runs_vite_with_the_requested_loopback_port(self):
        frontend = self.root / "源码/beadhouse-frontend"
        entry = frontend / "node_modules/vite/bin/vite.js"
        entry.parent.mkdir(parents=True)
        # A tiny CLI fixture exercises the actual launcher expression without
        # downloading dependencies or starting the application or Docker.
        entry.write_text(
            "import argparse,json\n"
            "parser=argparse.ArgumentParser()\n"
            "parser.add_argument('--host', required=True)\n"
            "parser.add_argument('--port', type=int, required=True)\n"
            "print(json.dumps(vars(parser.parse_args())))\n", encoding="utf-8")
        answer = self.result("""
$tokens=$null; $errors=$null
$tree=[Management.Automation.Language.Parser]::ParseFile($env:SC_STARTUP,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Invalid startup script' }
$frontendDir=Join-Path $env:SC_ROOT '源码/beadhouse-frontend'
$node=$env:SC_PYTHON; $ports=@{FrontendPort=18765}
$entry=$tree.Find({param($item)
    $item -is [Management.Automation.Language.AssignmentStatementAst] -and
    $item.Left -is [Management.Automation.Language.VariableExpressionAst] -and
    $item.Left.VariablePath.UserPath -in @('vue','vite')
},$true)
if (-not $entry) { throw 'Missing frontend CLI entry' }
. ([scriptblock]::Create($entry.Extent.Text))
$launchExpression=$tree.Find({param($item)
    $item -is [Management.Automation.Language.HashtableAst] -and
    $item.Extent.Text -like '@{file=$node;arguments=*'
},$true)
if (-not $launchExpression) { throw 'Missing frontend launch expression' }
$launch=& ([scriptblock]::Create($launchExpression.Extent.Text))
$log=Join-Path $env:SC_ROOT 'frontend-arguments.log'
Invoke-LifecycleCommand $launch.file $launch.arguments $launch.directory $log
$answer=@{entry=$launch.arguments[0];directory=$launch.directory;arguments=(Get-Content -LiteralPath $log -Raw | ConvertFrom-Json)}
""", extra={"SC_STARTUP": ROOT / "启动AI养老院.ps1"})
        self.assertEqual(Path(answer["entry"]), entry)
        self.assertEqual(Path(answer["directory"]), frontend)
        self.assertEqual(answer["arguments"], {"host": "127.0.0.1", "port": 18765})

    def test_missing_configuration_fails_before_external_tools_or_services(self):
        for name in ("启动AI养老院.ps1", "启停工具.ps1", "校验环境.ps1"):
            shutil.copyfile(ROOT / name, self.root / name)
        result = self.ps("""
foreach($name in @('MYSQL_ROOT_PASSWORD','DB_PASSWORD','REDIS_PASSWORD','JWT_SECRET','AI_RAG_INTERNAL_TOKEN','MINIO_ROOT_PASSWORD','MILVUS_ROOT_PASSWORD')) {
    [Environment]::SetEnvironmentVariable($name,$null,'Process')
}
& (Join-Path $env:SC_ROOT '启动AI养老院.ps1') -ComposeProject 'missing-config-test'
""", success=False)
        self.assertIn("MYSQL_ROOT_PASSWORD", result.stderr)
        self.assertFalse((self.root / ".runtime/lifecycle-missing-config-test.json").exists())


if __name__ == "__main__":
    unittest.main()
