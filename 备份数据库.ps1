# MySQL only. Stop application servers and scheduled writers before acknowledging.
param(
    [string]$Container,
    [string]$Output,
    [Parameter(Mandatory = $true)][switch]$WritersStopped,
    [string]$PythonPath
)
$ErrorActionPreference = 'Stop'
if (-not $WritersStopped) { throw '请先停止应用及定时写入，再提供 -WritersStopped。' }
. (Join-Path $PSScriptRoot '数据库工具.ps1')
$containerId = Resolve-DatabaseContainer -Container $Container
if (-not $Output) {
    $parent = Join-Path $PSScriptRoot '.runtime/backups'
    [void](New-Item -ItemType Directory -Path $parent -Force)
    $Output = Join-Path $parent ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N'))
}
$outputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Output)
$arguments = @((Join-Path $PSScriptRoot 'tools/database.py'), 'backup', '--container', $containerId,
    '--output', $outputPath, '--writers-stopped')
Invoke-ProjectPython -Arguments $arguments -PythonPath $PythonPath
