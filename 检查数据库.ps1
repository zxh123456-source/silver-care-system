param([string]$Container)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '数据库工具.ps1')
Invoke-ProjectDatabase -Mode inspect -Container $Container
