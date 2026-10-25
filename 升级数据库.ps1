param([string]$Container, [switch]$RetryFailed)
# Stop application writers and back up first. This entry point never imports the demo dump.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '数据库工具.ps1')
Invoke-ProjectDatabase -Mode upgrade -Container $Container -RetryFailed:$RetryFailed
