"""Offline lifecycle safety regressions; never start Docker or the application.

Run with Python 3.10+ and PowerShell 7 on Windows:
    python -m unittest discover -s tools -p test_startup.py -v
Only processes created by these tests are terminated.
"""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest
import venv
from unittest import mock

import startup_acceptance


ROOT = Path(__file__).resolve().parents[1]
PWSH = shutil.which("pwsh")


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

    def ps(self, body, *, success=True, extra=None):
        script = self.folder / "外部 调用.ps1"
        script.write_text(
            "$ErrorActionPreference='Stop'\n"
            ". $env:SC_HELPER\n" + body, encoding="utf-8")
        env = os.environ.copy()
        env.update(SC_HELPER=str(ROOT / "启停工具.ps1"),
                   SC_ROOT=str(self.root), SC_PYTHON=sys.executable)
        if extra:
            env.update({key: str(value) for key, value in extra.items()})
        result = subprocess.run(
            [PWSH, "-NoProfile", "-NonInteractive", "-File", str(script)],
            cwd=self.folder, env=env, text=True, encoding="utf-8",
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
try {
    $identities=@(Start-LifecycleProcess -Name 'quoting' -FilePath $env:SC_PYTHON -Arguments $arguments -WorkingDirectory $env:SC_ROOT -ProjectRoot $env:SC_ROOT -LogDirectory $logs)
    Wait-LifecycleCondition -TimeoutSeconds 10 -Description 'test output' -Condition {(Get-Item (Join-Path $logs 'quoting.log')).Length -gt 0}
    $values=Get-Content (Join-Path $logs 'quoting.log') -Raw -Encoding UTF8 | ConvertFrom-Json
    $answer=@{arguments=@($values);identityCount=$identities.Count;allOwned=(@($identities | Where-Object {-not (Test-ProcessIdentity $_)}).Count -eq 0)}
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
