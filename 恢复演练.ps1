# No target parameter: the tool creates and verifies its own isolated container.
param(
    [Parameter(Mandatory = $true)][string]$Backup,
    [switch]$KeepOnFailure,
    [string]$PythonPath
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '数据库工具.ps1')
$backupPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Backup)
$arguments = @((Join-Path $PSScriptRoot 'tools/database.py'), 'restore-drill', '--backup', $backupPath)
if ($KeepOnFailure) { $arguments += '--keep-on-failure' }
Invoke-ProjectPython -Arguments $arguments -PythonPath $PythonPath
