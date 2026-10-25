"""Timeout evidence and partial-start regressions; no Docker or application starts."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import shutil
import json
from unittest import mock

import startup_system_checks as checks


class CommandEvidenceTests(unittest.TestCase):
    def test_timeout_retains_output_stops_only_caller_and_invokes_scoped_cleanup(self):
        with tempfile.TemporaryDirectory(prefix="silvercare-timeout-evidence-") as folder:
            log = Path(folder) / "command.log"
            cleaned = []
            foreign = subprocess.Popen([sys.executable, "-c", "import time;time.sleep(60)"])
            try:
                # Advance the test clock after both writes are observed, so a
                # slow Windows process launch cannot erase the fixture's output.
                def clock():
                    return 2 if log.exists() and b"stderr-evidence" in log.read_bytes() else 0
                with mock.patch.object(checks.time, "monotonic", side_effect=clock):
                    with self.assertRaisesRegex(TimeoutError, "command.log"):
                        checks.run_monitored_command(
                            [sys.executable, "-u", "-c",
                             "import sys,time;print('before-hang');print('stderr-evidence',file=sys.stderr);time.sleep(60)"],
                            cwd=folder, env=os.environ.copy(), log=log, timeout=1,
                            observe=lambda: None, on_timeout=lambda: cleaned.append(True))
                self.assertIn(b"before-hang", log.read_bytes())
                self.assertIn(b"stderr-evidence", log.read_bytes())
                self.assertEqual(cleaned, [True])
                self.assertIsNone(foreign.poll(), "Existing services must not be stopped by a timeout")
            finally:
                foreign.terminate()
                foreign.wait(timeout=5)

    def test_success_and_nonzero_exit_preserve_output_without_timeout_cleanup(self):
        with tempfile.TemporaryDirectory(prefix="silvercare-command-evidence-") as folder:
            for code in (0, 7):
                log = Path(folder) / f"command-{code}.log"
                result = checks.run_monitored_command(
                    [sys.executable, "-c", f"print('complete');raise SystemExit({code})"],
                    cwd=folder, env=os.environ.copy(), log=log, timeout=5,
                    observe=lambda: None, on_timeout=lambda: self.fail("Unexpected cleanup"))
                self.assertEqual(result.returncode, code)
                self.assertIn(b"complete", result.stdout)
                self.assertEqual(result.stdout, log.read_bytes())

    def test_cleanup_candidates_exclude_preexisting_and_precision_only_changes(self):
        old = {"pid": 1, "createdUtc": "2026-10-21T14:40:18.1995150Z"}
        restored = {"pid": 1, "createdUtc": "2026-10-21T22:40:18.199515+08:00"}
        new = {"pid": 2, "createdUtc": "2026-10-21T14:40:19Z"}
        self.assertEqual(checks.new_process_identities([old], [restored, new]), [new])
        reused = {"pid": 1, "createdUtc": "2026-10-21T14:41:18Z"}
        self.assertEqual(checks.new_process_identities([old], [reused]), [reused])


@unittest.skipUnless(shutil.which("pwsh"), "PowerShell 7 required")
class StartupProbeTests(unittest.TestCase):
    def test_timeout_cleanup_preserves_unregistered_baseline_without_adopting_it(self):
        with tempfile.TemporaryDirectory(prefix="silvercare-timeout-ledger-") as folder:
            root = Path(folder)
            (root / ".env.example").write_text("", encoding="utf-8")
            state = {"processes": [], "containers": [{"id": "registered", "running": False}]}
            (root / "state.json").write_text(json.dumps(state), encoding="utf-8")
            live = [{"id": "registered", "running": False},
                    {"id": "unregistered-baseline", "running": True},
                    {"id": "new-unregistered", "running": True}]
            (root / "containers.json").write_text(json.dumps(live), encoding="utf-8")
            (root / "启停工具.ps1").write_text("""
function Get-LifecycleContainers($DockerPath,$ComposeProject,$ProjectRoot) {
    Get-Content (Join-Path $env:SC_CHECK_ROOT 'containers.json') -Raw | ConvertFrom-Json -AsHashtable
}
function Stop-OwnedContainer($Identity,$DockerPath) {
    $rows=@(Get-LifecycleContainers)
    foreach($row in $rows){if($row.id -eq $Identity.id){$row.running=$false}}
    ConvertTo-Json -InputObject $rows -Depth 5 | Set-Content (Join-Path $env:SC_CHECK_ROOT 'containers.json')
}
function Read-LifecycleState($Path,$ProjectRoot,$ComposeProject) {
    Get-Content (Join-Path $env:SC_CHECK_ROOT 'state.json') -Raw | ConvertFrom-Json -AsHashtable
}
function Test-ProcessIdentity($Identity){return $false}
function Save-LifecycleState($State,$Path) {
    ConvertTo-Json -InputObject $State -Depth 5 | Set-Content (Join-Path $env:SC_CHECK_ROOT 'state.json')
}
""", encoding="utf-8")
            checks.cleanup_timed_out_command({"projectRoot": folder, "composeProject": "fixture"},
                                              [], [], ["unregistered-baseline"], shutil.which("pwsh"),
                                              "fixture-docker", root / "cleanup.log")
            saved = json.loads((root / "state.json").read_text(encoding="utf-8-sig"))
            self.assertEqual([item["id"] for item in saved["containers"]], ["registered"])
            remaining = json.loads((root / "containers.json").read_text(encoding="utf-8-sig"))
            self.assertTrue(next(item["running"] for item in remaining if item["id"] == "unregistered-baseline"))
            self.assertFalse(next(item["running"] for item in remaining if item["id"] == "new-unregistered"))

    def execute(self, body):
        with tempfile.TemporaryDirectory(prefix="silvercare-probe-branches-") as folder:
            env = os.environ.copy()
            env["SC_ENTRY"] = os.environ.get("STARTUP_CONTRACT_SOURCE", str(Path(__file__).resolve().parents[1] / "启动AI养老院.ps1"))
            env["SC_FOLDER"] = folder
            prefix = """
$ErrorActionPreference='Stop'
$tokens=$null;$errors=$null
$tree=[Management.Automation.Language.Parser]::ParseFile($env:SC_ENTRY,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Startup script did not parse'}
$commandLogDir=$env:SC_FOLDER;$aiDir=$env:SC_FOLDER;$python='test-python';$TimeoutSeconds=3
$calls=[Collections.Generic.List[object]]::new()
function Invoke-LifecycleCommand($FilePath,$Arguments,$WorkingDirectory,$LogPath,$TimeoutSeconds) {
    $calls.Add(@{file=$FilePath;arguments=$Arguments;timeout=$TimeoutSeconds;log=$LogPath})
}
"""
            result = subprocess.run([shutil.which("pwsh"), "-NoProfile", "-NonInteractive", "-Command", prefix + body],
                                    env=env, encoding="utf-8", stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_existing_rag_avoids_duplicate_import_but_new_rag_checks_dependencies(self):
        self.execute("""
$guard=$tree.Find({param($item)
    $item -is [Management.Automation.Language.IfStatementAst] -and
    $item.Extent.Text.Contains('python-check.log')
},$true)
if(-not $guard){throw 'Missing dependency reuse guard'}
$liveProcesses=@(@{name='rag'})
. ([scriptblock]::Create($guard.Extent.Text))
if($calls.Count -ne 0){throw 'Existing RAG imported dependencies again'}
$marker=Get-Content (Join-Path $commandLogDir 'python-check.skipped.json') -Raw | ConvertFrom-Json
if($marker.reason -ne 'existing-registered-rag-process'){throw 'Missing reuse evidence'}
$liveProcesses=@(@{name='frontend'})
. ([scriptblock]::Create($guard.Extent.Text))
if($calls.Count -ne 1 -or $calls[0].timeout -ne 3 -or -not ($calls[0].arguments -join ' ').Contains('fastembed, pymilvus')){
    throw 'New RAG did not execute the real bounded dependency import'
}
""")

    def test_milvus_authentication_bounds_import_and_preserves_authenticated_query(self):
        self.execute("""
$probe=$tree.Find({param($item)
    $item -is [Management.Automation.Language.ScriptBlockExpressionAst] -and
    $item.Extent.Text.Contains('from pymilvus import MilvusClient')
},$true)
if(-not $probe){throw 'Missing actual Milvus authentication probe'}
$answer=& $probe.ScriptBlock.GetScriptBlock()
if($answer -ne $true -or $calls.Count -ne 1 -or $calls[0].timeout -ne 3){throw 'Unbounded authentication command'}
$code=$calls[0].arguments -join ' '
if(-not $code.Contains('RAG_MILVUS_TOKEN') -or -not $code.Contains('list_collections(timeout=5)')){
    throw 'Authenticated Milvus operation changed'
}
""")

    def test_java_version_reads_only_the_current_run_without_legacy_logs(self):
        self.execute("""
$assignment=$tree.Find({param($item)
    $item -is [Management.Automation.Language.AssignmentStatementAst] -and
    $item.Left -is [Management.Automation.Language.VariableExpressionAst] -and
    $item.Left.VariablePath.UserPath -eq 'javaVersion'
},$true)
if(-not $assignment){throw 'Missing Java version reader'}
$logDir=Join-Path $env:SC_FOLDER 'empty-legacy-logs'
New-Item -ItemType Directory -Path $logDir | Out-Null
Set-Content -LiteralPath (Join-Path $commandLogDir 'java-version.log') -Value '' -NoNewline
Set-Content -LiteralPath (Join-Path $commandLogDir 'java-version.log.stderr') -Value 'openjdk version "1.8.0_201"'
. ([scriptblock]::Create($assignment.Extent.Text))
if($javaVersion -notmatch 'version "1\\.8\\.') {throw 'Current run JDK version was not read'}
""")


if __name__ == "__main__":
    unittest.main()
