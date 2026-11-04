[CmdletBinding()]
param(
    [switch]$Prepare, [switch]$InitializeDemo, [switch]$AcknowledgeDemoOnly,
    [string]$ComposeProject,
    [int]$MysqlPort, [int]$RedisPort, [int]$MilvusPort, [int]$MilvusHealthPort,
    [int]$RagPort, [int]$BackendPort, [int]$FrontendPort,
    [ValidateRange(1,3600)][int]$TimeoutSeconds=300
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '启停工具.ps1')
Assert-LifecycleHost
if ($InitializeDemo -and -not $AcknowledgeDemoOnly) { throw '-InitializeDemo 必须同时提供 -AcknowledgeDemoOnly。' }
Import-LifecycleEnvironment $PSScriptRoot
$parameters=@{} + $PSBoundParameters
$parameters.ComposeProject=Resolve-LifecycleProject $PSScriptRoot $ComposeProject
$config=Get-LifecycleConfiguration $PSScriptRoot $parameters
$lifecycleLock=Enter-LifecycleLock $config.statePath
try {
    $state=Read-LifecycleState $config.statePath $config.projectRoot $config.composeProject
    $arguments=@{} + $PSBoundParameters
    if ($state) { foreach ($key in $state.ports.Keys) { if (-not $arguments.ContainsKey($key)) { $arguments[$key]=$state.ports[$key] } } }
    $arguments.ComposeProject=$config.composeProject
    & (Join-Path $PSScriptRoot '停止AI养老院.ps1') -ComposeProject $config.composeProject
    & (Join-Path $PSScriptRoot '启动AI养老院.ps1') @arguments
} finally { $lifecycleLock.ReleaseMutex(); $lifecycleLock.Dispose() }
